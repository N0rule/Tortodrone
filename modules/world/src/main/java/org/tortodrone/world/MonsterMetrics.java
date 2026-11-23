package org.tortodrone.world;

import java.util.Map;

/**
 * Per-scene monster and path counters plus a bounded tick-duration window.
 *
 * <p>Counters are monotonic for the life of the scene instance; gauges are read from the
 * live entities when a snapshot is taken. The tick window keeps the most recent 256 samples,
 * enough for a stable p99 without retaining an unbounded history.
 */
public final class MonsterMetrics {
    private static final int TICK_WINDOW = 256;
    private final long[] ticks = new long[TICK_WINDOW];
    private int tickCount, tickNext;
    private long spawned, despawned, pathSearches, pathFailures;

    /** Immutable read for the console, WebUI and focused tests. */
    public record Snapshot(
            int total,
            int awake,
            int engaged,
            Map<String, Integer> byType,
            long spawned,
            long despawned,
            long pathSearches,
            long pathFailures,
            int pathQueued,
            long pathDropped,
            double tickP50Millis,
            double tickP99Millis) {
        public Snapshot {
            byType = Map.copyOf(byType);
        }
    }

    void spawned() {
        spawned++;
    }

    void despawned() {
        despawned++;
    }

    void searched(boolean failed) {
        pathSearches++;
        if (failed) pathFailures++;
    }

    void tick(long nanos) {
        ticks[tickNext] = Math.max(0, nanos);
        tickNext = (tickNext + 1) % TICK_WINDOW;
        if (tickCount < TICK_WINDOW) tickCount++;
    }

    Snapshot snapshot(
            int total, int awake, int engaged, Map<String, Integer> byType, int pathQueued, long pathDropped) {
        return new Snapshot(
                total,
                awake,
                engaged,
                byType,
                spawned,
                despawned,
                pathSearches,
                pathFailures,
                pathQueued,
                pathDropped,
                percentile(.50),
                percentile(.99));
    }

    private double percentile(double fraction) {
        if (tickCount == 0) return 0;
        long[] samples = new long[tickCount];
        System.arraycopy(ticks, 0, samples, 0, tickCount);
        java.util.Arrays.sort(samples);
        int index = Math.min(tickCount - 1, Math.max(0, (int) Math.ceil(fraction * tickCount) - 1));
        return samples[index] / 1_000_000d;
    }
}
