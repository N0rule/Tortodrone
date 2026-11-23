package org.tortodrone.storage;

import java.util.concurrent.atomic.AtomicLong;

/** Process-wide storage counters and duration samples. Telemetry never changes write
 *  behaviour; it is kept out of {@link Database} so the transaction owner stays focused
 *  on connections, ordering and rollback. */
final class StorageTelemetry {
    private static final AtomicLong operations=new AtomicLong(),slow=new AtomicLong(),failures=new AtomicLong(),
        rollbacks=new AtomicLong(),maximum=new AtomicLong();
    private static final AtomicLong busyErrors=new AtomicLong(),expiryRuns=new AtomicLong(),expiryNanos=new AtomicLong(),
        checkpoints=new AtomicLong(),checkpointNanos=new AtomicLong();
    private static final long[] durationSamples=new long[256];
    private static int durationCount,durationNext;
    private StorageTelemetry() { }

    static void measured(String kind,long start,boolean failed,Database.Observer observer){
        long nanos=System.nanoTime()-start;long elapsed=nanos/1000000;operations.incrementAndGet();maximum.accumulateAndGet(elapsed,Math::max);
        synchronized(StorageTelemetry.class){durationSamples[durationNext]=(int)Math.min(Integer.MAX_VALUE,nanos/1000);durationNext=(durationNext+1)%durationSamples.length;if(durationCount<durationSamples.length)durationCount++;}
        if(elapsed>=100)slow.incrementAndGet();if(failed)failures.incrementAndGet();
        try{observer.completed(kind,elapsed,failed);}catch(RuntimeException ignored){ }
    }
    static void rollback(){rollbacks.incrementAndGet();}
    static void busyError(){busyErrors.incrementAndGet();}
    static void checkpoint(long nanos){checkpoints.incrementAndGet();checkpointNanos.addAndGet(nanos);}
    static void expiry(long nanos){expiryRuns.incrementAndGet();expiryNanos.addAndGet(nanos);}
    static long operations(){return operations.get();}
    static long slow(){return slow.get();}
    static long failures(){return failures.get();}
    static long rollbacks(){return rollbacks.get();}
    static long maximum(){return maximum.get();}
    static long busyErrors(){return busyErrors.get();}
    static long expiryRuns(){return expiryRuns.get();}
    static long expiryNanos(){return expiryNanos.get();}
    static long checkpoints(){return checkpoints.get();}
    static long checkpointNanos(){return checkpointNanos.get();}
    static synchronized long percentile(double quantile) {
        if(durationCount==0)return 0;
        long[] copy=java.util.Arrays.copyOf(durationSamples,durationCount);java.util.Arrays.sort(copy);
        return copy[Math.min(copy.length-1,(int)Math.ceil(copy.length*quantile)-1)];
    }
}
