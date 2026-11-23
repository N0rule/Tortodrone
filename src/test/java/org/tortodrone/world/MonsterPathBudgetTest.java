package org.tortodrone.world;

import static org.tortodrone.support.TestWire.check;

import java.util.*;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.core.world.SceneContent;

/**
 * Phase 5: one scene admits at most four A* searches per tick inside a shared wall-clock
 * slice, keeps a bounded waiting queue and drops its oldest deferred request when a burst
 * exceeds the queue depth.
 */
public final class MonsterPathBudgetTest {
    private MonsterPathBudgetTest() {}

    public static void main(String[] args) throws Exception {
        var budget = new ScenePathBudget();
        budget.begin(100);
        for (int id = 0; id < ScenePathBudget.PER_TICK; id++)
            check(budget.acquire(id), "the first four searches in a tick are admitted");
        check(budget.granted() == ScenePathBudget.PER_TICK, "the tick reports exactly its admitted searches");
        check(!budget.acquire(999), "a fifth search is deferred");
        check(budget.waiting(999), "the deferred id is queued");
        check(budget.granted() == ScenePathBudget.PER_TICK, "deferral does not consume a token");

        budget.begin(100);
        check(budget.granted() == ScenePathBudget.PER_TICK, "the same stamp keeps its spent budget");
        budget.begin(150);
        check(budget.granted() == 0 && budget.queued() == 0, "a new tick resets the budget and burst queue");

        // A blocked search costs milliseconds. Searches admitted on one tick share a
        // wall-clock slice so a synchronized repath burst cannot spend the whole cadence.
        long[] nanos = {0};
        var sliced = new ScenePathBudget(() -> nanos[0]);
        sliced.begin(100);
        check(sliced.acquire(1), "the first search of a tick is always admitted");
        nanos[0] = ScenePathBudget.SLICE_MILLIS * 1_000_000L - 1;
        check(sliced.acquire(2), "a search inside the tick slice is admitted");
        nanos[0] = ScenePathBudget.SLICE_MILLIS * 1_000_000L;
        check(!sliced.acquire(3), "a search past the tick slice is deferred even below the count limit");
        check(sliced.granted() == 2, "a deferred over-slice search does not consume a token");
        check(sliced.waiting(3), "the over-slice request retries on a later tick");
        sliced.begin(150);
        check(sliced.acquire(4), "the next tick opens a fresh slice");

        for (int id = 0; id < ScenePathBudget.PER_TICK; id++) budget.acquire(id);
        for (int id = 100; id < 100 + ScenePathBudget.DEPTH + 5; id++) budget.acquire(id);
        check(budget.queued() == ScenePathBudget.DEPTH, "the waiting queue is bounded");
        check(budget.dropped() == 5, "overflow is counted");
        check(!budget.waiting(100), "overflow drops the oldest deferred request");
        check(budget.waiting(100 + ScenePathBudget.DEPTH + 4), "the newest deferred request is retained");

        // Scene-level: eight blocked pursuers all want a path on the same tick.
        var fields = new HashMap<String, String>();
        fields.putAll(Map.of(
                "config",
                "Fixture/PathBudget",
                "kind",
                "Monster",
                "native-type",
                "com.threerings.projectx.dungeon.data.actor.Monster",
                "health",
                "100",
                "damage-mask",
                "2",
                "radius",
                ".4",
                "speed",
                "2",
                "collision-mask",
                "1"));
        fields.putAll(Map.of(
                "acquire-distance",
                "40",
                "acquisition-interval",
                "0",
                "attack-distance",
                "2",
                "attack",
                "0",
                "turn-rate",
                "100"));
        var entries = new ArrayList<SceneContent.Entry>();
        for (int i = 0; i < 8; i++)
            entries.add(new SceneContent.Entry("monster" + i, i - 4, 20, 0, new SceneContent.Spec(fields)));
        entries.add(new SceneContent.Entry(
                "wall",
                0,
                4,
                0,
                new SceneContent.Spec(Map.of(
                        "native-type",
                        "com.threerings.projectx.dungeon.data.actor.Block",
                        "kind",
                        "Block",
                        "config",
                        "Fixture/Wall",
                        "radius",
                        "3",
                        "collision-flags",
                        "1"))));
        var world = new SceneEntities(new SceneContent(entries, Map.of()), SceneWeaponsTest.openTerrain());
        var player = new ActorState(
                219, 1001, "Fixture", 256, 1, 0, 0, 0, 0, 0, new StarterCatalog.Model("Fixture", Map.of()));

        int stamp = 1000;
        world.advance(stamp, List.of(player));
        check(
                world.pathSearches() >= 1 && world.pathSearches() <= ScenePathBudget.PER_TICK,
                "a burst is clipped to the tick's admission slice (" + world.pathSearches() + " searches)");
        check(world.pathQueued() == 8 - world.pathSearches(), "the denied pursuers wait in the scene queue");
        var firstMetrics = world.metrics();
        check(
                firstMetrics.pathSearches() == world.pathSearches() && firstMetrics.pathQueued() == world.pathQueued(),
                "scene metrics expose the same admitted and queued searches");
        var before = new HashMap<Integer, float[]>();
        for (var actor : world.actors())
            if (actor.entity().kind().equals("Monster")) before.put(actor.id(), new float[] {actor.x(), actor.y()});
        int seen = 0;
        for (int tick = 0; tick < 6; tick++) {
            world.advance(stamp += 50, List.of(player));
            check(world.pathSearches() <= ScenePathBudget.PER_TICK, "no tick exceeds the path budget");
            seen += world.pathSearches();
        }
        check(seen > 0, "deferred pursuers retry on later ticks");
        check(
                world.actors().stream()
                        .filter(a -> a.entity().kind().equals("Monster"))
                        .anyMatch(a -> {
                            var origin = before.get(a.id());
                            return origin != null && (a.x() != origin[0] || a.y() != origin[1]);
                        }),
                "a budgeted pursuer still starts moving");

        System.out.println(
                "PASS: per-tick path admission, bounded burst queue with oldest-drop, and deferred retry across ticks");
    }
}
