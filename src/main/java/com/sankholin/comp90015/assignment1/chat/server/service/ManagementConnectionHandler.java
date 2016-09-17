package com.sankholin.comp90015.assignment1.chat.server.service;

import com.sankholin.comp90015.assignment1.chat.server.model.Message;
import com.sankholin.comp90015.assignment1.chat.server.model.Protocol;
import com.sankholin.comp90015.assignment1.chat.server.model.RemoteChatRoomInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.*;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

public class ManagementConnectionHandler implements Runnable {

    private ServerState serverState = ServerState.getInstance();

    private Socket clientSocket;
    private BufferedReader reader;
    private BufferedWriter writer;
    private BlockingQueue<Message> messageQueue;
    private JSONParser parser;
    private ExecutorService pool;

    public ManagementConnectionHandler(Socket clientSocket) {
        try {
            this.clientSocket = clientSocket;
            this.reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), "UTF-8"));;
            this.writer = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream(), "UTF-8"));
            this.messageQueue = new LinkedBlockingQueue<>();
            this.parser = new JSONParser();
            this.pool = Executors.newSingleThreadExecutor();
        } catch (Exception e) {
            logger.trace(e.getMessage());
        }
    }

    @Override
    public void run() {
        try {

            pool.execute(new MessageReader(reader, messageQueue));

            while (true) {

                Message msg = messageQueue.take();

                if (!msg.isFromClient() && msg.getMessage().equals("exit")) {
                    logger.trace("EOF");
                    break;
                }

                if (msg.isFromClient()) {

                    JSONObject jsonMessage = (JSONObject) parser.parse(msg.getMessage());
                    logger.debug("[S2S]Receiving: " + msg.getMessage());

                    String type = (String) jsonMessage.get(Protocol.type.toString());

                    // acquire lock for user id
                    if (type.equalsIgnoreCase(Protocol.lockidentity.toString())) {
                        // {"type" : "lockidentity", "serverid" : "s1", "identity" : "Adel"}
                        String requestUserId = (String) jsonMessage.get(Protocol.identity.toString());
                        String serverId = (String) jsonMessage.get(Protocol.serverid.toString());
                        String lok = serverId.concat(requestUserId);

                        boolean isUserExisted = serverState.isUserExisted(requestUserId);
                        boolean isUserLocked = serverState.isIdentityLocked(lok);

                        if (isUserExisted || isUserLocked) {
                            messageQueue.add(new Message(false, lockIdentity(serverId, requestUserId, "false")));
                        } else {
                            serverState.lockIdentity(lok);
                            messageQueue.add(new Message(false, lockIdentity(serverId, requestUserId, "true")));
                        }
                    }

                    // release lock for user id
                    if (type.equalsIgnoreCase(Protocol.releaseidentity.toString())) {
                        // {"type" : "releaseidentity", "serverid" : "s1", "identity" : "Adel"}
                        String requestUserId = (String) jsonMessage.get(Protocol.identity.toString());
                        String serverId = (String) jsonMessage.get(Protocol.serverid.toString());
                        String lok = serverId.concat(requestUserId);
                        serverState.unlockIdentity(lok);
                        messageQueue.add(new Message(false, "exit"));
                    }

                    // acquire vote for locking room id
                    if (type.equalsIgnoreCase(Protocol.lockroomid.toString())) {
                        // {"type" : "lockroomid", "serverid" : "s1", "roomid" : "jokes"}
                        String requestRoomId = (String) jsonMessage.get(Protocol.roomid.toString());
                        String serverId = (String) jsonMessage.get(Protocol.serverid.toString());

                        boolean locked = serverState.isRoomIdLocked(requestRoomId);
                        Set<String> existingRooms = serverState.getLocalChatRooms().keySet();
                        boolean existed = existingRooms.contains(requestRoomId);
                        if (locked || existed) { // deny lock
                            messageQueue.add(new Message(false, lockRoom(serverId, requestRoomId, "false")));
                        } else { // approve lock
                            serverState.lockRoomIdentity(requestRoomId);
                            messageQueue.add(new Message(false, lockRoom(serverId, requestRoomId, "true")));
                        }
                    }

                    // release lock for room id
                    if (type.equalsIgnoreCase(Protocol.releaseroomid.toString())) {
                        // "type" : "releaseroomid", "serverid" : "s1", "roomid" : "jokes", "approved":"true"}
                        String requestRoomId = (String) jsonMessage.get(Protocol.roomid.toString());
                        String serverId = (String) jsonMessage.get(Protocol.serverid.toString());
                        String approved = (String) jsonMessage.get(Protocol.approved.toString());

                        if (approved.equalsIgnoreCase("true")) {
                            // Servers receiving a releaseroomid message with "approved" : "true" must
                            // then release the lock and
                            // record it as a new chat room with id "jokes" that was created in server s1.
                            serverState.unlockRoomIdentity(requestRoomId);

                            RemoteChatRoomInfo remoteChatRoomInfo = new RemoteChatRoomInfo();
                            remoteChatRoomInfo.setChatRoomId(requestRoomId);
                            remoteChatRoomInfo.setManagingServer(serverId);
                            serverState.getRemoteChatRooms().put(requestRoomId, remoteChatRoomInfo);
                        } else {
                            // do nothing
                        }

                        messageQueue.add(new Message(false, "exit"));
                    }

                    // delete room
                    if (type.equalsIgnoreCase(Protocol.deleteroom.toString())) {
                        //{"type" : "deleteroom", "serverid" : "s1", "roomid" : "jokes"}
                        String deletingRoomId = (String) jsonMessage.get(Protocol.roomid.toString());
                        serverState.getRemoteChatRooms().remove(deletingRoomId);
                        messageQueue.add(new Message(false, "exit"));
                    }

                } else {
                    logger.debug("[S2S]Sending  : " + msg.getMessage());
                    write(msg.getMessage());
                }
            }

            pool.shutdown();
            clientSocket.close();

        } catch (InterruptedException | IOException | ParseException e) {
            logger.trace(e.getMessage());
            pool.shutdownNow();
        }
    }

    private String lockIdentity(String serverId, String userId, String locked) {
        // {"type" : "lockidentity", "serverid" : "s2", "identity" : "Adel", "locked" : "true"}
        JSONObject jj = new JSONObject();
        jj.put(Protocol.type.toString(), Protocol.lockidentity.toString());
        jj.put(Protocol.serverid.toString(), serverId);
        jj.put(Protocol.identity.toString(), userId);
        jj.put(Protocol.locked.toString(), locked);
        return jj.toJSONString();
    }

    private String lockRoom(String serverId, String roomId, String locked) {
        //{"type" : "lockroomid", "serverid" : "s2", "roomid" : "jokes", "locked" : "true"}
        JSONObject jj = new JSONObject();
        jj.put(Protocol.type.toString(), Protocol.lockroomid.toString());
        jj.put(Protocol.serverid.toString(), serverId);
        jj.put(Protocol.roomid.toString(), roomId);
        jj.put(Protocol.locked.toString(), locked);
        return jj.toJSONString();
    }

    private void write(String msg) {
        try {
            writer.write(msg + "\n");
            writer.flush();
        } catch (IOException e) {
            logger.trace(e.getMessage());
        }
    }

    private static final Logger logger = LogManager.getLogger(ManagementConnectionHandler.class);
}
