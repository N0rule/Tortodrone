package org.tortodrone.resources;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.zip.*;
import org.tortodrone.character.ItemCatalog;
import org.tortodrone.world.SceneCatalog;
import static org.tortodrone.support.TestWire.check;

public final class DefaultResourcesTest {
    public static void main(String[] args) throws Exception {
        // macOS temp paths can include system directory aliases; keep the disposable root canonical.
        Path home=Files.createTempDirectory("tortodrone-default-download-").toRealPath();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var requests=new AtomicInteger(); var warnings=new ArrayList<String>(); var infos=new ArrayList<String>();
        byte[] good=archive(false),bad=archive(true);
        var releaseSlow=new CountDownLatch(1);
        server.createContext("/good",exchange->{requests.incrementAndGet();exchange.sendResponseHeaders(200,good.length);try(var out=exchange.getResponseBody()){out.write(good);}});
        server.createContext("/bad",exchange->{exchange.sendResponseHeaders(200,bad.length);try(var out=exchange.getResponseBody()){out.write(bad);}});
        server.createContext("/slow",exchange->{exchange.sendResponseHeaders(200,0);try {
            exchange.getResponseBody().write(1);exchange.getResponseBody().flush();releaseSlow.await();
        } catch(InterruptedException interrupted){Thread.currentThread().interrupt();} finally {exchange.close();}});
        server.createContext("/missing",exchange->{exchange.sendResponseHeaders(503,-1);exchange.close();});server.start();
        URI uri=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
        try {
            Path fresh=home.resolve("fresh");
            DefaultResources.prepare(fresh,uri.resolve("/good"),infos::add,warnings::add);
            check(warnings.isEmpty() && requests.get()==1,"complete defaults download successfully");
            check(SceneCatalog.load(fresh.resolve("defaults/maps")).scenes().size()==4,"downloaded original maps pass checksums and spawn validation");
            check(ItemCatalog.load(fresh.resolve("defaults/items/catalog.json")).entries().size()==9348,"all JSON definitions downloaded");
            check(Files.readString(fresh.resolve("defaults/future/example.txt")).equals("future resource"),"additional default directories are retained");
            check(!Files.exists(fresh.resolve("outside.txt")),"archive files outside root defaults ignored");
            DefaultResources.prepare(fresh,uri.resolve("/missing"),infos::add,warnings::add);
            check(requests.get()==1 && warnings.isEmpty(),"complete local defaults require no request");
            Path partial=home.resolve("partial"),custom=partial.resolve("defaults/maps/haven/map.properties");
            Files.createDirectories(custom.getParent());Files.writeString(custom,"owner-edited descriptor");
            DefaultResources.prepare(partial,uri.resolve("/good"),infos::add,warnings::add);
            check(Files.readString(custom).equals("owner-edited descriptor") && Files.exists(partial.resolve("defaults/items/catalog.json")),"download fills missing files without changing existing bytes");
            Path unavailable=home.resolve("unavailable");
            DefaultResources.prepare(unavailable,uri.resolve("/missing"),infos::add,warnings::add);
            check(warnings.getLast().contains("HTTP 503") && !Files.exists(unavailable.resolve("defaults")),"unavailable repository warns and returns normally");
            Path unsafe=home.resolve("unsafe");
            DefaultResources.prepare(unsafe,uri.resolve("/bad"),infos::add,warnings::add);
            check(warnings.getLast().contains("Unsafe archive path") && !Files.exists(unsafe.resolve("defaults")),"unsafe archive rejected before deployment files are published");
            Path symlink=home.resolve("symlink"),outside=home.resolve("owner");Files.createDirectories(outside);
            Files.createDirectories(symlink.resolve("defaults"));
            boolean symlinks=false;
            try { Files.createSymbolicLink(symlink.resolve("defaults/maps"),outside); symlinks=true; }
            catch (UnsupportedOperationException | IOException unavailableLink) {
                System.out.println("SKIP: destination symlink test (symlinks unavailable)");
            }
            if (symlinks) {
                DefaultResources.prepare(symlink,uri.resolve("/good"),infos::add,warnings::add);
                try(var files=Files.list(outside)){check(files.findAny().isEmpty(),"download cannot write through a destination symlink");}
            }
            long start=System.nanoTime();
            DefaultResources.prepare(home.resolve("slow"),uri.resolve("/slow"),infos::add,warnings::add);
            check(warnings.getLast().contains("timed out") && (System.nanoTime()-start)/1_000_000_000L<12,"stalled response body cannot block startup indefinitely");
            releaseSlow.countDown();
            server.stop(0);
            DefaultResources.prepare(home.resolve("offline"),uri.resolve("/good"),infos::add,warnings::add);
            check(warnings.getLast().contains("continuing with available local resources"),"offline connection does not interrupt startup");
            System.out.println("PASS: complete and offline default downloads, warm startup, future directories, owner-file preservation, path validation and symlink refusal");
        } finally {
            releaseSlow.countDown();
            server.stop(0);
            try(var paths=Files.walk(home)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
        }
    }
    private static byte[] archive(boolean unsafe) throws IOException {
        var bytes=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(bytes)) {
            if(unsafe){zip.putNextEntry(new ZipEntry("repo/defaults/../../escaped.txt"));zip.write(1);zip.closeEntry();}
            else {
                try(var paths=Files.walk(Path.of("defaults"))){for(Path path:paths.filter(Files::isRegularFile).toList()) {
                    zip.putNextEntry(new ZipEntry("repo/"+path.toString().replace('\\','/')));Files.copy(path,zip);zip.closeEntry();
                }}
                zip.putNextEntry(new ZipEntry("repo/defaults/future/example.txt"));zip.write("future resource".getBytes());zip.closeEntry();
                zip.putNextEntry(new ZipEntry("repo/outside.txt"));zip.write(1);zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
