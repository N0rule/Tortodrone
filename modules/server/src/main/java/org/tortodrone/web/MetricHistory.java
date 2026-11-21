package org.tortodrone.web;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.tortodrone.server.*;

/** Five-second samples, retained independently of browser visits for at most one hour. */
final class MetricHistory implements AutoCloseable {
    static final int CAPACITY = 721;
    public record Sample(long time, RuntimeMetrics runtime, int players, int connections,
            int scenes, int scenePopulation, long connected, long disconnected) { }
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private final ScheduledExecutorService timer;
    private final Supplier<ServerControl.Snapshot> source;
    private final SessionRegistry sessions;
    private final LongSupplier clock;

    MetricHistory(ServerControl control) {
        this(control::snapshot, control.sessions(), System::currentTimeMillis, true);
    }
    MetricHistory(Supplier<ServerControl.Snapshot> source, SessionRegistry sessions, LongSupplier clock, boolean schedule) {
        this.source=source; this.sessions=sessions; this.clock=clock;
        sample();
        timer=schedule?Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"webui-metrics");t.setDaemon(true);return t;}):null;
        if(timer!=null)timer.scheduleWithFixedDelay(()->{try{sample();}catch(RuntimeException unavailable){/* Leave a visible gap. */}},5,5,TimeUnit.SECONDS);
    }
    void sample() {
        var s=source.get();
        var value=new Sample(clock.getAsLong(),s.metrics(),(int)s.sessions().stream().filter(p->p.knightId()!=null).count(),
            s.sessions().size(),s.scenes().size(),s.scenes().stream().mapToInt(p->p.players()).sum(),sessions.connections(),sessions.disconnections());
        synchronized(samples) {
            while(!samples.isEmpty()&&(samples.size()>=CAPACITY||samples.getFirst().time()<value.time()-3_600_000))samples.removeFirst();
            samples.addLast(value);
        }
    }
    List<Sample> read(int minutes) {
        if(!Set.of(5,15,60).contains(minutes))throw new IllegalArgumentException("Choose 5, 15 or 60 minutes");
        long since=clock.getAsLong()-minutes*60_000L;
        synchronized(samples){return samples.stream().filter(s->s.time()>=since).toList();}
    }
    @Override public void close(){if(timer!=null)timer.shutdownNow();}
}
