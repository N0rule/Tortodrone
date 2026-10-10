import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Properties;
import java.util.List;
import java.util.concurrent.*;

/** Java 25 build inputs and offline preparation of the extensible defaults tree. */
public final class BuildTools {
    public static void main(String[] args) throws Exception {
        Path project = Path.of("").toAbsolutePath();
        if (args.length == 0) throw new IllegalArgumentException("Usage: java scripts/BuildTools.java sqlite|jline output, or defaults|maps [output]");
        switch (args[0]) {
            case "sqlite", "jline" -> {
                if (args.length != 2) throw new IllegalArgumentException("Expected dependency output path");
                boolean sqlite = args[0].equals("sqlite");
                fetch(Path.of(args[1]), sqlite
                    ? "https://repo.maven.apache.org/maven2/org/xerial/sqlite-jdbc/3.53.4.0/sqlite-jdbc-3.53.4.0.jar"
                    : "https://repo.maven.apache.org/maven2/org/jline/jline/4.4.7/jline-4.4.7-jdk11.jar", sqlite
                    ? "bcb1f51e36f940867e83342f9efbf5968ac44a6bef4d397bb4af7b17b45cd2fb"
                    : "091c6aaf65de86d4106528a626fe1d9daebcd2313102d6f7e484700846d0e63d");
            }
            case "maps", "defaults" -> {
                if (args.length > 2) throw new IllegalArgumentException("Expected at most one map output directory");
                boolean maps=args[0].equals("maps");
                Path source=project.resolve(maps?"defaults/maps":"defaults");
                Path output = args.length == 2 ? Path.of(args[1]) : project.resolve(maps?"build/defaults/maps":"build/defaults");
                verifyMaps(project);
                if (Files.notExists(source, LinkOption.NOFOLLOW_LINKS)) {
                    System.out.println("Optional default source is absent: " + source + "; continuing without it");
                    return;
                }
                if (!Files.isDirectory(source)) throw new IOException("Default source is not a directory: " + source);
                try(var paths=Files.walk(source)) {
                    for(Path file:paths.filter(Files::isRegularFile).sorted().toList()) {
                        if(Files.isSymbolicLink(file) || !file.toRealPath().startsWith(source.toRealPath()))throw new IOException("Default file escapes its source tree");
                        copyMissing(file,output.resolve(source.relativize(file)));
                    }
                }
            }
            default -> throw new IllegalArgumentException("Unknown build input command");
        }
    }
    private static Properties properties(Path file) throws IOException {
        var values = new Properties();
        try (var input = Files.newInputStream(file)) { values.load(input); }
        return values;
    }
    private static void verifyMaps(Path project) throws Exception {
        Path maps = project.resolve("defaults/maps");
        if (Files.notExists(maps, LinkOption.NOFOLLOW_LINKS)) {
            System.out.println("Optional map source is absent: " + maps + "; continuing without maps");
            return;
        }
        try(var folders=Files.list(maps)) {
            for(Path pack:folders.filter(Files::isDirectory).toList()) {
                var descriptor = properties(pack.resolve("map.properties"));
                verify(pack.resolve("scene.dat"), descriptor.getProperty("scene-sha256"));
                verify(pack.resolve("walkability.gz"), descriptor.getProperty("collision-sha256"));
            }
        }
    }
    private static void copyMissing(Path source, Path target) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            try { Files.copy(source, target); } catch (FileAlreadyExistsException preserved) { }
        }
    }
    static void fetch(Path target, String url, String digest) throws Exception {
        fetch(target, url, digest, Duration.ofSeconds(30));
    }
    static void fetch(Path target, String url, String digest, Duration timeout) throws Exception {
        if (Files.exists(target)) {
            verify(target, digest);
            Files.setLastModifiedTime(target, FileTime.fromMillis(System.currentTimeMillis()));
            System.out.println("Using verified cached dependency: " + target);
            return;
        }
        Files.createDirectories(target.toAbsolutePath().getParent());
        System.out.println("Missing dependency: " + target + "; downloading from " + url);
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()) {
            var request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build();
            IOException failure = null;
            try {
                for (int attempt = 1; attempt <= 3; attempt++) {
                    System.out.printf("Download attempt %d/3 (%.1f s deadline)%n", attempt, timeout.toMillis() / 1000.0);
                    // Each attempt owns a separate file, including when a cancelled transfer unwinds.
                    Path temporary = Files.createTempFile(target.toAbsolutePath().getParent(), target.getFileName() + ".download-", ".tmp");
                    CompletableFuture<HttpResponse<Path>> download = null;
                    try {
                        download = client.sendAsync(request, info -> new DownloadBody(temporary,
                            target.getFileName().toString(), info.headers().firstValueAsLong("Content-Length").orElse(-1)));
                        var response = download.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
                        verify(temporary, digest);
                        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                        System.out.println("Downloaded and checksum verified: " + target);
                        return;
                    } catch (TimeoutException e) {
                        failure = new IOException("Download timed out", e);
                    } catch (ExecutionException e) {
                        failure = new IOException("Download failed: " + e.getCause(), e.getCause());
                    } catch (IOException e) { failure = e; }
                    finally {
                        if (download != null) download.cancel(true);
                        Files.deleteIfExists(temporary);
                    }
                    System.out.println("Download attempt " + attempt + " failed: " + failure.getMessage());
                }
                throw new IOException("Could not download " + target + " after 3 attempts; check your connection and rerun the dependency preparation command", failure);
            } finally {
                // A body can stall after headers arrive. Cancel transfers before closing the client.
                client.shutdownNow();
            }
        }
    }
    private static final class DownloadBody implements HttpResponse.BodySubscriber<Path> {
        private final HttpResponse.BodySubscriber<Path> delegate;
        private final String name;
        private final long length;
        private long received, lastReport = System.nanoTime();
        DownloadBody(Path target, String name, long length) {
            delegate = HttpResponse.BodySubscribers.ofFile(target, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            this.name = name; this.length = length;
        }
        public CompletionStage<Path> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription subscription) { delegate.onSubscribe(subscription); }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) received += buffer.remaining();
            long now = System.nanoTime();
            if (now - lastReport >= TimeUnit.SECONDS.toNanos(5)) {
                System.out.printf("Downloading %s: %.1f MiB%s%n", name, received / 1048576.0,
                    length > 0 ? " (" + received * 100 / length + "%)" : "");
                lastReport = now;
            }
            delegate.onNext(buffers);
        }
        public void onError(Throwable error) { delegate.onError(error); }
        public void onComplete() { delegate.onComplete(); }
    }
    private static void verify(Path file, String digest) throws Exception {
        var hash = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            for (int count; (count = input.read(buffer)) != -1;) hash.update(buffer, 0, count);
        }
        if (!HexFormat.of().formatHex(hash.digest()).equals(digest)) throw new IOException("Checksum mismatch: " + file);
    }
}
