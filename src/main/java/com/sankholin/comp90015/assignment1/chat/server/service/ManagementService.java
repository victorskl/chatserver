package com.sankholin.comp90015.assignment1.chat.server.service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ManagementService implements Runnable {

    private final ServerSocket serverSocket;
    private final ExecutorService pool;

    public ManagementService(int port, int poolSize) throws IOException {
        serverSocket = new ServerSocket(port);
        pool = Executors.newFixedThreadPool(poolSize);
    }

    @Override
    public void run() {
        try {

            logger.info("Server listening peer on management port "+ serverSocket.getLocalPort() +" for a connection...");

            for (;;) {
                pool.execute(new ClientConnection(serverSocket.accept()));
            }
        } catch (IOException ex) {
            pool.shutdown();
        }
    }

    private static final Logger logger = LogManager.getLogger(ManagementService.class);
}
