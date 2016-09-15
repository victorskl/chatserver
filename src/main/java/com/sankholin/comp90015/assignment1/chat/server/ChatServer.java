package com.sankholin.comp90015.assignment1.chat.server;

import com.opencsv.CSVReader;
import com.opencsv.bean.ColumnPositionMappingStrategy;
import com.opencsv.bean.CsvToBean;
import com.sankholin.comp90015.assignment1.chat.server.model.LocalChatRoomInfo;
import com.sankholin.comp90015.assignment1.chat.server.model.ServerInfo;
import com.sankholin.comp90015.assignment1.chat.server.service.ClientConnection;
import com.sankholin.comp90015.assignment1.chat.server.service.PeerServerConnection;
import com.sankholin.comp90015.assignment1.chat.server.service.ServerState;
import com.sankholin.comp90015.assignment1.chat.server.service.ShutdownService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class ChatServer {

    @Option(name = "-n", usage = "n=Server ID")
    private String serverId = "s1";

    @Option(name = "-l", usage = "l=Server Configuration File")
    private String serverConfig = "./server.tab";

    private ServerState serverState = ServerState.getInstance();
    private ServerInfo serverInfo;

    public ChatServer(String[] args) {
        try {
            CmdLineParser parser = new CmdLineParser(this);
            logger.info("Parsing args...");
            parser.parseArgument(args);

            logger.info("option: -s " + serverId);
            logger.info("option: -l " + serverConfig);

            logger.info("Reading server config...");
            readServerConfiguration();

            logger.info("Init server state...");
            serverState.initServerState(serverId);

            serverInfo = serverState.getServerInfo();

            // POST

            Runtime.getRuntime().addShutdownHook(new ShutdownService(Thread.currentThread()));

            setupMainHall();
            startUpConnections();

        } catch (CmdLineException e) {
            e.printStackTrace();
        }
    }

    private void readServerConfiguration() {
        ColumnPositionMappingStrategy<ServerInfo> strategy = new ColumnPositionMappingStrategy<>();
        strategy.setType(ServerInfo.class);
        CsvToBean<ServerInfo> csvToBean = new CsvToBean<>();
        try {
            serverState.setServerInfoList(csvToBean.parse(strategy, new CSVReader(new FileReader(serverConfig), '\t')));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
    }

    private void setupMainHall() {
        LocalChatRoomInfo mainHall = new LocalChatRoomInfo();
        mainHall.setOwner(""); //The owner of the MainHall in each server is "" (empty string)
        mainHall.setChatRoomId("MainHall-" + serverInfo.getServerId());
        serverState.getLocalChatRooms().put("MainHall-" + serverInfo.getServerId(), mainHall);
    }

    private void startUpConnections() {

        new Thread(
                () -> {
                    ServerSocket clientServingSocket = null;
                    try {
                        clientServingSocket = new ServerSocket(serverInfo.getPort());
                        logger.info("Server listening client on port "+ serverInfo.getPort() +" for a connection...");

                        //int clientNum = 0;

                        while (!serverState.isStopRunning()) {
                            //clientNum++;

                            //Create one thread per connection, each thread will be
                            //responsible for listening for messages from the client, placing them
                            //in a queue, and creating another thread that processes the messages
                            //placed in the queue
                            Socket clientSocket = clientServingSocket.accept();
                            ClientConnection clientConnection = new ClientConnection(clientSocket);
                            //clientConnection.setName("Thread" + clientNum);
                            //clientConnection.start();
                            new Thread(clientConnection).start();

                            //Register the new connection with the client manager
                            //ServerState.getInstance().clientConnected(clientConnection);
                        }

                        logger.info("Closing client socket: " + clientServingSocket.getLocalPort());
                        clientServingSocket.close();
                        logger.info("DONE!");

                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        if (clientServingSocket != null) {
                            try {
                                clientServingSocket.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
        ).start();

        new Thread(
                ()-> {
                    ServerSocket managementSocket = null;
                    try {
                        managementSocket = new ServerSocket(serverInfo.getManagementPort());
                        logger.info("Server listening peer on management port "+ serverInfo.getManagementPort() +" for a connection...");

                        //int clientNum = 0;

                        while (!serverState.isStopRunning()) {
                            //clientNum++;

                            Socket managementClientSocket = managementSocket.accept();
                            PeerServerConnection peerServerConnection = new PeerServerConnection(managementClientSocket);
                            new Thread(peerServerConnection).start();

                            //Register the new connection with the client manager
                            //ServerState.getInstance().clientConnected(clientConnection);
                        }

                        logger.info("Closing management socket: " + managementSocket.getLocalPort());
                        managementSocket.close();
                        logger.info("DONE!");

                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        if (managementSocket != null) {
                            try {
                                managementSocket.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
        ).start();
    }

    private static final Logger logger = LogManager.getLogger(ChatServer.class);
}
