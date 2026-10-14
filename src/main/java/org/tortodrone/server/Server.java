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
import java.util.concurrent.Semaphore;
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
                System.out.println("Usage: java -jar tortodrone.jar [--gui | --nogui] [--config path/to/server.properties] | --version | --help");
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
            ServerOptions options=ServerOptions.parse(args,ServerSettings.defaultPath(directory));
            boolean desktop=options.desktopRequested();
            if(desktop) {
                Integer desktopExit=org.tortodrone.admin.DesktopBootstrap.relaunchIfNeeded(location,args,Server.class.getName());
                if(desktopExit!=null){if(desktopExit!=0)System.exit(desktopExit);return;}
            }
            Path config=options.config();
            Files.createDirectories(dataDirectory);
            try (FileChannel channel = FileChannel.open(dataDirectory.resolve("server.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = channel.tryLock()) {
                if (lock == null) throw new IOException("Another server is using this JAR's data directory");
                log = new ServerLog(dataDirectory.resolve("logs"));
                log.info("Starting Tortodrone server version " + version());
                ServerSettings settings = ServerSettings.load(config);
                log.setDebugEnabled(settings.logMode() == ServerSettings.LogMode.DEBUG);
                log.debug("Log mode: " + settings.logMode().name().toLowerCase(java.util.Locale.ROOT));
                log.debug("Configuration: " + config.toAbsolutePath().normalize());
                log.debug("Data directory: " + dataDirectory);
                log.configure(settings.logMaxMiB()*1048576L,settings.logRetention());
                ServerControl control=new ServerControl(version(),dataDirectory);
                AutoCloseable window=null;
                if(desktop) {
                    try {window=ServerWindow.open(control,log);}
                    catch(Exception | java.awt.AWTError failed) {
                        log.warn("Graphical interface unavailable: "+failed.getClass().getSimpleName()+"; starting terminal mode");
                        desktop=false;
                    }
                }
                try {
                    while(control.awaitStart(desktop)) {
                        try {run(settings,dataDirectory,log,control,desktop);}
                        catch(Exception failed){control.failed(failed);log.error("Server startup failed: "+failed.getMessage());if(!desktop)throw failed;}
                        finally{control.stopped();}
                    }
                } finally {if(window!=null)window.close();}
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

    private static void run(ServerSettings settings, Path dataDirectory, ServerLog log,ServerControl control,boolean desktop) throws Exception {
        long startup=System.nanoTime();
        hostDiagnostics(log);
        var content=org.tortodrone.resources.ContentManager.load(dataDirectory.getParent());
        var scenes=content.createScenes();
        org.tortodrone.world.SceneRegistry.install(scenes);
        var catalog=content.items();
        for(String warning:content.warnings())log.warn(warning);
        log.info("Content packs: "+content.packs());
        content.resources().forEach((name,resource)->log.debug("Content "+name+": "+resource.pack()+" / "+resource.path()));
        try {content.writeLockfile(dataDirectory.getParent());}
        catch(IOException cacheUnavailable){log.warn("Content lockfile unavailable: "+cacheUnavailable.getMessage());}
        InetAddress address = InetAddress.getByName(settings.bind());
        try (ServerSocket listener = new ServerSocket()) {
            Path keys = dataDirectory.resolve("keys");
            boolean generate=!Files.exists(keys.resolve("private.pk8"));
            log.info(generate ? "Generating server keypair" : "Loading server keypair");
            PrivateKey key = HandshakeKeys.loadOrCreate(keys);
            if(generate)log.info("Public key : "+Files.readString(keys.resolve("public.txt")).trim());
            try (AccountStore accounts = new AccountStore(dataDirectory,true,catalog)) {
                log.debug("Resources and database initialization elapsed-ms="+((System.nanoTime()-startup)/1000000)+"; weapons="+scenes.weapons().weapons().size());
                accounts.observeDatabase((operation,millis,failed)->{
                    if(failed)log.warn("Database operation failed; operation="+operation+"; duration-ms="+millis);
                    else if(millis>=100)log.debug("Slow database operation; operation="+operation+"; duration-ms="+millis);
                });
                accounts.watchSessions();
                log.info("Database: SQLite WAL; schema="+org.tortodrone.storage.Database.SCHEMA_VERSION);
                log.info("Loaded " + scenes.catalog().scenes().size() + " maps, "
                    + catalog.entries().size() + " items, " + accounts.list().size() + " accounts");
                listener.bind(new InetSocketAddress(address, settings.port()));
                log.debug("Public key: " + keys.resolve("public.txt"));
                log.debug("Account database: " + accounts.databaseFile());
                control.resources(scenes,catalog.entries().size());
                accept(listener,key,log,accounts,settings.maxClients(),control,desktop,startup);
            }
        }
    }

    private static void accept(ServerSocket listener, PrivateKey key, ServerLog log,
            AccountStore accounts,int maxClients,ServerControl control,boolean desktop,long startup)
            throws IOException {
        ClientConnections clients = new ClientConnections();
        Semaphore slots = new Semaphore(maxClients);
        ServerConsole console = new ServerConsole(accounts,log,control::stop,()->!listener.isClosed(),control,!desktop);
        control.listening(listener,console);
        if(listener.isClosed()){console.close();log.info("Server stopped; startup cancelled.");return;}
        Thread shutdown = new Thread(() -> {
            console.close();
            try { listener.close(); } catch (IOException ignored) { }
            clients.close();
            // Joins must finish before taking the log monitor used by handlers.
            synchronized (log) {
                log.info("Server stopped.");
                log.close();
            }
        }, "ServerShutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        try {
            log.debug("Limits: "+maxClients+" clients; 64 KiB upstream / 1 MiB downstream frame; 15 s handshake / 60 s session idle timeout");
            log.info("Done! Listening on " + listener.getInetAddress().getHostAddress() + ":" + listener.getLocalPort() + " (TCP)");
            log.info("Server ready ("+String.format(java.util.Locale.ROOT,"%.3fs",(System.nanoTime()-startup)/1e9)+")");
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
                long id = control.nextSessionId();
                clients.start(client, "Client-" + id, () -> {
                    try {
                        receive(client, id, key, listener.getLocalPort(), log, accounts,control.sessions());
                    } finally {
                        slots.release();
                    }
                });
            }
        } finally {
            console.close();
            clients.close();
            try {
                Runtime.getRuntime().removeShutdownHook(shutdown);
                log.info("Server stopped.");
            } catch (IllegalStateException shuttingDown) {
                // Keep main's account/log/lock cleanup behind the hook's final message.
                ClientConnections.await(shutdown);
            }
        }
    }

    private static void hostDiagnostics(ServerLog log) {
        var metrics=RuntimeMetrics.capture();
        log.info("OS: "+System.getProperty("os.name")+" ("+System.getProperty("os.arch")+")");
        log.info("Java: "+System.getProperty("java.runtime.version")+" / "+System.getProperty("java.vendor"));
        log.info("CPU: "+metrics.processors()+" logical processors");
        log.info("Heap: "+RuntimeMetrics.mib(metrics.heapUsed())+" / "+RuntimeMetrics.mib(metrics.heapMax())+" MiB");
        log.debug("Host: os-version="+System.getProperty("os.version")+"; physical-total="+metrics.physicalTotal()+"; physical-free="+metrics.physicalFree()+"; process-uptime-ms="+metrics.processUptime()+"; process-cpu="+metrics.processCpu());
        log.debug("Garbage collectors: "+java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().stream().map(java.lang.management.GarbageCollectorMXBean::getName).toList());
    }

    private static void receive(Socket client, long id, PrivateKey key, int port,
            ServerLog log, AccountStore accounts,SessionRegistry sessions) {
        long connected=System.nanoTime();String reason="remote closed";
        sessions.connected(id,client.getRemoteSocketAddress());
        log.info("Connected | session="+id+" | endpoint="+client.getRemoteSocketAddress());
        log.debug("Client connected from " + client.getRemoteSocketAddress());
        try (client) {
            client.setSoTimeout(15000);
            SessionLog sessionLog = sessions.observe(id,new SessionLog() {
                @Override public void debug(String message) { log.debug(message+" | session="+id); }
                @Override public void info(String message) { log.info(message+" | session="+id); }
                @Override public void warn(String message) { log.warn(message+" | session="+id); }
            });
            // A wildcard listener uses the concrete local interface of this connection.
            SecureHandshake.run(client, key, sessionLog, true,
                Math.toIntExact(id), client.getLocalAddress().getHostAddress(), port, accounts);
        } catch (SocketTimeoutException e) {
            reason="idle timeout";log.debug("Client idle timeout | session="+id);
        } catch (java.security.GeneralSecurityException e) {
            reason="secure exchange rejected";log.warn("Secure exchange failed; check the launcher's public key");
        } catch (IOException e) {
            reason=client.isClosed()?"server closed or session invalidated":"protocol/I/O rejected";log.debug("Protocol/I/O rejected: " +
                (e instanceof org.tortodrone.protocol.SessionDecoder.Rejected ? e.getMessage() : e.getClass().getSimpleName()));
        } catch (Exception e) {
            reason="handshake rejected";log.warn("Handshake rejected: " + e.getClass().getSimpleName());
        } finally {
            var identity=sessions.find(id);
            log.info("Disconnected | session="+id+" | account="+(identity==null||identity.accountId()==null?"unverified":identity.username()+" (#"+identity.accountId()+")")+" | duration="+((System.nanoTime()-connected)/1000000000)+"s | reason="+reason);
            sessions.disconnected(id);
            log.debug("Client disconnected | session="+id);
        }
    }
}
