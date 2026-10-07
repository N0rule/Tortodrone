package org.tortodrone.server;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Message types and lifecycle only; authentication payloads are never logged. */
final class ServerLog implements AutoCloseable {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final PrintWriter file;
    private boolean debugEnabled;
    private boolean closed;

    ServerLog(Path directory) throws IOException {
        Files.createDirectories(directory);
        file = new PrintWriter(Files.newBufferedWriter(directory.resolve("latest.log"),
            StandardOpenOption.CREATE, StandardOpenOption.APPEND), true);
    }

    synchronized void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
    }

    void info(String message) { write("INFO", message); }
    void warn(String message) { write("WARN", message); }
    void error(String message) { write("ERROR", message); }

    synchronized void debug(String message) {
        if (debugEnabled) write("DEBUG", message);
    }

    private synchronized void write(String level, String message) {
        if (closed) return;
        String line = format(level, message);
        System.out.println(line);
        file.println(line);
        if (file.checkError()) startupError("Could not write the server log");
    }

    static void startupError(String message) {
        System.err.println(format("ERROR", message));
    }

    private static String format(String level, String message) {
        // Keep each message on one line, including errors containing user-supplied paths.
        message = message.replace('\r', ' ').replace('\n', ' ');
        return "[" + LocalTime.now().format(TIME) + "] [" + Thread.currentThread().getName()
            + "/" + level + "]: " + message;
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        file.close();
    }
}
