package org.tortodrone.world;

import java.nio.file.Path;
import java.util.*;
import org.tortodrone.character.StarterCatalog;
import org.tortodrone.resources.ContentManager;
import static org.tortodrone.support.TestWire.check;

/**
 * Phase 6: the grid broadphase must return exactly the entities the linear scan returns, in
 * the same insertion order, on every bundled map. Both worlds receive identical creation
 * stamps, players, ticks and a scripted status pulse; every actor field is compared bit for bit.
 */
public final class SpatialIndexDifferentialTest {
    private SpatialIndexDifferentialTest() { }

    public static void main(String[] args) throws Exception {
        var catalog = ContentManager.load(Path.of(".")).scenes();
        var maps = catalog.scenes();
        check(maps.size() >= 9, "every bundled map participates in the differential (" + maps.size() + ")");
        int compared = 0;
        for (var map : maps) {
            var terrain = map.collision();
            float[] spawn = walkableSpawn(terrain, map.spawnX(), map.spawnY());
            var content = staged(map.content(), spawn);
            var indexed = new SceneEntities(content, terrain, map.staticCollision(), new Random(7319), true, 1000);
            var linear = new SceneEntities(content, terrain, map.staticCollision(), new Random(7319), false, 1000);
            var players = players(spawn, 1000);
            int stamp = 1000;
            for (int tick = 0; tick <= 240; tick++, stamp += 50) {
                indexed.advance(stamp, players);
                linear.advance(stamp, players);
                if (tick % 10 == 0 || tick == 240) {
                    check(digest(indexed).equals(digest(linear)),
                        "indexed scene state matches the linear scan on map " + map.id() + " at tick " + tick);
                    check(indexed.pathSearches() == linear.pathSearches() && indexed.pathQueued() == linear.pathQueued(),
                        "path budget counters match on map " + map.id() + " at tick " + tick);
                }
                if (tick == 60) {
                    var pulse = new SceneStatus.Pulse(new FireCatalog.DataValue(
                        "com.threerings.projectx.dungeon.config.DamageConfig$Normal",
                        Map.of("flags", 2, "amount", 3f)), 2.5f, 128);
                    indexed.statusPulse(0, pulse, spawn[0], spawn[1], stamp);
                    linear.statusPulse(0, pulse, spawn[0], spawn[1], stamp);
                    check(digest(indexed).equals(digest(linear)),
                        "status pulse candidates match on map " + map.id());
                }
                if (tick == 120) players = players(shift(spawn, tick), stamp);
            }
            compared++;
        }
        check(compared == maps.size(), "all maps completed the replay");
        System.out.println("PASS: grid broadphase is bit-identical to the linear scan on " + compared + " bundled maps");
    }

