package com.sankholin.comp90015.assignment1.chat.server.service;

import com.sankholin.comp90015.assignment1.chat.server.model.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.*;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

public class ClientConnection implements Runnable {

    private Socket clientSocket;
    private BufferedReader reader;
    private BufferedWriter writer;
    private BlockingQueue<Message> messageQueue;
    //private int clientNum;
    private JSONParser parser;

    private ServerState serverState = ServerState.getInstance();

    private ServerInfo serverInfo;
    private String mainHall;

    private UserInfo userInfo;

    public ClientConnection(Socket clientSocket) {
        try {
            this.clientSocket = clientSocket;
            this.reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), "UTF-8"));
            this.writer = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream(), "UTF-8"));
            this.messageQueue = new LinkedBlockingQueue<>();
            //this.clientNum = clientNum;
            this.parser = new JSONParser();

            this.serverInfo = serverState.getServerInfo();
            this.mainHall =  "MainHall-" + serverInfo.getServerId();
        } catch (Exception e) {
            e.printStackTrace();
        }
        logger.info("Client connection is up...");
    }

    @Override
    public void run() {

        try {

            ClientMessageReader messageReader = new ClientMessageReader(reader, messageQueue);
            //messageReader.setName(this.getName() + "Reader");
            messageReader.start();
            System.out.println(Thread.currentThread().getName() + " - Processing client messages");

            while (true) {

                Message msg = messageQueue.take();

                if (!msg.isFromClient() && msg.getMessage().equals("exit")) {
                    //The client program is abruptly terminated (e.g. using Ctrl-C)
                    //broadcastMessageToRoom(roomChange(userInfo.getCurrentChatRoom(), ""), userInfo.getCurrentChatRoom());
                    //write(roomChange(currentChatRoom, "")); cant do it, too late. socket has already gone!
                    performPreExit();
                    break;
                }

                if (msg.isFromClient()) {

                    JSONObject jsonMessage = (JSONObject) parser.parse(msg.getMessage());
                    String type = (String) jsonMessage.get(Protocol.type.toString());

                    if (type.equalsIgnoreCase(Protocol.newidentity.toString())) {
                        String requestIdentity = (String) jsonMessage.get(Protocol.identity.toString());

                        //serverState.lockIdentity(identity); // TODO not stated in spec

                        boolean isUserExisted = serverState.isUserExisted(requestIdentity);
                        boolean isUserIdValid = serverState.isIdValid(requestIdentity);

                        if (isUserExisted || !isUserIdValid) {
                            // {"type" : "newidentity", "approved" : "false"}
                            messageQueue.add(new Message(false, newIdentityResp("false")));
                        } else {

                            boolean canLock = canPeersLockId(lockIdentity(requestIdentity));

                            if (canLock) {
                                userInfo = new UserInfo();
                                userInfo.setIdentity(requestIdentity);
                                userInfo.setCurrentChatRoom(mainHall);
                                userInfo.setManagingThread(this);
                                userInfo.setSocket(clientSocket);

                                serverState.getConnectedClients().put(requestIdentity, userInfo);
                                serverState.getLocalChatRooms().get(mainHall).addMember(requestIdentity);

                                //{"type" : "newidentity", "approved" : "true"}
                                messageQueue.add(new Message(false, newIdentityResp("true")));

                                //{"type" : "roomchange", "identity" : "Adel", "former" : "", "roomid" : "MainHall-s1"}
                                broadcastMessageToRoom(roomChange("", mainHall), mainHall);
                            }

                            // release identity on peers
                            relayPeers(releaseIdentity(requestIdentity));
                        }

                        //serverState.unlockIdentity(identity); // TODO not stated in spec
                    }

                    if (type.equalsIgnoreCase(Protocol.list.toString())) {
                        //write(listRooms());
                        messageQueue.add(new Message(false, listRooms()));
                    }

                    if (type.equalsIgnoreCase(Protocol.who.toString())) {
                        //write(whoByRoom(currentChatRoom));
                        messageQueue.add(new Message(false, whoByRoom(userInfo.getCurrentChatRoom())));
                    }

                    if (type.equalsIgnoreCase(Protocol.createroom.toString())) {
                        String requestRoomId = (String) jsonMessage.get(Protocol.roomid.toString());

                        boolean isRoomExisted = serverState.isRoomExisted(requestRoomId);
                        boolean hasRoomAlreadyLocked = serverState.isRoomIdLocked(requestRoomId);

                        // if and only if the client is not the owner of another chat room
                        if (userInfo.isRoomOwner() || hasRoomAlreadyLocked || isRoomExisted) {
                            write(createRoomResp(requestRoomId, "false"));
                        } else {

                            //serverState.lockRoomIdentity(requestRoomId); // TODO not stated in spec

                            boolean canLock = canPeersLockId(lockRoom(requestRoomId));
                            if (canLock) {
                                // release lock
                                relayPeers(releaseRoom(requestRoomId, "true"));

                                // create and update room
                                LocalChatRoomInfo newRoom = new LocalChatRoomInfo();
                                newRoom.setChatRoomId(requestRoomId);
                                newRoom.setOwner(userInfo.getIdentity());
                                newRoom.addMember(userInfo.getIdentity());
                                serverState.getLocalChatRooms().put(requestRoomId, newRoom);

                                // update former room
                                String former = userInfo.getCurrentChatRoom();
                                serverState.getLocalChatRooms().get(former).removeMember(userInfo.getIdentity());

                                // update this client
                                userInfo.setCurrentChatRoom(requestRoomId);
                                userInfo.setRoomOwner(true);

                                // response client
                                write(createRoomResp(requestRoomId, "true"));
                                broadcastMessageToRoom(roomChange(former, userInfo.getCurrentChatRoom()), former);

                            } else {
                                relayPeers(releaseRoom(requestRoomId, "false"));
                                write(createRoomResp(requestRoomId, "false"));
                            }

                            //serverState.unlockRoomIdentity(requestRoomId); // TODO not stated in spec
                        }
                    }

                    if (type.equalsIgnoreCase(Protocol.join.toString())) {
                        // {"type" : "join", "roomid" : "jokes"}
                        String joiningRoomId = (String) jsonMessage.get(Protocol.roomid.toString());
                        boolean isRoomExisted = serverState.isRoomExisted(joiningRoomId);
                        if (userInfo.isRoomOwner() || !isRoomExisted) {
                            messageQueue.add(new Message(false, roomChange(joiningRoomId, joiningRoomId)));
                        }

                        String former = userInfo.getCurrentChatRoom();

                        // if room is in the same server
                        if (serverState.getLocalChatRooms().containsKey(joiningRoomId)) {
                            userInfo.setCurrentChatRoom(joiningRoomId);

                            serverState.getLocalChatRooms().get(former).removeMember(userInfo.getIdentity());
                            serverState.getLocalChatRooms().get(joiningRoomId).addMember(userInfo.getIdentity());

                            broadcastMessageToRoom(roomChange(former, joiningRoomId), former);
                            broadcastMessageToRoom(roomChange(former, joiningRoomId), joiningRoomId);
                            messageQueue.add(new Message(false, roomChange(former, joiningRoomId)));
                        }

                        // If the chat room is managed by a different server
                        if (serverState.getRemoteChatRooms().containsKey(joiningRoomId)) {
                            RemoteChatRoomInfo remoteChatRoomInfo = serverState.getRemoteChatRooms().get(joiningRoomId);
                            ServerInfo server = serverState.getServerInfoById(remoteChatRoomInfo.getManagingServer());

                            messageQueue.add(new Message(false, route(joiningRoomId, server.getAddress(), server.getPort())));

                            performPreExit();
                            //serverState.getConnectedClients().remove(userInfo.getIdentity());
                            broadcastMessageToRoom(roomChange(former, joiningRoomId), former);
                        }
                    }

                    if (type.equalsIgnoreCase(Protocol.movejoin.toString())) {
                        // {"type" : "movejoin", "former" : "MainHall-s1", "roomid" : "jokes", "identity" : "Maria"}
                        String joiningRoomId = (String) jsonMessage.get(Protocol.roomid.toString());
                        String former = (String) jsonMessage.get(Protocol.former.toString());
                        String identity = (String) jsonMessage.get(Protocol.identity.toString());
                        boolean isRoomExisted = serverState.getLocalChatRooms().containsKey(joiningRoomId);

                        UserInfo migrant = new UserInfo();
                        migrant.setIdentity(identity);
                        migrant.setRoomOwner(false);
                        migrant.setSocket(clientSocket);
                        migrant.setManagingThread(this);

                        String roomId;
                        if (isRoomExisted) {
                            roomId = joiningRoomId;
                        } else {
                            // room has gone, place in MainHall
                            roomId = mainHall;
                        }
                        migrant.setCurrentChatRoom(roomId);
                        serverState.getLocalChatRooms().get(roomId).addMember(identity);
                        serverState.getConnectedClients().put(identity, migrant);

                        broadcastMessageToRoom(roomChange(former, joiningRoomId), joiningRoomId);
                        messageQueue.add(new Message(false, serverChange("true", serverInfo.getServerId())));
                    }

                    if (type.equalsIgnoreCase(Protocol.deleteroom.toString())) {
                        // {"type" : "deleteroom", "roomid" : "jokes"}
                        String deleteRoomId = (String) jsonMessage.get(Protocol.roomid.toString());
                        boolean isRoomExisted = serverState.isRoomExisted(deleteRoomId);
                        LocalChatRoomInfo deletingRoom = serverState.getLocalChatRooms().get(deleteRoomId);
                        if (deletingRoom.getOwner().equalsIgnoreCase(userInfo.getIdentity()) && isRoomExisted) {

                            userInfo.setRoomOwner(false);

                            doDeleteRoomProtocol(deletingRoom);

                            messageQueue.add(new Message(false, deleteRoom(deleteRoomId, "true")));

                        } else {

                            messageQueue.add(new Message(false, deleteRoom(deleteRoomId, "false")));
                        }
                    }

                    if (type.equalsIgnoreCase(Protocol.message.toString())) {
                        // {"type" : "message", "content" : "Hi there!"}
                        String content = (String) jsonMessage.get(Protocol.content.toString());
                        broadcastMessageToRoom(message(content), userInfo.getCurrentChatRoom());
                    }

                    if (type.equalsIgnoreCase(Protocol.quit.toString())) {
                        //{"type" : "roomchange", "identity" : "Adel", "former" : "MainHall-s1", "roomid" : ""}

                        String former = userInfo.getCurrentChatRoom();
                        LocalChatRoomInfo deletingRoom = serverState.getLocalChatRooms().get(former);

                        // follow delete room protocol if owner
                        if (userInfo.isRoomOwner()) {
                            doDeleteRoomProtocol(deletingRoom);
                        }

                        // update about quitting user
                        broadcastMessageToRoom(roomChange(former, ""), mainHall);
                        messageQueue.add(new Message(false, roomChange(mainHall, "")));
                        performPreExit();
                    }

                } else {
                    //If the message is from a thread and it isn't exit, then
                    //it is a message that needs to be sent to the client
                    write(msg.getMessage());  // write message from queue
                }
            }

            clientSocket.close();
            System.out.println(Thread.currentThread().getName() + " - Client disconnected");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void performPreExit() {
        //serverState.getConnectedClients().remove(userInfo.getIdentity());
        //serverState.getLocalChatRooms()
    }

    private String serverChange(String approved, String serverId) {
        // {"type" : "serverchange", "approved" : "true", "serverid" : "s2"}
        JSONObject jj = new JSONObject();
        jj.put(Protocol.type.toString(), Protocol.serverchange.toString());
        jj.put(Protocol.approved.toString(), approved);
        jj.put(Protocol.serverid.toString(), serverId);
        return jj.toJSONString();
    }

    private String route(String joiningRoomId, String host, Integer port) {
        // {"type" : "route", "roomid" : "jokes", "host" : "122.134.2.4", "port" : "4445"}
        JSONObject jj = new JSONObject();
        jj.put(Protocol.type.toString(), Protocol.route.toString());
        jj.put(Protocol.roomid.toString(), joiningRoomId);
        jj.put(Protocol.host.toString(), host);
        jj.put(Protocol.port.toString(), port);
        return jj.toJSONString();
    }

    private String message(String content) {
        // {"type" : "message", "identity" : "Adel", "content" : "Hi there!"}
        JSONObject jj = new JSONObject();
        jj.put(Protocol.type.toString(), Protocol.message.toString());
        jj.put(Protocol.identity.toString(), userInfo.getIdentity());
        jj.put(Protocol.content.toString(), content);
        return jj.toJSONString();
    }

    private void doDeleteRoomProtocol(LocalChatRoomInfo deletingRoom) {
        // put all users to main hall
        serverState.getLocalChatRooms().get(mainHall).getMembers().addAll(deletingRoom.getMembers());
        for (String member : deletingRoom.getMembers()) {
            UserInfo client = serverState.getConnectedClients().get(member);
            client.setCurrentChatRoom(mainHall);
        }

        // delete the room
        serverState.getLocalChatRooms().remove(deletingRoom.getChatRoomId());

        // inform peers
        relayPeers(deleteRoomPeers(deletingRoom.getChatRoomId()));

        broadcastMessageToRoom(roomChange(deletingRoom.getChatRoomId(), mainHall), mainHall);
    }

    private String deleteRoom(String roomId, String approved) {
        // {"type" : "deleteroom", "roomid" : "jokes", "approved" : "true"}
        JSONObject jj = new JSONObject();
        jj.put(Protocol.type.toString(), Protocol.deleteroom.toString());
        jj.put(Protocol.roomid.toString(), roomId);
        jj.put(Protocol.approved.toString(), approved);
        return jj.toJSONString();
    }

    private String deleteRoomPeers(String roomId) {
        // {"type" : "deleteroom", "serverid" : "s1", "roomid" : "jokes"}
        JSONObject jj = new JSONObject();
        jj.put(Protocol.type.toString(), Protocol.deleteroom.toString());
        jj.put(Protocol.serverid.toString(), serverInfo.getServerId());
        jj.put(Protocol.roomid.toString(), roomId);
        return jj.toJSONString();
    }

    private String releaseRoom(String roomId, String approved) {
        // "type" : "releaseroomid", "serverid" : "s1", "roomid" : "jokes", "approved":"false"}
        JSONObject jj = new JSONObject();
        jj.put(Protocol.type.toString(), Protocol.releaseroomid.toString());
        jj.put(Protocol.serverid.toString(), serverInfo.getServerId());
        jj.put(Protocol.roomid.toString(), roomId);
        jj.put(Protocol.approved.toString(), approved);
        return jj.toJSONString();
    }

    private String lockRoom(String roomId) {
        //{"type" : "lockroomid", "serverid" : "s1", "roomid" : "jokes"}
        JSONObject jj = new JSONObject();
        jj.put(Protocol.type.toString(), Protocol.lockroomid.toString());
        jj.put(Protocol.serverid.toString(), serverInfo.getServerId());
        jj.put(Protocol.roomid.toString(), roomId);
        return jj.toJSONString();
    }

    private String createRoomResp(String roomId, String approved) {
        //{"type" : "createroom", "roomid" : "jokes", "approved" : "false"}
        JSONObject jj = new JSONObject();
        jj.put(Protocol.type.toString(), Protocol.createroom.toString());
        jj.put(Protocol.roomid.toString(), roomId);
        jj.put(Protocol.approved.toString(), approved);
        return jj.toJSONString();
    }

    private String whoByRoom(String room) {
        JSONObject jj = new JSONObject();
        //{ "type" : "roomcontents", "roomid" : "jokes", "identities" : ["Adel","Chenhao","Maria"], "owner" : "Adel" }
        jj.put(Protocol.type.toString(), Protocol.roomcontents.toString());
        jj.put(Protocol.roomid.toString(), room);
        LocalChatRoomInfo localChatRoomInfo = serverState.getLocalChatRooms().get(room);
        JSONArray ja = new JSONArray();
        ja.addAll(localChatRoomInfo.getMembers());
        jj.put(Protocol.identities.toString(), ja);
        jj.put(Protocol.owner.toString(), localChatRoomInfo.getOwner());
        return jj.toJSONString();
    }

    private String listRooms() {
        //{ "type" : "roomlist", "rooms" : ["MainHall-s1", "MainHall-s2", "jokes"] }
        JSONObject jj = new JSONObject();
        jj.put(Protocol.type.toString(), Protocol.roomlist.toString());

        JSONArray ja = serverState.getLocalChatRooms().values().stream()
                .map(ChatRoomInfo::getChatRoomId)
                .collect(Collectors.toCollection(JSONArray::new));

        ja.addAll(serverState.getRemoteChatRooms().values().stream()
                .map(ChatRoomInfo::getChatRoomId)
                .collect(Collectors.toList()));

        jj.put(Protocol.rooms.toString(), ja);

        return jj.toJSONString();
    }

    private String releaseIdentity(String userId) {
        //{"type" : "releaseidentity", "serverid" : "s1", "identity" : "Adel"}
        JSONObject jj = new JSONObject();
        jj.put(Protocol.type.toString(), Protocol.releaseidentity.toString());
        jj.put(Protocol.serverid.toString(), serverInfo.getServerId());
        jj.put(Protocol.identity.toString(), userId);
        return jj.toJSONString();
    }

    private String lockIdentity(String userId) {
        // send peer server {"type" : "lockidentity", "serverid" : "s1", "identity" : "Adel"}
        JSONObject jj = new JSONObject();
        jj.put(Protocol.type.toString(), Protocol.lockidentity.toString());
        jj.put(Protocol.serverid.toString(), serverInfo.getServerId());
        jj.put(Protocol.identity.toString(), userId);
        return jj.toJSONString();
    }

    private String newIdentityResp(String approve) {
        JSONObject jj = new JSONObject();
        jj.put(Protocol.type.toString(), Protocol.newidentity.toString());
        jj.put(Protocol.approved.toString(), approve);
        return jj.toJSONString();
    }

    private String roomChange(String former, String roomId) {
        // {"type" : "roomchange", "identity" : "Maria", "former" : "jokes", "roomid" : "jokes"}
        JSONObject jj = new JSONObject();
        jj.put(Protocol.type.toString(), Protocol.roomchange.toString());
        jj.put(Protocol.identity.toString(), userInfo.getIdentity());
        jj.put(Protocol.former.toString(), former);
        jj.put(Protocol.roomid.toString(), roomId);
        return jj.toJSONString();
    }

    private void broadcastMessageToRoom(String message, String room) {
        Message msgForThreads = new Message(false, message);

        Map<String, UserInfo> connectedClients = serverState.getConnectedClients();
        //Place the message on the client's queue
        connectedClients.values().stream()
                .filter(client -> client.getCurrentChatRoom().equalsIgnoreCase(room))
                .forEach(client -> {
            //Place the message on the client's queue
            client.getManagingThread().getMessageQueue().add(msgForThreads);
        });
    }

    public BlockingQueue<Message> getMessageQueue() {
        return messageQueue;
    }

    private void write(String msg) {
        try {
            writer.write(msg + "\n");
            writer.flush();
            System.out.println(Thread.currentThread().getName() + " - Message sent to client ");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    //public String getCurrentChatRoom() {
    //    return currentChatRoom;
    //}

    //////

    private String commPeers(ServerInfo server, String message) {

        Socket socket = null;
        try {
            socket = new Socket(server.getAddress(), server.getManagementPort());
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"));
            writer.write(message + "\n");
            writer.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            return reader.readLine();

        } catch (IOException ioe) {
            System.out.println(server.getServerId() + " is not online...");
            //ioe.printStackTrace();
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        return null;
    }

    private boolean canPeersLockId(String jsonMessage) {
        boolean canLock = true;

        for (ServerInfo server : serverState.getServerInfoList()) {
            if (!server.getServerId().equalsIgnoreCase(this.serverInfo.getServerId())) {

                String resp = commPeers(server, jsonMessage);

                if (resp == null) continue;

                JSONObject jj = null;
                try {
                    jj = (JSONObject) parser.parse(resp);
                } catch (ParseException e) {
                    e.printStackTrace();
                }

                String status = (String) jj.get(Protocol.locked.toString());
                if (status.equalsIgnoreCase("false")) {
                    canLock = false; // denied lock
                }

            }
        }

        return canLock;
    }

    private void relayPeers(String jsonMessage) {
        serverState.getServerInfoList().stream()
                .filter(server -> !server.getServerId().equalsIgnoreCase(this.serverInfo.getServerId()))
                .forEach(server -> {
                    commPeers(server, jsonMessage);
        });
    }

    private static final Logger logger = LogManager.getLogger(ClientConnection.class);
}
