import java.io.*;
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
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("Usage: java scripts/compat/CheckCombatRendering.java \"/path/to/Spiral Knights\" [output-dir]");
        }
        Path project = Path.of("").toRealPath();
        Path game = Path.of(args[0]).toRealPath();
        if (!Files.isRegularFile(game.resolve("full.dat")) ||
            !Files.isRegularFile(game.resolve("code/projectx-pcode.jar"))) {
            throw new IOException("Game directory must contain full.dat and code/projectx-pcode.jar");
        }
        Path jar = project.resolve("build/tortodrone.jar");
        if (!Files.isRegularFile(jar)) throw new IOException("Build first with ./gradlew build");
        Path defaults = project.resolve("build/defaults");
        requireMaps(defaults);

        Path output;
        if (args.length == 2) {
            output = Path.of(args[1]).toAbsolutePath().normalize();
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
            copyTree(defaults, scratch.resolve("defaults"));
            Path maps = scratch.resolve("defaults");
            requireMaps(maps);
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
            Files.writeString(config, "server-ip=127.0.0.1\nserver-port=0\nlog-mode=debug\n");
            Path serverLog = output.resolve("server.log");
            Path outputDir = output.resolve("captures");
            Files.createDirectories(outputDir);
            var base = graphicsBase(game, app, scratch);
            var seed = new ArrayList<>(base);
            seed.addAll(List.of("CombatRenderingCheck", "--seed", scratch.resolve("data").toString()));
            run(seed, "seed", 45, scratch);

            Process server = start(List.of(executable("java"), "-jar", scratch.resolve("tortodrone.jar").toString(),"--nogui"), serverLog, scratch);
            server.getOutputStream().close();
            String port = ready(server, serverLog, Duration.ofSeconds(30));
            Path publicKey = scratch.resolve("data/keys/public.txt");
            long keyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!Files.isRegularFile(publicKey) && System.nanoTime() < keyDeadline) Thread.sleep(50);
            if (!Files.isRegularFile(publicKey)) throw new IOException("Server did not create disposable public key:\n" + tail(serverLog));

            var graphical = new ArrayList<>(base);
            graphical.addAll(List.of("CombatRenderingCheck", port, publicKey.toString(), outputDir.toString()));
            Path clientLog = output.resolve("client.log");
            Process client = start(graphical, clientLog, scratch);
            if (!client.waitFor(240, TimeUnit.SECONDS)) {
                throw new IOException("Graphical combat probe timed out:\n" + tail(clientLog) + "\nServer:\n" + tail(serverLog));
            }
            String log = Files.readString(clientLog);
            if (client.exitValue() != 0 || !log.lines().anyMatch(line -> line.startsWith("PASS:"))) {
                throw new IOException("Graphical combat probe did not pass (exit " + client.exitValue() + "):\n" + tail(clientLog) + "\nServer:\n" + tail(serverLog));
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
