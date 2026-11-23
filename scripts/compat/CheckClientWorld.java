import java.io.*;
import org.tortodrone.core.Yaml;
import java.nio.file.*;
import java.util.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.tortodrone.core.character.Knight;

/** Optional stock-reader/network checks. All server state and preferences are disposable. */
public final class CheckClientWorld {
    private static Path scratch;
    private static String deployment() { return System.getenv().getOrDefault("TORTODRONE_DEPLOYMENT", "dist"); }
    public static void main(String[] args) throws Exception {
        Path project = Path.of("").toAbsolutePath();
        if (args.length < 1 || args.length > 2 || !Files.isRegularFile(Path.of(args[0], "code/projectx-pcode.jar"))) {
            throw new IllegalArgumentException("Usage: java scripts/compat/CheckClientWorld.java \"/path/to/Spiral Knights\" [prepared-seven-scene-maps]");
        }
        Path game = Path.of(args[0]).toAbsolutePath();
        for (String jar : List.of("tortodrone.jar")) {
            if (!Files.isRegularFile(project.resolve(deployment()).resolve(jar))) throw new IOException("Build first with ./gradlew build");
        }
        scratch = Files.createTempDirectory("tortodrone-installed-client-");
        Thread cleanup = new Thread(ClientCheckHarness::stopChildren, "check-cleanup");
        Runtime.getRuntime().addShutdownHook(cleanup);
        try {
            for (String jar : List.of("tortodrone.jar")) Files.copy(project.resolve(deployment()).resolve(jar), scratch.resolve(jar));
            Path maps=project.resolve(deployment()).resolve("content");
            try(var paths=Files.walk(maps)) {
                for(var path:paths.toList()) {
                    Path target=scratch.resolve("content").resolve(maps.relativize(path));
                    if(Files.isDirectory(path))Files.createDirectories(target);else Files.copy(path,target);
                }
            }
            ClientCheckHarness.copyTree(project.resolve(deployment()).resolve("config"),scratch.resolve("config"),"config");Files.deleteIfExists(scratch.resolve("config/server.yml"));
            Files.createDirectories(scratch.resolve("home/prefs"));
            Files.createDirectories(scratch.resolve("data"));
            String cp = game.resolve("code/projectx-pcode.jar") + File.pathSeparator + game.resolve("code") + File.separator + "*" + File.pathSeparator + scratch.resolve("tortodrone.jar");
            ClientCheckHarness.run(scratch, List.of(ClientCheckHarness.executable("javac"), "--release", "25", "-cp", cp, "-d", scratch.toString(),
                project.resolve("scripts/compat/WorldConnectionCheck.java").toString(), project.resolve("scripts/compat/WorldCompatibility.java").toString(),
                project.resolve("scripts/compat/AccountConnectionCheck.java").toString(),
                project.resolve("scripts/compat/GameMasterConnectionCheck.java").toString(),
                project.resolve("scripts/compat/AdminConnectionCheck.java").toString(),
                project.resolve("scripts/compat/TellConnectionCheck.java").toString(),
                project.resolve("scripts/compat/PortalConnectionCheck.java").toString(),
                project.resolve("scripts/compat/HavenConnectionCheck.java").toString(),
                project.resolve("scripts/compat/HavenCompatibility.java").toString(),
                project.resolve("scripts/compat/EmoteCompatibility.java").toString(),
                project.resolve("scripts/compat/FireCompatibility.java").toString(),
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
            List<String> base = new ArrayList<>(List.of(ClientCheckHarness.executable("java"), "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED", "-Djava.awt.headless=true", "-Dno_unpack_resources=true",
                "-Duser.home=" + scratch.resolve("home"), "-Djava.util.prefs.userRoot=" + scratch.resolve("home/prefs"),
                "-Djava.util.prefs.systemRoot=" + scratch.resolve("home/system-prefs"), "-cp", cp));
            List<String> reader = new ArrayList<>(base);
            reader.addAll(List.of("WorldCompatibility", game.toString()));
            ClientCheckHarness.run(scratch, reader, "reader", "", 30);
            var havenReader=new ArrayList<>(base);
            havenReader.addAll(List.of("HavenCompatibility",game.toString()));
            ClientCheckHarness.run(scratch, havenReader,"haven-reader","",45);
            var emoteReader=new ArrayList<>(base);
            emoteReader.addAll(List.of("EmoteCompatibility",game.toString()));
            ClientCheckHarness.run(scratch, emoteReader,"emote-reader","",45);
            var tradeReader=new ArrayList<>(base);tradeReader.add("TradeCompatibility");
            ClientCheckHarness.run(scratch, tradeReader,"trade-reader","",30);
            var inspectReader=new ArrayList<>(base);inspectReader.add("InspectCompatibility");
            ClientCheckHarness.run(scratch, inspectReader,"inspect-reader","",30);
            var interiorReader=new ArrayList<>(base);interiorReader.addAll(List.of("InteriorCompatibility",game.toString()));
            ClientCheckHarness.run(scratch, interiorReader,"interior-reader","",45);
            var export=new ArrayList<>(base);export.addAll(List.of("ItemCatalogExport",game.toString(),scratch.resolve("exports/items").toString()));
            ClientCheckHarness.run(scratch, export,"item-export","",60);
            var catalogCheck=new ArrayList<>(base);catalogCheck.addAll(List.of("ItemCatalogCompatibility",game.toString(),scratch.resolve("exports/items/catalog.json").toString()));
            ClientCheckHarness.run(scratch, catalogCheck,"item-catalog","",60);
            Files.writeString(scratch.resolve("data/server.properties"), "server-ip=127.0.0.1\nserver-port=0\nlog-mode=debug\ncontent-packs=vanilla\nlegacy-content=disabled\n");
            try(var accounts=new org.tortodrone.account.AccountStore(scratch.resolve("data"))) {
                char[] password="lan-test-password".toCharArray();
                try{for(String user:List.of("LanPlayer","PeerPlayer"))accounts.create(user,password);}
                finally{Arrays.fill(password,'\0');}
            }
            Path serverLog = scratch.resolve("server.log");
            Process server = ClientCheckHarness.start(scratch, List.of(ClientCheckHarness.executable("java"), "-jar", scratch.resolve("tortodrone.jar").toString(),"--nogui"), serverLog,scratch);
            server.getOutputStream().close();
            String port = ClientCheckHarness.ready(server, serverLog, Duration.ofSeconds(15));
            List<String> network = new ArrayList<>(base);
            network.addAll(List.of("WorldConnectionCheck", port, scratch.resolve("data/keys/public.txt").toString()));
            try { ClientCheckHarness.run(scratch, network, "network", "", 60); }
            catch (Exception failure) { System.err.println(ClientCheckHarness.tail(serverLog)); throw failure; }
            List<String> staffCheck=new ArrayList<>(base);
            staffCheck.addAll(List.of("GameMasterConnectionCheck",port,scratch.resolve("data/keys/public.txt").toString()));
            try{ClientCheckHarness.run(scratch, staffCheck,"game-master","",45);}
            catch(Exception failure){System.err.println(ClientCheckHarness.tail(serverLog));throw failure;}
            var adminCheck=new ArrayList<>(base);
            adminCheck.addAll(List.of("AdminConnectionCheck",port,scratch.resolve("data/keys/public.txt").toString()));
            try{ClientCheckHarness.run(scratch, adminCheck,"admin-dashboard","",90);}
            catch(Exception failure){System.err.println(ClientCheckHarness.tail(serverLog));throw failure;}
            var tellCheck=new ArrayList<>(base);
            tellCheck.addAll(List.of("TellConnectionCheck",port,scratch.resolve("data/keys/public.txt").toString()));
            try{ClientCheckHarness.run(scratch, tellCheck,"tells","",45);}
            catch(Exception failure){System.err.println(ClientCheckHarness.tail(serverLog));throw failure;}
            var portalCheck=new ArrayList<>(base);
            portalCheck.addAll(List.of("PortalConnectionCheck",port,scratch.resolve("data/keys/public.txt").toString()));
            try{ClientCheckHarness.run(scratch, portalCheck,"portals","",60);}
            catch(Exception failure){System.err.println(ClientCheckHarness.tail(serverLog));throw failure;}
            var havenCheck=new ArrayList<>(base);
            havenCheck.addAll(List.of("HavenConnectionCheck",port,scratch.resolve("data/keys/public.txt").toString()));
            // Twelve modern routes start at authored entrances; walk them at native speed.
            try{ClientCheckHarness.run(scratch, havenCheck,"haven-links","",600);}
            catch(Exception failure){System.err.println(ClientCheckHarness.tail(serverLog));throw failure;}
            var inspectCheck=new ArrayList<>(base);
            inspectCheck.addAll(List.of("InspectConnectionCheck",port,scratch.resolve("data/keys/public.txt").toString(),
                "LanPlayer","lan-test-password","Town-Knight","PeerPlayer","lan-test-password","Peer-Knight"));
            try{ClientCheckHarness.run(scratch, inspectCheck,"inspection","",45);}
            catch(Exception failure){System.err.println(ClientCheckHarness.tail(serverLog));throw failure;}
            var emoteCheck=new ArrayList<>(base);
            emoteCheck.addAll(List.of("EmoteConnectionCheck",port,scratch.resolve("data/keys/public.txt").toString()));
            try{ClientCheckHarness.run(scratch, emoteCheck,"emotes","",150);}
            catch(Exception failure){System.err.println(ClientCheckHarness.tail(serverLog));throw failure;}
            server.destroy();
            if (!server.waitFor(5, TimeUnit.SECONDS)) throw new IOException("Temporary server did not stop");
            Path prepared=args.length==2?Path.of(args[1]).toAbsolutePath():project.resolve(deployment()).resolve("content/maps");
            if(args.length==2||Files.isDirectory(prepared.resolve("training/advanced_training"))
                    &&Files.isDirectory(prepared.resolve("level/laboratory"))&&Files.isDirectory(prepared.resolve("level/hall_of_heroes"))) {
                if(args.length==2) {
                    Path current=scratch.resolve("content/maps");try(var paths=Files.walk(current)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
                    ClientCheckHarness.copyTree(prepared,current,"prepared maps");
                }
                Path interiorLog=scratch.resolve("interior-server.log");
                Process interiorServer=ClientCheckHarness.start(scratch, List.of(ClientCheckHarness.executable("java"),"-jar",scratch.resolve("tortodrone.jar").toString(),"--nogui"),interiorLog,scratch);
                interiorServer.getOutputStream().close();
                String interiorPort=ClientCheckHarness.ready(interiorServer, interiorLog, Duration.ofSeconds(15));
                var interiorCheck=new ArrayList<>(base);
                interiorCheck.addAll(List.of("InteriorConnectionCheck",interiorPort,scratch.resolve("data/keys/public.txt").toString(),"127.0.0.1",scratch.resolve("content/maps").toString()));
                try{ClientCheckHarness.run(scratch, interiorCheck,"interior-links","",180);}
                catch(Exception failure){System.err.println(ClientCheckHarness.tail(interiorLog));throw failure;}
                interiorServer.destroy();if(!interiorServer.waitFor(5,TimeUnit.SECONDS))throw new IOException("Temporary interior server did not stop");
            }
            List<String> seed = new ArrayList<>(base);
            seed.addAll(List.of("AccountConnectionCheck", "seed", scratch.resolve("data").toString()));
            ClientCheckHarness.run(scratch, seed,"seed-reconnect","",15);
            Path descriptor = scratch.resolve("content/maps/town/haven/map.yml");
            var properties=Yaml.read(descriptor);properties.put("instances",2);Files.delete(descriptor);Yaml.write(descriptor,properties);
            Path isolatedLog = scratch.resolve("isolated-server.log");
            Process isolated = ClientCheckHarness.start(scratch, List.of(ClientCheckHarness.executable("java"), "-jar", scratch.resolve("tortodrone.jar").toString(),"--nogui"), isolatedLog,scratch);
            isolated.getOutputStream().close();
            port = ClientCheckHarness.ready(isolated, isolatedLog, Duration.ofSeconds(15));
            List<String> accountCheck = new ArrayList<>(base);
            accountCheck.addAll(List.of("AccountConnectionCheck", port, scratch.resolve("data/keys/public.txt").toString()));
            try { ClientCheckHarness.run(scratch, accountCheck,"account-sessions","",45); }
            catch (Exception failure) { System.err.println(ClientCheckHarness.tail(isolatedLog)); throw failure; }
            List<String> instanceCheck = new ArrayList<>(base);
            instanceCheck.addAll(List.of("WorldConnectionCheck", port, scratch.resolve("data/keys/public.txt").toString(), "isolated"));
            try { ClientCheckHarness.run(scratch, instanceCheck, "instances", "", 60); }
            catch (Exception failure) { System.err.println(ClientCheckHarness.tail(isolatedLog)); throw failure; }
            var itemCheck=new ArrayList<>(base);itemCheck.addAll(List.of("ItemGrantConnectionCheck",port,scratch.resolve("data/keys/public.txt").toString()));
            try{ClientCheckHarness.run(scratch, itemCheck,"item-grants","",60);}catch(Exception failure){System.err.println(ClientCheckHarness.tail(isolatedLog));throw failure;}
            // Trade deliberately changes persistent inventories and balances;
            // finish the five-starter reconnect checks before its separate fixture.
            isolated.destroy();if(!isolated.waitFor(5,TimeUnit.SECONDS))throw new IOException("Temporary instance server did not stop");
            properties.put("instances",1);Files.delete(descriptor);Yaml.write(descriptor,properties);
            var tradeSeed=new ArrayList<>(base);
            tradeSeed.addAll(List.of("TradeConnectionCheck","--seed",scratch.resolve("data").toString(),scratch.resolve("trade.properties").toString()));
            ClientCheckHarness.run(scratch, tradeSeed,"seed-trade","",15);
            Path tradeLog=scratch.resolve("trade-server.log");
            Process tradeServer=ClientCheckHarness.start(scratch, List.of(ClientCheckHarness.executable("java"),"-jar",scratch.resolve("tortodrone.jar").toString(),"--nogui"),tradeLog,scratch);
            tradeServer.getOutputStream().close();String tradePort=ClientCheckHarness.ready(tradeServer, tradeLog, Duration.ofSeconds(15));
            var tradeCheck=new ArrayList<>(base);
            tradeCheck.addAll(List.of("TradeConnectionCheck",tradePort,scratch.resolve("data/keys/public.txt").toString(),scratch.resolve("trade.properties").toString()));
            try{ClientCheckHarness.run(scratch, tradeCheck,"trade","",60);}
            catch(Exception failure){System.err.println(ClientCheckHarness.tail(tradeLog));throw failure;}
        } finally {
            ClientCheckHarness.stopChildren();
            try { Runtime.getRuntime().removeShutdownHook(cleanup); }
            catch (IllegalStateException shuttingDown) { /* The registered hook is already stopping the children. */ }
            ClientCheckHarness.deleteTree(scratch);
        }
    }
}
