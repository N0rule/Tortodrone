import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Properties;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.*;

/** Java 25 build inputs and offline preparation of versioned content. */
public final class BuildTools {
    public static void main(String[] args) throws Exception {
        Path project = Path.of("").toAbsolutePath();
        if (args.length == 0) throw new IllegalArgumentException("Usage: java scripts/BuildTools.java sqlite|jline output, or content|defaults|maps [output]");
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
            case "maps", "defaults", "content" -> {
                if (args.length > 2) throw new IllegalArgumentException("Expected at most one map output directory");
                String command=args[0];
                Path output = args.length == 2 ? Path.of(args[1]) : project.resolve(command.equals("maps")?"build/content/maps":"build/content");
                prepare(project, output.toAbsolutePath().normalize(), command);
            }
            default -> throw new IllegalArgumentException("Unknown build input command");
        }
    }
    private static Properties properties(Path file) throws IOException {
        var values = new Properties();
        try (var input = Files.newInputStream(file)) { values.load(input); }
        return values;
    }
    private record Copy(Path source, Path target, boolean replace) { }
    private record Removal(Path target,String digest) { }
    /** Map provenance manifest checked in beside the packs it pins. */
    private static final String MAP_MANIFEST = "scene-source.properties";
    private static void prepare(Path project, Path output, String command) throws Exception {
        boolean mapsOnly=command.equals("maps");
        Path contentSource=project.resolve("content"),mapSource=contentSource.resolve("maps");
        if(!Files.isDirectory(mapSource,LinkOption.NOFOLLOW_LINKS))throw new IOException("Required unified map source is absent: "+mapSource);
        Path deploymentRoot=inferDeploymentRoot(output);
        if(Files.exists(deploymentRoot.resolve("content/packs"))||Files.exists(deploymentRoot.resolve("defaults")))
            throw new IOException("Legacy deployment requires an explicit ContentMigration candidate; no files copied");
        // Preserve legacy fallback offers until their explicit DB migration has been exported.
        Path oldShops=deploymentRoot.resolve("content/town/shops.yml");
        if(Files.isRegularFile(deploymentRoot.resolve("data/tortodrone.db"),LinkOption.NOFOLLOW_LINKS)
            &&Files.isRegularFile(oldShops,LinkOption.NOFOLLOW_LINKS)&&Files.size(oldShops)<32_000_000
            &&java.util.regex.Pattern.compile("(?m)(?:^|[\\r\\n{,])\\s*(?:depot|\"depot\"|'depot')\\s*:").matcher(Files.readString(oldShops)).find())
            throw new IOException("Legacy Depot YAML and existing database require EconomyUpgrade before content replacement; original files retained");
        var copies=new ArrayList<Copy>();var conflicts=new ArrayList<String>();
        var ownership=sourceOwnership(contentSource,"_internal/default-source.properties");
        if(mapsOnly)plan(mapSource,output,copies,null,null,conflicts);
        else {
            plan(contentSource,output,copies,null,ownership,conflicts);
            if(output.endsWith(Path.of("content"))) {
                plan(project.resolve("config"),deploymentRoot.resolve("config"),copies,null,null);
                plan(project.resolve("plugins"),deploymentRoot.resolve("plugins"),copies,null,null);
            }
        }
        var removals=new ArrayList<Removal>();
        if(!mapsOnly&&ownership!=null)for(String key:new java.util.TreeSet<>(ownership.stringPropertyNames()))if(key.startsWith("owned.")) {
            Path relative=Path.of(key.substring(6)),old=output.resolve(relative);
            if(Files.notExists(contentSource.resolve(relative))&&Files.isRegularFile(old,LinkOption.NOFOLLOW_LINKS)) {
                if(sourceOwns(ownership,relative,old))removals.add(new Removal(old,fileDigest(old)));
                else conflicts.add("Customized path removed or renamed upstream: "+old);
            }
        }
        if(!conflicts.isEmpty()) {
            throw new IOException("Customized content conflicts with bundled defaults:\n"+String.join("\n",conflicts)
                +"\nNo runtime content was changed. Build a fresh -PdeploymentOutput candidate, transfer your customizations, validate and install while stopped.");
        }
        // Refuse all planned content changes while the target deployment is locked.
        Path lockFile=deploymentRoot.resolve("data/server.lock");
        boolean changing=!copies.isEmpty()||!removals.isEmpty();
        try(var channel=changing&&Files.exists(lockFile,LinkOption.NOFOLLOW_LINKS)
                ?FileChannel.open(lockFile,StandardOpenOption.WRITE):null;
            FileLock lock=channel==null?null:channel.tryLock()) {
            if(channel!=null&&lock==null)throw new IOException("Content needs updating but the server is running; stop it yourself before rebuilding");
            for(var removal:removals)if(!Files.isRegularFile(removal.target,LinkOption.NOFOLLOW_LINKS)||!fileDigest(removal.target).equals(removal.digest))throw new IOException("Obsolete generated file changed during preparation: "+removal.target);
            for(var copy:copies) {
                Files.createDirectories(copy.target.getParent());
                if(copy.replace) {
                    Path temporary=Files.createTempFile(copy.target.getParent(),".map-update-",".tmp");
                    try {
                        Files.copy(copy.source,temporary,StandardCopyOption.REPLACE_EXISTING);
                        Files.move(temporary,copy.target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                    } finally { Files.deleteIfExists(temporary); }
                } else copyMissing(copy.source,copy.target);
            }
            for(var removal:removals)Files.delete(removal.target);
        }
        System.out.println("Prepared "+(mapsOnly?"maps":"content")+": " + (mapsOnly?mapSource:contentSource) + " -> " + output);
    }
    static Path inferDeploymentRoot(Path output) {
        Path normalized=output.toAbsolutePath().normalize();
        if(normalized.endsWith(Path.of("content/maps")))return normalized.getParent().getParent();
        if(normalized.endsWith(Path.of("content")))return normalized.getParent();
        if(normalized.endsWith(Path.of("content/packs/vanilla/maps")))return normalized.getParent().getParent().getParent().getParent();
        if(normalized.endsWith(Path.of("content/packs/vanilla")))return normalized.getParent().getParent().getParent();
        if(normalized.endsWith(Path.of("defaults")))return normalized.getParent();
        if(normalized.endsWith(Path.of("defaults/maps")))return normalized.getParent().getParent();
        if(normalized.endsWith(Path.of("maps")))return normalized.getParent();
        return normalized.getParent();
    }
    private static void plan(Path source,Path output,List<Copy> copies,Path skip,Properties ownership) throws Exception {
        plan(source,output,copies,skip,ownership,new ArrayList<>());
    }
    private static void plan(Path source,Path output,List<Copy> copies,Path skip,Properties ownership,List<String> conflicts) throws Exception {
        if(Files.notExists(source,LinkOption.NOFOLLOW_LINKS)) {
            System.out.println("Optional default source is absent: " + source + "; continuing without it");return;
        }
        if(!Files.isDirectory(source,LinkOption.NOFOLLOW_LINKS))throw new IOException("Default source is not a directory: " + source);
        try(var paths=Files.walk(source)) {
            for(Path file:paths.sorted().toList()) {
                Path relative=source.relativize(file);
                if(skip!=null&&relative.startsWith(skip))continue;
                if(Files.isSymbolicLink(file))throw new IOException("Default source contains a symlink: " + file);
                if(!Files.isRegularFile(file))continue;
                Path target=output.resolve(relative);
                for(Path parent=target.getParent();parent!=null;parent=parent.getParent())
                    if(Files.isSymbolicLink(parent))throw new IOException("Default output contains a symlink directory: " + parent);
                if(Files.notExists(target,LinkOption.NOFOLLOW_LINKS)) {copies.add(new Copy(file,target,false));continue;}
                // An owner's special path (dangling symlink, directory, device) is never written
                // through or replaced; keep it exactly as the offline preparation always did.
                if(!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS))continue;
                if(Files.mismatch(target,file)==-1)continue;
                // Only files this source published before are upgraded; edited packs keep their bytes.
                if(sourceOwns(ownership,relative,target))copies.add(new Copy(file,target,true));
                else if(ownership!=null) {
                    conflicts.add(target.toString());
                }
            }
        }
    }
    private static Properties sourceOwnership(Path maps,String manifestName) throws IOException {
        Path manifest=maps.resolve(manifestName);
        if(Files.notExists(manifest,LinkOption.NOFOLLOW_LINKS))return null;
        if(Files.isSymbolicLink(manifest)||!Files.isRegularFile(manifest)||Files.size(manifest)>262144)
            throw new IOException("Invalid source manifest: "+manifest);
        Properties values=properties(manifest);
        if(!"1".equals(values.getProperty("format")))throw new IOException("Unsupported source manifest format");
        if(values.stringPropertyNames().stream().noneMatch(key->key.startsWith("source.")))
            throw new IOException("Source manifest contains no source file checksums");
        for(String key:values.stringPropertyNames())if(key.startsWith("source.")) {
            String relative=key.substring("source.".length());Path rel=Path.of(relative);
            if(rel.isAbsolute()||rel.normalize().startsWith("..")||!rel.normalize().toString().replace('\\','/').equals(relative))
                throw new IOException("Invalid source path in source manifest: "+relative);
            String expected=values.getProperty(key);
            if(expected==null||!expected.matches("[0-9a-f]{64}"))throw new IOException("Invalid source digest in source manifest: "+relative);
            Path source=maps.resolve(rel).normalize();
            if(!source.startsWith(maps.normalize())||!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS)
                ||!expected.equals(fileDigest(source)))throw new IOException("Map source differs from its manifest: "+relative);
        }
        for(String key:values.stringPropertyNames())if(key.startsWith("owned.")) {
            String relative=key.substring("owned.".length());Path rel=Path.of(relative);
            if(rel.isAbsolute()||rel.normalize().startsWith("..")||!rel.normalize().toString().replace('\\','/').equals(relative))
                throw new IOException("Invalid owned path in source manifest: "+relative);
            for(String digest:values.getProperty(key).split(","))if(!digest.matches("[0-9a-f]{64}"))
                throw new IOException("Invalid owned digest in source manifest: "+relative);
        }
        return values;
    }
    static boolean sourceOwns(Properties ownership,Path relative,Path target)throws IOException {
        if(ownership==null)return false;
        String key="owned."+relative.toString().replace('\\','/'),expected=ownership.getProperty(key);
        if(expected==null)return false;
        String actual=fileDigest(target);
        for(String digest:expected.split(","))if(digest.equals(actual))return true;
        return false;
    }
    private static String fileDigest(Path file)throws IOException {
        try {
            var hash=java.security.MessageDigest.getInstance("SHA-256");
            try(var in=Files.newInputStream(file)) {byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))!=-1;)hash.update(buffer,0,n);}
            return java.util.HexFormat.of().formatHex(hash.digest());
        } catch(java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
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
