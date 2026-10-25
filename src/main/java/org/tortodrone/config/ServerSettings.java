package org.tortodrone.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

/** Listener, logging, chat notice and initial map settings; legacy selection is migration-only. */
public record ServerSettings(String bind, int port, int newsPort, LogMode logMode, int maxClients, int logMaxMiB, int logRetention, String contentPacks, String legacyContent, String startupMap, String welcomeMessage, org.tortodrone.web.WebSettings webui) {
    /** Printed once in the chat log when a knight first enters the world after login; empty disables it. */
    public static final String DEFAULT_WELCOME_MESSAGE="Welcome to Tortodrone!";
    private static final int MAX_WELCOME_CHARACTERS=256;
    public ServerSettings(String bind,int port,int newsPort,LogMode mode,int clients,int maxMiB,int retention,String packs,String legacy,String startupMap){this(bind,port,newsPort,mode,clients,maxMiB,retention,packs,legacy,startupMap,DEFAULT_WELCOME_MESSAGE,org.tortodrone.web.WebSettings.DISABLED);}
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
    public static Path defaultPath(Path deployment) throws IOException {
        Path yaml=deployment.resolve("config/server.yml"),old=deployment.resolve("data/server.properties");
        Path file=Files.exists(yaml)||!Files.exists(old)?yaml:old;
        org.tortodrone.resources.ContentFiles.safe(file);
        return file;
    }
    public static ServerSettings load(Path file) throws IOException {
        file=file.toAbsolutePath().normalize();
        org.tortodrone.resources.ContentFiles.safe(file);
        if (!Files.exists(file)) {
            Files.createDirectories(file.getParent());
            try (InputStream source=ServerSettings.class.getResourceAsStream(file.toString().endsWith(".yml")?"/server.yml":"/server.properties")) {
                if(source==null)throw new IOException("Missing bundled server.properties");
                Files.write(file,source.readAllBytes(),StandardOpenOption.CREATE_NEW);
            }
        }
        return read(file);
    }
    /** Read defaults and optional settings without creating configuration or persistent state. */
    public static Properties properties(Path file) throws IOException {
        Properties defaults=new Properties();
        try(InputStream source=ServerSettings.class.getResourceAsStream("/server.properties")) {
            if(source==null)throw new IOException("Missing bundled server.properties");
            defaults.load(source);
        }
        Properties settings=new Properties();settings.putAll(defaults);
        Properties overrides=new Properties();
        org.tortodrone.resources.ContentFiles.safe(file);
        if(Files.exists(file)) {
            if(file.toString().endsWith(".yml")) {
                for(var e:org.tortodrone.resources.Yaml.read(file).entrySet()) {
                    if(java.util.Set.of("content-packs","legacy-content").contains(e.getKey()))throw new IOException(file+": pack settings are migration-only; use unified content directories");
                    if(!defaults.containsKey(e.getKey())&&!e.getKey().equals("news-port"))throw new IOException(file+": unknown server setting "+e.getKey());
                    if(!(e.getValue() instanceof String||e.getValue() instanceof Number||e.getValue() instanceof Boolean))throw new IOException(file+": expected scalar setting "+e.getKey());
                    overrides.setProperty(e.getKey(),e.getValue().toString());
                }
            } else overrides.putAll(org.tortodrone.resources.ContentFiles.properties(file));
        }
        for(String name:overrides.stringPropertyNames())
            if(!defaults.containsKey(name)&&!name.equals("news-port"))throw new IOException("Unknown server property: "+name);
        if(overrides.containsKey("news-port")) {
            int legacy=number(overrides,"news-port",0,65535);
            if(overrides.containsKey(HTTP_PORT_SETTING)&&number(overrides,HTTP_PORT_SETTING,0,65535)!=legacy)
                throw new IOException("news-port and "+HTTP_PORT_SETTING+" configure the same listener and must agree; keep only "+HTTP_PORT_SETTING);
            overrides.setProperty(HTTP_PORT_SETTING,Integer.toString(legacy));
        }
        settings.putAll(overrides);
        return settings;
    }
    public static ServerSettings read(Path file) throws IOException {
        Properties settings=properties(file);
        String bind = value(settings, "server-ip");
        if (bind.isEmpty()) bind = "0.0.0.0";
        int port;
        try {
            port = Integer.parseInt(value(settings, "server-port"));
        } catch (NumberFormatException e) {
            throw new IOException("server-port must be an integer from 0 to 65535");
        }
        if (port < 0 || port > 65535) throw new IOException("server-port must be from 0 to 65535");
        int newsPort=number(settings,HTTP_PORT_SETTING,0,65535);
        LogMode logMode = switch (value(settings, "log-mode")) {
            case "normal" -> LogMode.NORMAL;
            case "debug" -> LogMode.DEBUG;
            default -> throw new IOException("log-mode must be normal or debug");
        };
        int maxClients;
        try { maxClients=Integer.parseInt(value(settings,"max-clients")); }
        catch(NumberFormatException invalid){throw new IOException("max-clients must be an integer from 1 to 1024");}
        if(maxClients<1||maxClients>1024)throw new IOException("max-clients must be from 1 to 1024");
        String packs=value(settings,"content-packs"),legacy=value(settings,"legacy-content");
        if(packs.isEmpty())throw new IOException("content-packs must list at least one pack");
        if(!java.util.Set.of("auto","disabled").contains(legacy))throw new IOException("legacy-content must be auto or disabled");
        return new ServerSettings(bind, port, newsPort, logMode, maxClients, number(settings,"log-max-mib",1,1024), number(settings,"log-retention",1,100),packs,legacy,startupMap(settings),welcomeMessage(settings),org.tortodrone.web.WebSettings.read(settings,newsPort));
    }

    private static String welcomeMessage(Properties settings)throws IOException {
        return welcomeMessage(value(settings,"welcome-message"));
    }

    /** Validate a configured welcome notice; the empty string disables it. */
    public static String welcomeMessage(String message)throws IOException {
        message=message==null?"":message.strip();
        if(message.codePointCount(0,message.length())>MAX_WELCOME_CHARACTERS)
            throw new IOException("welcome-message must be at most "+MAX_WELCOME_CHARACTERS+" characters");
        for(int i=0;i<message.length();i++) {
            char c=message.charAt(i);
            if(Character.isISOControl(c))throw new IOException("welcome-message must not contain control characters");
            if(Character.isHighSurrogate(c)) {
                if(++i>=message.length()||!Character.isLowSurrogate(message.charAt(i)))
                    throw new IOException("welcome-message must not contain invalid text");
            }else if(Character.isLowSurrogate(c))throw new IOException("welcome-message must not contain invalid text");
        }
        return message;
    }

    public static String startupMap(Path file)throws IOException {
        return startupMap(properties(file));
    }
    private static String startupMap(Properties settings)throws IOException {
        String map=value(settings,"startup-map");
        if(!map.matches("[a-z][a-z0-9_]{0,63}"))throw new IOException("startup-map must name a content/maps folder");return map;
    }

    private static int number(Properties settings,String key,int min,int max) throws IOException {
        try {int value=Integer.parseInt(value(settings,key));if(value>=min&&value<=max)return value;} catch(NumberFormatException ignored) { }
        throw new IOException(key+" must be an integer from "+min+" to "+max);
    }

    private static String value(Properties settings, String name) {
        return settings.getProperty(name).trim();
    }
}
