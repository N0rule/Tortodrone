package org.tortodrone.server.network;

import java.util.Comparator;
import java.util.function.Predicate;
import org.tortodrone.core.world.InputBits;
import org.tortodrone.core.world.SceneClock;
import org.tortodrone.world.SceneMembership;
import org.tortodrone.world.SceneSimulation;
import org.tortodrone.world.SceneSnapshot;
import static org.tortodrone.support.TestWire.check;

/** Shared Crash Site jelly-wave choreography for the full-session regressions: arm the
 *  arena entrance sensor, wait for the wave, and drive the knight like a live client.
 *  Both jelly tests keep their own assertions; only the session footwork is shared. */
final class JellySceneFixture {
    private JellySceneFixture() { }

    /** Per-snapshot bookkeeping before the helper decides whether to stop. */
    @FunctionalInterface interface Tick {
        void snapshot(SceneSnapshot snapshot) throws Exception;
    }

    /** Enter the wave sensor, wait until a live jelly spawns and stand in the arena. */
    static void armWave(SceneMembership member, SceneSimulation simulation) throws Exception {
        check(member.teleport(-1.5f, -69f, 0), "knight enters the wave sensor");
        long waveEnd = System.nanoTime() + 10_000_000_000L;
        boolean jellySeen = false;
        while (System.nanoTime() < waveEnd && !jellySeen) {
            Thread.sleep(250);
            checkSimulation(simulation, "before jelly contact");
            var snapshot = snapshot(simulation);
            for (var actor : snapshot.actors())
                if (live(actor)) jellySeen = true;
        }
        check(jellySeen, "the jelly wave spawned");
        // The spawner tiles themselves are occupied, so take the nearest open approach.
        boolean placed = false;
        for (float[] spot : new float[][]{{-2f, -55f}, {-6f, -55f}, {0f, -52f}, {-4f, -57f}, {-1f, -50f}})
            if (member.teleport(spot[0], spot[1], 0)) {
                placed = true;
                break;
            }
        check(placed, "knight moved into the jelly wave arena");
    }

    /** Fight like a live client until {@code finished} accepts a snapshot or time runs out. */
    static SceneSnapshot fight(SceneMembership member, SceneSimulation simulation, long durationNanos,
            String phase, Predicate<SceneSnapshot> finished) throws Exception {
        return fight(member, simulation, durationNanos, phase, snapshot -> { }, finished);
    }

    /** The same fight loop with per-snapshot bookkeeping for tests that collect events. */
    static SceneSnapshot fight(SceneMembership member, SceneSimulation simulation, long durationNanos,
            String phase, Tick tick, Predicate<SceneSnapshot> finished) throws Exception {
        long end = System.nanoTime() + durationNanos;
        long lastFrame = 0;
        while (System.nanoTime() < end) {
            Thread.sleep(20);
            checkSimulation(simulation, phase);
            var snapshot = snapshot(simulation);
            tick.snapshot(snapshot);
            if (finished.test(snapshot)) return snapshot;
            if (System.nanoTime() - lastFrame > 100_000_000L) {
                lastFrame = System.nanoTime();
                steer(member, snapshot);
            }
        }
        return null;
    }

    /** Walk at the nearest live monster with the proto sword swinging. */
    private static void steer(SceneMembership member, SceneSnapshot snapshot) {
        var pawn = snapshot.players().stream().filter(p -> p.entity() == null && p.pet() == null).findFirst().orElse(null);
        var target = snapshot.actors().stream()
            .filter(JellySceneFixture::live)
            .min(Comparator.comparingDouble(a -> pawn == null ? 0 : Math.hypot(a.x() - pawn.x(), a.y() - pawn.y()))).orElse(null);
        if (pawn == null || target == null) return;
        float heading = (float) Math.atan2(target.y() - pawn.y(), target.x() - pawn.x());
        member.input(SceneClock.now(), heading, heading, InputBits.MOVING | InputBits.ACTION,
            SceneClock.now(), SceneClock.now(), System.nanoTime() / 1_000_000L);
    }

    static boolean live(org.tortodrone.core.world.ActorState actor) {
        return actor.entity() != null && "Monster".equals(actor.entity().kind())
            && actor.entity().destroyed() == Integer.MAX_VALUE;
    }

    private static SceneSnapshot snapshot(SceneSimulation simulation) {
        synchronized (simulation) {
            return simulation.snapshot();
        }
    }

    private static void checkSimulation(SceneSimulation simulation, String phase) {
        var failure = simulation.failure();
        if (failure != null) {
            failure.printStackTrace();
            throw new AssertionError("Scene simulation crashed " + phase + ": " + failure);
        }
    }
}
