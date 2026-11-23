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
 * update frames (scene snapshots and effect chatter) are skipped before encoding once the budget
 * is exhausted. The budget decision and the encoding both happen under the encoding lock, so an
 * optional frame that starts encoding is always queued; dropping it afterwards would orphan the
 * class/intern mappings it may have registered. One such frame can leave the queue up to one
 * frame past the byte budget, which the next optional send sees immediately. A client that keeps
 * the queue over budget for a sustained window is disconnected.
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
        private static final java.util.concurrent.atomic.AtomicLong largestFrame =
                new java.util.concurrent.atomic.AtomicLong();
        private static final java.util.concurrent.atomic.LongAdder droppedOptional =
                new java.util.concurrent.atomic.LongAdder();
        private static final java.util.concurrent.atomic.LongAdder reliableOverflows =
                new java.util.concurrent.atomic.LongAdder();
        private static final java.util.concurrent.atomic.LongAdder budgetDisconnects =
                new java.util.concurrent.atomic.LongAdder();
        private static final java.util.concurrent.atomic.AtomicLong peakPendingFrames =
                new java.util.concurrent.atomic.AtomicLong();
        private static final java.util.concurrent.atomic.AtomicLong peakPendingBytes =
                new java.util.concurrent.atomic.AtomicLong();

        private Metrics() {}

        public record Snapshot(
                long frames,
                long bytes,
                long largestFrame,
                long droppedOptional,
                long reliableOverflows,
                long budgetDisconnects,
                long peakPendingFrames,
                long peakPendingBytes) {}

        static void queued(int length, int pendingFrames, long pendingBytes) {
            frames.increment();
            bytes.add(length);
            largestFrame.accumulateAndGet(length, Math::max);
            peakPendingFrames.accumulateAndGet(pendingFrames, Math::max);
            peakPendingBytes.accumulateAndGet(pendingBytes, Math::max);
        }

        static void optionalDropped() {
            droppedOptional.increment();
        }

        static void reliableOverflow() {
            reliableOverflows.increment();
        }

        static void budgetDisconnect() {
            budgetDisconnects.increment();
        }

        public static Snapshot snapshot() {
            return new Snapshot(
                    frames.sum(),
                    bytes.sum(),
                    largestFrame.get(),
                    droppedOptional.sum(),
                    reliableOverflows.sum(),
                    budgetDisconnects.sum(),
                    peakPendingFrames.get(),
                    peakPendingBytes.get());
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
    private volatile boolean closed;
    private long pendingBytes;
    private long overBudgetSince;
    private long droppedOptional;

    private final int maxPending;
    private final int maxPendingBytes;
    private final long maxOverBudgetNanos;
    /** Time source for the sustained-budget window; tests advance it instead of sleeping. */
    private final java.util.function.LongSupplier nanoClock;

    SessionOutput(Socket socket) {
        this(socket, MAX_PENDING, MAX_PENDING_BYTES, MAX_OVER_BUDGET_NANOS);
    }

    SessionOutput(Socket socket, int maxPending, int maxPendingBytes, long maxOverBudgetNanos) {
        this(socket, maxPending, maxPendingBytes, maxOverBudgetNanos, System::nanoTime);
    }

    SessionOutput(
            Socket socket,
            int maxPending,
            int maxPendingBytes,
            long maxOverBudgetNanos,
            java.util.function.LongSupplier nanoClock) {
        this.socket = socket;
        this.maxPending = maxPending;
        this.maxPendingBytes = maxPendingBytes;
        this.maxOverBudgetNanos = maxOverBudgetNanos;
        this.nanoClock = nanoClock;
        writer = new Thread(this::write, Thread.currentThread().getName() + "-Output");
        writer.setDaemon(true);
        writer.start();
    }

    @FunctionalInterface
    interface Encoder {
        byte[] frame() throws IOException;
    }

    /** Reliable state change: never dropped. */
    void send(Encoder encoder) throws IOException {
        org.tortodrone.core.LockOrder.enter(org.tortodrone.core.LockOrder.OUTPUT);
        try {
            encodeAndQueue(encoder, false);
        } finally {
            org.tortodrone.core.LockOrder.exit();
        }
    }

    /** Optional update: skipped before encoding when the queue is over budget. */
    boolean sendOptional(Encoder encoder) throws IOException {
        org.tortodrone.core.LockOrder.enter(org.tortodrone.core.LockOrder.OUTPUT);
        try {
            return encodeAndQueue(encoder, true);
        } finally {
            org.tortodrone.core.LockOrder.exit();
        }
    }

    /** True when optional work should be skipped without encoding anything this tick. */
    boolean optionalOverBudget() {
        org.tortodrone.core.LockOrder.enter(org.tortodrone.core.LockOrder.OUTPUT);
        try {
            lock.lock();
            try {
                if (closed || failure != null) return true;
                boolean over = overBudget();
                if (over) {
                    droppedOptional++;
                    Metrics.optionalDropped();
                    noteOverBudget();
                } else clearOverBudget();
                return over;
            } finally {
                lock.unlock();
            }
        } finally {
            org.tortodrone.core.LockOrder.exit();
        }
    }

    private boolean overBudget() {
        return pendingBytes >= maxPendingBytes || pending.size() >= maxPending;
    }

    private void noteOverBudget() {
        if (overBudgetSince == 0) overBudgetSince = nanoClock.getAsLong();
        else if (nanoClock.getAsLong() - overBudgetSince >= maxOverBudgetNanos)
            fail(new IOException("Downstream byte budget exceeded"), Failure.BUDGET_DISCONNECT);
    }

    private void clearOverBudget() {
        overBudgetSince = 0;
    }

    private boolean encodeAndQueue(Encoder encoder, boolean optional) throws IOException {
        encoding.lock();
        try {
            lock.lock();
            try {
                if (closed || failure != null || socket.isClosed())
                    throw new IOException("Downstream connection closed", failure);
                if (overBudgetSince != 0 && nanoClock.getAsLong() - overBudgetSince >= maxOverBudgetNanos) {
                    var error = new IOException("Downstream byte budget exceeded");
                    fail(error, Failure.BUDGET_DISCONNECT);
                    throw error;
                }
                // The decision is made while the encoding lock is held. Every enqueue takes
                // that lock too, so a frame that starts encoding cannot be overtaken by a
                // producer that pushes the queue over budget before this frame is queued.
                if (optional && overBudget()) {
                    droppedOptional++;
                    Metrics.optionalDropped();
                    noteOverBudget();
                    return false;
                }
                if (optional) clearOverBudget();
            } finally {
                lock.unlock();
            }
            byte[] frame;
            try {
                frame = encoder.frame();
                if (frame.length > org.tortodrone.protocol.SessionCodec.MAX_DOWNSTREAM_FRAME)
                    throw new IOException("Downstream frame exceeds size limit");
            } catch (IOException | RuntimeException error) {
                // An unsent frame may have introduced codec mappings. Never reuse that stream.
                fail(error);
                if (error instanceof IOException io) throw io;
                throw new IOException("Downstream encoding failed", error);
            }
            lock.lock();
            try {
                if (closed || failure != null || socket.isClosed())
                    throw new IOException("Downstream connection closed", failure);
                if (!optional && pending.size() >= maxPending) {
                    fail(new IOException("Downstream queue full"), Failure.RELIABLE_OVERFLOW);
                    throw new IOException("Downstream queue full", failure);
                }
                if (!optional && pendingBytes + frame.length > maxPendingBytes) {
                    fail(new IOException("Downstream frame does not fit the byte budget"), Failure.RELIABLE_OVERFLOW);
                    throw new IOException("Downstream frame does not fit the byte budget", failure);
                }
                // An optional frame that began encoding is always queued: its encoder may have
                // registered class/intern mappings, and dropping it now would leave the client
                // with mappings it never saw. The overshoot is bounded by one frame and the
                // next optional send sees it immediately.
                pending.addLast(frame);
                pendingBytes += frame.length;
                notEmpty.signal();
                if (optional && overBudget()) noteOverBudget();
                Metrics.queued(frame.length, pending.size(), pendingBytes);
                return true;
            } finally {
                lock.unlock();
            }
        } finally {
            encoding.unlock();
        }
    }

    long droppedOptional() {
        lock.lock();
        try {
            return droppedOptional;
        } finally {
            lock.unlock();
        }
    }

    /** Test seam: holding this lock parks a producer exactly like a slow downstream consumer. */
    java.util.concurrent.locks.ReentrantLock encodingLockForTest() {
        return encoding;
    }

    /** Test seam: queued bytes after the writer has taken (but not yet written) its frame. */
    long pendingBytesForTest() {
        lock.lock();
        try {
            return pendingBytes;
        } finally {
            lock.unlock();
        }
    }

    private void write() {
        try {
            while (true) {
                byte[] frame;
                lock.lock();
                try {
                    while (pending.isEmpty()) notEmpty.await();
                    frame = pending.removeFirst();
                    pendingBytes -= frame.length;
                    if (!overBudget()) clearOverBudget();
                    notFull.signalAll();
                } finally {
                    lock.unlock();
                }
                if (frame == END) return;
                socket.getOutputStream().write(frame);
                socket.getOutputStream().flush();
            }
        } catch (IOException | InterruptedException e) {
            fail(e);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (closed) return;
            closed = true;
            pending.addLast(END);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
        try {
            writer.join(1000);
            if (writer.isAlive()) {
                closeSocket();
                writer.interrupt();
                writer.join(1000);
            }
        } catch (InterruptedException e) {
            closeSocket();
            writer.interrupt();
            Thread.currentThread().interrupt();
        }
    }

    /** True while this queue still owns a live socket. */
    boolean open() {
        return !closed && !socket.isClosed();
    }

    /** Drains queued frames, then closes the socket: the official post-rename session end. */
    void disconnect() {
        lock.lock();
        try {
            if (closed) return;
            closed = true;
            pending.addLast(END);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
        try {
            writer.join(5000);
            closeSocket();
            writer.join(1000);
        } catch (InterruptedException e) {
            closeSocket();
            writer.interrupt();
            Thread.currentThread().interrupt();
        }
    }

    /** Why a queue ends, for the process counters; only the first failure is counted. */
    private enum Failure {
        NONE,
        RELIABLE_OVERFLOW,
        BUDGET_DISCONNECT
    }

    private void fail(Throwable error) {
        fail(error, Failure.NONE);
    }

    private void fail(Throwable error, Failure kind) {
        boolean first;
        lock.lock();
        try {
            first = failure == null;
            if (first) {
                failure = error;
                switch (kind) {
                    case RELIABLE_OVERFLOW -> Metrics.reliableOverflow();
                    case BUDGET_DISCONNECT -> Metrics.budgetDisconnect();
                    case NONE -> {}
                }
            }
        } finally {
            lock.unlock();
        }
        closeSocket();
        lock.lock();
        try {
            notEmpty.signalAll();
            notFull.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void closeSocket() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
