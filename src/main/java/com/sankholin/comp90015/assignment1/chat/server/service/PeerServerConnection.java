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
import java.util.concurrent.LinkedBlockingQueue;

public class PeerServerConnection implements Runnable {

    private Socket clientSocket;
    private BufferedReader reader;
    private BufferedWriter writer;
    private BlockingQueue<Message> messageQueue;
    private JSONParser parser;

    private ServerState serverState = ServerState.getInstance();

    public PeerServerConnection(Socket clientSocket) {
        try {
            this.clientSocket = clientSocket;
            this.reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), "UTF-8"));;
            this.writer = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream(), "UTF-8"));
            this.messageQueue = new LinkedBlockingQueue<>();
            this.parser = new JSONParser();
        } catch (Exception e) {
            e.printStackTrace();
        }
        logger.info("Peer connection is up...");
    }

    @Override
    public void run() {
        try {

            ClientMessageReader messageReader = new ClientMessageReader(reader, messageQueue);
            //messageReader.setName(this.getName() + "Reader");
            messageReader.start();
            System.out.println(Thread.currentThread().getName() + " - Processing peer messages");

            while (true) {

                Message msg = messageQueue.take();

                if (!msg.isFromClient() && msg.getMessage().equals("exit")) {
                    //The client program is abruptly terminated (e.g. using Ctrl-C)
                    //{"type" : "roomchange", "identity" : "Adel", "former" : "MainHall-s1", "roomid" : ""}
                    //broadcastMessageToRoom(roomChange(currentChatRoom, ""), currentChatRoom);
                    //write(roomChange(currentChatRoom, "")); cant do it, too late. socket has already gone!
                    break;
                }

                if (msg.isFromClient()) {

                    JSONObject jsonMessage = (JSONObject) parser.parse(msg.getMessage());
                    String type = (String) jsonMessage.get(Protocol.type.toString());

                    // acquire lock for user id
                    if (type.equalsIgnoreCase(Protocol.lockidentity.toString())) {
                        // {"type" : "lockidentity", "serverid" : "s1", "identity" : "Adel"}
                        String requestUserId = (String) jsonMessage.get(Protocol.identity.toString());
                        String serverId = (String) jsonMessage.get(Protocol.serverid.toString());

                        boolean isUserExisted = serverState.isUserExisted(requestUserId);
                        boolean isUserLocked = serverState.isIdentityLocked(requestUserId);

                        if (isUserExisted || isUserLocked) {
                            messageQueue.add(new Message(false, lockIdentity(serverId, requestUserId, "false")));
                        } else {
                            serverState.lockIdentity(requestUserId);
                            messageQueue.add(new Message(false, lockIdentity(serverId, requestUserId, "true")));
                        }
                    }

                    // release lock
                    if (type.equalsIgnoreCase(Protocol.releaseidentity.toString())) {
                        // {"type" : "releaseidentity", "serverid" : "s1", "identity" : "Adel"}
                        String requestUserId = (String) jsonMessage.get(Protocol.identity.toString());
                        serverState.unlockIdentity(requestUserId); //TODO spec say check serverId, but it is not required, ambiguous??
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

                    // release room
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
                        }
                    }

                    // delete room // TODO not stated in spec
                    if (type.equalsIgnoreCase(Protocol.deleteroom.toString())) {
                        //{"type" : "deleteroom", "serverid" : "s1", "roomid" : "jokes"}
                        String deletingRoomId = (String) jsonMessage.get(Protocol.roomid.toString());
                        serverState.getRemoteChatRooms().remove(deletingRoomId);
                    }


                } else {
                    write(msg.getMessage());
                }
            }

            //serverState.getConnectedClients().remove(this.identity);
            this.clientSocket.close();
            //System.out.println(Thread.currentThread().getName() + " - Client " + clientNum + " disconnected");

        } catch (InterruptedException | IOException | ParseException e) {
            e.printStackTrace();
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
            //System.out.println(Thread.currentThread().getName() + " - Message sent to client " + clientNum);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static final Logger logger = LogManager.getLogger(PeerServerConnection.class);
}
