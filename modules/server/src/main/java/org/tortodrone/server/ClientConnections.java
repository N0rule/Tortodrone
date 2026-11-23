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
                Thread thread = new Thread(
                        () -> {
                            try {
                                handler.run();
                            } finally {
                                closeSocket(socket);
                                synchronized (ClientConnections.this) {
                                    clients.remove(socket);
                                }
                            }
                        },
                        name);
                clients.put(socket, thread);
                try {
                    thread.start();
                } catch (RuntimeException | Error failure) {
                    clients.remove(socket);
                    throw failure;
                }
            }
        } catch (IOException | RuntimeException | Error failure) {
            closeSocket(socket);
            throw failure;
        }
    }

    @Override
    public void close() {
        Map<Socket, Thread> active;
        synchronized (this) {
            closing = true;
            active = new LinkedHashMap<>(clients);
        }
        // Closing every socket first unblocks reads/writes before joining any handler.
        // No registry or log monitor is held while handlers finish their cleanup.
        active.keySet().forEach(ClientConnections::closeSocket);
        long deadline = System.nanoTime() + 5_000_000_000L;
        active.values().forEach(thread -> await(thread, deadline));
    }

    static void await(Thread thread) {
        await(thread, System.nanoTime() + 5_000_000_000L);
    }

    /** Wait for a handler until the shared deadline, then interrupt it so shutdown cannot hang. */
    static void await(Thread thread, long deadlineNanos) {
        boolean interrupted = false;
        try {
            while (thread.isAlive()) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    thread.interrupt();
                    return;
                }
                try {
                    thread.join(Math.max(1, remaining / 1_000_000L));
                } catch (InterruptedException interruption) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void closeSocket(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
