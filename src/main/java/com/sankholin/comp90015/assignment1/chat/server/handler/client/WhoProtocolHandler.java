package com.sankholin.comp90015.assignment1.chat.server.handler.client;

import com.sankholin.comp90015.assignment1.chat.server.handler.IProtocolHandler;
import com.sankholin.comp90015.assignment1.chat.server.model.Message;
import org.json.simple.JSONObject;

public class WhoProtocolHandler extends CommonHandler implements IProtocolHandler {

    public WhoProtocolHandler(JSONObject jsonMessage, Runnable connection) {
        super(jsonMessage, connection);
    }

    @Override
    public void handle() {
        messageQueue.add(new Message(false, messageBuilder.whoByRoom(userInfo.getCurrentChatRoom())));
    }
}
