package com.sankholin.comp90015.assignment1.chat.server;

import com.opencsv.CSVReader;
import com.opencsv.bean.ColumnPositionMappingStrategy;
import com.opencsv.bean.CsvToBean;
import com.sankholin.comp90015.assignment1.chat.server.model.LocalChatRoomInfo;
import com.sankholin.comp90015.assignment1.chat.server.model.Protocol;
import com.sankholin.comp90015.assignment1.chat.server.model.RemoteChatRoomInfo;
import com.sankholin.comp90015.assignment1.chat.server.model.ServerInfo;
import com.sankholin.comp90015.assignment1.chat.server.service.*;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChatServer {

    @Option(name = "-n", usage = "n=Server ID")
    private String serverId = "s1";

    @Option(name = "-l", usage = "l=Server Configuration File")
    private String serverConfig = "./server.tab";

    @Option(name = "-d", usage = "d=Debug")
    private boolean debug = false;

    @Option(name = "--trace", usage = "trace=Trace")
    private boolean trace = false;

    private ServerState serverState = ServerState.getInstance();
    private ServerInfo serverInfo;
    private ExecutorService servicePool;
    private String mainHall;

    public ChatServer(String[] args) {
        try {
            CmdLineParser cmdLineParser = new CmdLineParser(this);
            logger.info("Parsing args...");
            cmdLineParser.parseArgument(args);

            logger.info("option: -n " + serverId);
            logger.info("option: -l " + serverConfig);
            logger.info("option: -d " + debug);

            logger.info("Reading server config");
            readServerConfiguration();

            logger.info("Init server state");
            serverState.initServerState(serverId);

            serverInfo = serverState.getServerInfo();

            updateLogger();

            // POST

            mainHall = "MainHall-" + serverInfo.getServerId();
            LocalChatRoomInfo localChatRoomInfo = new LocalChatRoomInfo();
            localChatRoomInfo.setOwner(""); //The owner of the MainHall in each server is "" (empty string)
            localChatRoomInfo.setChatRoomId(mainHall);
            serverState.getLocalChatRooms().put(mainHall, localChatRoomInfo);

            startUpConnections();

            syncChatRooms();

            // Shutdown hook
            Runtime.getRuntime().addShutdownHook(new ShutdownService(servicePool));

        } catch (CmdLineException e) {
            logger.trace(e.getMessage());
        }
    }

    private void readServerConfiguration() {
        ColumnPositionMappingStrategy<ServerInfo> strategy = new ColumnPositionMappingStrategy<>();
        strategy.setType(ServerInfo.class);
        CsvToBean<ServerInfo> csvToBean = new CsvToBean<>();
        try {
            serverState.setServerInfoList(csvToBean.parse(strategy, new CSVReader(new FileReader(serverConfig), '\t')));
        } catch (FileNotFoundException e) {
            logger.trace(e.getMessage());
        }
    }

    private void updateLogger() {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();
        LoggerConfig loggerConfig = config.getLoggerConfig("com.sankholin");

        if (debug && !trace) {
            loggerConfig.setLevel(Level.DEBUG);
            ctx.updateLoggers();
            logger.debug("Server is running in DEBUG mode");
        }

        if (trace) {
            loggerConfig.setLevel(Level.TRACE);
            ctx.updateLoggers();
            logger.trace("Server is running in TRACE mode");
        }
    }

    private void startUpConnections() {
        servicePool = Executors.newFixedThreadPool(SERVER_SOCKET_POOL);
        try {
            servicePool.execute(new ClientService(serverInfo.getPort(), CLIENT_SOCKET_POOL));
            servicePool.execute(new ManagementService(serverInfo.getManagementPort(), serverState.getServerInfoList().size()));
        } catch (IOException e) {
            logger.trace(e.getMessage());
            servicePool.shutdown();
        }
    }

    private void syncChatRooms() {
        PeerClient peerClient = new PeerClient();
        JSONMessageBuilder messageBuilder = JSONMessageBuilder.getInstance();
        JSONParser parser = new JSONParser();

        for (ServerInfo server : serverState.getServerInfoList()) {
            if (server.equals(serverInfo)) continue;

            if (serverState.isOnline(server)) {
                // promote my main hall
                peerClient.commPeer(server, messageBuilder.lockRoom(mainHall));
                peerClient.commPeer(server, messageBuilder.releaseRoom(mainHall, "true"));

                // accept theirs
                String key = "MainHall-" + server.getServerId();
                if (serverState.isRoomExistedRemotely(key)) continue;
                RemoteChatRoomInfo remoteChatRoomInfo = new RemoteChatRoomInfo();
                remoteChatRoomInfo.setChatRoomId(key);
                remoteChatRoomInfo.setManagingServer(server.getServerId());
                serverState.getRemoteChatRooms().put(key, remoteChatRoomInfo);

                String resp = peerClient.commServerSingleResp(server, messageBuilder.listRoomsClient());
                if (resp != null) {
                    try {
                        JSONObject jsonMessage = (JSONObject) parser.parse(resp);
                        JSONArray ja = (JSONArray) jsonMessage.get(Protocol.rooms.toString());
                        for (Object o : ja.toArray()) {
                            String room = (String) o;
                            if (room.equalsIgnoreCase(key) || room.equalsIgnoreCase(mainHall)) continue;
                            RemoteChatRoomInfo remoteRoom = new RemoteChatRoomInfo();
                            remoteRoom.setChatRoomId(room);
                            remoteRoom.setManagingServer(server.getServerId());
                            serverState.getRemoteChatRooms().put(room, remoteRoom);
                        }
                    } catch (ParseException e) {
                        //e.printStackTrace();
                        logger.trace(e.getMessage());
                    }
                }
            }
        }
    }

    private static final int SERVER_SOCKET_POOL = 2;
    private static final int CLIENT_SOCKET_POOL = 100;
    private static final Logger logger = LogManager.getLogger(ChatServer.class);
}
