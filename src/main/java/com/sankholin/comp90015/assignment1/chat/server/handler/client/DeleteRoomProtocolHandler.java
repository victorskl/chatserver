package com.sankholin.comp90015.assignment1.chat.server.handler.client;

import com.sankholin.comp90015.assignment1.chat.server.handler.IProtocolHandler;
import com.sankholin.comp90015.assignment1.chat.server.model.LocalChatRoomInfo;
import com.sankholin.comp90015.assignment1.chat.server.model.Message;
import com.sankholin.comp90015.assignment1.chat.server.model.Protocol;
import org.json.simple.JSONObject;

public class DeleteRoomProtocolHandler extends CommonHandler implements IProtocolHandler {

    public DeleteRoomProtocolHandler(JSONObject jsonMessage, Runnable connection) {
        super(jsonMessage, connection);
    }

    @Override
    public void handle() {
        // {"type" : "deleteroom", "roomid" : "jokes"}
        String deleteRoomId = (String) jsonMessage.get(Protocol.roomid.toString());
        boolean roomExistedLocally = serverState.isRoomExistedLocally(deleteRoomId);
        if (roomExistedLocally) {
            LocalChatRoomInfo deletingRoom = serverState.getLocalChatRooms().get(deleteRoomId);
            if (deletingRoom.getOwner().equalsIgnoreCase(userInfo.getIdentity())) {

                userInfo.setRoomOwner(false);
                userInfo.setCurrentChatRoom(mainHall);

                doDeleteRoomProtocol(deletingRoom);

                broadcastMessageToRoom(messageBuilder.roomChange(deleteRoomId, mainHall, userInfo.getIdentity()), deleteRoomId);
                broadcastMessageToRoom(messageBuilder.roomChange(deleteRoomId, mainHall, userInfo.getIdentity()), mainHall);

                write(messageBuilder.deleteRoom(deleteRoomId, "true"));
                //messageQueue.add(new Message(false, deleteRoom(deleteRoomId, "true")));
            } else {
                messageQueue.add(new Message(false, messageBuilder.deleteRoom(deleteRoomId, "false")));
            }
        } else {
            messageQueue.add(new Message(false, messageBuilder.deleteRoom(deleteRoomId, "false")));
        }
    }
}
