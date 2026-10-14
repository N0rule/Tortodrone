import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Optional stock-reader/network checks. All server state and preferences are disposable. */
public final class CheckClientWorld {
    private static final List<Process> CHILDREN = new ArrayList<>();
    private static Path scratch;
    public static void main(String[] args) throws Exception {
        Path project = Path.of("").toAbsolutePath();
        if (args.length < 1 || args.length > 2 || !Files.isRegularFile(Path.of(args[0], "code/projectx-pcode.jar"))) {
            throw new IllegalArgumentException("Usage: java scripts/compat/CheckClientWorld.java \"/path/to/Spiral Knights\" [prepared-seven-scene-maps]");
        }
        Path game = Path.of(args[0]).toAbsolutePath();
        for (String jar : List.of("tortodrone.jar", "tortodrone-admin.jar")) {
            if (!Files.isRegularFile(project.resolve("build/" + jar))) throw new IOException("Build first with ./gradlew build");
        }
        scratch = Files.createTempDirectory("tortodrone-installed-client-");
        Thread cleanup = new Thread(CheckClientWorld::stopChildren, "check-cleanup");
        Runtime.getRuntime().addShutdownHook(cleanup);
        try {
            for (String jar : List.of("tortodrone.jar", "tortodrone-admin.jar")) Files.copy(project.resolve("build/" + jar), scratch.resolve(jar));
            Path maps=project.resolve("build/content/packs/vanilla");
            try(var paths=Files.walk(maps)) {
                for(var path:paths.toList()) {
                    Path target=scratch.resolve("content/packs/vanilla").resolve(maps.relativize(path));
                    if(Files.isDirectory(path))Files.createDirectories(target);else Files.copy(path,target);
                }
            }
            Files.createDirectories(scratch.resolve("home/prefs"));
            Files.createDirectories(scratch.resolve("data"));
            Files.createDirectories(scratch.resolve("config"));
            Files.writeString(scratch.resolve("config/content.properties"),"format=1\nenabled=vanilla\nlegacy=disabled\n");
            String cp = game.resolve("code/projectx-pcode.jar") + File.pathSeparator + game.resolve("code/*") + File.pathSeparator + scratch.resolve("tortodrone.jar");
            run(List.of(executable("javac"), "--release", "25", "-cp", cp, "-d", scratch.toString(),
                project.resolve("scripts/compat/WorldConnectionCheck.java").toString(), project.resolve("scripts/compat/WorldCompatibility.java").toString(),
                project.resolve("scripts/compat/AccountConnectionCheck.java").toString(),
                project.resolve("scripts/compat/GameMasterConnectionCheck.java").toString(),
                project.resolve("scripts/compat/AdminConnectionCheck.java").toString(),
                project.resolve("scripts/compat/TellConnectionCheck.java").toString(),
                project.resolve("scripts/compat/PortalConnectionCheck.java").toString(),
                project.resolve("scripts/compat/HavenConnectionCheck.java").toString(),
                project.resolve("scripts/compat/HavenCompatibility.java").toString(),
                project.resolve("scripts/compat/EmoteCompatibility.java").toString(),
                project.resolve("scripts/compat/EmoteConnectionCheck.java").toString(),
                project.resolve("scripts/compat/TradeCompatibility.java").toString(),
                project.resolve("scripts/compat/TradeConnectionCheck.java").toString(),
                project.resolve("scripts/compat/InspectCompatibility.java").toString(),
                project.resolve("scripts/compat/InspectConnectionCheck.java").toString(),
                project.resolve("scripts/compat/InteriorCompatibility.java").toString(),
                project.resolve("scripts/compat/InteriorConnectionCheck.java").toString(),
                project.resolve("scripts/compat/ItemCatalogExport.java").toString(),project.resolve("scripts/compat/ItemCatalogCompatibility.java").toString(),
                project.resolve("scripts/compat/ItemGrantConnectionCheck.java").toString()), "compile", "", 30);
            cp = scratch + File.pathSeparator + cp;
            List<String> base = new ArrayList<>(List.of(executable("java"), "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED", "-Djava.awt.headless=true", "-Dno_unpack_resources=true",
                "-Duser.home=" + scratch.resolve("home"), "-Djava.util.prefs.userRoot=" + scratch.resolve("home/prefs"),
                "-Djava.util.prefs.systemRoot=" + scratch.resolve("home/system-prefs"), "-cp", cp));
            List<String> reader = new ArrayList<>(base);
            reader.addAll(List.of("WorldCompatibility", game.toString()));
            run(reader, "reader", "", 30);
            var havenReader=new ArrayList<>(base);
            havenReader.addAll(List.of("HavenCompatibility",game.toString()));
            run(havenReader,"haven-reader","",45);
            var emoteReader=new ArrayList<>(base);
            emoteReader.addAll(List.of("EmoteCompatibility",game.toString()));
            run(emoteReader,"emote-reader","",45);
            var tradeReader=new ArrayList<>(base);tradeReader.add("TradeCompatibility");
            run(tradeReader,"trade-reader","",30);
            var inspectReader=new ArrayList<>(base);inspectReader.add("InspectCompatibility");
            run(inspectReader,"inspect-reader","",30);
            var interiorReader=new ArrayList<>(base);interiorReader.addAll(List.of("InteriorCompatibility",game.toString()));
            run(interiorReader,"interior-reader","",45);
            var export=new ArrayList<>(base);export.addAll(List.of("ItemCatalogExport",game.toString(),scratch.resolve("exports/items").toString()));
            run(export,"item-export","",60);
            var catalogCheck=new ArrayList<>(base);catalogCheck.addAll(List.of("ItemCatalogCompatibility",game.toString(),scratch.resolve("exports/items/catalog.json").toString()));
            run(catalogCheck,"item-catalog","",60);
            Files.writeString(scratch.resolve("data/server.properties"), "server-ip=127.0.0.1\nserver-port=0\nlog-mode=debug\n");
            for (String user : List.of("LanPlayer", "PeerPlayer")) run(List.of(executable("java"), "-jar", scratch.resolve("tortodrone-admin.jar").toString(), "--create-account", user), "account-" + user, "lan-test-password\nlan-test-password\n", 15);
            Path serverLog = scratch.resolve("server.log");
            Process server = start(List.of(executable("java"), "-jar", scratch.resolve("tortodrone.jar").toString(),"--nogui"), serverLog);
            server.getOutputStream().close();
            String port = ready(server, serverLog);
            List<String> network = new ArrayList<>(base);
            network.addAll(List.of("WorldConnectionCheck", port, scratch.resolve("data/keys/public.txt").toString()));
            try { run(network, "network", "", 60); }
            catch (Exception failure) { System.err.println(tail(serverLog)); throw failure; }
            List<String> staffCheck=new ArrayList<>(base);
            staffCheck.addAll(List.of("GameMasterConnectionCheck",port,scratch.resolve("data/keys/public.txt").toString()));
            try{run(staffCheck,"game-master","",45);}
            catch(Exception failure){System.err.println(tail(serverLog));throw failure;}
            var adminCheck=new ArrayList<>(base);
            adminCheck.addAll(List.of("AdminConnectionCheck",port,scratch.resolve("data/keys/public.txt").toString()));
            try{run(adminCheck,"admin-dashboard","",90);}
            catch(Exception failure){System.err.println(tail(serverLog));throw failure;}
            var tellCheck=new ArrayList<>(base);
            tellCheck.addAll(List.of("TellConnectionCheck",port,scratch.resolve("data/keys/public.txt").toString()));
            try{run(tellCheck,"tells","",45);}
            catch(Exception failure){System.err.println(tail(serverLog));throw failure;}
            var portalCheck=new ArrayList<>(base);
            portalCheck.addAll(List.of("PortalConnectionCheck",port,scratch.resolve("data/keys/public.txt").toString()));
            try{run(portalCheck,"portals","",60);}
            catch(Exception failure){System.err.println(tail(serverLog));throw failure;}
            var havenCheck=new ArrayList<>(base);
            havenCheck.addAll(List.of("HavenConnectionCheck",port,scratch.resolve("data/keys/public.txt").toString()));
            // Twelve modern routes start at authored entrances; walk them at native speed.
            try{run(havenCheck,"haven-links","",600);}
            catch(Exception failure){System.err.println(tail(serverLog));throw failure;}
            var inspectCheck=new ArrayList<>(base);
            inspectCheck.addAll(List.of("InspectConnectionCheck",port,scratch.resolve("data/keys/public.txt").toString(),
                "LanPlayer","lan-test-password","Town-Knight","PeerPlayer","lan-test-password","Peer-Knight"));
            try{run(inspectCheck,"inspection","",45);}
            catch(Exception failure){System.err.println(tail(serverLog));throw failure;}
            var emoteCheck=new ArrayList<>(base);
            emoteCheck.addAll(List.of("EmoteConnectionCheck",port,scratch.resolve("data/keys/public.txt").toString()));
            try{run(emoteCheck,"emotes","",150);}
            catch(Exception failure){System.err.println(tail(serverLog));throw failure;}
            server.destroy();
            if (!server.waitFor(5, TimeUnit.SECONDS)) throw new IOException("Temporary server did not stop");
            Path prepared=args.length==2?Path.of(args[1]).toAbsolutePath():project.resolve("build/content/packs/vanilla/maps");
            if(args.length==2||Files.isDirectory(prepared.resolve("advanced_training"))
                    &&Files.isDirectory(prepared.resolve("laboratory"))&&Files.isDirectory(prepared.resolve("hall_of_heroes"))) {
                copyTree(prepared,scratch.resolve("maps"));
                Path interiorLog=scratch.resolve("interior-server.log");
                Process interiorServer=start(List.of(executable("java"),"-jar",scratch.resolve("tortodrone.jar").toString(),"--nogui"),interiorLog);
                interiorServer.getOutputStream().close();
                String interiorPort=ready(interiorServer,interiorLog);
                var interiorCheck=new ArrayList<>(base);
                interiorCheck.addAll(List.of("InteriorConnectionCheck",interiorPort,scratch.resolve("data/keys/public.txt").toString(),"127.0.0.1",scratch.resolve("maps").toString()));
                try{run(interiorCheck,"interior-links","",180);}
                catch(Exception failure){System.err.println(tail(interiorLog));throw failure;}
                interiorServer.destroy();if(!interiorServer.waitFor(5,TimeUnit.SECONDS))throw new IOException("Temporary interior server did not stop");
                try(var paths=Files.walk(scratch.resolve("maps"))){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
            }
            List<String> seed = new ArrayList<>(base);
            seed.addAll(List.of("AccountConnectionCheck", "seed", scratch.resolve("data").toString()));
            run(seed,"seed-reconnect","",15);
            Path descriptor = scratch.resolve("content/packs/vanilla/maps/haven/map.properties");
            var properties = new Properties();
            try (var in = Files.newInputStream(descriptor)) { properties.load(in); }
            properties.setProperty("instances", "2");
            try (var out = Files.newOutputStream(descriptor)) { properties.store(out, "Disposable instance check"); }
            refreshSceneFingerprint(descriptor.getParent(),"scene.1");
            Path isolatedLog = scratch.resolve("isolated-server.log");
            Process isolated = start(List.of(executable("java"), "-jar", scratch.resolve("tortodrone.jar").toString(),"--nogui"), isolatedLog);
            isolated.getOutputStream().close();
            port = ready(isolated, isolatedLog);
            List<String> accountCheck = new ArrayList<>(base);
            accountCheck.addAll(List.of("AccountConnectionCheck", port, scratch.resolve("data/keys/public.txt").toString()));
            try { run(accountCheck,"account-sessions","",45); }
            catch (Exception failure) { System.err.println(tail(isolatedLog)); throw failure; }
            List<String> instanceCheck = new ArrayList<>(base);
            instanceCheck.addAll(List.of("WorldConnectionCheck", port, scratch.resolve("data/keys/public.txt").toString(), "isolated"));
            try { run(instanceCheck, "instances", "", 60); }
            catch (Exception failure) { System.err.println(tail(isolatedLog)); throw failure; }
            var itemCheck=new ArrayList<>(base);itemCheck.addAll(List.of("ItemGrantConnectionCheck",port,scratch.resolve("data/keys/public.txt").toString()));
            try{run(itemCheck,"item-grants","",60);}catch(Exception failure){System.err.println(tail(isolatedLog));throw failure;}
            // Trade deliberately changes persistent inventories and balances;
            // finish the five-starter reconnect checks before its separate fixture.
            isolated.destroy();if(!isolated.waitFor(5,TimeUnit.SECONDS))throw new IOException("Temporary instance server did not stop");
            properties.setProperty("instances","1");
            try(var out=Files.newOutputStream(descriptor)){properties.store(out,"Disposable trade check");}
            refreshSceneFingerprint(descriptor.getParent(),"scene.1");
            var tradeSeed=new ArrayList<>(base);
            tradeSeed.addAll(List.of("TradeConnectionCheck","--seed",scratch.resolve("data").toString(),scratch.resolve("trade.properties").toString()));
            run(tradeSeed,"seed-trade","",15);
            Path tradeLog=scratch.resolve("trade-server.log");
            Process tradeServer=start(List.of(executable("java"),"-jar",scratch.resolve("tortodrone.jar").toString(),"--nogui"),tradeLog);
            tradeServer.getOutputStream().close();String tradePort=ready(tradeServer,tradeLog);
            var tradeCheck=new ArrayList<>(base);
            tradeCheck.addAll(List.of("TradeConnectionCheck",tradePort,scratch.resolve("data/keys/public.txt").toString(),scratch.resolve("trade.properties").toString()));
            try{run(tradeCheck,"trade","",60);}
            catch(Exception failure){System.err.println(tail(tradeLog));throw failure;}
        } finally {
            stopChildren();
            try { Runtime.getRuntime().removeShutdownHook(cleanup); }
            catch (IllegalStateException shuttingDown) { /* The registered hook is already stopping the children. */ }
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
    private static void copyTree(Path source,Path destination)throws IOException {
        try(var paths=Files.walk(source)) {for(var path:paths.toList()) {
            if(Files.isSymbolicLink(path))throw new IOException("Symlink in prepared maps");
            Path target=destination.resolve(source.relativize(path));
            if(Files.isDirectory(path))Files.createDirectories(target);else Files.copy(path,target);
        }}
    }
    private static void refreshSceneFingerprint(Path folder,String key)throws Exception {
        run(List.of(executable("java"),"-cp",scratch.resolve("tortodrone.jar").toString(),
            "org.tortodrone.resources.ContentFiles",folder.toString()),"fingerprint","",15);
        Path file=scratch.resolve("content/packs/vanilla/pack.properties");var manifest=new Properties();
        try(var input=Files.newInputStream(file)){manifest.load(input);}
        manifest.setProperty("sha256."+key,Files.readString(scratch.resolve("fingerprint.log")).trim());
        try(var output=Files.newOutputStream(file)){manifest.store(output,"Disposable scene configuration");}
    }
    private static String ready(Process server,Path log)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<end){
            var match=Pattern.compile("Listening on 127\\.0\\.0\\.1:(\\d+)").matcher(Files.readString(log));
            if(match.find())return match.group(1);
            if(!server.isAlive())throw new IOException("Temporary server failed:\n"+tail(log));
            Thread.sleep(50);
        }
        throw new IOException("Temporary server readiness timeout:\n"+tail(log));
    }
    private static String executable(String name) { return Path.of(System.getProperty("java.home"), "bin", name).toString(); }
    private static synchronized Process start(List<String> command, Path log) throws IOException {
        Process process = new ProcessBuilder(command).directory(scratch.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        CHILDREN.add(process);
        return process;
    }
    private static void run(List<String> command, String name, String input, int timeout) throws Exception {
        Path log = scratch.resolve(name + ".log");
        Process process = start(command, log);
        try (var out = process.outputWriter()) { out.write(input); }
        if (!process.waitFor(timeout, TimeUnit.SECONDS)) throw new IOException(name + " timed out:\n" + tail(log));
        if (process.exitValue() != 0) throw new IOException(name + " failed:\n" + tail(log));
        for (String line : Files.readAllLines(log)) if (line.startsWith("PASS:")) System.out.println(line);
    }
    private static String tail(Path path) throws IOException {
        String text = Files.readString(path);
        return text.substring(Math.max(0, text.length() - 9000));
    }
    private static synchronized void stopChildren() {
        boolean interrupted = Thread.interrupted();
        try {
            for (int i = CHILDREN.size() - 1; i >= 0; i--) interrupted = stopChild(CHILDREN.get(i), interrupted);
        } finally {
            if (Thread.interrupted()) interrupted = true;
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
    private static boolean stopChild(Process process, boolean interrupted) {
        if (!process.isAlive()) return interrupted;
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS) && process.isAlive()) process.destroyForcibly();
        } catch (InterruptedException e) {
            interrupted = true;
            process.destroyForcibly();
        }
        while (process.isAlive()) {
            try { process.waitFor(); }
            catch (InterruptedException e) {
                interrupted = true;
                process.destroyForcibly();
            }
        }
        return interrupted;
    }
}
