package org.tortodrone.server;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Bounded UTF-8 operational logs and structured subscriptions; no authentication payloads. */
public final class ServerLog implements AutoCloseable {
    public record Event(Instant timestamp, String level, String thread, String message, String line) { }
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final Path directory;
    private final CopyOnWriteArrayList<Consumer<Event>> observers=new CopyOnWriteArrayList<>();
    private PrintWriter file;
    private long bytes;
    private long maxBytes=10L*1024*1024;
    private int retention=5;
    private boolean debugEnabled,closed;
    private Consumer<String> consoleOutput;
    private volatile Event recentFailure;

    synchronized void setConsoleOutput(Consumer<String> output) { consoleOutput=output; }
    public AutoCloseable subscribe(Consumer<Event> observer) { observers.add(observer);return ()->observers.remove(observer); }
    public Event recentFailure() { return recentFailure; }
    public Path directory() { return directory; }
    ServerLog(Path directory) throws IOException {
        this.directory=directory;
        Files.createDirectories(directory);
        if(Files.getFileStore(directory).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"));
        open();
    }
    private void open() throws IOException {
        Path path=directory.resolve("latest.log");
        file=new PrintWriter(Files.newBufferedWriter(path,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND),true);
        if(Files.getFileStore(path).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));
        bytes=Files.size(path);
    }
    synchronized void configure(long maxBytes,int retention) throws IOException {
        if(maxBytes<1024||retention<1||retention>100)throw new IllegalArgumentException("Invalid log rotation settings");
        this.maxBytes=maxBytes;this.retention=retention;
        try(var paths=Files.newDirectoryStream(directory,"server-*.log")) {
            for(Path path:paths) {
                var match=java.util.regex.Pattern.compile("server-([0-9]+)\\.log").matcher(path.getFileName().toString());
                if(match.matches())try{if(Long.parseLong(match.group(1))>retention)Files.delete(path);}catch(NumberFormatException ignored){ }
            }
        }
    }
    synchronized void setDebugEnabled(boolean enabled) { debugEnabled=enabled; }
    void info(String message) { write("INFO",message); }
    void warn(String message) { write("WARN",message); }
    void error(String message) { write("ERROR",message); }
    synchronized void debug(String message) { if(debugEnabled)write("DEBUG",message); }
    private synchronized void write(String level,String message) {
        if(closed)return;
        message=safe(message);
        var timestamp=Instant.now();String thread=Thread.currentThread().getName();
        String line=format(level,thread,message);
        var event=new Event(timestamp,level,thread,message,line);
        if(level.equals("WARN")||level.equals("ERROR"))recentFailure=event;
        if(consoleOutput==null)System.out.println(line);else consoleOutput.accept(line);
        int length=line.getBytes(StandardCharsets.UTF_8).length+System.lineSeparator().getBytes(StandardCharsets.UTF_8).length;
        try {
            if(bytes>0&&bytes+length>maxBytes)rotate();
            file.println(line);bytes+=length;
            if(file.checkError())startupError("Could not write the server log");
        } catch(IOException failure) { startupError("Could not rotate the server log: "+failure.getClass().getSimpleName()); }
        // Observers must enqueue quickly. UI implementations consume bounded queues on the EDT.
        for(var observer:observers)try{observer.accept(event);}catch(RuntimeException ignored){/* Observation cannot break gameplay. */}
    }
    private void rotate() throws IOException {
        file.close();
        try {
            Files.deleteIfExists(directory.resolve("server-"+retention+".log"));
            for(int n=retention-1;n>=1;n--){Path from=directory.resolve("server-"+n+".log");if(Files.exists(from))Files.move(from,directory.resolve("server-"+(n+1)+".log"),StandardCopyOption.REPLACE_EXISTING);}
            Files.move(directory.resolve("latest.log"),directory.resolve("server-1.log"),StandardCopyOption.REPLACE_EXISTING);
        } finally { open(); }
    }
    static void startupError(String message) { System.err.println(format("ERROR",Thread.currentThread().getName(),safe(message))); }
    private static String safe(String message) {
        message=String.valueOf(message).replace('\r',' ').replace('\n',' ').replace('\u001b',' ');
        return message.length()>8192?message.substring(0,8192)+" [truncated]":message;
    }
    private static String format(String level,String thread,String message) {
        return "["+LocalTime.now().format(TIME)+"] ["+thread+"/"+level+"]: "+message;
    }
    @Override public synchronized void close() { if(closed)return;closed=true;file.close();observers.clear(); }
}
