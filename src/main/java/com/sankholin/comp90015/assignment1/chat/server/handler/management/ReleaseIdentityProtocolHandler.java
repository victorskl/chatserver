package com.sankholin.comp90015.assignment1.chat.server.handler.management;

import com.sankholin.comp90015.assignment1.chat.server.handler.IProtocolHandler;
import com.sankholin.comp90015.assignment1.chat.server.model.Message;
import com.sankholin.comp90015.assignment1.chat.server.model.Protocol;
import org.json.simple.JSONObject;

public class ReleaseIdentityProtocolHandler extends ManagementHandler implements IProtocolHandler {

    public ReleaseIdentityProtocolHandler(JSONObject jsonMessage, Runnable connection) {
        super(jsonMessage, connection);
    }

    @Override
    public void handle() {
        // {"type" : "releaseidentity", "serverid" : "s1", "identity" : "Adel"}
        String requestUserId = (String) jsonMessage.get(Protocol.identity.toString());
        String serverId = (String) jsonMessage.get(Protocol.serverid.toString());
        String lok = serverId.concat(requestUserId);
        serverState.unlockIdentity(lok);
        messageQueue.add(new Message(false, "exit"));
    }
}
