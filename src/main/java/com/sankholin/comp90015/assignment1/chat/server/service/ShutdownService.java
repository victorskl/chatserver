package com.sankholin.comp90015.assignment1.chat.server.service;

import com.sankholin.comp90015.assignment1.chat.server.model.Protocol;
import com.sankholin.comp90015.assignment1.chat.server.model.ServerInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.simple.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public class ShutdownService extends Thread {

    private ServerState serverState = ServerState.getInstance();
    private ServerInfo serverInfo = serverState.getServerInfo();
    private ExecutorService pool;

    public ShutdownService(ExecutorService pool) {
        this.pool = pool;
    }

    @Override
    public void run() {
        logger.info("Server is shutting down. Please wait...");

        serverState.stopRunning(true);

        pool.shutdown();

        removeMyMainHallOnPeers();

        try {
            if (!pool.awaitTermination(SHUTDOWN_TIME, TimeUnit.MILLISECONDS)) {
                logger.warn("Executor pool did not shutdown in the specified time.");
                List<Runnable> droppedTasks = pool.shutdownNow();
                logger.warn("Executor pool was abruptly shutdown. " + droppedTasks.size() + " tasks will not be executed.");
            }
        } catch (InterruptedException e) {
            logger.trace(e.getMessage());
            //e.printStackTrace();
        }
    }

    private void removeMyMainHallOnPeers() {
        String roomId = "MainHall-" + serverInfo.getServerId();
        PeerClient peerClient = new PeerClient();

        //deleteRoomPeers()
        JSONObject jj = new JSONObject();
        jj.put(Protocol.type.toString(), Protocol.deleteroom.toString());
        jj.put(Protocol.serverid.toString(), serverInfo.getServerId());
        jj.put(Protocol.roomid.toString(), roomId);

        for (ServerInfo server : serverState.getServerInfoList()) {
            if (server.equals(serverInfo)) continue;
            peerClient.commPeer(server, jj.toJSONString());
        }
    }

    private static final long SHUTDOWN_TIME = TimeUnit.SECONDS.toMillis(10);
    private static final Logger logger = LogManager.getLogger(ShutdownService.class);
}
