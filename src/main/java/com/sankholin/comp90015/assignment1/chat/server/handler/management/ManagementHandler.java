package com.sankholin.comp90015.assignment1.chat.server.handler.management;

import com.sankholin.comp90015.assignment1.chat.server.model.Message;
import com.sankholin.comp90015.assignment1.chat.server.service.JSONMessageBuilder;
import com.sankholin.comp90015.assignment1.chat.server.service.ManagementConnection;
import com.sankholin.comp90015.assignment1.chat.server.service.ServerState;
import org.json.simple.JSONObject;

import java.net.Socket;
import java.util.concurrent.BlockingQueue;

public class ManagementHandler {

    protected JSONMessageBuilder messageBuilder = JSONMessageBuilder.getInstance();
    protected ServerState serverState = ServerState.getInstance();

    protected BlockingQueue<Message> messageQueue;
    private Socket clientSocket;
    protected JSONObject jsonMessage;
    private ManagementConnection managementConnection;

    public ManagementHandler(JSONObject jsonMessage, Runnable connection) {
        this.jsonMessage = jsonMessage;

        this.managementConnection = (ManagementConnection) connection;
        this.messageQueue = managementConnection.getMessageQueue();
        this.clientSocket = managementConnection.getClientSocket();
    }
}
