package com.sankholin.comp90015.assignment1.chat.server.handler.client;

import com.sankholin.comp90015.assignment1.chat.server.handler.IProtocolHandler;
import com.sankholin.comp90015.assignment1.chat.server.model.Protocol;
import org.json.simple.JSONObject;

public class MessageProtocolHandler extends CommonHandler implements IProtocolHandler {

    public MessageProtocolHandler(JSONObject jsonMessage, Runnable connection) {
        super(jsonMessage, connection);
    }

    @Override
    public void handle() {
        // {"type" : "message", "content" : "Hi there!"}
        String content = (String) jsonMessage.get(Protocol.content.toString());
        broadcastMessageToRoom(messageBuilder.message(userInfo.getIdentity(), content), userInfo.getCurrentChatRoom(), userInfo.getIdentity());
    }
}
