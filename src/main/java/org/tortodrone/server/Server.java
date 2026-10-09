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
                System.out.println("Type help in the running server terminal for commands. Use tortodrone-admin.jar for desktop account management.");
                return;
            }
            if (args.length == 1 && args[0].equals("--version")) {
                System.out.println("Tortodrone " + version());
                return;
            }
            Path location = Path.of(Server.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path directory = Files.isDirectory(location) ? location : location.getParent();
            Path dataDirectory = directory.resolve("data").toAbsolutePath().normalize();
            Path config;
            if (args.length == 2 && args[0].equals("--config")) config = Path.of(args[1]);
            else if (args.length == 0) config = dataDirectory.resolve("server.properties");
            else throw new IOException("Use --help for server options; type help in the running server terminal");
            Files.createDirectories(dataDirectory);
            try (FileChannel channel = FileChannel.open(dataDirectory.resolve("server.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = channel.tryLock()) {
                if (lock == null) throw new IOException("Another server is using this JAR's data directory");
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
        org.tortodrone.resources.DefaultResources.prepare(dataDirectory.getParent(),log::info,log::warn);
        log.info("Loading external scene catalog");
        org.tortodrone.world.SceneRegistry scenes;
        try { scenes=org.tortodrone.world.SceneRegistry.system(); }
        catch(IOException missing) {
            log.warn("Maps unavailable: "+missing.getMessage()+"; login and administration remain available");
            scenes=new org.tortodrone.world.SceneRegistry(org.tortodrone.world.SceneCatalog.empty());
            org.tortodrone.world.SceneRegistry.install(scenes);
        }
        log.info("Loaded maps: "+scenes.catalog().scenes().size()+"; scene IDs: "+scenes.catalog().scenes().stream().map(org.tortodrone.world.SceneCatalog.MapScene::id).toList());
        org.tortodrone.character.ItemCatalog catalog;
        Path itemSource=org.tortodrone.character.ItemCatalog.source(dataDirectory);
        try {
            catalog=org.tortodrone.character.ItemCatalog.deployment(dataDirectory);
            if(itemSource!=null)log.info("Item definitions: "+itemSource);
        }
        catch(IOException invalid) { log.warn("Item definitions unavailable: "+invalid.getMessage()+"; using built-in starter definitions");catalog=org.tortodrone.character.ItemCatalog.starter(); }
        if(itemSource==null)log.warn("Missing defaults/items/catalog.json; using built-in starter definitions");
        InetAddress address = InetAddress.getByName(settings.bind());
        try (ServerSocket listener = new ServerSocket()) {
            Path keys = dataDirectory.resolve("keys");
            boolean generate=!Files.exists(keys.resolve("private.pk8"));
            log.info(generate ? "Generating server keypair" : "Loading server keypair");
            PrivateKey key = HandshakeKeys.loadOrCreate(keys);
            if(generate)log.info("Public key (game.publicKey): "+Files.readString(keys.resolve("public.txt")).trim());
            log.info("Loading normal accounts and saved characters");
            try (AccountStore accounts = new AccountStore(dataDirectory,true,catalog)) {
                accounts.watchSessions();
                log.info("Loaded accounts: " + accounts.list().size());
                listener.bind(new InetSocketAddress(address, settings.port()));
                log.info("Starting Tortodrone on " + (address.isAnyLocalAddress() ? "*" : address.getHostAddress())
                    + ":" + listener.getLocalPort());
                log.debug("Public key: " + keys.resolve("public.txt"));
                log.info(scenes.catalog().scenes().isEmpty()
                    ? "Normal login and account administration available; maps are required for world entry."
                    : "Normal login, town movement and persistent inventory available.");
                log.info("Item references: "+accounts.catalog().entries().size()+"; grantable: "+accounts.catalog().entries().stream().filter(org.tortodrone.character.ItemCatalog.Entry::grantable).count());
                accept(listener, key, log, accounts);
            }
        }
    }

    private static void accept(ServerSocket listener, PrivateKey key, ServerLog log,
            AccountStore accounts)
            throws IOException {
        Set<Socket> clients = ConcurrentHashMap.newKeySet();
        Semaphore slots = new Semaphore(16);
        AtomicLong ids = new AtomicLong();
        ServerConsole console = new ServerConsole(accounts, log, () -> {
            try { listener.close(); } catch (IOException ignored) { }
        }, () -> !listener.isClosed());
        Thread shutdown = new Thread(() -> {
            console.close();
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
            log.debug("Limits: 16 clients; 64 KiB upstream / 1 MiB downstream frame; 15 s handshake / 60 s session idle timeout");
            log.info("Done! Listening on " + listener.getInetAddress().getHostAddress() + ":" + listener.getLocalPort() + " (TCP)");
            log.info("Type help for server commands.");
            console.start();
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
            console.close();
            for (Socket client : clients) {
                try { client.close(); } catch (IOException ignored) { }
            }
            try {
                Runtime.getRuntime().removeShutdownHook(shutdown);
                log.info("Server stopped.");
            } catch (IllegalStateException ignored) { /* The shutdown hook owns the final message. */ }
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
