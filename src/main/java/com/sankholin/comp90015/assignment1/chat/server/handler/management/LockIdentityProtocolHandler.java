package com.sankholin.comp90015.assignment1.chat.server.handler.management;

import com.sankholin.comp90015.assignment1.chat.server.handler.IProtocolHandler;
import com.sankholin.comp90015.assignment1.chat.server.handler.management.ManagementHandler;
import com.sankholin.comp90015.assignment1.chat.server.model.Message;
import com.sankholin.comp90015.assignment1.chat.server.model.Protocol;
import org.json.simple.JSONObject;

public class LockIdentityProtocolHandler extends ManagementHandler implements IProtocolHandler {

    public LockIdentityProtocolHandler(JSONObject jsonMessage, Runnable connection) {
        super(jsonMessage, connection);
    }

    @Override
    public void handle() {
        // {"type" : "lockidentity", "serverid" : "s1", "identity" : "Adel"}
        String requestUserId = (String) jsonMessage.get(Protocol.identity.toString());
        String serverId = (String) jsonMessage.get(Protocol.serverid.toString());
        String lok = serverId.concat(requestUserId);

        boolean isUserExisted = serverState.isUserExisted(requestUserId);
        boolean isUserLocked = serverState.isIdentityLocked(lok);

        if (isUserExisted || isUserLocked) {
            messageQueue.add(new Message(false, messageBuilder.lockIdentity(serverId, requestUserId, "false")));
        } else {
            serverState.lockIdentity(lok);
            messageQueue.add(new Message(false, messageBuilder.lockIdentity(serverId, requestUserId, "true")));
        }
    }
}
