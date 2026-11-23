package org.tortodrone.core.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;

/** Listener, logging, chat notice and initial map settings; legacy selection is migration-only. */
public record ServerSettings(String bind, int port, int newsPort, LogMode logMode, int maxClients, int logMaxMiB, int logRetention, String contentPacks, String legacyContent, String startupMap, String welcomeMessage, org.tortodrone.core.config.WebSettings webui) {
    /** Printed once in the chat log when a knight first enters the world after login; empty disables it. */
    public static final String DEFAULT_WELCOME_MESSAGE="Welcome to Tortodrone!";
    public ServerSettings(String bind,int port,int newsPort,LogMode mode,int clients,int maxMiB,int retention,String packs,String legacy,String startupMap){this(bind,port,newsPort,mode,clients,maxMiB,retention,packs,legacy,startupMap,DEFAULT_WELCOME_MESSAGE,org.tortodrone.core.config.WebSettings.DISABLED);}
    /** One port shared by the WebUI and stock-client content_url News images. */
    public static final String HTTP_PORT_SETTING="webui/content_url-port";
    public static final int DEFAULT_HTTP_PORT=8080;
    public static final int DEFAULT_NEWS_PORT=DEFAULT_HTTP_PORT;
    public int httpPort(){return newsPort;}
    public ServerSettings(String bind,int port,LogMode mode,int clients,int maxMiB,int retention,String packs,String legacy){this(bind,port,DEFAULT_NEWS_PORT,mode,clients,maxMiB,retention,packs,legacy,"haven");}
    public ServerSettings(String bind,int port,LogMode mode,int clients,int maxMiB,int retention) { this(bind,port,mode,clients,maxMiB,retention,"vanilla","auto"); }
    public ServerSettings(String bind,int port,LogMode mode,int clients) { this(bind,port,mode,clients,10,5); }
    public enum LogMode { NORMAL, DEBUG }
    /** Unified config/server.yml; preserve an existing properties-only deployment until migration. */
    public static Path defaultPath(Path deployment) throws IOException { return ServerSettingsReader.defaultPath(deployment); }
    public static ServerSettings load(Path file) throws IOException { return ServerSettingsReader.load(file); }
    /** Read defaults and optional settings without creating configuration or persistent state. */
    public static Properties properties(Path file) throws IOException { return ServerSettingsReader.properties(file); }
    public static ServerSettings read(Path file) throws IOException { return ServerSettingsReader.read(file); }
    /** Validate a configured welcome notice; the empty string disables it. */
    public static String welcomeMessage(String message)throws IOException { return ServerSettingsReader.welcomeMessage(message); }
    public static String startupMap(Path file)throws IOException { return ServerSettingsReader.startupMap(file); }
}
