package com.sankholin.comp90015.assignment1.chat.server.handler.management;

import com.sankholin.comp90015.assignment1.chat.server.handler.IProtocolHandler;
import com.sankholin.comp90015.assignment1.chat.server.model.Message;
import com.sankholin.comp90015.assignment1.chat.server.model.Protocol;
import org.json.simple.JSONObject;

public class DeleteRoomServerProtocolHandler extends ManagementHandler implements IProtocolHandler {

    public DeleteRoomServerProtocolHandler(JSONObject jsonMessage, Runnable connection) {
        super(jsonMessage, connection);
    }

    @Override
    public void handle() {
        //{"type" : "deleteroom", "serverid" : "s1", "roomid" : "jokes"}
        String deletingRoomId = (String) jsonMessage.get(Protocol.roomid.toString());
        serverState.getRemoteChatRooms().remove(deletingRoomId);
        messageQueue.add(new Message(false, "exit"));
    }
}
