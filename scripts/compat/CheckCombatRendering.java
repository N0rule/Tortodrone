import java.io.*;
import org.tortodrone.resources.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Launches the stock graphical client against a disposable local combat fixture. */
public final class CheckCombatRendering {
    private static final List<Process> CHILDREN = new ArrayList<>();
    private static Path scratch;

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 3) {
            throw new IllegalArgumentException("Usage: java scripts/compat/CheckCombatRendering.java \"/path/to/Spiral Knights\" [output-dir] [--tutorial-entry-only|--tutorial-only|--ready-room-only|--equipment-groups|--items-only|--arsenal-blink-only|--boxes-only|--town-dialogs-only|--dialogue-only|--training-only|--health-only|--movement-only|--depot-only|--hazards-only|--vortex-shield-only|--multiplayer-only|--multiplayer-world-only]");
        }
        boolean campProgression=Arrays.asList(args).contains("--camp-progression-only");
        boolean campStory=campProgression||Arrays.asList(args).contains("--camp-story-only");
        boolean freshOpening=Arrays.asList(args).contains("--opening-tutorial-only");
        boolean tutorialOnly=freshOpening||Arrays.asList(args).contains("--tutorial-only");
        boolean tutorialEntryOnly=tutorialOnly||Arrays.asList(args).contains("--tutorial-entry-only");
        boolean readyRoomOnly=campStory||Arrays.asList(args).contains("--ready-room-only");
        boolean sharedWorldOnly=Arrays.asList(args).contains("--multiplayer-world-only");
        boolean multiplayerOnly=sharedWorldOnly||Arrays.asList(args).contains("--multiplayer-only");
        boolean arsenalStationOnly=Arrays.asList(args).contains("--arsenal-station-only");
        boolean arsenalBlinkOnly=arsenalStationOnly||Arrays.asList(args).contains("--arsenal-blink-only");
        boolean itemsOnly=Arrays.asList(args).contains("--items-only");
        boolean boxesOnly=Arrays.asList(args).contains("--boxes-only");
        boolean dialogueOnly=Arrays.asList(args).contains("--dialogue-only");
        boolean townDialogsOnly=Arrays.asList(args).contains("--town-dialogs-only");
        boolean vortexShieldOnly=Arrays.asList(args).contains("--vortex-shield-only");
        boolean hazardsOnly=Arrays.asList(args).contains("--hazards-only");
        boolean depotOnly=Arrays.asList(args).contains("--depot-only");
        boolean healthOnly=Arrays.asList(args).contains("--health-only");
        boolean movementOnly=Arrays.asList(args).contains("--movement-only");
        boolean equipmentGroups=Arrays.asList(args).contains("--equipment-groups");
        boolean trainingOnly=tutorialEntryOnly||readyRoomOnly||equipmentGroups||itemsOnly||multiplayerOnly||arsenalBlinkOnly||boxesOnly||townDialogsOnly||dialogueOnly||vortexShieldOnly||hazardsOnly||depotOnly||healthOnly||movementOnly||Arrays.asList(args).contains("--training-only");
        String outputArgument=Arrays.stream(args).skip(1).filter(arg->!arg.startsWith("--")).findFirst().orElse(null);
        Path project = Path.of("").toRealPath();
        Path game = Path.of(args[0]).toRealPath();
        if (!Files.isRegularFile(game.resolve("full.dat")) ||
            !Files.isRegularFile(game.resolve("code/projectx-pcode.jar"))) {
            throw new IOException("Game directory must contain full.dat and code/projectx-pcode.jar");
        }
        Path deployment=Path.of(System.getProperty("tortodrone.deployment",project.resolve("build").toString()));
        Path jar = deployment.resolve("tortodrone.jar");
        if (!Files.isRegularFile(jar)) throw new IOException("Build first with ./gradlew build");
        Path defaults = deployment.resolve("content");
        requireMaps(defaults);

        Path output;
        if (outputArgument != null) {
            output = Path.of(outputArgument).toAbsolutePath().normalize();
            rejectUnsafeOutput(output, project, game);
            if (Files.exists(output)) throw new IOException("Output directory already exists: " + output);
            Files.createDirectories(output);
        } else {
            output = Files.createTempDirectory("tortodrone-combat-graphics-");
        }
        System.out.println("Combat rendering output: " + output);
        scratch = Files.createTempDirectory("tortodrone-combat-sandbox-");
        Thread cleanup = new Thread(CheckCombatRendering::stopChildren, "combat-render-cleanup");
        Runtime.getRuntime().addShutdownHook(cleanup);
        try {
            Path app = scratch.resolve("app");
            Files.createDirectories(app);
            Files.copy(jar, scratch.resolve("tortodrone.jar"));
            copyTree(defaults, scratch.resolve("content"));
            copyTree(deployment.resolve("config"),scratch.resolve("config"));
            Files.deleteIfExists(scratch.resolve("config/server.yml"));
            Path maps = scratch.resolve("content");
            if(dialogueOnly){Path npc=maps.resolve("maps/advanced_training/npcs/konway.yml");var yaml=Yaml.read(npc);var dialogue=Yaml.map(yaml.get("dialogue"),"dialogue");var nodes=Yaml.map(dialogue.get("nodes"),"nodes");Yaml.map(nodes.get("part 1a"),"node").put("text","Thanks! I'm off to explore now.");Files.delete(npc);Yaml.write(npc,yaml);}
            requireMaps(maps);
            if(arsenalBlinkOnly)arsenalBlinkFixture(maps);
            if(healthOnly)healthFixture(maps);
            if(sharedWorldOnly)vortexShieldFixture(maps,true);
            if(hazardsOnly)hazardFixture(maps);
            if(vortexShieldOnly)vortexShieldFixture(maps);
            Files.createDirectories(scratch.resolve("data"));
            Files.createDirectories(scratch.resolve("home/prefs"));
            Files.createDirectories(scratch.resolve("home/system-prefs"));
            Files.createDirectories(scratch.resolve("tmp"));
            Files.copy(game.resolve("full.dat"), app.resolve("full.dat"));

            String cp = game.resolve("code/projectx-pcode.jar") + File.pathSeparator +
                game.resolve("code/*") + File.pathSeparator + scratch.resolve("tortodrone.jar");
            Path source = project.resolve("scripts/compat/CombatRenderingCheck.java");
            if (!Files.isRegularFile(source)) throw new IOException("Missing child probe: " + source);
            run(List.of(executable("javac"), "--release", "25", "-cp", cp, "-d", scratch.toString(), source.toString()),
                "compile", 60, null);

            Path config = scratch.resolve("data/server.properties");
            Files.writeString(config, "server-ip=127.0.0.1\nserver-port=0\nnews-port=0\nlog-mode=debug\n");
            Path serverLog = output.resolve("server.log");
            Path outputDir = output.resolve("captures");
            Files.createDirectories(outputDir);
            var base = graphicsBase(game, app, scratch);
            var seed = new ArrayList<>(base);
            seed.addAll(List.of("CombatRenderingCheck", campStory?"--seed-camp":freshOpening?"--seed-opening":equipmentGroups?"--seed-equipment":itemsOnly?"--seed-items":multiplayerOnly?"--seed-multiplayer":boxesOnly?"--seed-boxes":depotOnly?"--seed-depot":"--seed", scratch.resolve("data").toString()));
            run(seed, "seed", 45, scratch);

            Process server = start(List.of(executable("java"), "-jar", scratch.resolve("tortodrone.jar").toString(),"--nogui","--skip-client-assets"), serverLog, scratch);
            server.getOutputStream().close();
            String port = ready(server, serverLog, Duration.ofSeconds(120));
            Path publicKey = scratch.resolve("data/keys/public.txt");
            long keyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.isRegularFile(publicKey) && System.nanoTime() < keyDeadline) Thread.sleep(50);
            if (!Files.isRegularFile(publicKey)) throw new IOException("Server did not create disposable public key:\n" + tail(serverLog));

            Path peerOutput=outputDir.resolve("peer"),peerHome=scratch.resolve("peer-home"),peerTemp=scratch.resolve("peer-tmp");
            Files.createDirectories(peerOutput);Files.createDirectories(peerHome.resolve("prefs"));
            Files.createDirectories(peerHome.resolve("system-prefs"));Files.createDirectories(peerTemp);
            var peerCommand=new ArrayList<>(base);
            peerCommand.replaceAll(arg->arg.startsWith("-Duser.home=")?"-Duser.home="+peerHome:
                arg.startsWith("-Djava.util.prefs.userRoot=")?"-Djava.util.prefs.userRoot="+peerHome.resolve("prefs"):
                arg.startsWith("-Djava.util.prefs.systemRoot=")?"-Djava.util.prefs.systemRoot="+peerHome.resolve("system-prefs"):
                arg.startsWith("-Djava.io.tmpdir=")?"-Djava.io.tmpdir="+peerTemp:arg);
            peerCommand.addAll(List.of("CombatRenderingCheck","--peer",port,publicKey.toString(),peerOutput.toString()));
            if(multiplayerOnly)peerCommand.add(sharedWorldOnly?"--multiplayer-world-only":"--multiplayer-only");
            if(equipmentGroups)peerCommand.add("--equipment-groups");
            Path peerLog=output.resolve("peer-client.log");Process peer=tutorialEntryOnly||readyRoomOnly||itemsOnly||arsenalBlinkOnly||boxesOnly||townDialogsOnly||dialogueOnly||vortexShieldOnly||hazardsOnly||depotOnly||healthOnly||movementOnly?null:start(peerCommand,peerLog,scratch);
            long peerDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
            while(peer!=null&&peer.isAlive()&&!Files.isRegularFile(peerOutput.resolve("peer-ready"))&&System.nanoTime()<peerDeadline)Thread.sleep(50);
            if(peer!=null&&(!peer.isAlive()||!Files.isRegularFile(peerOutput.resolve("peer-ready"))))
                throw new IOException("Graphical peer did not become ready:\n"+tail(peerLog));

            var graphical = new ArrayList<>(base);
            graphical.addAll(List.of("CombatRenderingCheck", port, publicKey.toString(), outputDir.toString()));
            if(trainingOnly)graphical.add(campProgression?"--camp-progression-only":campStory?"--camp-story-only":freshOpening?"--opening-tutorial-only":tutorialOnly?"--tutorial-only":tutorialEntryOnly?"--tutorial-entry-only":readyRoomOnly?"--ready-room-only":equipmentGroups?"--equipment-groups":itemsOnly?"--items-only":sharedWorldOnly?"--multiplayer-world-only":multiplayerOnly?"--multiplayer-only":arsenalBlinkOnly?(arsenalStationOnly?"--arsenal-station-only":"--arsenal-blink-only"):boxesOnly?"--boxes-only":townDialogsOnly?"--town-dialogs-only":dialogueOnly?"--dialogue-only":vortexShieldOnly?"--vortex-shield-only":hazardsOnly?"--hazards-only":depotOnly?"--depot-only":healthOnly?"--health-only":movementOnly?"--movement-only":"--training-only");
            Path clientLog = output.resolve("client.log");
            Process client = start(graphical, clientLog, scratch);
            if(multiplayerOnly&&!sharedWorldOnly){
                long mpDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(180);
                while(peer.isAlive()&&client.isAlive()&&System.nanoTime()<mpDeadline)Thread.sleep(50);
                if(peer.isAlive()||peer.exitValue()!=0||!Files.isRegularFile(outputDir.resolve("mp-disconnected")))throw new IOException("Multiplayer failed: "+tail(peerLog)+"\nPrimary: "+tail(clientLog));
                peerLog=output.resolve("peer-reconnect.log");peer=start(peerCommand,peerLog,scratch);
            }
            if (!client.waitFor(tutorialOnly?440:tutorialEntryOnly||readyRoomOnly?100:trainingOnly?440:400, TimeUnit.SECONDS)) {
                throw new IOException("Graphical combat probe timed out:\n" + tail(clientLog) + "\nServer:\n" + tail(serverLog));
            }
            String log = Files.readString(clientLog);
            if((itemsOnly||readyRoomOnly)&&(log.contains("NullPointerException")||log.contains("ClassCastException")))throw new IOException("Native controller error: "+tail(clientLog));
            if (client.exitValue() != 0 || !log.lines().anyMatch(line -> line.startsWith("PASS:"))) {
                throw new IOException("Graphical combat probe did not pass (exit " + client.exitValue() + "):\n" + tail(clientLog) + "\nServer:\n" + tail(serverLog));
            }
            if(freshOpening||campStory){
                var reconnect=new ArrayList<>(base);Path captures=outputDir.resolve("reconnect");Files.createDirectories(captures);
                reconnect.addAll(List.of("CombatRenderingCheck",port,publicKey.toString(),captures.toString(),campProgression?"--camp-progression-return-only":campStory?"--camp-story-return-only":"--opening-return-only"));
                Path reconnectLog=output.resolve("reconnect.log");Process resumed=start(reconnect,reconnectLog,scratch);
                if(!resumed.waitFor(100,TimeUnit.SECONDS)||resumed.exitValue()!=0||!Files.readString(reconnectLog).lines().anyMatch(l->l.startsWith("PASS:")))throw new IOException("Opening reconnect failed: "+tail(reconnectLog));
            }
            if(peer!=null&&(!peer.waitFor(15,TimeUnit.SECONDS)||peer.exitValue()!=0
                    ||!Files.readString(peerLog).lines().anyMatch(line->line.startsWith("PASS:"))))
                throw new IOException("Graphical peer did not pass:\n"+tail(peerLog));
            if(multiplayerOnly||equipmentGroups)for(Path nativeLog:List.of(clientLog,output.resolve("peer-client.log"),peerLog)){
                String nativeText=Files.readString(nativeLog);
                if(nativeText.contains("NullPointerException")||nativeText.contains("ClassCastException"))
                    throw new IOException("Native multiplayer HUD/controller error: "+tail(nativeLog));
            }
            System.out.println(log.lines().filter(line -> line.startsWith("PASS:")).findFirst().orElseThrow());
            System.out.println("Combat screenshots and logs retained at " + output);
        } finally {
            stopChildren();
            try { Runtime.getRuntime().removeShutdownHook(cleanup); }
            catch (IllegalStateException shuttingDown) { /* The registered hook is already stopping the children. */ }
            deleteTree(scratch);
        }
    }

    private static List<String> graphicsBase(Path game, Path app, Path sandbox) {
        return new ArrayList<>(List.of(executable("java"), "--enable-native-access=ALL-UNNAMED",
            "--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.util=ALL-UNNAMED",
            "-Ddisable_sound=true", "-Dno_unpack_resources=true", "-Dresource_dir=" + game.resolve("rsrc/"),
            "-Duser.home=" + sandbox.resolve("home"),
            "-Djava.util.prefs.userRoot=" + sandbox.resolve("home/prefs"),
            "-Djava.util.prefs.systemRoot=" + sandbox.resolve("home/system-prefs"),
            "-Djava.io.tmpdir=" + sandbox.resolve("tmp"), "-Dappdir=" + app,
            "-cp", sandbox + File.pathSeparator + game.resolve("code/projectx-pcode.jar") +
                File.pathSeparator + game.resolve("code/*") + File.pathSeparator + sandbox.resolve("tortodrone.jar")));
    }

    private static void requireMaps(Path maps) throws IOException {
        for (String name : List.of("haven", "arcade", "bazaar", "garrison", "advanced_training", "laboratory", "hall_of_heroes")) {
            if (!Files.isDirectory(maps.resolve("maps").resolve(name)))
                throw new IOException("Missing prepared scene map " + maps.resolve("maps").resolve(name));
        }
    }

    /** Fixture placements are edited through the same map-local YAML as administrator content. */
    private static void hazardFixture(Path content)throws Exception { relocate(content,"hazards"); }
    private static void arsenalBlinkFixture(Path content)throws Exception {
        Path map=content.resolve("maps/advanced_training"),file=map.resolve("actors.yml");
        var prepared=MapYaml.prepare(map,content.resolve("shared"));var yaml=Yaml.read(file);var actors=Yaml.map(yaml.get("actors"),"actors");
        float x=Float.parseFloat(prepared.descriptor().getProperty("spawn-x")),y=Float.parseFloat(prepared.descriptor().getProperty("spawn-y"));
        var bindings=Yaml.map(Json.parseCatalog(Files.readString(map.resolve("native/bindings.json"))),"bindings");inheritFixtureActors(bindings,actors);
        var originals=Yaml.map(bindings.get("actors"),"actors");
        for(var actor:actors.entrySet()){
            var original=Yaml.map(originals.get(actor.getKey()),"binding");var editable=Yaml.map(actor.getValue(),"actor");
            if("1955".equals(original.get("source-key"))){editable.put("x",x+2.65f);editable.put("y",y+1.95f);}
            if(((String)original.getOrDefault("config","")).endsWith("/Floor/Fire")){editable.put("x",x);editable.put("y",y);}
        }
        Files.delete(file);Yaml.write(file,yaml);
    }
    private static void healthFixture(Path content)throws Exception { relocate(content,"health"); }
    private static void relocate(Path content,String kind)throws Exception {
        Path map=content.resolve("maps/advanced_training"),file=map.resolve("actors.yml");
        var prepared=MapYaml.prepare(map,content.resolve("shared"));var yaml=Yaml.read(file);var actors=Yaml.map(yaml.get("actors"),"actors");
        float x=Float.parseFloat(prepared.descriptor().getProperty("spawn-x")),y=Float.parseFloat(prepared.descriptor().getProperty("spawn-y"));int pot=0;boolean pad=false,capsule=false;
        var bindings=Yaml.map(Json.parseCatalog(Files.readString(map.resolve("native/bindings.json"))),"bindings");var nativeActors=Yaml.map(bindings.get("actors"),"actors");
        inheritFixtureActors(bindings,actors);
        for(var actor:actors.entrySet()) {
            var fixed=Yaml.map(nativeActors.get(actor.getKey()),"binding");var value=Yaml.map(actor.getValue(),"actor");String config=(String)fixed.get("config");
            if(kind.equals("hazards")&&(fixed.getOrDefault("child.config","").equals("Dynamic/Lift Objects/Fire Pot")||fixed.getOrDefault("child.config","").equals("Dynamic/Lift Objects/Oil Pot"))){value.put("x",x+(pot++==0?-.8f:.8f));value.put("y",y+1);}
            if(kind.equals("health")&&(config.endsWith("/Floor/Fire")||config.endsWith("/Floor/Shock"))){value.put("x",x);value.put("y",y+3);}
            else if(kind.equals("health")&&!pad&&config.endsWith("/Heal Pad")){value.put("x",x);value.put("y",y+1);pad=true;}
            // Keep a replacement pickup from changing the inventory assertion
            // while the isolated client waits for its scene-entry crossfade.
            if(kind.equals("health")&&!capsule&&fixed.getOrDefault("child.config","").equals("Item/Pickups/Capsule/Health Capsule")){value.put("x",x);value.put("y",y);value.put("delay",60000);capsule=true;}
        }
        if(kind.equals("hazards")&&pot!=2)throw new IOException("Expected original Fire and Oil Pot pads");
        Files.delete(file);Yaml.write(file,yaml);
    }
    private static void vortexShieldFixture(Path content)throws Exception { vortexShieldFixture(content,false); }
    private static void vortexShieldFixture(Path content,boolean multiplayer)throws Exception {
        Path map=content.resolve("maps/advanced_training"),file=map.resolve("actors.yml"),bindingFile=map.resolve("native/bindings.json");
        var prepared=MapYaml.prepare(map,content.resolve("shared"));var props=prepared.content();
        String prefix=Arrays.stream(props.getProperty("entries").split(",")).map(id->"entry."+id+".child.")
            .filter(key->props.getProperty(key+"kind","").equals("Monster")).findFirst().orElseThrow();
        var yaml=Yaml.read(file);var actors=Yaml.map(yaml.get("actors"),"actors");var bindings=Yaml.map(Json.parseCatalog(Files.readString(bindingFile)),"bindings");
        inheritFixtureActors(bindings,actors);
        bindings.remove("actorDefaults");bindings.remove("actorOrder");bindings.remove("actorAliases");
        var fixedActors=Yaml.map(bindings.get("actors"),"actors");var fields=Yaml.map(bindings.get("actorFields"),"actor fields");var types=Yaml.map(bindings.get("actorTypes"),"actor types");
        float x=Float.parseFloat(prepared.descriptor().getProperty("spawn-x")),y=Float.parseFloat(prepared.descriptor().getProperty("spawn-y"))+3;
        for(int i=0;i<(multiplayer?1:2);i++) {
            String id="vortex_shield_"+i;var fixed=new LinkedHashMap<String,Object>();var editable=new LinkedHashMap<String,Object>();var schema=new LinkedHashMap<String,Object>();
            for(String key:props.stringPropertyNames())if(key.startsWith(prefix)) {
                String name=key.substring(prefix.length()),value=props.getProperty(key);
                if(name.equals("config")||name.equals("kind")||name.equals("native-type")||name.startsWith("config.")||value.startsWith("{")||value.startsWith("["))fixed.put(name,value);
                else if(name.equals("rotation"))editable.put("facing",Math.toDegrees(Float.parseFloat(value)));
                else {Object scalar=value;if(value.equals("true")||value.equals("false"))scalar=Boolean.parseBoolean(value);else try{scalar=new java.math.BigDecimal(value);}catch(NumberFormatException text){ }editable.put(name,scalar);}
            }
            fixed.put("source-key","vortex-shield-"+i);editable.put("x",x);editable.put("y",y+(multiplayer?2.3f:i==0?-1.2f:2.3f));editable.put("facing",0);editable.put("health",multiplayer?1000:10000);editable.put("acquire-distance",0);
            editable.forEach((key,value)->schema.put(key,value instanceof Number?"number":value instanceof Boolean?"boolean":"text"));fixedActors.put(id,fixed);actors.put(id,editable);fields.put(id,new ArrayList<>(editable.keySet()));types.put(id,schema);
        }
        Files.delete(file);Yaml.write(file,yaml);Files.writeString(bindingFile,Json.stringify(bindings));
        Path metadata=map.resolve("native/metadata.properties");var descriptor=new Properties();descriptor.putAll(properties(metadata));descriptor.setProperty("sha256.bindings.json",ContentFiles.digest(bindingFile));save(metadata,descriptor);
        Path portals=map.resolve("portals.yml");var p=Yaml.read(portals);String spawn=Yaml.text(Yaml.read(map.resolve("map.yml")).get("defaultSpawn"),"default spawn");Yaml.map(Yaml.map(p.get("entrances"),"entrances").get(spawn),"entrance").put("y",y);Files.delete(portals);Yaml.write(portals,p);
    }
    private static void inheritFixtureActors(Map<String,Object> bindings,Map<String,Object> actors)throws IOException {
        var defaults=Yaml.map(bindings.getOrDefault("actorDefaults",Map.of()),"actor defaults");
        if(defaults.isEmpty())return;var full=new LinkedHashMap<String,Object>();
        for(Object raw:Yaml.list(bindings.get("actorOrder"),"actor order")){String id=Yaml.text(raw,"actor ID");var row=new LinkedHashMap<>(Yaml.map(defaults.get(id),"actor default"));if(actors.containsKey(id))row.putAll(Yaml.map(actors.get(id),"actor override"));full.put(id,row);}
        actors.clear();actors.putAll(full);
    }
    private static Properties properties(Path file)throws IOException {var values=new Properties();try(var in=Files.newInputStream(file)){values.load(in);}return values;}
    private static void save(Path file,Properties values)throws IOException {try(var out=Files.newOutputStream(file)){values.store(out,"Disposable health HUD fixture");}}
    private static String digest(Path path)throws Exception {
        var hash=java.security.MessageDigest.getInstance("SHA-256");
        if(Files.isDirectory(path)){try(var files=Files.walk(path)){for(Path file:files.filter(Files::isRegularFile).sorted(Comparator.comparing(p->path.relativize(p).toString().replace('\\','/'))).toList()){
            hash.update(path.relativize(file).toString().replace('\\','/').getBytes(java.nio.charset.StandardCharsets.UTF_8));hash.update((byte)0);hash.update(HexFormat.of().parseHex(digest(file)));
        }}}else hash.update(Files.readAllBytes(path));
        return HexFormat.of().formatHex(hash.digest());
    }

    private static void rejectUnsafeOutput(Path output, Path project, Path game) throws IOException {
        Path ancestor = output;
        while (ancestor != null && !Files.exists(ancestor)) ancestor = ancestor.getParent();
        if (ancestor == null) throw new IOException("Cannot resolve output directory parent: " + output);
        Path canonical = ancestor.toRealPath().resolve(ancestor.relativize(output)).normalize();
        if (canonical.equals(project) || canonical.startsWith(project) || canonical.equals(game) || canonical.startsWith(game)) {
            throw new IOException("Output directory must be outside the repository and installed game: " + output);
        }
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("Symlink in defaults: " + path);
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else Files.copy(path, target);
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static String ready(Process server, Path log, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        Pattern pattern = Pattern.compile("Listening on 127\\.0\\.0\\.1:(\\d+)");
        while (System.nanoTime() < deadline) {
            var match = pattern.matcher(Files.exists(log) ? Files.readString(log) : "");
            if (match.find()) return match.group(1);
            if (!server.isAlive()) throw new IOException("Disposable server failed:\n" + tail(log));
            Thread.sleep(50);
        }
        throw new IOException("Disposable server readiness timeout:\n" + tail(log));
    }

    private static String executable(String name) { return Path.of(System.getProperty("java.home"), "bin", name).toString(); }

    private static Process start(List<String> command, Path log, Path directory) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("_JAVA_AWT_WM_NONREPARENTING", "1");
        Process process = builder.start();
        synchronized (CHILDREN) { CHILDREN.add(process); }
        return process;
    }

    private static void run(List<String> command, String name, int timeout, Path directory) throws Exception {
        Path log = scratch.resolve(name + ".log");
        Process process = start(command, log, directory == null ? scratch : directory);
        if (!process.waitFor(timeout, TimeUnit.SECONDS)) throw new IOException(name + " timed out:\n" + tail(log));
        if (process.exitValue() != 0) throw new IOException(name + " failed:\n" + tail(log));
    }

    private static String tail(Path path) throws IOException {
        if (!Files.exists(path)) return "(no log yet)";
        String text = Files.readString(path);
        return text.substring(Math.max(0, text.length() - 9000));
    }

    private static void stopChildren() {
        synchronized (CHILDREN) {
            boolean interrupted = Thread.interrupted();
            try {
                for (int i = CHILDREN.size() - 1; i >= 0; i--) interrupted = stopChild(CHILDREN.get(i), interrupted);
            } finally {
                if (Thread.interrupted()) interrupted = true;
                if (interrupted) Thread.currentThread().interrupt();
            }
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
