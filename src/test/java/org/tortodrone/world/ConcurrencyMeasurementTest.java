package org.tortodrone.world;

import java.io.IOException;
import java.lang.management.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.resources.ContentManager;
import org.tortodrone.storage.Database;

/** Opt-in, local Phase 4 measurement of the scene lane, storage writer and thread/GC accounting. */
public final class ConcurrencyMeasurementTest {
    public static void main(String[] args) throws Exception {
        int seconds = args.length == 0 ? 2 : Math.max(1, Math.min(10, Integer.parseInt(args[0])));
        System.out.println("host=" + System.getProperty("os.name") + "/" + System.getProperty("os.arch")
                + " cpus=" + Runtime.getRuntime().availableProcessors()
                + " maxHeapMiB=" + (Runtime.getRuntime().maxMemory() / 1048576)
                + " java=" + System.getProperty("java.runtime.version"));
        var catalog = ContentManager.load(Path.of(".")).scenes();
        var map = catalog.scene(1);
        var simulation = new SceneRegistry.Instance(9_100, 0, map).simulation();
        var members = new ArrayList<SceneMembership>();
        for (int i = 0; i < 8; i++) {
            var actor = new ActorState(
                    100 + i,
                    200 + i,
                    "Measure " + i,
                    256,
                    1,
                    map.spawnX(),
                    map.spawnY(),
                    0,
                    0,
                    0,
                    new StarterCatalog.Model("Fixture", Map.of()));
            members.add(simulation.join(actor, ignored -> {}));
        }
        for (var member : members) member.enter();

        long started = System.nanoTime();
        long deadline = started + seconds * 1_000_000_000L;
        Path home = Files.createTempDirectory("tortodrone-measure-");
        try {
            var database = new Database(home, false);
            int account = database.create(
                    "MeasureOwner", new org.tortodrone.account.Passwords.Protection(new byte[16], new byte[32]), false);
            var owner = database.identity("MeasureOwner");
            // Contention measurement: a real owner-checked write transaction (the preference
            // commit path) runs on the dedicated writer while the scene lane keeps its cadence.
            var contention = new Thread(
                    () -> {
                        try {
                            for (int i = 0; i < 200; i++)
                                database.inspectionHidden(owner.id(), owner.authRevision(), (i & 1) == 0);
                        } catch (IOException failure) {
                            throw new RuntimeException(failure);
                        }
                    },
                    "MeasurementWriter");
            contention.setDaemon(true);
            contention.start();
            while (System.nanoTime() < deadline) Thread.sleep(25);
            contention.join(30_000);
            var tick = simulation.tickMetrics();
            var late = SceneScheduler.shared().lateness();
            for (var member : members) member.close();

            System.out.printf(
                    Locale.ROOT,
                    "scene ticks=%d lockWaitLastUs=%.1f workLastUs=%.1f workMaxUs=%.1f"
                            + " lockWaitP50Us=%.1f lockWaitP99Us=%.1f workP50Us=%.1f workP99Us=%.1f"
                            + " publishP50Us=%.1f publishP99Us=%.1f deliveryP50Us=%.1f deliveryP99Us=%.1f deliveryMaxUs=%.1f"
                            + " cadenceP50Ms=%.1f cadenceP99Ms=%.1f cadenceMaxMs=%.1f overruns=%d%n",
                    tick.ticks(),
                    tick.lockWaitNanos() / 1000.0,
                    tick.workNanos() / 1000.0,
                    tick.maxWorkNanos() / 1000.0,
                    tick.lockWaitP50Nanos() / 1000.0,
                    tick.lockWaitP99Nanos() / 1000.0,
                    tick.workP50Nanos() / 1000.0,
                    tick.workP99Nanos() / 1000.0,
                    tick.publicationP50Nanos() / 1000.0,
                    tick.publicationP99Nanos() / 1000.0,
                    tick.deliveryP50Nanos() / 1000.0,
                    tick.deliveryP99Nanos() / 1000.0,
                    tick.maxDeliveryNanos() / 1000.0,
                    late.p50Millis(),
                    late.p99Millis(),
                    late.maxMillis(),
                    SceneScheduler.shared().overruns());

            long writeStarted = System.nanoTime();
            for (int i = 0; i < 200; i++) database.write(connection -> null);
            long writeNanos = System.nanoTime() - writeStarted;
            long readStarted = System.nanoTime();
            for (int i = 0; i < 200; i++) database.read(connection -> null);
            long readNanos = System.nanoTime() - readStarted;
            var diagnostics = Database.diagnostics();
            var storage = database.storageMetrics();
            long wal =
                    Files.exists(Path.of(database.file() + "-wal")) ? Files.size(Path.of(database.file() + "-wal")) : 0;
            long checkpointStarted = System.nanoTime();
            database.close();
            database.checkpoint();
            long checkpointNanos = System.nanoTime() - checkpointStarted;
            var finalStorage = database.storageMetrics();
            System.out.printf(
                    Locale.ROOT,
                    "storage writes=200 avgUs=%.1f reads=200 avgUs=%.1f operations=%d slow=%d failures=%d rollbacks=%d maxMs=%d durationP50Us=%d durationP99Us=%d walBytes=%d checkpointMs=%.1f busy=%d expiryRuns=%d expiryAvgUs=%.1f checkpoints=%d%n",
                    writeNanos / 1000.0 / 200,
                    readNanos / 1000.0 / 200,
                    diagnostics.operations(),
                    diagnostics.slowOperations(),
                    diagnostics.failures(),
                    diagnostics.rollbacks(),
                    diagnostics.maximumMillis(),
                    finalStorage.durationP50Micros(),
                    finalStorage.durationP99Micros(),
                    wal,
                    checkpointNanos / 1_000_000.0,
                    storage.busyErrors(),
                    storage.expiryRuns(),
                    storage.expiryRuns() == 0 ? 0 : storage.expiryNanos() / 1000.0 / storage.expiryRuns(),
                    finalStorage.checkpoints());
        } finally {
            try (var paths = Files.walk(home)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }

        // Eight-player interior scene: the same simulation path a mission floor uses, with the
        // same fixed cadence. Full client connection load is recorded by LoadBenchmark instead.
        var interiorMap = catalog.scene(380);
        var interior = new SceneRegistry.Instance(9_200, 1, interiorMap).simulation();
        var interiorMembers = new ArrayList<SceneMembership>();
        for (int i = 0; i < 8; i++) {
            var actor = new ActorState(
                    200 + i,
                    300 + i,
                    "Interior " + i,
                    256,
                    1,
                    interiorMap.spawnX(),
                    interiorMap.spawnY(),
                    0,
                    0,
                    0,
                    new StarterCatalog.Model("Fixture", Map.of()));
            interiorMembers.add(interior.join(actor, ignored -> {}));
        }
        for (var member : interiorMembers) member.enter();
        long interiorDeadline = System.nanoTime() + seconds * 1_000_000_000L;
        while (System.nanoTime() < interiorDeadline) Thread.sleep(25);
        var interiorTick = interior.tickMetrics();
        for (var member : interiorMembers) member.close();
        System.out.printf(
                Locale.ROOT,
                "interior scene=%d ticks=%d lockWaitP50Us=%.1f lockWaitP99Us=%.1f workP50Us=%.1f workP99Us=%.1f workMaxUs=%.1f"
                        + " publishP50Us=%.1f publishP99Us=%.1f deliveryP50Us=%.1f deliveryP99Us=%.1f overruns=%d%n",
                interiorMap.id(),
                interiorTick.ticks(),
                interiorTick.lockWaitP50Nanos() / 1000.0,
                interiorTick.lockWaitP99Nanos() / 1000.0,
                interiorTick.workP50Nanos() / 1000.0,
                interiorTick.workP99Nanos() / 1000.0,
                interiorTick.maxWorkNanos() / 1000.0,
                interiorTick.publicationP50Nanos() / 1000.0,
                interiorTick.publicationP99Nanos() / 1000.0,
                interiorTick.deliveryP50Nanos() / 1000.0,
                interiorTick.deliveryP99Nanos() / 1000.0,
                SceneScheduler.shared().overruns());

        long allocated = -1;
        if (ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean memory
                && memory.isThreadAllocatedMemorySupported()) {
            memory.setThreadAllocatedMemoryEnabled(true);
            long before = memory.getThreadAllocatedBytes(Thread.currentThread().threadId());
            for (int i = 0; i < 100; i++) simulation.snapshot();
            allocated = memory.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before;
        }
        var threads = ManagementFactory.getThreadMXBean();
        long gcCount = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(bean -> bean.getCollectionCount())
                .sum();
        long gcMillis = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(bean -> bean.getCollectionTime())
                .sum();
        System.out.printf(
                Locale.ROOT,
                "threads platform=%d peak=%d virtualCarrierParallelism=%s snapshotAllocBytesPerCall=%.0f gcCount=%d gcMs=%d%n",
                threads.getThreadCount(),
                threads.getPeakThreadCount(),
                System.getProperty(
                        "jdk.virtualThreadScheduler.parallelism",
                        String.valueOf(Runtime.getRuntime().availableProcessors())),
                allocated < 0 ? -1 : allocated / 100.0,
                gcCount,
                gcMillis);

        long drainStarted = System.nanoTime();
        SceneScheduler.shared().close();
        System.out.printf(Locale.ROOT, "shutdownDrainMs=%.1f%n", (System.nanoTime() - drainStarted) / 1_000_000.0);
        // The standalone suite keeps one JVM: restore the shared lane pool for later programs.
        SceneScheduler.resetSharedForTests();
        System.out.println("PASS: Phase 4 concurrency measurements recorded on this host");
    }
}
