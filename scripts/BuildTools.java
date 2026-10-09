import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Properties;

/** Java 25 build inputs, offline map preparation. */
public final class BuildTools {
    private static final String[] MAPS = {"haven", "bazaar"};
    public static void main(String[] args) throws Exception {
        Path project = Path.of("").toAbsolutePath();
        if (args.length == 0) throw new IllegalArgumentException("Usage: java scripts/BuildTools.java sqlite|jline output, or maps [output]");
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
            case "maps" -> {
                if (args.length > 2) throw new IllegalArgumentException("Expected at most one map output directory");
                Path output = args.length == 2 ? Path.of(args[1]) : project.resolve("build/maps");
                verifyMaps(project);
                copyMissing(project.resolve("maps/default/catalog.properties"), output.resolve("catalog.properties"));
                for (String map : MAPS) {
                    for (String name : new String[]{"map.properties", "walkability.gz", "scene.dat"})
                        copyMissing(project.resolve("maps/default/" + map + "/" + name), output.resolve(map + "/" + name));
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
        for (String map : MAPS) {
            Path pack = project.resolve("maps/default/" + map);
            var descriptor = properties(pack.resolve("map.properties"));
            verify(pack.resolve("scene.dat"), descriptor.getProperty("scene-sha256"));
            verify(pack.resolve("walkability.gz"), descriptor.getProperty("collision-sha256"));
        }
    }
    private static void copyMissing(Path source, Path target) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        if (!Files.exists(target)) Files.copy(source, target);
    }
    static void fetch(Path target, String url, String digest) throws Exception {
        if (Files.exists(target)) {
            verify(target, digest);
            Files.setLastModifiedTime(target, FileTime.fromMillis(System.currentTimeMillis()));
            return;
        }
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(target.toAbsolutePath().getParent(), target.getFileName() + ".download-", ".tmp");
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build()) {
            var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120)).GET().build();
            IOException failure = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    var response = client.send(request, HttpResponse.BodyHandlers.ofFile(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING));
                    if (response.statusCode() != 200) throw new IOException("Download failed: HTTP " + response.statusCode());
                    verify(temporary, digest);
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                    return;
                } catch (IOException e) { failure = e; }
            }
            throw failure;
        } finally { Files.deleteIfExists(temporary); }
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
