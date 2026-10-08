import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Optional stock-reader/network checks. All server state and preferences are disposable. */
public final class CheckClientWorld {
    private static final List<Process> CHILDREN = new ArrayList<>();
    private static Path scratch;
    public static void main(String[] args) throws Exception {
        Path project = Path.of("").toAbsolutePath();
        if (args.length != 1 || !Files.isRegularFile(Path.of(args[0], "code/projectx-pcode.jar"))) {
            throw new IllegalArgumentException("Usage: java scripts/compat/CheckClientWorld.java \"/path/to/Spiral Knights\"");
        }
        Path game = Path.of(args[0]).toAbsolutePath();
        for (String jar : List.of("tortodrone.jar", "tortodrone-admin.jar")) {
            if (!Files.isRegularFile(project.resolve("build/" + jar))) throw new IOException("Build first with make build");
        }
        scratch = Files.createTempDirectory("tortodrone-installed-client-");
        Thread cleanup = new Thread(CheckClientWorld::stopChildren, "check-cleanup");
        Runtime.getRuntime().addShutdownHook(cleanup);
        try {
            for (String jar : List.of("tortodrone.jar", "tortodrone-admin.jar")) Files.copy(project.resolve("build/" + jar), scratch.resolve(jar));
            Files.createDirectories(scratch.resolve("home/prefs"));
            Files.createDirectories(scratch.resolve("data"));
            String cp = game.resolve("code/projectx-pcode.jar") + File.pathSeparator + game.resolve("code/*") + File.pathSeparator + scratch.resolve("tortodrone.jar");
            run(List.of(executable("javac"), "--release", "17", "-cp", cp, "-d", scratch.toString(),
                project.resolve("scripts/compat/WorldConnectionCheck.java").toString(), project.resolve("scripts/compat/WorldCompatibility.java").toString()), "compile", "", 30);
            cp = scratch + File.pathSeparator + cp;
            List<String> base = new ArrayList<>(List.of(executable("java"), "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED", "-Djava.awt.headless=true", "-Dno_unpack_resources=true",
                "-Duser.home=" + scratch.resolve("home"), "-Djava.util.prefs.userRoot=" + scratch.resolve("home/prefs"),
                "-Djava.util.prefs.systemRoot=" + scratch.resolve("home/system-prefs"), "-cp", cp));
            List<String> reader = new ArrayList<>(base);
            reader.addAll(List.of("WorldCompatibility", game.toString()));
            run(reader, "reader", "", 30);
            Files.writeString(scratch.resolve("data/server.properties"), "server-ip=127.0.0.1\nserver-port=0\nlog-mode=debug\n");
            for (String user : List.of("LanPlayer", "PeerPlayer")) run(List.of(executable("java"), "-jar", scratch.resolve("tortodrone-admin.jar").toString(), "--create-account", user), "account-" + user, "lan-test-password\nlan-test-password\n", 15);
            Path serverLog = scratch.resolve("server.log");
            Process server = start(List.of(executable("java"), "-jar", scratch.resolve("tortodrone.jar").toString()), serverLog);
            server.getOutputStream().close();
            String port = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline) {
                var match = Pattern.compile("Listening on 127\\.0\\.0\\.1:(\\d+)").matcher(Files.readString(serverLog));
                if (match.find()) { port = match.group(1); break; }
                if (!server.isAlive()) throw new IOException("Temporary server failed:\n" + tail(serverLog));
                Thread.sleep(50);
            }
            if (port == null) throw new IOException("Temporary server readiness timeout:\n" + tail(serverLog));
            List<String> network = new ArrayList<>(base);
            network.addAll(List.of("WorldConnectionCheck", port, scratch.resolve("data/keys/public.txt").toString()));
            try { run(network, "network", "", 60); }
            catch (Exception failure) { System.err.println(tail(serverLog)); throw failure; }
        } finally {
            stopChildren();
            Runtime.getRuntime().removeShutdownHook(cleanup);
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
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
        for (int i = CHILDREN.size() - 1; i >= 0; i--) {
            Process process = CHILDREN.get(i);
            if (!process.isAlive()) continue;
            process.destroy();
            try { if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); } }
            catch (InterruptedException interrupted) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
        }
    }
}
