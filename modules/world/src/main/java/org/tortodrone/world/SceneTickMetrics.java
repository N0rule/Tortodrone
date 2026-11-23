package org.tortodrone.world;

/** Per-scene tick observations: rolling p50/p99 windows and process maxima, sampled
 *  without holding the scene monitor. The simulation owns the tick loop; this class
 *  only records its timings. */
public final class SceneTickMetrics {
    private static final int WINDOW = 256;

    private long lockWaitNanos, workNanos, maxWorkNanos, maxLockWaitNanos, maxPublicationNanos;
    private final long[] lockWaitSamples = new long[WINDOW], workSamples = new long[WINDOW],
        publicationSamples = new long[WINDOW], deliverySamples = new long[WINDOW];
    private int sampleCount, sampleNext;
    private long maxDeliveryNanos;

    public record Snapshot(long ticks,long lockWaitNanos,long workNanos,long maxWorkNanos,
            long lockWaitP50Nanos,long lockWaitP99Nanos,long workP50Nanos,long workP99Nanos,
            long publicationP50Nanos,long publicationP99Nanos,long deliveryP50Nanos,long deliveryP99Nanos,
            long maxLockWaitNanos,long maxPublicationNanos,long maxDeliveryNanos) { }

    /** Record the latest tick's lock wait, work and publication durations. */
    synchronized void record(long lockWait,long work,long publication) {
        lockWaitNanos = lockWait;
        workNanos = work;
        if (work > maxWorkNanos) maxWorkNanos = work;
        if (lockWait > maxLockWaitNanos) maxLockWaitNanos = lockWait;
        if (publication > maxPublicationNanos) maxPublicationNanos = publication;
    }

    /** Add one observation to the rolling window after delivery completes. */
    synchronized void sample(long lockWait,long work,long publication,long delivery) {
        lockWaitSamples[sampleNext] = lockWait;
        workSamples[sampleNext] = work;
        publicationSamples[sampleNext] = publication;
        deliverySamples[sampleNext] = delivery;
        sampleNext = (sampleNext + 1) % WINDOW;
        if (sampleCount < WINDOW) sampleCount++;
        if (delivery > maxDeliveryNanos) maxDeliveryNanos = delivery;
    }

    synchronized Snapshot snapshot(long ticks) {
        return new Snapshot(ticks,lockWaitNanos,workNanos,maxWorkNanos,
            percentile(lockWaitSamples,.50),percentile(lockWaitSamples,.99),
            percentile(workSamples,.50),percentile(workSamples,.99),
            percentile(publicationSamples,.50),percentile(publicationSamples,.99),
            percentile(deliverySamples,.50),percentile(deliverySamples,.99),
            maxLockWaitNanos,maxPublicationNanos,maxDeliveryNanos);
    }

    private long percentile(long[] samples,double quantile) {
        if (sampleCount == 0) return 0;
        long[] copy = java.util.Arrays.copyOf(samples,sampleCount);
        java.util.Arrays.sort(copy);
        return copy[Math.min(copy.length - 1,(int)Math.ceil(copy.length * quantile) - 1)];
    }
}
