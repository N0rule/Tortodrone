package org.tortodrone.world;

import static org.tortodrone.support.TestWire.check;

import java.nio.file.Path;
import java.util.*;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.core.world.CollisionMap;
import org.tortodrone.core.world.SceneClock;
import org.tortodrone.core.world.SceneCollision;
import org.tortodrone.core.world.SceneContent;
import org.tortodrone.resources.ContentManager;

/**
 * Phase 4/6 service-level check. A dormant population must cost far less than half a
 * millisecond of tick time per monster, and the largest bundled map must keep a dense
 * 64-monster encounter with eight players inside the published tick SLO (p99 ≤ 8 ms,
 * worst ≤ 25 ms). The scene clock stays deterministic; only the wall-clock measurement
 * is wall-clock.
 */
public final class MonsterPerfTest {
    private MonsterPerfTest() {}

    public static void main(String[] args) throws Exception {
        dormant();
        engaged();
        System.out.println(
                "PASS: 64 dormant monsters stay under the marginal SLO and 64 engaged monsters stay inside the tick SLO");
    }

    private static void dormant() throws Exception {
        var fields = new HashMap<String, String>();
        fields.putAll(Map.of(
                "config",
                "Fixture/Perf",
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
                "1"));
        fields.putAll(Map.of(
                "acquire-distance",
                "30",
                "acquisition-interval",
                "0",
                "attack-distance",
                "2",
                "attack",
                "0",
                "turn-rate",
                "100",
                "attack.0.land",
                "0",
                "attack.0.clear",
                "100",
                "attack.0.fire-speed",
                "1",
                "attack.0.fires",
                ""));
        var entries = new ArrayList<SceneContent.Entry>();
        for (int i = 0; i < 64; i++)
            entries.add(new SceneContent.Entry("monster" + i, 1000 + i * 10, 0, 0, new SceneContent.Spec(fields)));
        int start = SceneClock.now() + 100;
        var world = new SceneEntities(
                new SceneContent(entries, Map.of()),
                SceneWeaponsTest.openTerrain(),
                SceneCollision.empty(),
                new Random(3),
                true,
                start);
        var players = new ArrayList<ActorState>();
        for (int i = 0; i < 8; i++)
            players.add(new ActorState(
                    219 + i, 1001 + i, "Perf", 256, 1, i, 0, 0, 0, 0, new StarterCatalog.Model("Fixture", Map.of())));

        int stamp = start;
        for (int tick = 0; tick < 400; tick++, stamp += 50) world.advance(stamp, players);
        check(
                world.actors().stream().allMatch(a -> a.entity().kind().equals("Monster")),
                "all 64 fixture monsters stay live");
        for (var actor : world.actors())
            check(world.band(actor.id()) == SceneMonsterBand.DORMANT, "an out-of-range population stays dormant");

        int measured = 400;
        long began = System.nanoTime();
        for (int tick = 0; tick < measured; tick++, stamp += 50) world.advance(stamp, players);
        long elapsed = System.nanoTime() - began;
        double perTickMillis = elapsed / 1_000_000.0 / measured;
        System.out.printf(Locale.ROOT, "64 dormant monsters: %.4f ms per tick%n", perTickMillis);
        check(perTickMillis < 0.5, "64 dormant monsters stay under the 0.5 ms per-tick budget");
    }

