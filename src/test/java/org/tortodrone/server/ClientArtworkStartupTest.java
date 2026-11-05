package org.tortodrone.server;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.support.TestDeployment;
import static org.tortodrone.support.TestWire.check;

/** Real packaged startup and HTTP requests; a tiny owned PNG pack, no installed client. */
public final class ClientArtworkStartupTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("tortodrone-artwork-startup-");
        Process process = null;
        try {
            Path source = Path.of(ClientArtworkSetup.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path jar = root.resolve("tortodrone.jar");
            TestDeployment.copyServer(source, jar);
            Path cache = root.resolve("data/client-artwork");
            ClientArtworkSetupTest.pack(cache, 0xff334455);
            Path assets = root.resolve("webui/assets/game");
            delete(assets); Files.createDirectories(assets);
            Files.writeString(assets.resolve("manifest.json"), "{\"items\":{},\"sprites\":{},\"ui\":{}}");
            Path config = root.resolve("config/server.yml");
            Files.writeString(config, "server-ip: 127.0.0.1\nserver-port: 0\nwebui/content_url-port: 0\nwebui-enabled: true\nwebui-public-url: 0.0.0.0\nwebui-tls-mode: http\n");
            String javaExecutable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
            process = new ProcessBuilder(javaExecutable, "-XX:ActiveProcessorCount=2", "-Duser.home=" + root.resolve("isolated-home"), "-jar", jar.toString()).redirectErrorStream(true).start();
            var lines = new LinkedBlockingQueue<String>();
            var transcript = new ArrayList<String>();
            Process running = process;
            Thread reader = new Thread(() -> {
                try (var input = new BufferedReader(new InputStreamReader(running.getInputStream(), StandardCharsets.UTF_8))) {
                    String line; while ((line = input.readLine()) != null) lines.add(line);
                } catch (IOException ignored) { }
            }, "ArtworkStartupFixtureOutput");
            reader.setDaemon(true); reader.start();
            int port = 0; boolean restored = false, listening = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
            while (port == 0 || !listening) {
                long left = deadline - System.nanoTime();
                String line = left <= 0 ? null : lines.poll(left, TimeUnit.NANOSECONDS);
                check(line != null, "Packaged artwork startup timeout: " + String.join("\n", transcript));
                transcript.add(line);
                if (line.contains("WebUI artwork restored from the local cache")) restored = true;
                if (line.contains("Done! Listening")) { check(restored, "Artwork published before the game listener"); listening = true; }
                if (line.contains("WebUI Ready")) {
                    var matcher = java.util.regex.Pattern.compile("http://[^ ]+:(\\d+)").matcher(line);
                    check(matcher.find(), "Ephemeral HTTP address published"); port = Integer.parseInt(matcher.group(1));
                }
            }
            byte[] expected = Files.readAllBytes(cache.resolve("manifest.json"));
            check(Arrays.equals(get(port, "manifest.json"), expected), "Published cached manifest served by the packaged WebUI");
            Path icon;
            try (var files = Files.list(cache.resolve("icons"))) { icon = files.findFirst().orElseThrow(); }
            check(Arrays.equals(get(port, "icons/" + icon.getFileName()), Files.readAllBytes(icon)), "Actual PNG bytes served after restoration");
            try (var channel = java.nio.channels.FileChannel.open(root.resolve("data/server.lock"), StandardOpenOption.WRITE)) {
                try { check(channel.tryLock() == null, "Server lease protects artwork setup and storage"); }
                catch (java.nio.channels.OverlappingFileLockException expectedLock) { throw new AssertionError("Fixture unexpectedly shares a process lock", expectedLock); }
            }
            var commands = process.outputWriter(StandardCharsets.UTF_8);
            commands.write("status\nshutdown\nSHUT DOWN PROCESS\n"); commands.flush();
            check(process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0, "Normal console commands remain readable after pre-listener setup");
            reader.join(1000);
            check(Files.isRegularFile(cache.resolve("manifest.json")), "Local artwork cache survives shutdown");
            System.out.println("PASS: packaged startup restores artwork before listeners, serves exact manifest/PNG bytes over HTTP, holds the deployment lease and shuts down through the unchanged console");
        } finally {
            if (process != null && process.isAlive()) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); }
            delete(root);
        }
    }

    private static byte[] get(int port, String path) throws IOException {
        var connection = (HttpURLConnection)URI.create("http://127.0.0.1:" + port + "/admin/assets/game/" + path).toURL().openConnection();
        connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
        try { check(connection.getResponseCode() == 200, "Artwork HTTP response"); try (var input = connection.getInputStream()) { return input.readAllBytes(); } }
        finally { connection.disconnect(); }
    }

    private static void delete(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try (var entries = Files.walk(path)) { for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(entry); }
    }
}
