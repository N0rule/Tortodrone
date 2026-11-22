package org.tortodrone.server.network;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** Serialize codec mappings and frames together; a slow reader cannot block other accounts.
 *
 * <p>The queue is bounded by both frames and bytes. Reliable state changes are never dropped:
 * if the reliable queue limit is reached the connection fails so the client reconnects. Optional
 * update frames (scene snapshots and effect chatter) are skipped before encoding once the byte
 * budget is exhausted, so skipping never introduces codec mappings the client has not seen. A
 * client that keeps the queue over budget for a sustained window is disconnected.
 */
final class SessionOutput implements AutoCloseable {
    static final int MAX_PENDING = 64;
    static final int MAX_PENDING_BYTES = 4 * 1024 * 1024;
    static final long MAX_OVER_BUDGET_NANOS = 5_000_000_000L;
    private static final byte[] END = new byte[0];

    /** Process-wide observation of the bounded output path; no policy reads these counters. */
    static final class Metrics {
        private static final java.util.concurrent.atomic.LongAdder frames = new java.util.concurrent.atomic.LongAdder();
        private static final java.util.concurrent.atomic.LongAdder bytes = new java.util.concurrent.atomic.LongAdder();
        private static final java.util.concurrent.atomic.AtomicLong largestFrame = new java.util.concurrent.atomic.AtomicLong();
        private static final java.util.concurrent.atomic.LongAdder droppedOptional = new java.util.concurrent.atomic.LongAdder();
        private static final java.util.concurrent.atomic.LongAdder reliableOverflows = new java.util.concurrent.atomic.LongAdder();
        private static final java.util.concurrent.atomic.LongAdder budgetDisconnects = new java.util.concurrent.atomic.LongAdder();
        private static final java.util.concurrent.atomic.AtomicLong peakPendingFrames = new java.util.concurrent.atomic.AtomicLong();
        private static final java.util.concurrent.atomic.AtomicLong peakPendingBytes = new java.util.concurrent.atomic.AtomicLong();
        private Metrics() { }
        public record Snapshot(long frames,long bytes,long largestFrame,long droppedOptional,
                long reliableOverflows,long budgetDisconnects,long peakPendingFrames,long peakPendingBytes) { }
        static void queued(int length,int pendingFrames,long pendingBytes) {
            frames.increment();bytes.add(length);
            largestFrame.accumulateAndGet(length,Math::max);
            peakPendingFrames.accumulateAndGet(pendingFrames,Math::max);
            peakPendingBytes.accumulateAndGet(pendingBytes,Math::max);
        }
        static void optionalDropped() { droppedOptional.increment(); }
        static void reliableOverflow() { reliableOverflows.increment(); }
        static void budgetDisconnect() { budgetDisconnects.increment(); }
        public static Snapshot snapshot() {
            return new Snapshot(frames.sum(),bytes.sum(),largestFrame.get(),droppedOptional.sum(),
                reliableOverflows.sum(),budgetDisconnects.sum(),peakPendingFrames.get(),peakPendingBytes.get());
        }
    }
    private final Socket socket;
    private final ReentrantLock lock = new ReentrantLock();
    /** Serializes frame encoding with queue insertion so connection-global codec mappings never race. */
    private final ReentrantLock encoding = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    private final ArrayDeque<byte[]> pending = new ArrayDeque<>();
    private final Thread writer;
    private volatile Throwable failure;
    private boolean closed;
    private long pendingBytes;
    private long overBudgetSince;
    private long droppedOptional;

    private final int maxPending;
    private final int maxPendingBytes;
    private final long maxOverBudgetNanos;

    SessionOutput(Socket socket) {
        this(socket,MAX_PENDING,MAX_PENDING_BYTES,MAX_OVER_BUDGET_NANOS);
    }

    SessionOutput(Socket socket,int maxPending,int maxPendingBytes,long maxOverBudgetNanos) {
        this.socket = socket;
        this.maxPending = maxPending;
        this.maxPendingBytes = maxPendingBytes;
        this.maxOverBudgetNanos = maxOverBudgetNanos;
        writer = new Thread(this::write, Thread.currentThread().getName() + "-Output");
        writer.setDaemon(true);
        writer.start();
    }

    @FunctionalInterface interface Encoder { byte[] frame() throws IOException; }

    /** Reliable state change: never dropped. */
    void send(Encoder encoder) throws IOException {
        org.tortodrone.core.LockOrder.enter(org.tortodrone.core.LockOrder.OUTPUT);
        try { encodeAndQueue(encoder,false); }
        finally { org.tortodrone.core.LockOrder.exit(); }
    }

    /** Optional update: skipped before encoding when the queue is over budget. */
    boolean sendOptional(Encoder encoder) throws IOException {
        org.tortodrone.core.LockOrder.enter(org.tortodrone.core.LockOrder.OUTPUT);
        try {
            lock.lock();
            try {
                if (closed || failure != null || socket.isClosed()) throw new IOException("Downstream connection closed", failure);
                if (overBudget()) { droppedOptional++; Metrics.optionalDropped(); noteOverBudget(); return false; }
                clearOverBudget();
            } finally { lock.unlock(); }
            return encodeAndQueue(encoder,true);
        } finally { org.tortodrone.core.LockOrder.exit(); }
    }

