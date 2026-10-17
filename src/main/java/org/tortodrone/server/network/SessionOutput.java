package org.tortodrone.server.network;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;

/** Serialize codec mappings and frames together; a slow reader cannot block other accounts. */
final class SessionOutput implements AutoCloseable {
    static final int MAX_PENDING = 64;
    private static final byte[] END = new byte[0];
    private final Socket socket;
    private final ArrayBlockingQueue<byte[]> pending = new ArrayBlockingQueue<>(MAX_PENDING);
    private final Thread writer;
    private volatile boolean failed;
    private boolean closed;

    SessionOutput(Socket socket) {
        this.socket = socket;
        writer = new Thread(this::write, Thread.currentThread().getName() + "-Output");
        writer.setDaemon(true);
        writer.start();
    }

    @FunctionalInterface interface Encoder { byte[] frame() throws IOException; }

    synchronized void send(Encoder encoder) throws IOException {
        if (closed || failed || socket.isClosed()) throw new IOException("Downstream connection closed");
        try {
            byte[] frame = encoder.frame();
            if (frame.length > org.tortodrone.protocol.SessionCodec.MAX_DOWNSTREAM_FRAME) throw new IOException("Downstream frame exceeds size limit");
            if (!pending.offer(frame)) throw new IOException("Downstream queue full");
        } catch (IOException | RuntimeException error) {
            // An unsent frame may have introduced codec mappings. Never reuse that stream.
            failed = true; closeSocket();
            if (error instanceof IOException io) throw io;
            throw new IOException("Downstream encoding failed", error);
        }
    }

    private void write() {
        try {
            while (true) {
                byte[] frame = pending.take();
                if (frame == END) return;
                socket.getOutputStream().write(frame);
                socket.getOutputStream().flush();
            }
        } catch (IOException | InterruptedException e) {
            failed = true;
            closeSocket();
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            if (!pending.offer(END)) { closeSocket(); writer.interrupt(); }
        }
        try {
            writer.join(1000);
            if (writer.isAlive()) { closeSocket(); writer.interrupt(); writer.join(1000); }
        } catch (InterruptedException e) {
            closeSocket(); writer.interrupt(); Thread.currentThread().interrupt();
        }
    }

    /** Drains queued frames, then closes the socket: the official post-rename session end. */
    void disconnect() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            if (!pending.offer(END)) { closeSocket(); writer.interrupt(); }
        }
        try {
            writer.join(5000);
            closeSocket();
            writer.join(1000);
        } catch (InterruptedException e) {
            closeSocket(); writer.interrupt(); Thread.currentThread().interrupt();
        }
    }

    private void closeSocket() { try { socket.close(); } catch (IOException ignored) { } }
}