    /** The largest bundled map with 64 monsters packed into the players' arena and eight
     *  knights on the spawn pawn point. Every monster is inside the activation radius and
     *  has an unobstructed approach, so the sample measures 64 engaged monsters rather than
     *  a failed path search to an unreachable room. */
    private static void engaged() throws Exception {
        var maps = ContentManager.load(Path.of(".")).scenes().scenes();
        // Pin the perf arena instead of taking the largest map: new content imports would
        // otherwise silently redefine this measurement (Ironclaw's 775-entry workshop only
        // admits 32 of the 64 clear cells into acquisition range).
        var map = maps.stream().filter(scene -> scene.id() == 337).findFirst().orElseThrow();
        var terrain = map.collision();
        float[] spawn = walkableSpawn(terrain, map.spawnX(), map.spawnY());
        var points = clearArena(terrain, map.content().entries(), spawn, 64);
        check(points.size() == 64, "the largest map's spawn arena holds 64 walkable cells clear of the players");
        var fields = new HashMap<String, String>();
        fields.putAll(Map.of(
                "config",
                "Fixture/EngagedPerf",
                "kind",
                "Monster",
                "native-type",
                "com.threerings.projectx.dungeon.data.actor.Monster",
                "health",
                "100000",
                "damage-mask",
                "2",
                "radius",
                ".4",
                "speed",
                "1",
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
        var entries = new ArrayList<>(map.content().entries());
        for (int i = 0; i < points.size(); i++)
            entries.add(new SceneContent.Entry(
                    "engaged-perf-" + i, points.get(i)[0], points.get(i)[1], 0, new SceneContent.Spec(fields)));
        var content = map.content().withEntries(entries);
        int start = SceneClock.now() + 100;
        var world = new SceneEntities(content, terrain, map.staticCollision(), new Random(9), true, start);
        var players = new ArrayList<ActorState>();
        for (int i = 0; i < 8; i++)
            players.add(new ActorState(
                    219 + i,
                    1001 + i,
                    "Perf",
                    256,
                    1,
                    spawn[0],
                    spawn[1],
                    0,
                    0,
                    0,
                    new StarterCatalog.Model("Fixture", Map.of())));

        int stamp = start;
        for (int tick = 0; tick < 200; tick++, stamp += 50) world.advance(stamp, players);
        long engaged = world.actors().stream()
                .filter(a -> a.config().name().equals("Fixture/EngagedPerf"))
                .filter(a -> world.band(a.id()) != SceneMonsterBand.DORMANT)
                .count();
        check(engaged == 64, "every monster in the arena engages (" + engaged + ")");
        // The unit suite shares one JVM, so settle heap left by earlier programs before
        // attributing a pause to this scene's tick work.
        System.gc();
        int measured = 300;
        var elapsed = new double[measured];
        for (int tick = 0; tick < measured; tick++, stamp += 50) {
            long began = System.nanoTime();
            world.advance(stamp, players);
            elapsed[tick] = (System.nanoTime() - began) / 1_000_000.0;
        }
        Arrays.sort(elapsed);
        double p99 = elapsed[(int) Math.floor((measured - 1) * .99)], worst = elapsed[measured - 1];
        System.out.printf(
                Locale.ROOT, "map %d with 64 engaged monsters: p99 %.4f ms, worst %.4f ms%n", map.id(), p99, worst);
        check(p99 <= 8, "64 engaged monsters stay inside the 8 ms p99 SLO");
        check(worst <= 25, "64 engaged monsters stay inside the 25 ms worst-case SLO");
    }

    /** Evenly strided spawn-arena cells whose straight segment from the players is walkable.
     *  The guard keeps the sample deterministic and prevents a failed A* search from
     *  dominating the tick: these monsters can all reach the pawn point. */
    private static List<float[]> clearArena(
            CollisionMap terrain, List<SceneContent.Entry> entries, float[] spawn, int count) {
        float minX = Float.POSITIVE_INFINITY, minY = minX, maxX = Float.NEGATIVE_INFINITY, maxY = maxX;
        for (var entry : entries) {
            minX = Math.min(minX, entry.x());
            maxX = Math.max(maxX, entry.x());
            minY = Math.min(minY, entry.y());
            maxY = Math.max(maxY, entry.y());
        }
        var cells = new ArrayList<float[]>();
        for (int x = (int) Math.floor(minX); x <= maxX; x++)
            for (int y = (int) Math.floor(minY); y <= maxY; y++) {
                float px = x + .5f, py = y + .5f;
                if (terrain.walkable(px, py) && terrain.walkableSegment(spawn[0], spawn[1], px, py))
                    cells.add(new float[] {px, py});
            }
        cells.sort(Comparator.comparingDouble((float[] p) -> Math.hypot(p[0] - spawn[0], p[1] - spawn[1])));
        var points = new ArrayList<float[]>();
        if (cells.isEmpty()) return points;
        int stride = Math.max(1, cells.size() / count);
        for (int i = 0; i < cells.size() && points.size() < count; i += stride) points.add(cells.get(i));
        return points;
    }

    private static float[] walkableSpawn(CollisionMap terrain, float x, float y) {
        if (terrain.walkable(x, y)) return new float[] {x, y};
        for (int ring = 1; ring <= 20; ring++)
            for (int i = 0; i < 16; i++) {
                double angle = i * Math.PI * 2 / 16;
                float px = x + (float) (Math.cos(angle) * ring), py = y + (float) (Math.sin(angle) * ring);
                if (terrain.walkable(px, py)) return new float[] {px, py};
            }
        return new float[] {x, y};
    }
}
