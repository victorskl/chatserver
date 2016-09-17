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
import org.json.simple.JSONObject;
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

    private ServerState serverState = ServerState.getInstance();
    private ServerInfo serverInfo;
    private ExecutorService servicePool;

    public ChatServer(String[] args) {
        try {
            CmdLineParser parser = new CmdLineParser(this);
            logger.info("Parsing args...");
            parser.parseArgument(args);

            logger.info("option: -s " + serverId);
            logger.info("option: -l " + serverConfig);
            logger.info("option: -d " + debug);

            logger.info("Reading server config");
            readServerConfiguration();

            logger.info("Init server state");
            serverState.initServerState(serverId);

            serverInfo = serverState.getServerInfo();

            updateLogger();

            // POST
            setupMainHall();
            startUpConnections();
            publishMainHall();

            // Shutdown hook
            Runtime.getRuntime().addShutdownHook(new ShutdownService(servicePool));

        } catch (CmdLineException e) {
            logger.trace(e.getMessage());
        }
    }

    private void publishMainHall() {
        String roomId = "MainHall-" + serverInfo.getServerId();
        PeerClient peerClient = new PeerClient();
        for (ServerInfo server : serverState.getServerInfoList()) {
            if (server.equals(serverInfo)) continue;

            if (serverState.isOnline(server)) {
                // promote my main hall
                JSONObject jj = new JSONObject();
                //lockRoom()
                jj.put(Protocol.type.toString(), Protocol.lockroomid.toString());
                jj.put(Protocol.serverid.toString(), serverInfo.getServerId());
                jj.put(Protocol.roomid.toString(), roomId);
                peerClient.commPeer(server, jj.toJSONString());
                jj.clear();
                //releaseRoom()
                jj.put(Protocol.type.toString(), Protocol.releaseroomid.toString());
                jj.put(Protocol.serverid.toString(), serverInfo.getServerId());
                jj.put(Protocol.roomid.toString(), roomId);
                jj.put(Protocol.approved.toString(), "true");
                peerClient.commPeer(server, jj.toJSONString());

                // accept theirs
                String key = "MainHall-" + server.getServerId();
                if (serverState.isRoomExistedRemotely(key)) continue;
                RemoteChatRoomInfo remoteChatRoomInfo = new RemoteChatRoomInfo();
                remoteChatRoomInfo.setChatRoomId(key);
                remoteChatRoomInfo.setManagingServer(server.getServerId());
                serverState.getRemoteChatRooms().put(key, remoteChatRoomInfo);
            }
        }
    }

    private void updateLogger() {
        if (debug) {
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            Configuration config = ctx.getConfiguration();
            LoggerConfig loggerConfig = config.getLoggerConfig("com.sankholin");
            loggerConfig.setLevel(Level.DEBUG);
            ctx.updateLoggers();
            logger.debug("Server is running in DEBUG mode");
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

    private void setupMainHall() {
        LocalChatRoomInfo mainHall = new LocalChatRoomInfo();
        mainHall.setOwner(""); //The owner of the MainHall in each server is "" (empty string)
        mainHall.setChatRoomId("MainHall-" + serverInfo.getServerId());
        serverState.getLocalChatRooms().put("MainHall-" + serverInfo.getServerId(), mainHall);
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

    private static final int SERVER_SOCKET_POOL = 2;
    private static final int CLIENT_SOCKET_POOL = 100;
    private static final Logger logger = LogManager.getLogger(ChatServer.class);
}