    /** True when optional work should be skipped without encoding anything this tick. */
    boolean optionalOverBudget() {
        lock.lock();
        try {
            if (closed || failure != null) return true;
            boolean over = overBudget();
            if (over) { droppedOptional++; Metrics.optionalDropped(); noteOverBudget(); } else clearOverBudget();
            return over;
        } finally { lock.unlock(); }
    }

    private boolean overBudget() { return pendingBytes >= maxPendingBytes || pending.size() >= maxPending; }

    private void noteOverBudget() {
        if (overBudgetSince == 0) overBudgetSince = System.nanoTime();
        else if (System.nanoTime() - overBudgetSince >= maxOverBudgetNanos) fail(new IOException("Downstream byte budget exceeded"));
    }

    private void clearOverBudget() { overBudgetSince = 0; }

    private boolean encodeAndQueue(Encoder encoder,boolean optional) throws IOException {
        encoding.lock();
        try {
            lock.lock();
            try {
                if (overBudgetSince != 0 && System.nanoTime() - overBudgetSince >= maxOverBudgetNanos)
                    fail(new IOException("Downstream byte budget exceeded"));
                if (closed || failure != null || socket.isClosed())
                    throw new IOException("Downstream connection closed", failure);
            } finally { lock.unlock(); }
            byte[] frame;
            try {
                frame = encoder.frame();
                if (frame.length > org.tortodrone.protocol.SessionCodec.MAX_DOWNSTREAM_FRAME) throw new IOException("Downstream frame exceeds size limit");
            } catch (IOException | RuntimeException error) {
                // An unsent frame may have introduced codec mappings. Never reuse that stream.
                fail(error);
                if (error instanceof IOException io) throw io;
                throw new IOException("Downstream encoding failed", error);
            }
            lock.lock();
            try {
                if (closed || failure != null || socket.isClosed()) throw new IOException("Downstream connection closed", failure);
                if (optional && overBudget()) { droppedOptional++; Metrics.optionalDropped(); noteOverBudget(); return false; }
                if (!optional && (pending.size() >= maxPending || pendingBytes + frame.length > maxPendingBytes)) {
                    fail(new IOException("Downstream queue full"));
                    throw new IOException("Downstream queue full", failure);
                }
                if (pendingBytes + frame.length > maxPendingBytes) {
                    if (optional) { droppedOptional++; Metrics.optionalDropped(); noteOverBudget(); return false; }
                    fail(new IOException("Downstream byte budget exceeded"));
                    throw new IOException("Downstream byte budget exceeded", failure);
                }
                pending.addLast(frame); pendingBytes += frame.length; notEmpty.signal();
                Metrics.queued(frame.length,pending.size(),pendingBytes);
                return true;
            } finally { lock.unlock(); }
        } finally { encoding.unlock(); }
    }

    long droppedOptional() { lock.lock(); try { return droppedOptional; } finally { lock.unlock(); } }

    private void write() {
        try {
            while (true) {
                byte[] frame;
                lock.lock();
                try {
                    while (pending.isEmpty()) notEmpty.await();
                    frame = pending.removeFirst();
                    pendingBytes -= frame.length;
                    if (pendingBytes < maxPendingBytes) clearOverBudget();
                    notFull.signalAll();
                } finally { lock.unlock(); }
                if (frame == END) return;
                socket.getOutputStream().write(frame);
                socket.getOutputStream().flush();
            }
        } catch (IOException | InterruptedException e) {
            fail(e);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    @Override public void close() {
        lock.lock();
        try {
            if (closed) return;
            closed = true;
            pending.addLast(END);
            notEmpty.signal();
        } finally { lock.unlock(); }
        try {
            writer.join(1000);
            if (writer.isAlive()) { closeSocket(); writer.interrupt(); writer.join(1000); }
        } catch (InterruptedException e) {
            closeSocket(); writer.interrupt(); Thread.currentThread().interrupt();
        }
    }

    /** Drains queued frames, then closes the socket: the official post-rename session end. */
    void disconnect() {
        lock.lock();
        try {
            if (closed) return;
            closed = true;
            pending.addLast(END);
            notEmpty.signal();
        } finally { lock.unlock(); }
        try {
            writer.join(5000);
            closeSocket();
            writer.join(1000);
        } catch (InterruptedException e) {
            closeSocket(); writer.interrupt(); Thread.currentThread().interrupt();
        }
    }

    private void fail(Throwable error) {
        boolean first;
        lock.lock();
        try { first=failure == null; if (first) failure = error; } finally { lock.unlock(); }
        if (first) {
            String message=String.valueOf(error.getMessage());
            if (message.contains("queue full")) Metrics.reliableOverflow();
            else if (message.contains("byte budget exceeded")) Metrics.budgetDisconnect();
        }
        closeSocket();
        lock.lock();
        try { notEmpty.signalAll(); notFull.signalAll(); } finally { lock.unlock(); }
    }

    private void closeSocket() { try { socket.close(); } catch (IOException ignored) { } }
}
