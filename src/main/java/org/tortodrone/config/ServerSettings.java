package org.tortodrone.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Editable listener, advertised endpoint and logging settings. */
public record ServerSettings(String bind, int port, String clientHost, LogMode logMode) {
    public enum LogMode { NORMAL, DEBUG }
    public static ServerSettings load(Path file) throws IOException {
        file = file.toAbsolutePath().normalize();
        Properties defaults = new Properties();
        try (InputStream source = ServerSettings.class.getResourceAsStream("/server.properties")) {
            if (source == null) throw new IOException("Missing bundled server.properties");
            byte[] contents = source.readAllBytes();
            defaults.load(new java.io.ByteArrayInputStream(contents));
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.write(file, contents, java.nio.file.StandardOpenOption.CREATE_NEW);
            }
        }
        Properties settings = new Properties(defaults);
        try (InputStream source = Files.newInputStream(file)) {
            settings.load(source);
        }
        for (String name : settings.stringPropertyNames()) {
            if (!defaults.containsKey(name)) throw new IOException("Unknown server property: " + name);
        }
        String bind = value(settings, "server-ip");
        if (bind.isEmpty()) bind = "0.0.0.0";
        int port;
        try {
            port = Integer.parseInt(value(settings, "server-port"));
        } catch (NumberFormatException e) {
            throw new IOException("server-port must be an integer from 0 to 65535");
        }
        if (port < 0 || port > 65535) throw new IOException("server-port must be from 0 to 65535");
        String clientHost = value(settings, "client-host");
        if (clientHost.isEmpty() || clientHost.equals("0.0.0.0") || clientHost.equals("::")
                || clientHost.chars().anyMatch(Character::isWhitespace)) {
            throw new IOException("client-host must be an address players can connect to");
        }
        LogMode logMode = switch (value(settings, "log-mode")) {
            case "normal" -> LogMode.NORMAL;
            case "debug" -> LogMode.DEBUG;
            default -> throw new IOException("log-mode must be normal or debug");
        };
        return new ServerSettings(bind, port, clientHost, logMode);
    }

    private static String value(Properties settings, String name) {
        return settings.getProperty(name).trim();
    }
}
