package org.tortodrone.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.tortodrone.resources.ContentFiles;

/** Serves Uplink News images from content/shared/news over plain HTTP for the unmodified client. */
public final class NewsServer implements AutoCloseable {
    private static final long MAX_IMAGE_BYTES=8L*1024*1024;
    private final HttpServer server;
    private final ExecutorService executor;
    private NewsServer(HttpServer server,ExecutorService executor){this.server=server;this.executor=executor;}
    /** Bind the same interface as the game listener; the caller decides how to handle an unavailable port. */
    public static NewsServer start(InetAddress address,int port,Path root,Consumer<String> debug)throws IOException {
        var server=HttpServer.create(new InetSocketAddress(address,port),16);
        var executor=Executors.newVirtualThreadPerTaskExecutor();
        server.createContext("/news/",exchange->serve(exchange,root,debug));
        server.setExecutor(executor);
        server.start();
        return new NewsServer(server,executor);
    }
    public int port(){return server.getAddress().getPort();}
    /** Only GET/HEAD reach a stored PNG/JPEG; traversal, other extensions and missing files return 404. */
    private static void serve(HttpExchange exchange,Path root,Consumer<String> debug)throws IOException {
        try(exchange) {
            String method=exchange.getRequestMethod();
            if(!method.equals("GET")&&!method.equals("HEAD")){respond(exchange,405,"Method Not Allowed");return;}
            String path=exchange.getRequestURI().getPath();
            String name=path.startsWith("/news/")?path.substring("/news/".length()):"";
            String type=contentType(name);Path file=null;
            if(type!=null)try {
                file=ContentFiles.relative(root,name);
                if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>MAX_IMAGE_BYTES)file=null;
            }catch(IOException refused){file=null;}
            if(file==null){debug.accept("News image unavailable: "+name);respond(exchange,404,"Not Found");return;}
            byte[] body=Files.readAllBytes(file);
            exchange.getResponseHeaders().set("Content-Type",type);
            // The client caches by URL; no-cache keeps an edited banner visible after a restart.
            exchange.getResponseHeaders().set("Cache-Control","no-cache");
            boolean head=method.equals("HEAD");
            exchange.sendResponseHeaders(200,head||body.length==0?-1:body.length);
            if(!head)try(var output=exchange.getResponseBody()){output.write(body);}
        }
    }
    private static void respond(HttpExchange exchange,int status,String message)throws IOException {
        byte[] body=(message+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type","text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status,body.length);
        try(var output=exchange.getResponseBody()){output.write(body);}
    }
    private static String contentType(String name) {
        String lower=name.toLowerCase(Locale.ROOT);
        if(lower.endsWith(".png"))return "image/png";
        if(lower.endsWith(".jpg")||lower.endsWith(".jpeg"))return "image/jpeg";
        return null;
    }
    @Override public void close(){server.stop(0);executor.shutdownNow();}
}
