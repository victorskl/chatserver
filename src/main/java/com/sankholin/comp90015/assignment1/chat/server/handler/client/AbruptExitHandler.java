package com.sankholin.comp90015.assignment1.chat.server.handler.client;

import com.sankholin.comp90015.assignment1.chat.server.handler.IProtocolHandler;
import org.json.simple.JSONObject;

public class AbruptExitHandler extends CommonHandler implements IProtocolHandler {

    public AbruptExitHandler(JSONObject jsonMessage, Runnable connection) {
        super(jsonMessage, connection);
    }

    @Override
    public void handle() {
        if (userInfo != null) {
            doGracefulQuit();
            if (!clientConnection.isRouted()) {
                String former = userInfo.getCurrentChatRoom();
                broadcastMessageToRoom(messageBuilder.roomChange(former, "", userInfo.getIdentity()), former, userInfo.getIdentity());
            }
        }
    }
}
