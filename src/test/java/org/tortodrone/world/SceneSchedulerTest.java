package org.tortodrone.world;

import static org.tortodrone.support.TestWire.check;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** One serial lane per scene, a bounded active-scene cap, no overlap and a bounded shutdown. */
public final class SceneSchedulerTest {
    public static void main(String[] args) throws Exception {
        serialLaneNeverOverlaps();
        activeSceneCapRefuses();
        concurrentStartRespectsCap();
        failureStopsOnlyItsLane();
        closeStopsLanes();
        cadenceLatenessIsMeasured();
        System.out.println(
                "PASS: serial scene lanes, active-scene cap (including concurrent admission), failure isolation, shutdown and cadence lateness");
    }

    private static void serialLaneNeverOverlaps() throws Exception {
        try (var scheduler = new SceneScheduler(2, 8)) {
            var inside = new AtomicInteger();
            var overlap = new AtomicBoolean();
            var ticks = new AtomicInteger();
            scheduler.start(1, () -> {
                if (inside.incrementAndGet() > 1) overlap.set(true);
                ticks.incrementAndGet();
                try {
                    Thread.sleep(5);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                inside.decrementAndGet();
            });
            await(() -> ticks.get() >= 3, 2000);
            check(!overlap.get(), "one lane is never ticked concurrently");
            check(scheduler.activeScenes() == 1, "one active lane is tracked");
            scheduler.stop(1);
            int settled = ticks.get();
            Thread.sleep(150);
            check(ticks.get() <= settled + 1, "a stopped lane stops ticking");
        }
    }

    private static void activeSceneCapRefuses() throws Exception {
        try (var scheduler = new SceneScheduler(1, 2)) {
            scheduler.start(1, () -> {});
            scheduler.start(2, () -> {});
            boolean refused = false;
            try {
                scheduler.start(3, () -> {});
            } catch (IllegalStateException expected) {
                refused = true;
            }
            check(refused, "the active-scene cap refuses a new lane");
            check(scheduler.activeScenes() == 2, "the cap keeps the admitted lanes");
        }
    }

    private static void concurrentStartRespectsCap() throws Exception {
        try (var scheduler = new SceneScheduler(2, 4)) {
            int callers = 32;
            var ready = new CountDownLatch(callers);
            var go = new CountDownLatch(1);
            var admitted = new AtomicInteger();
            var refused = new AtomicInteger();
            var threads = new Thread[callers];
            for (int i = 0; i < callers; i++) {
                int oid = i + 1;
                threads[i] = new Thread(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        scheduler.start(oid, () -> {});
                        admitted.incrementAndGet();
                    } catch (IllegalStateException expected) {
                        refused.incrementAndGet();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
                threads[i].start();
            }
            ready.await();
            go.countDown();
            for (var thread : threads) thread.join(5000);
            check(scheduler.activeScenes() <= 4, "concurrent starts never exceed the active-scene cap");
            check(admitted.get() == 4 && scheduler.activeScenes() == 4, "exactly the cap is admitted");
            check(refused.get() == callers - 4, "every other caller is refused");
        }
    }

    private static void failureStopsOnlyItsLane() throws Exception {
        try (var scheduler = new SceneScheduler(2, 8)) {
            var healthy = new AtomicInteger();
            scheduler.start(1, () -> {
                throw new IllegalStateException("boom");
            });
            scheduler.start(2, healthy::incrementAndGet);
            await(() -> !scheduler.running(1), 2000);
            await(() -> healthy.get() >= 2, 2000);
            check(scheduler.activeScenes() == 1, "a failed lane is removed");
            check(scheduler.running(2), "other lanes keep running after one failure");
        }
    }

    private static void closeStopsLanes() throws Exception {
        var scheduler = new SceneScheduler(1, 8);
        var ticks = new AtomicInteger();
        scheduler.start(1, ticks::incrementAndGet);
        await(() -> ticks.get() >= 1, 2000);
        scheduler.close();
        int settled = ticks.get();
        Thread.sleep(150);
        check(ticks.get() <= settled + 1, "close stops all lanes within the drain deadline");
        check(scheduler.activeScenes() == 0, "close clears the lane table");
    }

    /** A tick that outruns the cadence is counted, and its lateness is reported as p50/p99/max. */
    private static void cadenceLatenessIsMeasured() throws Exception {
        try (var scheduler = new SceneScheduler(1, 4)) {
            var ticks = new AtomicInteger();
            scheduler.start(1, () -> {
                if (ticks.incrementAndGet() <= 2)
                    try {
                        Thread.sleep(SceneScheduler.CADENCE_MILLIS + 30);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
            });
            await(() -> scheduler.lateTicks() >= 2, 5000);
            check(scheduler.overruns() >= 2, "a tick that outruns the cadence is counted as an overrun");
            var lateness = scheduler.lateness();
            check(
                    lateness.p50Millis() > 0 && lateness.p99Millis() > 0 && lateness.maxMillis() >= 20,
                    "cadence lateness reports p50, p99 and the maximum");
            check(
                    lateness.p50Millis() <= lateness.p99Millis() && lateness.p99Millis() <= lateness.maxMillis(),
                    "cadence lateness percentiles are ordered");
        }
    }

    private static void await(java.util.function.BooleanSupplier condition, long millis) throws Exception {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(5);
        }
        throw new AssertionError("condition not met within " + millis + " ms");
    }
}
