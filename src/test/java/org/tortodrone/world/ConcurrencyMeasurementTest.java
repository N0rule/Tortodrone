package org.tortodrone.world;

import java.lang.management.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.resources.ContentManager;
import org.tortodrone.storage.Database;

/** Opt-in, local Phase 4 measurement of the scene lane, storage writer and thread/GC accounting. */
public final class ConcurrencyMeasurementTest {
    public static void main(String[] args)throws Exception {
        int seconds=args.length==0?2:Math.max(1,Math.min(10,Integer.parseInt(args[0])));
        System.out.println("host="+System.getProperty("os.name")+"/"+System.getProperty("os.arch")
            +" cpus="+Runtime.getRuntime().availableProcessors()
            +" maxHeapMiB="+(Runtime.getRuntime().maxMemory()/1048576)
            +" java="+System.getProperty("java.runtime.version"));
        var catalog=ContentManager.load(Path.of(".")).scenes();
        var map=catalog.scene(1);
        var simulation=new SceneRegistry.Instance(9_100,0,map).simulation();
        var members=new ArrayList<SceneSimulation.Membership>();
        for(int i=0;i<8;i++) {
            var actor=new ActorState(100+i,200+i,"Measure "+i,256,1,map.spawnX(),map.spawnY(),0,0,0,
                new StarterCatalog.Model("Fixture",Map.of()));
            members.add(simulation.join(actor,ignored->{}));
        }
        for(var member:members)member.enter();

        long started=System.nanoTime();
        long deadline=started+seconds*1_000_000_000L;
        while(System.nanoTime()<deadline)Thread.sleep(25);
        var tick=simulation.tickMetrics();
        var late=SceneScheduler.shared().lateness();
        for(var member:members)member.close();

        System.out.printf(Locale.ROOT,"scene ticks=%d lockWaitLastUs=%.1f workLastUs=%.1f workMaxUs=%.1f cadenceP50Ms=%.1f cadenceP99Ms=%.1f cadenceMaxMs=%.1f overruns=%d%n",
            tick.ticks(),tick.lockWaitNanos()/1000.0,tick.workNanos()/1000.0,tick.maxWorkNanos()/1000.0,
            late.p50Millis(),late.p99Millis(),late.maxMillis(),SceneScheduler.shared().overruns());

        Path home=Files.createTempDirectory("tortodrone-measure-");
        try {
            var database=new Database(home,false);
            long writeStarted=System.nanoTime();
            for(int i=0;i<200;i++)database.write(connection->null);
            long writeNanos=System.nanoTime()-writeStarted;
            long readStarted=System.nanoTime();
            for(int i=0;i<200;i++)database.read(connection->null);
            long readNanos=System.nanoTime()-readStarted;
            var diagnostics=Database.diagnostics();
            long wal=Files.exists(Path.of(database.file()+"-wal"))?Files.size(Path.of(database.file()+"-wal")):0;
            long checkpointStarted=System.nanoTime();
            database.close();database.checkpoint();
            long checkpointNanos=System.nanoTime()-checkpointStarted;
            System.out.printf(Locale.ROOT,"storage writes=200 avgUs=%.1f reads=200 avgUs=%.1f operations=%d slow=%d failures=%d rollbacks=%d maxMs=%d walBytes=%d checkpointMs=%.1f%n",
                writeNanos/1000.0/200,readNanos/1000.0/200,diagnostics.operations(),diagnostics.slowOperations(),
                diagnostics.failures(),diagnostics.rollbacks(),diagnostics.maximumMillis(),wal,checkpointNanos/1_000_000.0);
        } finally {
            try(var paths=Files.walk(home)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }

        long allocated=-1;
        if(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean memory&&memory.isThreadAllocatedMemorySupported()) {
            memory.setThreadAllocatedMemoryEnabled(true);
            long before=memory.getThreadAllocatedBytes(Thread.currentThread().threadId());
            for(int i=0;i<100;i++)simulation.snapshot();
            allocated=memory.getThreadAllocatedBytes(Thread.currentThread().threadId())-before;
        }
        var threads=ManagementFactory.getThreadMXBean();
        long gcCount=ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean->bean.getCollectionCount()).sum();
        long gcMillis=ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean->bean.getCollectionTime()).sum();
        System.out.printf(Locale.ROOT,"threads platform=%d peak=%d snapshotAllocBytesPerCall=%.0f gcCount=%d gcMs=%d%n",
            threads.getThreadCount(),threads.getPeakThreadCount(),allocated<0?-1:allocated/100.0,gcCount,gcMillis);

        long drainStarted=System.nanoTime();
        SceneScheduler.shared().close();
        System.out.printf(Locale.ROOT,"shutdownDrainMs=%.1f%n",(System.nanoTime()-drainStarted)/1_000_000.0);
        System.out.println("PASS: Phase 4 concurrency measurements recorded on this host");
    }
}
