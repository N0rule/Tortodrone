package org.tortodrone.resources;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.function.Consumer;
import java.util.zip.ZipInputStream;
import org.tortodrone.character.ItemCatalog;
import org.tortodrone.world.SceneCatalog;

/** Optional startup recovery of external defaults. No deployment files are replaced. */
public final class DefaultResources {
    // Public repository snapshot: GitHub redirects archive requests to its ZIP host.
    // Before the repository is public, unauthenticated startup recovery will warn and continue.
    public static final URI SOURCE = URI.create("https://github.com/N0rule/Tortodrone/archive/refs/heads/main.zip");
    private static final List<String> REQUIRED = List.of("maps/catalog.properties", "maps/haven/map.properties",
        "maps/haven/scene.dat", "maps/haven/walkability.gz", "maps/bazaar/map.properties",
        "maps/bazaar/scene.dat", "maps/bazaar/walkability.gz", "maps/arcade/map.properties",
        "maps/arcade/scene.dat", "maps/arcade/walkability.gz", "maps/garrison/map.properties",
        "maps/garrison/scene.dat", "maps/garrison/walkability.gz", "items/catalog.json");
    private static final long ARCHIVE_LIMIT=32_000_000, EXPANDED_LIMIT=128_000_000;
    private DefaultResources() { }
    public static List<String> missing(Path defaults) {
        var missing=new ArrayList<String>();
        for (String name:REQUIRED) if (!Files.isRegularFile(defaults.resolve(name),LinkOption.NOFOLLOW_LINKS)) missing.add(name);
        // Additional map packs participate in completeness checks without a Java name list.
        Path maps=defaults.resolve("maps");
        if (Files.isDirectory(maps,LinkOption.NOFOLLOW_LINKS)) try (var folders=Files.list(maps)) {
            for (Path folder:folders.filter(p->Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)).toList())
                for (String file:List.of("map.properties","scene.dat","walkability.gz"))
                    if (!Files.isRegularFile(folder.resolve(file),LinkOption.NOFOLLOW_LINKS)) {
                        String name=defaults.relativize(folder.resolve(file)).toString(); if (!missing.contains(name)) missing.add(name);
                    }
        } catch (IOException unreadable) { missing.add("maps/ (unreadable)"); }
        return List.copyOf(missing);
    }
    public static void prepare(Path deployment, Consumer<String> info, Consumer<String> warning) {
        prepare(deployment,SOURCE,info,warning);
    }
    /** Explicit source argument is for isolated tests; production has no URL setting. */
    public static void prepare(Path deployment, URI source, Consumer<String> info, Consumer<String> warning) {
        Path defaults=deployment.resolve("defaults");
        var absent=missing(defaults); if (absent.isEmpty()) return;
        info.accept("Missing default resources: "+String.join(", ",absent.stream().limit(4).toList())
            +(absent.size()>4?" (and "+(absent.size()-4)+" more)":"")+"; attempting download from "+source);
        Path staging=null;
        try {
            staging=Files.createTempDirectory("tortodrone-defaults-");
            byte[] archive;
            try (var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                    .followRedirects(HttpClient.Redirect.NORMAL).build()) {
                var download=client.sendAsync(HttpRequest.newBuilder(source).timeout(Duration.ofSeconds(8)).GET().build(),
                    ignored->new LimitedBody());
                try {
                    var response=download.get(8,TimeUnit.SECONDS);
                    if (response.statusCode()!=200) throw new IOException("HTTP "+response.statusCode());
                    archive=response.body();
                } catch (TimeoutException timeout) {
                    throw new IOException("Download timed out",timeout);
                } catch (ExecutionException failed) {
                    throw new IOException("Download failed: "+failed.getCause().getMessage(),failed.getCause());
                } finally {
                    download.cancel(true); client.shutdownNow();
                }
            }
            extract(archive,staging);
            Path pack=staging.resolve("defaults");
            if (!missing(pack).isEmpty()) throw new IOException("Archive does not contain complete defaults");
            SceneCatalog.load(pack.resolve("maps")); ItemCatalog.load(pack.resolve("items/catalog.json"));
            int added=install(pack,defaults);
            info.accept("Default resources downloaded; installed "+added+" missing files; existing files preserved");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); warning.accept("Default resource download interrupted; continuing with available local resources");
        } catch (IOException | RuntimeException failed) {
            warning.accept("Default resource download failed ("+failed.getMessage()+"); continuing with available local resources");
        } finally {
            if (staging!=null) try (var files=Files.walk(staging)) {
                for (Path path:files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (IOException ignored) { warning.accept("Could not remove temporary default download files"); }
        }
    }
    static void extract(byte[] archive, Path staging) throws IOException {
        long total=0; int count=0; var seen=new HashSet<Path>();
        try (var zip=new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry()) {
                if (++count>10_000) throw new IOException("Too many archive entries");
                String name=entry.getName();
                if (name.length()>1024 || name.startsWith("/") || name.contains("\\") || name.contains(":")) throw new IOException("Unsafe archive path");
                for (String part:name.split("/")) if (part.equals("..") || part.equals(".")) throw new IOException("Unsafe archive path");
                int slash=name.indexOf('/');
                if (slash<1 || !name.substring(slash+1).startsWith("defaults/")) {
                    byte[] buffer=new byte[65536];
                    for(int n;(n=zip.read(buffer))!=-1;) {total+=n;if(total>EXPANDED_LIMIT)throw new IOException("Default archive exceeds size limit");}
                    continue;
                }
                Path target=staging.resolve(name.substring(slash+1)).normalize();
                if (!target.startsWith(staging.resolve("defaults")) || !seen.add(target)) throw new IOException("Duplicate or escaped default file");
                if (entry.isDirectory()) {
                    Files.createDirectories(target);byte[] buffer=new byte[65536];
                    for(int n;(n=zip.read(buffer))!=-1;) {total+=n;if(total>EXPANDED_LIMIT)throw new IOException("Default archive exceeds size limit");}
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (var output=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer=new byte[65536];
                    for (int n;(n=zip.read(buffer))!=-1;) {
                        total+=n; if (total>EXPANDED_LIMIT) throw new IOException("Default archive exceeds size limit"); output.write(buffer,0,n);
                    }
                }
            }
        }
    }
    static int install(Path source, Path target) throws IOException {
        Path absolute=target.toAbsolutePath().normalize(); safeDirectories(absolute);
        int added=0;
        try (var files=Files.walk(source)) {
            for (Path file:files.filter(Files::isRegularFile).sorted().toList()) {
                Path destination=absolute.resolve(source.relativize(file));
                safeDirectories(destination.getParent());
                if (Files.exists(destination,LinkOption.NOFOLLOW_LINKS)) continue;
                // Stage in the destination directory; createLink publishes complete bytes atomically
                // and fails if another process creates the destination first. Never replace files.
                Path temp=Files.createTempFile(destination.getParent(),".defaults-", ".tmp");
                try {
                    Files.copy(file,temp,StandardCopyOption.REPLACE_EXISTING);
                    try { Files.createLink(destination,temp); added++; }
                    catch (FileAlreadyExistsException preserved) { }
                } finally { Files.deleteIfExists(temp); }
            }
        }
        return added;
    }
    private static void safeDirectories(Path directory) throws IOException {
        Path current=directory.getRoot();
        for (Path part:directory) {
            current=current.resolve(part);
            try { Files.createDirectory(current); } catch (FileAlreadyExistsException exists) { }
            if (!Files.isDirectory(current,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Default path is not a real directory");
        }
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription; private long size;
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription value) { subscription=value; delegate.onSubscribe(value); }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer:buffers) size+=buffer.remaining();
            if (size>ARCHIVE_LIMIT) { subscription.cancel(); delegate.onError(new IOException("Default archive exceeds download limit")); }
            else delegate.onNext(buffers);
        }
        public void onError(Throwable error) { delegate.onError(error); }
        public void onComplete() { delegate.onComplete(); }
    }
}
