package com.sankholin.comp90015.assignment1.chat.server.service;

import com.sankholin.comp90015.assignment1.chat.server.model.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.*;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

public class ClientConnectionHandler implements Runnable {

    private ServerState serverState = ServerState.getInstance();

    private Socket clientSocket;
    private BufferedReader reader;
    private BufferedWriter writer;
    private BlockingQueue<Message> messageQueue;
    private JSONParser parser;
    private ExecutorService pool;
    private PeerClient peerClient;

    private ServerInfo serverInfo;
    private String mainHall;
    private boolean routed = false;
    private UserInfo userInfo;

    public ClientConnectionHandler(Socket clientSocket) {
        try {
            this.clientSocket = clientSocket;
            this.reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), "UTF-8"));
            this.writer = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream(), "UTF-8"));
            this.messageQueue = new LinkedBlockingQueue<>();
            this.parser = new JSONParser();
            this.pool = Executors.newSingleThreadExecutor();
            this.peerClient = new PeerClient();

            this.serverInfo = serverState.getServerInfo();
            this.mainHall = "MainHall-" + serverInfo.getServerId();
        } catch (Exception e) {
            logger.trace(e.getMessage());
        }
    }

    @Override
    public void run() {

        try {

            pool.execute(new MessageReader(reader, messageQueue));

            logger.trace("Processing client messages");

            while (true) {

                Message msg = messageQueue.take();

                if (!msg.isFromClient() && msg.getMessage().equalsIgnoreCase("exit")) {
                    //The client program is abruptly terminated (e.g. using Ctrl-C)
                    if (userInfo != null) {
                        doGracefulQuit();
                        if (!routed) {
                            String former = userInfo.getCurrentChatRoom();
                            broadcastMessageToRoom(roomChange(former, ""), former, userInfo.getIdentity());
                        }
                    }
                    logger.trace("EOF");
                    break;
                }

                if (msg.isFromClient()) {

                    JSONObject jsonMessage = (JSONObject) parser.parse(msg.getMessage());
                    logger.debug("Receiving: " + msg.getMessage());

                    String type = (String) jsonMessage.get(Protocol.type.toString());

                    if (type.equalsIgnoreCase(Protocol.newidentity.toString())) {
                        String requestIdentity = (String) jsonMessage.get(Protocol.identity.toString());

                        boolean isUserExisted = serverState.isUserExisted(requestIdentity);
                        boolean isUserIdValid = serverState.isIdValid(requestIdentity);

                        if (isUserExisted || !isUserIdValid) {
                            // {"type" : "newidentity", "approved" : "false"}
                            messageQueue.add(new Message(false, newIdentityResp("false")));
                        } else {

                            boolean canLock = peerClient.canPeersLockId(lockIdentity(requestIdentity));

                            if (canLock) {
                                userInfo = new UserInfo();
                                userInfo.setIdentity(requestIdentity);
                                userInfo.setCurrentChatRoom(mainHall);
                                userInfo.setManagingThread(this);
                                userInfo.setSocket(clientSocket);

                                serverState.getConnectedClients().put(requestIdentity, userInfo);
                                serverState.getLocalChatRooms().get(mainHall).addMember(requestIdentity);

                                logger.info("Client connected: " + requestIdentity);

                                //{"type" : "newidentity", "approved" : "true"}
                                messageQueue.add(new Message(false, newIdentityResp("true")));

                                //{"type" : "roomchange", "identity" : "Adel", "former" : "", "roomid" : "MainHall-s1"}
                                broadcastMessageToRoom(roomChange("", mainHall), mainHall);
                            } else {
                                messageQueue.add(new Message(false, newIdentityResp("false")));
                            }

                            // release identity on peers
                            peerClient.relayPeers(releaseIdentity(requestIdentity));
                        }
                    }

                    if (type.equalsIgnoreCase(Protocol.list.toString())) {
                        messageQueue.add(new Message(false, listRooms()));
                    }

                    if (type.equalsIgnoreCase(Protocol.who.toString())) {
                        messageQueue.add(new Message(false, whoByRoom(userInfo.getCurrentChatRoom())));
                    }

                    if (type.equalsIgnoreCase(Protocol.createroom.toString())) {
                        String requestRoomId = (String) jsonMessage.get(Protocol.roomid.toString());

                        boolean isRoomExisted = serverState.isRoomExistedGlobally(requestRoomId);
                        boolean hasRoomAlreadyLocked = serverState.isRoomIdLocked(requestRoomId);
                        boolean isRoomIdValid = serverState.isIdValid(requestRoomId);

                        // if and only if the client is not the owner of another chat room
                        if (userInfo.isRoomOwner() || hasRoomAlreadyLocked || isRoomExisted || !isRoomIdValid) {
                            write(createRoomResp(requestRoomId, "false"));
                        } else {

                            boolean canLock = peerClient.canPeersLockId(lockRoom(requestRoomId));
                            if (canLock) {
                                // release lock
                                peerClient.relayPeers(releaseRoom(requestRoomId, "true"));

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
                                write(roomChange(former, userInfo.getCurrentChatRoom()));
                                broadcastMessageToRoom(roomChange(former, userInfo.getCurrentChatRoom()), former);

                            } else {
                                peerClient.relayPeers(releaseRoom(requestRoomId, "false"));
                                write(createRoomResp(requestRoomId, "false"));
                            }
                        }
                    }

                    if (type.equalsIgnoreCase(Protocol.join.toString())) {
                        // {"type" : "join", "roomid" : "jokes"}
                        String joiningRoomId = (String) jsonMessage.get(Protocol.roomid.toString());
                        boolean roomExistedGlobally = serverState.isRoomExistedGlobally(joiningRoomId);
                        boolean isTheSameRoom = userInfo.getCurrentChatRoom().equalsIgnoreCase(joiningRoomId);
                        if (userInfo.isRoomOwner() || !roomExistedGlobally || isTheSameRoom) {
                            messageQueue.add(new Message(false, roomChange(joiningRoomId, joiningRoomId)));
                        } else {

                            boolean roomExistedLocally = serverState.isRoomExistedLocally(joiningRoomId);
                            boolean roomExistedRemotely = serverState.isRoomExistedRemotely(joiningRoomId);

                            String former = userInfo.getCurrentChatRoom();

                            // If room is in the same server
                            if (roomExistedLocally) {
                                userInfo.setCurrentChatRoom(joiningRoomId);

                                serverState.getLocalChatRooms().get(joiningRoomId).addMember(userInfo.getIdentity());

                                broadcastMessageToRoom(roomChange(former, joiningRoomId), former, userInfo.getIdentity());
                                broadcastMessageToRoom(roomChange(former, joiningRoomId), joiningRoomId, userInfo.getIdentity());
                                messageQueue.add(new Message(false, roomChange(former, joiningRoomId)));
                            }

                            // If the chat room is managed by a different server
                            if (roomExistedRemotely) {
                                RemoteChatRoomInfo remoteChatRoomInfo = serverState.getRemoteChatRooms().get(joiningRoomId);
                                ServerInfo server = serverState.getServerInfoById(remoteChatRoomInfo.getManagingServer());

                                messageQueue.add(new Message(false, route(joiningRoomId, server.getAddress(), server.getPort())));

                                //serverState.getConnectedClients().remove(userInfo.getIdentity());
                                routed = true;

                                broadcastMessageToRoom(roomChange(former, joiningRoomId), former);

                                logger.info(userInfo.getIdentity() + " has routed to server " + server.getServerId());
                            }

                            // Either case, remove user from former room on this server memory
                            serverState.getLocalChatRooms().get(former).removeMember(userInfo.getIdentity());
                        }
                    }

                    if (type.equalsIgnoreCase(Protocol.movejoin.toString())) {
                        // {"type" : "movejoin", "former" : "MainHall-s1", "roomid" : "jokes", "identity" : "Maria"}
                        String joiningRoomId = (String) jsonMessage.get(Protocol.roomid.toString());
                        String former = (String) jsonMessage.get(Protocol.former.toString());
                        String identity = (String) jsonMessage.get(Protocol.identity.toString());
                        boolean roomExistedLocally = serverState.isRoomExistedLocally(joiningRoomId);

                        userInfo = new UserInfo();
                        userInfo.setIdentity(identity);
                        userInfo.setManagingThread(this);
                        userInfo.setSocket(clientSocket);

                        String roomId;
                        if (roomExistedLocally) {
                            roomId = joiningRoomId;
                        } else {
                            // room has gone, place in MainHall
                            roomId = mainHall;
                        }
                        userInfo.setCurrentChatRoom(roomId);
                        serverState.getConnectedClients().put(identity, userInfo);
                        serverState.getLocalChatRooms().get(roomId).addMember(identity);

                        logger.info("Client connected: " + identity);

                        write(serverChange("true", serverInfo.getServerId()));
                        broadcastMessageToRoom(roomChange(former, roomId), roomId);
                    }

                    if (type.equalsIgnoreCase(Protocol.deleteroom.toString())) {
                        // {"type" : "deleteroom", "roomid" : "jokes"}
                        String deleteRoomId = (String) jsonMessage.get(Protocol.roomid.toString());
                        boolean roomExistedLocally = serverState.isRoomExistedLocally(deleteRoomId);
                        if (roomExistedLocally) {
                            LocalChatRoomInfo deletingRoom = serverState.getLocalChatRooms().get(deleteRoomId);
                            if (deletingRoom.getOwner().equalsIgnoreCase(userInfo.getIdentity())) {

                                userInfo.setRoomOwner(false);
                                userInfo.setCurrentChatRoom(mainHall);

                                doDeleteRoomProtocol(deletingRoom);

                                broadcastMessageToRoom(roomChange(deleteRoomId, mainHall), deleteRoomId);
                                broadcastMessageToRoom(roomChange(deleteRoomId, mainHall), mainHall);

                                write(deleteRoom(deleteRoomId, "true"));
                                //messageQueue.add(new Message(false, deleteRoom(deleteRoomId, "true")));
                            } else {
                                messageQueue.add(new Message(false, deleteRoom(deleteRoomId, "false")));
                            }
                        } else {
                            messageQueue.add(new Message(false, deleteRoom(deleteRoomId, "false")));
                        }
                    }

                    if (type.equalsIgnoreCase(Protocol.message.toString())) {
                        // {"type" : "message", "content" : "Hi there!"}
                        String content = (String) jsonMessage.get(Protocol.content.toString());
                        broadcastMessageToRoom(message(content), userInfo.getCurrentChatRoom(), userInfo.getIdentity());
                    }

                    if (type.equalsIgnoreCase(Protocol.quit.toString())) {
                        //{"type" : "roomchange", "identity" : "Adel", "former" : "MainHall-s1", "roomid" : ""}

                        String former = userInfo.getCurrentChatRoom();

                        doGracefulQuit();

                        // update about quitting user
                        if (userInfo.isRoomOwner()) {
                            broadcastMessageToRoom(roomChange(former, ""), mainHall, userInfo.getIdentity());
                        } else {
                            broadcastMessageToRoom(roomChange(former, ""), former, userInfo.getIdentity());
                        }

                        write(roomChange(former, ""));
                        break;
                    }

                } else {
                    logger.debug("Sending  : " + msg.getMessage());
                    write(msg.getMessage());
                }
            }

            pool.shutdown();
            clientSocket.close();
            if (userInfo != null) {
                logger.info("Client disconnected: " + userInfo.getIdentity());
            } else {
                logger.info("Can not create UserInfo. Identity might already in use.");
            }

        } catch (Exception e) {
            logger.trace(e.getMessage());
            pool.shutdownNow();
        }
    }

    private void doGracefulQuit() {
        String former = userInfo.getCurrentChatRoom();

        // remove user from room
        serverState.getLocalChatRooms().get(former).removeMember(userInfo.getIdentity());

        // follow delete room protocol if owner
        if (userInfo.isRoomOwner()) {
            doDeleteRoomProtocol(serverState.getLocalChatRooms().get(former));
        }

        // remove user
        serverState.getConnectedClients().remove(userInfo.getIdentity());
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
        jj.put(Protocol.port.toString(), port.toString());
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
            if (client.getIdentity().equalsIgnoreCase(userInfo.getIdentity())) continue;

            // TODO option#1 - work from this thread, option#2 - work on each connected client thread
            client.setCurrentChatRoom(mainHall);
            String msg = roomChange(deletingRoom.getChatRoomId(), mainHall, client.getIdentity());
            broadcastMessageToRoom(msg, deletingRoom.getChatRoomId());
            broadcastMessageToRoom(msg, mainHall);
        }

        // delete the room
        serverState.getLocalChatRooms().remove(deletingRoom.getChatRoomId());

        // inform peers
        peerClient.relayPeers(deleteRoomPeers(deletingRoom.getChatRoomId()));
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
        return roomChange(former, roomId, userInfo.getIdentity());
    }

    private String roomChange(String former, String roomId, String identity) {
        // {"type" : "roomchange", "identity" : "Maria", "former" : "jokes", "roomid" : "jokes"}
        JSONObject jj = new JSONObject();
        jj.put(Protocol.type.toString(), Protocol.roomchange.toString());
        jj.put(Protocol.identity.toString(), identity);
        jj.put(Protocol.former.toString(), former);
        jj.put(Protocol.roomid.toString(), roomId);
        return jj.toJSONString();
    }

    private void broadcastMessageToRoom(String message, String room) {
        Message msg = new Message(false, message);

        Map<String, UserInfo> connectedClients = serverState.getConnectedClients();

        connectedClients.values().stream()
                .filter(client -> client.getCurrentChatRoom().equalsIgnoreCase(room))
                .forEach(client -> {
            client.getManagingThread().getMessageQueue().add(msg);
        });
    }

    private void broadcastMessageToRoom(String message, String room, String exceptUserId) {
        Message msg = new Message(false, message);

        Map<String, UserInfo> connectedClients = serverState.getConnectedClients();

        connectedClients.values().stream()
                .filter(client -> client.getCurrentChatRoom().equalsIgnoreCase(room))
                .filter(client -> !client.getIdentity().equalsIgnoreCase(exceptUserId))
                .forEach(client -> {
                    client.getManagingThread().getMessageQueue().add(msg);
                });
    }

    public BlockingQueue<Message> getMessageQueue() {
        return messageQueue;
    }

    private void write(String msg) {
        try {
            writer.write(msg + "\n");
            writer.flush();

            logger.trace("Message flush");

        } catch (IOException e) {
            logger.trace(e.getMessage());
        }
    }

    private static final Logger logger = LogManager.getLogger(ClientConnectionHandler.class);
}
