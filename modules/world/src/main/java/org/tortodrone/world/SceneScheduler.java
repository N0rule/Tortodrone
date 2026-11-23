package org.tortodrone.world;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One serial execution lane per scene on a bounded platform carrier pool.
 *
 * <p>The scheduler never starts a second tick for a scene before the previous one returns, never
 * catches up missed ticks, and refuses new lanes once the active-scene cap is reached. A tick that
 * overruns the cadence is counted and logged rather than run again immediately: the next slot is
 * simply the next fixed delay. This keeps a slow scene from starving the other lanes.
 *
 * <p>Admission, the lane table and close are guarded by the scheduler monitor; tick bodies and
 * lateness sampling run outside it.
 */
public final class SceneScheduler implements AutoCloseable {
    /** Native-ish scene publication cadence used by the existing simulation. */
    public static final long CADENCE_MILLIS = 50;
    /** Hard bound on concurrently scheduled scene lanes; one per scene with an entered player. */
    public static final int DEFAULT_MAX_ACTIVE_SCENES = 256;
    private static final int MEASUREMENT_WINDOW = 256;
    private static volatile SceneScheduler shared = new SceneScheduler();

    public static SceneScheduler shared() { return shared; }

    /** Test hook: the standalone suite shares one JVM, so a shutdown-drain measurement replaces
     *  the closed instance before later programs use the shared lane pool again. */
    static void resetSharedForTests() {
        if (shared.closed) shared = new SceneScheduler();
    }

    private record Lane(int oid, ScheduledFuture<?> future, Runnable tick) { }

    private final ScheduledThreadPoolExecutor timer;
    private final int maxActiveScenes;
    private final ConcurrentHashMap<Integer, Lane> lanes = new ConcurrentHashMap<>();
    private final AtomicLong overruns = new AtomicLong();
    private final AtomicLong lateTicks = new AtomicLong();
    private final long[] latenessNanos = new long[MEASUREMENT_WINDOW];
    private int latenessCount, latenessNext;
    private volatile boolean closed;

    public SceneScheduler() {
        this(Math.max(2, Math.min(Runtime.getRuntime().availableProcessors(), 8)),
            DEFAULT_MAX_ACTIVE_SCENES);
    }

    public SceneScheduler(int carriers, int maxActiveScenes) {
        this.maxActiveScenes = maxActiveScenes;
        this.timer = new ScheduledThreadPoolExecutor(Math.max(1, carriers), runnable -> {
            Thread thread = new Thread(runnable, "SceneScheduler");
            thread.setDaemon(true);
            return thread;
        });
        this.timer.setRemoveOnCancelPolicy(true);
        this.timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.timer.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    /**
     * Schedule one scene lane. The runtime cap is enforced here; callers translate a refusal into
     * a safe travel failure rather than an untracked scene.
     */
    public synchronized void start(int oid, Runnable tick) {
        if (closed) throw new IllegalStateException("Scene scheduler is closed");
        if (lanes.containsKey(oid)) return;
        if (lanes.size() >= maxActiveScenes)
            throw new IllegalStateException("Active scene cap reached (" + maxActiveScenes + ")");
        ScheduledFuture<?> future = timer.scheduleWithFixedDelay(() -> run(oid, tick),
            CADENCE_MILLIS, CADENCE_MILLIS, TimeUnit.MILLISECONDS);
        lanes.put(oid, new Lane(oid, future, tick));
    }

    public void stop(int oid) {
        Lane lane = lanes.remove(oid);
        if (lane != null) lane.future().cancel(false);
    }

    public boolean running(int oid) { return lanes.containsKey(oid); }
    public int activeScenes() { return lanes.size(); }
    public int maxActiveScenes() { return maxActiveScenes; }
    public long overruns() { return overruns.get(); }
    public long lateTicks() { return lateTicks.get(); }

    /** p50/p99/max of the observed cadence lateness in milliseconds, sampled outside scene locks. */
    public Lateness lateness() {
        long[] samples;
        synchronized (this) {
            samples = new long[latenessCount];
            System.arraycopy(latenessNanos, 0, samples, 0, latenessCount);
        }
        if (samples.length == 0) return new Lateness(0, 0, 0);
        java.util.Arrays.sort(samples);
        return new Lateness(samples[(int) ((samples.length - 1) * .50)] / 1_000_000.0,
            samples[(int) ((samples.length - 1) * .99)] / 1_000_000.0,
            samples[samples.length - 1] / 1_000_000.0);
    }

    public record Lateness(double p50Millis, double p99Millis, double maxMillis) { }

    private void run(int oid, Runnable tick) {
        if (closed || !lanes.containsKey(oid)) return;
        long started = System.nanoTime();
        try {
            tick.run();
        } catch (RuntimeException | Error failure) {
            stop(oid);
            throw failure;
        } finally {
            long elapsed = System.nanoTime() - started;
            long late = elapsed - CADENCE_MILLIS * 1_000_000L;
            if (late < 0) late = 0;
            if (late > 0) overruns.incrementAndGet();
            recordLateness(late);
        }
    }

    private synchronized void recordLateness(long nanos) {
        if (nanos == 0) return;
        lateTicks.incrementAndGet();
        latenessNanos[latenessNext] = nanos;
        latenessNext = (latenessNext + 1) % MEASUREMENT_WINDOW;
        if (latenessCount < MEASUREMENT_WINDOW) latenessCount++;
    }

    /** Stop scheduling, then wait for in-flight ticks up to the deadline before forcing shutdown. */
    @Override public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            for (Lane lane : lanes.values()) lane.future().cancel(false);
            lanes.clear();
        }
        timer.shutdown();
        try {
            if (!timer.awaitTermination(5, TimeUnit.SECONDS)) timer.shutdownNow();
        } catch (InterruptedException interrupted) {
            timer.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
