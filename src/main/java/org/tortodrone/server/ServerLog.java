package org.tortodrone.server;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** Message types and lifecycle only; authentication payloads are never logged. */
final class ServerLog implements AutoCloseable {
    private final PrintWriter file;
    private boolean closed;

    ServerLog(Path directory) throws IOException {
        Files.createDirectories(directory);
        file = new PrintWriter(Files.newBufferedWriter(directory.resolve("latest.log"),
            StandardOpenOption.CREATE, StandardOpenOption.APPEND), true);
    }

    synchronized void write(String message) {
        if (closed) return;
        String line = "[" + Instant.now() + "] " + message;
        System.out.println(line);
        file.println(line);
        if (file.checkError()) System.err.println("Could not write the server log");
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        file.close();
    }
}
