package org.tortodrone.server.network;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.*;
import org.tortodrone.protocol.HandshakeCodec;
import static org.tortodrone.support.TestWire.check;

public final class SessionOutputTest {
    public static void main(String[] args) throws Exception {
        RecordingSocket socket = new RecordingSocket(false);
        ExecutorService senders = Executors.newFixedThreadPool(2);
        try (SessionOutput output = new SessionOutput(socket)) {
            int[] next = {0};
            Callable<Void> send = () -> { for (int i = 0; i < 20; i++) output.send(() -> new byte[] {(byte) next[0]++}); return null; };
            var results = senders.invokeAll(java.util.List.of(send, send));
            for (var result : results) result.get();
        } finally { senders.shutdownNow(); }
        byte[] bytes = socket.bytes.toByteArray(); check(bytes.length == 40, "clean close drains responses");
        for (int i = 0; i < bytes.length; i++) check(bytes[i] == i, "encoding and frame order serialized together");

        RecordingSocket renamed=new RecordingSocket(false);
        try(var output=new SessionOutput(renamed)){
            output.send(()->new byte[]{1});output.send(()->new byte[]{2});
            output.disconnect();
            check(renamed.closed&&java.util.Arrays.equals(renamed.bytes.toByteArray(),new byte[]{1,2}),"post-rename disconnect delivers queued updates and confirmation before EOF");
            rejected(()->output.send(()->new byte[]{3}));
        }

        RecordingSocket stalled = new RecordingSocket(true);
        try (SessionOutput output = new SessionOutput(stalled)) {
            output.send(() -> new byte[] {1});
            check(stalled.started.await(2, TimeUnit.SECONDS), "writer reached stalled socket");
            for (int i = 0; i < SessionOutput.MAX_PENDING; i++) output.send(() -> new byte[] {2});
            rejected(() -> output.send(() -> new byte[] {3}));
            check(stalled.closed, "backpressure closes stalled peer");
            rejected(() -> output.send(() -> new byte[] {4}));
        }
        RecordingSocket failed = new RecordingSocket(false);
        try (SessionOutput output = new SessionOutput(failed)) {
            rejected(() -> output.send(() -> { throw new IOException("fixture encoding failure"); }));
            rejected(() -> output.send(() -> new byte[] {1}));
            check(failed.closed, "failed encoding cannot reuse unsent class mappings");
        }
        RecordingSocket large = new RecordingSocket(false);
        try (SessionOutput output = new SessionOutput(large)) {
            rejected(() -> output.send(() -> new byte[org.tortodrone.protocol.SessionCodec.MAX_DOWNSTREAM_FRAME + 1]));
            check(large.closed, "oversized frames fail closed");
        }
        System.out.println("PASS: downstream mapping/frame ordering, clean drain, bounded slow-peer queues and encoding failure cleanup");
    }
    private static void rejected(Operation operation) throws Exception {
        try { operation.run(); throw new AssertionError("Failed downstream write accepted"); }
        catch (IOException expected) { }
    }
    @FunctionalInterface private interface Operation { void run() throws Exception; }
    private static final class RecordingSocket extends Socket {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final CountDownLatch started = new CountDownLatch(1), released = new CountDownLatch(1);
        final boolean block;
        volatile boolean closed;
        RecordingSocket(boolean block) { this.block = block; }
        @Override public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override public void write(int value) throws IOException { write(new byte[] {(byte) value}); }
                @Override public void write(byte[] values) throws IOException {
                    started.countDown();
                    if (block) try { if (!released.await(5, TimeUnit.SECONDS)) throw new IOException("fixture stalled"); }
                        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                    if (closed) throw new IOException("fixture closed");
                    bytes.write(values);
                }
            };
        }
        @Override public void close() { closed = true; released.countDown(); }
    }
}
