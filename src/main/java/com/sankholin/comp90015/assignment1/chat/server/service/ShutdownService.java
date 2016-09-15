package com.sankholin.comp90015.assignment1.chat.server.service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ShutdownService extends Thread {

    private ServerState serverState = ServerState.getInstance();
    private Thread mainThread;

    public ShutdownService(Thread mainThread) {
        this.mainThread = mainThread;
    }

    @Override
    public void run() {
        logger.info("Server is shutting down ...");

        serverState.stopRunning(true);

        try {
            mainThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

/*
        int counter = 0;
        do {
            try {
                logger.info("Waiting services to be shutting down.");
                Thread.sleep(this.retryTimer * 1000L);
            } catch (InterruptedException e) {
                logger.warn("Shutting down process failed to halt, this may lose some data.", e);
            }

            ++counter;
        } while ((SOME_HEAVY_JOB_STILL_RUNNING && (counter < 6)));
*/

        logger.info("Server shutting down process finished.");

    }

    private final long retryTimer = 10L; //in seconds
    private static final Logger logger = LogManager.getLogger(ShutdownService.class);
}