    /** Deterministic dense population around a walkable spawn point. */
    private static SceneContent staged(SceneContent base, float[] spawn) {
        var entries = new ArrayList<>(base.entries());
        var monster = new HashMap<String, String>();
        monster.putAll(Map.of("config", "Fixture/SpatialMonster", "kind", "Monster",
            "native-type", "com.threerings.projectx.dungeon.data.actor.Monster",
            "health", "100", "damage-mask", "2", "radius", ".4", "speed", "2", "collision-mask", "1"));
        monster.putAll(Map.of("acquire-distance", "40", "acquisition-interval", "0", "attack-distance", "2",
            "attack", "0", "turn-rate", "100"));
        for (int i = 0; i < 24; i++) {
            double angle = i * Math.PI * 2 / 24;
            float radius = 2 + (i % 6) * .9f;
            entries.add(new SceneContent.Entry("spatial-monster-" + i,
                spawn[0] + (float) (Math.cos(angle) * radius), spawn[1] + (float) (Math.sin(angle) * radius), 0,
                new SceneContent.Spec(monster)));
        }
        var block = new HashMap<String, String>(Map.of(
            "native-type", "com.threerings.projectx.dungeon.data.actor.Block", "kind", "Block",
            "config", "Fixture/SpatialBlock", "bounds-min-x", "-.6", "bounds-max-x", ".6",
            "bounds-min-y", "-.6", "bounds-max-y", ".6", "collision-flags", "1"));
        for (int i = 0; i < 8; i++) {
            double angle = (i + .5) * Math.PI * 2 / 8;
            float radius = 1 + (i % 4) * 1.3f;
            entries.add(new SceneContent.Entry("spatial-block-" + i,
                spawn[0] + (float) (Math.cos(angle) * radius), spawn[1] + (float) (Math.sin(angle) * radius), 0,
                new SceneContent.Spec(block)));
        }
        // Native cosmetic Global blocks carry sentinel bounds that are not geometry. Their
        // infinite hypot must never become an index extent or the whole scene fails to load.
        var global = new HashMap<String, String>(Map.ofEntries(
            Map.entry("native-type", "com.threerings.projectx.dungeon.data.actor.Block"),
            Map.entry("kind", "Block"), Map.entry("config", "Fixture/GlobalMusic"),
            Map.entry("shape", "Global"),
            Map.entry("bounds-min-x", "-3.4028235E38"), Map.entry("bounds-max-x", "3.4028235E38"),
            Map.entry("bounds-min-y", "-3.4028235E38"), Map.entry("bounds-max-y", "3.4028235E38"),
            Map.entry("collision-flags", "0"), Map.entry("collision-mask", "0"),
            Map.entry("tags", "music")));
        entries.add(new SceneContent.Entry("spatial-global", spawn[0], spawn[1], 0, new SceneContent.Spec(global)));
        return base.withEntries(entries);
    }

    private static List<ActorState> players(float[] center, int stamp) {
        var players = new ArrayList<ActorState>();
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI * 2 / 8;
            players.add(new ActorState(219 + i, 1001 + i, "Spatial", 256, 1000,
                center[0] + (float) (Math.cos(angle) * .7), center[1] + (float) (Math.sin(angle) * .7),
                0, 0, 0, 0, stamp, new StarterCatalog.Model("Fixture", Map.of()), false));
        }
        return players;
    }

    private static float[] shift(float[] point, int tick) {
        double angle = tick * Math.PI / 180;
        return new float[]{point[0] + (float) (Math.cos(angle) * .5), point[1] + (float) (Math.sin(angle) * .5)};
    }

    private static float[] walkableSpawn(CollisionMap terrain, float x, float y) {
        if (terrain.walkable(x, y)) return new float[]{x, y};
        for (int ring = 1; ring <= 20; ring++)
            for (int i = 0; i < 16; i++) {
                double angle = i * Math.PI * 2 / 16;
                float px = x + (float) (Math.cos(angle) * ring), py = y + (float) (Math.sin(angle) * ring);
                if (terrain.walkable(px, py)) return new float[]{px, py};
            }
        return new float[]{x, y};
    }

    /** Every field the protocol can observe, with floats compared by their exact bits. */
    private static List<String> digest(SceneEntities world) {
        var lines = new ArrayList<String>();
        for (var actor : world.actors()) {
            var entity = actor.entity();
            lines.add(actor.id() + "|" + bits(actor.x()) + "|" + bits(actor.y()) + "|" + bits(actor.rotation()) + "|"
                + bits(actor.direction()) + "|" + actor.flags() + "|" + actor.activity() + "|" + actor.activityStarted() + "|"
                + (entity == null ? "-" : entity.kind() + "|" + entity.destroyed() + "|" + bits(entity.health()) + "|"
                    + entity.state() + "|" + entity.stateEntered() + "|" + entity.count() + "|" + entity.hitTime() + "|"
                    + entity.status() + "|" + entity.affectedType() + "|" + entity.affectedTime()));
        }
        return lines;
    }

    private static int bits(float value) { return Float.floatToIntBits(value); }
}
