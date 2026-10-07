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
import java.security.PrivateKey;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import org.tortodrone.account.AccountStore;
import org.tortodrone.config.ServerSettings;
import org.tortodrone.server.network.SecureHandshake;
import org.tortodrone.server.network.SessionLog;
import org.tortodrone.server.security.HandshakeKeys;

/** Standalone server entry point, independent of the game installation and launcher. */
public final class Server {
    public static void main(String[] args) {
        Thread.currentThread().setName("ServerMain");
        ServerLog log = null;
        int exitCode = 0;
        try {
            if (args.length == 1 && args[0].equals("--help")) {
                System.out.println("Usage: java -jar tortodrone.jar [--config path/to/server.properties | --version | --help]");
                System.out.println("Accounts (server must be stopped): --list-accounts | --create-account username | --set-password username");
                return;
            }
            if (args.length == 1 && args[0].equals("--version")) {
                System.out.println("Tortodrone " + version());
                return;
            }
            Path location = Path.of(Server.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path directory = Files.isDirectory(location) ? location : location.getParent();
            Path dataDirectory = directory.resolve("data").toAbsolutePath().normalize();
            boolean accountCommand = AccountCommands.matches(args);
            Path config;
            if (args.length == 2 && args[0].equals("--config")) config = Path.of(args[1]);
            else if (args.length == 0 || accountCommand) config = dataDirectory.resolve("server.properties");
            else throw new IOException("Use --help for server and account commands");
            Files.createDirectories(dataDirectory);
            try (FileChannel channel = FileChannel.open(dataDirectory.resolve("server.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = channel.tryLock()) {
                if (lock == null) throw new IOException("Another server or account command is using this JAR's data directory");
                if (accountCommand) { AccountCommands.run(args, dataDirectory); return; }
                log = new ServerLog(dataDirectory.resolve("logs"));
                log.info("Starting Tortodrone server version " + version());
                log.info("Loading properties");
                ServerSettings settings = ServerSettings.load(config);
                log.setDebugEnabled(settings.logMode() == ServerSettings.LogMode.DEBUG);
                log.info("Log mode: " + settings.logMode().name().toLowerCase(java.util.Locale.ROOT));
                log.debug("Configuration: " + config.toAbsolutePath().normalize());
                log.debug("Data directory: " + dataDirectory);
                run(settings, dataDirectory, log);
            }
        } catch (Exception e) {
            String message = "Server startup failed: " + e.getMessage();
            if (log == null) ServerLog.startupError(message);
            else log.error(message);
            exitCode = 1;
        } finally {
            if (log != null) log.close();
        }
        if (exitCode != 0) System.exit(exitCode);
    }

    private static String version() throws IOException {
        try (var source = Server.class.getResourceAsStream("/version.txt")) {
            if (source == null) throw new IOException("Missing bundled server version");
            String version = new String(source.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            if (version.isEmpty()) throw new IOException("Empty bundled server version");
            return version;
        }
    }

    private static void run(ServerSettings settings, Path dataDirectory, ServerLog log) throws Exception {
        InetAddress address = InetAddress.getByName(settings.bind());
        try (ServerSocket listener = new ServerSocket()) {
            Path keys = dataDirectory.resolve("keys");
            log.info(Files.exists(keys.resolve("private.pk8")) ? "Loading server keypair" : "Generating server keypair");
            PrivateKey key = HandshakeKeys.loadOrCreate(keys);
            log.info("Loading normal accounts and saved characters");
            AccountStore accounts = new AccountStore(dataDirectory);
            log.info("Loaded accounts: " + accounts.list().size());
            listener.bind(new InetSocketAddress(address, settings.port()));
            log.info("Starting Tortodrone on " + (address.isAnyLocalAddress() ? "*" : address.getHostAddress())
                + ":" + listener.getLocalPort());
            log.debug("Public key: " + keys.resolve("public.txt"));
            log.info("Normal login and saved characters available; world entry is not implemented.");
            accept(listener, key, log, accounts);
        }
    }

    private static void accept(ServerSocket listener, PrivateKey key, ServerLog log,
            AccountStore accounts)
            throws IOException {
        Set<Socket> clients = ConcurrentHashMap.newKeySet();
        Semaphore slots = new Semaphore(16);
        AtomicLong ids = new AtomicLong();
        Thread shutdown = new Thread(() -> {
            // The main thread may finish as soon as the listener closes. Keep its
            // log cleanup waiting until the shutdown message has been written.
            synchronized (log) {
                try { listener.close(); } catch (IOException ignored) { }
                for (Socket client : clients) {
                    try { client.close(); } catch (IOException ignored) { }
                }
                log.info("Server stopped.");
                log.close();
            }
        }, "ServerShutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        try {
            log.debug("Limits: 16 clients; 64 KiB/frame; 15 s handshake / 60 s session idle timeout");
            log.info("Done! Listening on " + listener.getInetAddress().getHostAddress() + ":" + listener.getLocalPort() + " (TCP)");
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
                    log.warn("Connection limit reached");
                    continue;
                }
                long id = ids.incrementAndGet();
                clients.add(client);
                new Thread(() -> {
                    try {
                        receive(client, id, key, listener.getLocalPort(), log, accounts);
                    } finally {
                        clients.remove(client);
                        slots.release();
                    }
                }, "Client-" + id).start();
            }
        } finally {
            for (Socket client : clients) {
                try { client.close(); } catch (IOException ignored) { }
            }
            try { Runtime.getRuntime().removeShutdownHook(shutdown); }
            catch (IllegalStateException ignored) { /* JVM is already shutting down. */ }
        }
    }

    private static void receive(Socket client, long id, PrivateKey key, int port,
            ServerLog log, AccountStore accounts) {
        log.info("Client connected from " + client.getRemoteSocketAddress());
        try (client) {
            client.setSoTimeout(15000);
            SessionLog sessionLog = new SessionLog() {
                @Override public void debug(String message) { log.debug(message); }
                @Override public void info(String message) { log.info(message); }
                @Override public void warn(String message) { log.warn(message); }
            };
            // A wildcard listener uses the concrete local interface of this connection.
            SecureHandshake.run(client, key, sessionLog, true,
                Math.toIntExact(id), client.getLocalAddress().getHostAddress(), port, accounts);
        } catch (SocketTimeoutException e) {
            log.debug("Client idle timeout");
        } catch (java.security.GeneralSecurityException e) {
            log.warn("Secure exchange failed; check the launcher's public key");
        } catch (IOException e) {
            log.debug("Protocol/I/O rejected: " +
                (e instanceof org.tortodrone.protocol.SessionDecoder.Rejected ? e.getMessage() : e.getClass().getSimpleName()));
        } catch (Exception e) {
            log.warn("Handshake rejected: " + e.getClass().getSimpleName());
        } finally {
            log.info("Client disconnected");
        }
    }
}
