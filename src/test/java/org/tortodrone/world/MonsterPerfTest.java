package org.tortodrone.world;

import java.util.*;
import org.tortodrone.character.StarterCatalog;
import static org.tortodrone.support.TestWire.check;

/**
 * Phase 4 service-level check: 64 dormant monsters far from the party must cost much less
 * than half a millisecond of tick time. The scene clock stays deterministic; only the
 * wall-clock measurement is wall-clock.
 */
public final class MonsterPerfTest {
    private MonsterPerfTest() { }

    public static void main(String[] args) throws Exception {
        var fields = new HashMap<String, String>();
        fields.putAll(Map.of("config", "Fixture/Perf", "kind", "Monster",
            "native-type", "com.threerings.projectx.dungeon.data.actor.Monster",
            "health", "100", "damage-mask", "2", "radius", ".4", "speed", "1"));
        fields.putAll(Map.of("acquire-distance", "30", "acquisition-interval", "0", "attack-distance", "2",
            "attack", "0", "turn-rate", "100",
            "attack.0.land", "0", "attack.0.clear", "100", "attack.0.fire-speed", "1", "attack.0.fires", ""));
        var entries = new ArrayList<SceneContent.Entry>();
        for (int i = 0; i < 64; i++)
            entries.add(new SceneContent.Entry("monster" + i, 1000 + i * 10, 0, 0, new SceneContent.Spec(fields)));
        var world = new SceneEntities(new SceneContent(entries, Map.of()), SceneWeaponsTest.openTerrain());
        var players = new ArrayList<ActorState>();
        for (int i = 0; i < 8; i++)
            players.add(new ActorState(219 + i, 1001 + i, "Perf", 256, 1, i, 0, 0, 0, 0,
                new StarterCatalog.Model("Fixture", Map.of())));

        int stamp = 1000;
        for (int tick = 0; tick < 400; tick++, stamp += 50) world.advance(stamp, players);
        check(world.actors().stream().allMatch(a -> a.entity().kind().equals("Monster")), "all 64 fixture monsters stay live");
        for (var actor : world.actors())
            check(world.band(actor.id()) == SceneMonsterBand.DORMANT, "an out-of-range population stays dormant");

        int measured = 400;
        long start = System.nanoTime();
        for (int tick = 0; tick < measured; tick++, stamp += 50) world.advance(stamp, players);
        long elapsed = System.nanoTime() - start;
        double perTickMillis = elapsed / 1_000_000.0 / measured;
        System.out.printf(Locale.ROOT, "64 dormant monsters: %.4f ms per tick%n", perTickMillis);
        check(perTickMillis < 0.5, "64 dormant monsters stay under the 0.5 ms per-tick budget");

        System.out.println("PASS: 64 dormant monsters remain published while their per-tick AI cost stays under the phase 4 SLO");
    }
}
