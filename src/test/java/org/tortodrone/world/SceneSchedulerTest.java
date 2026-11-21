package org.tortodrone.world;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.tortodrone.support.TestWire.check;

/** One serial lane per scene, a bounded active-scene cap, no overlap and a bounded shutdown. */
public final class SceneSchedulerTest {
    public static void main(String[] args)throws Exception {
        serialLaneNeverOverlaps();
        activeSceneCapRefuses();
        failureStopsOnlyItsLane();
        closeStopsLanes();
        System.out.println("PASS: serial scene lanes, active-scene cap, failure isolation and shutdown");
    }

    private static void serialLaneNeverOverlaps()throws Exception {
        try(var scheduler=new SceneScheduler(2,8)) {
            var inside=new AtomicInteger();
            var overlap=new AtomicBoolean();
            var ticks=new AtomicInteger();
            scheduler.start(1,()->{
                if(inside.incrementAndGet()>1)overlap.set(true);
                ticks.incrementAndGet();
                try{Thread.sleep(5);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
                inside.decrementAndGet();
            });
            await(()->ticks.get()>=3,2000);
            check(!overlap.get(),"one lane is never ticked concurrently");
            check(scheduler.activeScenes()==1,"one active lane is tracked");
            scheduler.stop(1);
            int settled=ticks.get();
            Thread.sleep(150);
            check(ticks.get()<=settled+1,"a stopped lane stops ticking");
        }
    }

    private static void activeSceneCapRefuses()throws Exception {
        try(var scheduler=new SceneScheduler(1,2)) {
            scheduler.start(1,()->{});
            scheduler.start(2,()->{});
            boolean refused=false;
            try{scheduler.start(3,()->{});}catch(IllegalStateException expected){refused=true;}
            check(refused,"the active-scene cap refuses a new lane");
            check(scheduler.activeScenes()==2,"the cap keeps the admitted lanes");
        }
    }

    private static void failureStopsOnlyItsLane()throws Exception {
        try(var scheduler=new SceneScheduler(2,8)) {
            var healthy=new AtomicInteger();
            scheduler.start(1,()->{throw new IllegalStateException("boom");});
            scheduler.start(2,healthy::incrementAndGet);
            await(()->!scheduler.running(1),2000);
            await(()->healthy.get()>=2,2000);
            check(scheduler.activeScenes()==1,"a failed lane is removed");
            check(scheduler.running(2),"other lanes keep running after one failure");
        }
    }

    private static void closeStopsLanes()throws Exception {
        var scheduler=new SceneScheduler(1,8);
        var ticks=new AtomicInteger();
        scheduler.start(1,ticks::incrementAndGet);
        await(()->ticks.get()>=1,2000);
        scheduler.close();
        int settled=ticks.get();
        Thread.sleep(150);
        check(ticks.get()<=settled+1,"close stops all lanes within the drain deadline");
        check(scheduler.activeScenes()==0,"close clears the lane table");
    }

    private static void await(java.util.function.BooleanSupplier condition,long millis)throws Exception {
        long deadline=System.nanoTime()+millis*1_000_000L;
        while(System.nanoTime()<deadline) {
            if(condition.getAsBoolean())return;
            Thread.sleep(5);
        }
        throw new AssertionError("condition not met within "+millis+" ms");
    }
}
