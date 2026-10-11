package org.tortodrone.server;

import java.io.IOException;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.Map;

/** Owns accepted sockets and handlers until their cleanup completes. */
final class ClientConnections implements AutoCloseable {
    private final Map<Socket, Thread> clients = new LinkedHashMap<>();
    private boolean closing;

    void start(Socket socket, String name, Runnable handler) throws IOException {
        try {
            synchronized (this) {
                if (closing) throw new IOException("Server is stopping");
                Thread thread = new Thread(() -> {
                    try { handler.run(); }
                    finally {
                        closeSocket(socket);
                        synchronized (ClientConnections.this) { clients.remove(socket); }
                    }
                }, name);
                clients.put(socket, thread);
                try { thread.start(); }
                catch (RuntimeException | Error failure) { clients.remove(socket); throw failure; }
            }
        } catch (IOException | RuntimeException | Error failure) {
            closeSocket(socket);
            throw failure;
        }
    }

    @Override public void close() {
        Map<Socket, Thread> active;
        synchronized (this) {
            closing = true;
            active = new LinkedHashMap<>(clients);
        }
        // Closing every socket first unblocks reads/writes before joining any handler.
        // No registry or log monitor is held while handlers finish their cleanup.
        active.keySet().forEach(ClientConnections::closeSocket);
        active.values().forEach(ClientConnections::await);
    }

    static void await(Thread thread) {
        boolean interrupted = false;
        try {
            while (true) {
                try { thread.join(); return; }
                catch (InterruptedException interruption) { interrupted = true; }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void closeSocket(Socket socket) {
        try { socket.close(); } catch (IOException ignored) { }
    }
}
