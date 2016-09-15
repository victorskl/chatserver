package com.sankholin.comp90015.assignment1.chat.server.model;

public class RemoteChatRoomInfo extends ChatRoomInfo {
    private String managingServer;

    public String getManagingServer() {
        return managingServer;
    }

    public void setManagingServer(String managingServer) {
        this.managingServer = managingServer;
    }
}
