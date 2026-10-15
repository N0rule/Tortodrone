package org.tortodrone.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

/** Editable listener, logging and content-selection settings. */
public record ServerSettings(String bind, int port, LogMode logMode, int maxClients, int logMaxMiB, int logRetention, String contentPacks, String legacyContent) {
    public ServerSettings(String bind,int port,LogMode mode,int clients,int maxMiB,int retention) { this(bind,port,mode,clients,maxMiB,retention,"vanilla","auto"); }
    public ServerSettings(String bind,int port,LogMode mode,int clients) { this(bind,port,mode,clients,10,5); }
    public enum LogMode { NORMAL, DEBUG }
    /** All operator settings default to data/server.properties beside the deployed JAR. */
    public static Path defaultPath(Path deployment) throws IOException {
        Path file=deployment.resolve("data/server.properties");
        org.tortodrone.resources.ContentFiles.safe(file);
        return file;
    }
    public static ServerSettings load(Path file) throws IOException {
        file=file.toAbsolutePath().normalize();
        org.tortodrone.resources.ContentFiles.safe(file);
        if (!Files.exists(file)) {
            Files.createDirectories(file.getParent());
            try (InputStream source=ServerSettings.class.getResourceAsStream("/server.properties")) {
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
        org.tortodrone.resources.ContentFiles.safe(file);
        if(Files.exists(file))try(InputStream source=Files.newInputStream(file)){settings.load(source);}
        for(String name:settings.stringPropertyNames())
            if(!defaults.containsKey(name))throw new IOException("Unknown server property: "+name);
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
        return new ServerSettings(bind, port, logMode, maxClients, number(settings,"log-max-mib",1,1024), number(settings,"log-retention",1,100),packs,legacy);
    }

    private static int number(Properties settings,String key,int min,int max) throws IOException {
        try {int value=Integer.parseInt(value(settings,key));if(value>=min&&value<=max)return value;} catch(NumberFormatException ignored) { }
        throw new IOException(key+" must be an integer from "+min+" to "+max);
    }

    private static String value(Properties settings, String name) {
        return settings.getProperty(name).trim();
    }
}
