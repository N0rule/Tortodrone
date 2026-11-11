package org.tortodrone.server;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.support.TestDeployment;
import org.tortodrone.resources.ContentManager;
import org.tortodrone.resources.Json;
import static org.tortodrone.support.TestWire.check;

/** Real packaged startup and HTTP requests; a tiny owned PNG pack, no installed client. */
public final class ClientArtworkStartupTest {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("tortodrone-artwork-startup-");
        Process process = null;
        try {
            Path source = Path.of(ClientArtworkSetup.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path jar = root.resolve("tortodrone.jar");
            TestDeployment.copyServer(source, jar);
            Path config = root.resolve("config/server.yml");
            Files.writeString(config, "server-ip: 127.0.0.1\nserver-port: 0\nwebui/content_url-port: 0\nwebui-enabled: true\nwebui-public-url: 0.0.0.0\nwebui-tls-mode: http\n");
            var before = ContentManager.load(root);
            var identities = new TreeMap<String,String>();
            for (var item : before.items().entries()) identities.put(item.config(), before.items().referenceId(item));
            var contentBytes = snapshot(root.resolve("content"));
            String clientOnly = "Weapon/Client Update Artwork Only";
            check(before.items().find(clientOnly) == null, "Client-only item not in supported catalog");
            Path cache = root.resolve("data/client-artwork");
            ClientArtworkSetupTest.pack(cache, 0xff334455);
            var manifest = new TreeMap<String,Object>((Map<String,Object>)Json.parse(Files.readString(cache.resolve("manifest.json"))));
            var itemIcons = new TreeMap<String,Object>((Map<String,Object>)manifest.get("items"));
            itemIcons.put(clientOnly, itemIcons.values().iterator().next());
            manifest.put("items", itemIcons);
            Files.writeString(cache.resolve("manifest.json"), Json.stringify(manifest));
            Path assets = root.resolve("webui/assets/game");
            delete(assets); Files.createDirectories(assets);
            Files.writeString(assets.resolve("manifest.json"), "{\"items\":{},\"sprites\":{},\"ui\":{}}");
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
            var after = ContentManager.load(root);
            check(after.items().find(clientOnly) == null && after.items().entries().equals(before.items().entries()), "Extra client artwork cannot enable or mutate gameplay items/native identities");
            for (var item : after.items().entries()) check(identities.get(item.config()).equals(after.items().referenceId(item)), "Existing item reference identity preserved");
            check(after.scenes().scenes().stream().map(s -> s.id()).toList().equals(before.scenes().scenes().stream().map(s -> s.id()).toList()), "Artwork cannot add gameplay maps");
            check(contentBytes.equals(snapshot(root.resolve("content"))), "Artwork startup never writes gameplay definitions or bindings");
            System.out.println("PASS: packaged artwork restoration/HTTP/lease/console; client-only artwork leaves gameplay catalog, maps, native identities and content bytes unchanged");
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

    private static Map<String,String> snapshot(Path root) throws Exception {
        var result = new TreeMap<String,String>();
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        try (var files = Files.walk(root)) {
            for (var file : files.filter(Files::isRegularFile).toList())
                result.put(root.relativize(file).toString(), HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file))));
        }
        return result;
    }

    private static void delete(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try (var entries = Files.walk(path)) { for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(entry); }
    }
}
