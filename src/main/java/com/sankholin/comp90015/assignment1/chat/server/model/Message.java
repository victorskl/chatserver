package com.sankholin.comp90015.assignment1.chat.server.model;

public class Message {

    private boolean isFromClient;
    private String message;

    public Message(boolean isFromClient, String message) {
        this.isFromClient = isFromClient;
        this.message = message;
    }

    public boolean isFromClient() {
        return isFromClient;
    }

    public String getMessage() {
        return message;
    }
}
