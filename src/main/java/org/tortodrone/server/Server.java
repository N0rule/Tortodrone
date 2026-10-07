package org.tortodrone.server;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.security.PrivateKey;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/** Standalone server entry point, independent of the game installation and launcher. */
public final class Server {
    public static void main(String[] args) {
        try {
            Path config = Path.of("build/settings/server.properties");
            if (args.length == 1 && args[0].equals("--help")) {
                System.out.println("Usage: java -jar tortodrone-server.jar [--config path/to/server.properties]");
                return;
            }
            if (args.length == 2 && args[0].equals("--config")) config = Path.of(args[1]);
            else if (args.length != 0) throw new IOException("Use --config path/to/server.properties or --help");
            run(ServerSettings.load(config));
        } catch (Exception e) {
            System.err.println("Server startup failed: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void run(ServerSettings settings) throws Exception {
        InetAddress address = InetAddress.getByName(settings.bind());
        Files.createDirectories(settings.dataDirectory());
        try (FileChannel channel = FileChannel.open(settings.dataDirectory().resolve("server.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Another server is using this data-directory");
            try (ServerSocket listener = new ServerSocket()) {
                listener.bind(new InetSocketAddress(address, settings.port()));
                Path keys = settings.dataDirectory().resolve("keys");
                PrivateKey key = HandshakeKeys.loadOrCreate(keys);
                CharacterStore characters = new CharacterStore(settings.dataDirectory());
                exportConnection(settings, listener.getLocalPort(), keys);
                try (ServerLog log = new ServerLog(settings.dataDirectory().resolve("logs"))) {
                    accept(listener, key, settings, log, characters);
                }
            }
        }
    }

    private static void exportConnection(ServerSettings settings, int port, Path keys) throws IOException {
        Properties connection = new Properties();
        connection.setProperty("server_host", settings.clientHost());
        connection.setProperty("server_ports", String.valueOf(port));
        connection.setProperty("datagram_ports", String.valueOf(port));
        connection.setProperty("key.public", Files.readString(keys.resolve("public.txt")).trim());
        connection.setProperty("anonymous_allowed", "false");
        Path target = settings.dataDirectory().resolve("client-connection.properties");
        Path temporary = Files.createTempFile(settings.dataDirectory(), "connection-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary)) {
                connection.store(writer, "Launcher connection values; merge into the client's deployment.properties");
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void accept(ServerSocket listener, PrivateKey key, ServerSettings settings, ServerLog log,
            CharacterStore characters)
            throws IOException {
        Set<Socket> clients = ConcurrentHashMap.newKeySet();
        Semaphore slots = new Semaphore(16);
        AtomicLong ids = new AtomicLong();
        Thread shutdown = new Thread(() -> {
            try { listener.close(); } catch (IOException ignored) { }
            for (Socket client : clients) {
                try { client.close(); } catch (IOException ignored) { }
            }
            log.write("Server stopped.");
            log.close();
        }, "server-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        try {
            log.write("TortodroneServer — experimental Spiral Knights server");
            log.write("Public key: " + settings.dataDirectory().resolve("keys/public.txt"));
            log.write("Launcher configuration: " + settings.dataDirectory().resolve("client-connection.properties"));
            log.write("Client endpoint: " + settings.clientHost() + ":" + listener.getLocalPort());
            log.write("Test login, character creation and saved roster available; world entry is not implemented.");
            log.write("Listening on " + listener.getInetAddress().getHostAddress() + ":" + listener.getLocalPort()
                + " (TCP; host network; 16 clients; 64 KiB/frame; 15 s handshake / 60 s session idle timeout)");
            while (!listener.isClosed()) {
                Socket client;
                try {
                    client = listener.accept();
                } catch (IOException e) {
                    if (listener.isClosed()) break;
                    throw e;
                }
                if (!slots.tryAcquire()) {
                    client.close();
                    log.write("Connection limit reached");
                    continue;
                }
                long id = ids.incrementAndGet();
                clients.add(client);
                new Thread(() -> {
                    try {
                        receive(client, id, key, settings, listener.getLocalPort(), log, characters);
                    } finally {
                        clients.remove(client);
                        slots.release();
                    }
                }, "connection-" + id).start();
            }
        } finally {
            for (Socket client : clients) {
                try { client.close(); } catch (IOException ignored) { }
            }
            try { Runtime.getRuntime().removeShutdownHook(shutdown); }
            catch (IllegalStateException ignored) { /* JVM is already shutting down. */ }
        }
    }

    private static void receive(Socket client, long id, PrivateKey key, ServerSettings settings, int port,
            ServerLog log, CharacterStore characters) {
        log.write("Connection #" + id + " from " + client.getRemoteSocketAddress());
        try (client) {
            client.setSoTimeout(15000);
            SecureHandshake.run(client, key, message -> log.write("Connection #" + id + " " + message), true,
                Math.toIntExact(id), settings.clientHost(), port, characters);
        } catch (SocketTimeoutException e) {
            log.write("Connection #" + id + " idle timeout");
        } catch (java.security.GeneralSecurityException e) {
            log.write("Connection #" + id + " secure exchange failed; check the launcher's public key");
        } catch (IOException e) {
            log.write("Connection #" + id + " protocol/I/O rejected: " +
                (e instanceof org.tortodrone.protocol.SessionDecoder.Rejected ? e.getMessage() : e.getClass().getSimpleName()));
        } catch (Exception e) {
            log.write("Connection #" + id + " handshake rejected: " + e.getClass().getSimpleName());
        } finally {
            log.write("Connection #" + id + " closed");
        }
    }
}
