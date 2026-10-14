import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Isolated dependency downloads: cache, retries, checksums and stalled response bodies. */
public final class BuildToolsTest {
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("tortodrone-build-inputs-");
        check(BuildTools.inferDeploymentRoot(home.resolve("content/packs/vanilla/maps")).equals(home),
            "canonical vanilla map output resolves its deployment root");
        check(BuildTools.inferDeploymentRoot(home.resolve("content/packs/vanilla")).equals(home),
            "canonical vanilla output resolves its deployment root");
        check(BuildTools.inferDeploymentRoot(home.resolve("defaults/maps")).equals(home),
            "legacy defaults/maps output resolves its deployment root");
        var requests = new AtomicInteger();
        var release = new CountDownLatch(1);
        byte[] content = "synthetic dependency".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.createContext("/download", exchange -> {
                try (exchange) {
                    int attempt = requests.incrementAndGet();
                    boolean retry = exchange.getRequestURI().getQuery().equals("retry") && attempt == 1;
                    exchange.sendResponseHeaders(retry ? 503 : 200, content.length);
                    exchange.getResponseBody().write(content);
                }
            });
            server.createContext("/stalled", exchange -> {
                try (exchange) {
                    requests.incrementAndGet();
                    exchange.sendResponseHeaders(200, 100);
                    exchange.getResponseBody().write(1);
                    exchange.getResponseBody().flush();
                    try { release.await(); }
                    catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
                }
            });
            server.start();
            try {
                String root = "http://127.0.0.1:" + server.getAddress().getPort();
                Path cached = home.resolve("deps/library.jar");
                BuildTools.fetch(cached, root + "/download?normal", digest);
                check(Arrays.equals(content, Files.readAllBytes(cached)), "verified download installed");
                check(requests.get() == 1, "one successful download");
                BuildTools.fetch(cached, root + "/download?normal", digest);
                check(requests.get() == 1, "cached build does not contact the network");

                Files.writeString(cached, "corrupt cached library");
                expectFailure(() -> BuildTools.fetch(cached, root + "/download?normal", digest));
                check(requests.get() == 1, "corrupt cache refused without silently replacing it");

                requests.set(0);
                Path retried = home.resolve("deps/retried.jar");
                BuildTools.fetch(retried, root + "/download?retry", digest);
                check(requests.get() == 2 && Arrays.equals(content, Files.readAllBytes(retried)), "failed HTTP attempt retried");

                requests.set(0);
                Path invalid = home.resolve("deps/invalid.jar");
                expectFailure(() -> BuildTools.fetch(invalid, root + "/download?normal", "0".repeat(64)));
                check(requests.get() == 3 && !Files.exists(invalid), "bad checksums never installed");

                requests.set(0);
                Path stalled = home.resolve("deps/stalled.jar");
                long start = System.nanoTime();
                expectFailure(() -> BuildTools.fetch(stalled, root + "/stalled", digest, Duration.ofMillis(400)));
                check(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 5, "response-body stalls obey the full download deadline");
                check(requests.get() == 3 && !Files.exists(stalled), "stalled downloads have bounded retries and no installed partial file");
                try (var files = Files.list(home.resolve("deps"))) {
                    check(files.noneMatch(p -> p.getFileName().toString().contains(".download-")), "temporary downloads cleaned up");
                }
                Path oldMap=home.resolve("maps/haven/scene.dat");Files.createDirectories(oldMap.getParent());
                byte[] oldBytes="previous archive map".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                Files.write(oldMap,oldBytes);
                String oldMapDigest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(oldBytes));
                var ownership=new Properties();ownership.setProperty("owned.haven/scene.dat",oldMapDigest);
                check(BuildTools.archiveOwns(ownership,Path.of("haven/scene.dat"),oldMap),
                    "previous generated archive map is a replacement baseline");
                ownership.setProperty("owned.haven/scene.dat","0".repeat(64));
                check(!BuildTools.archiveOwns(ownership,Path.of("haven/scene.dat"),oldMap),
                    "archive replacement ownership does not permit unrelated deployment edits");
                System.out.println("PASS: dependency cache, HTTP retries, checksum refusal, stalled-body deadlines and cleanup");
            } finally {
                release.countDown(); server.stop(0);
            }
        } finally {
            try (var files = Files.walk(home)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void expectFailure(Action action) throws Exception {
        try { action.run(); throw new AssertionError("Expected dependency preparation failure"); }
        catch (IOException expected) { }
    }
    private interface Action { void run() throws Exception; }
}
