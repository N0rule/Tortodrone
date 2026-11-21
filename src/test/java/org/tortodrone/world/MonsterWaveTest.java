package org.tortodrone.world;

import java.util.*;
import static org.tortodrone.support.TestWire.check;
import org.tortodrone.core.world.FireCatalog;

/**
 * Phase 7 wave controller contract: concurrency, total, per-tick spawn cap, generation drop and
 * despawn cleanup. Waves are runtime state and spawn through the catalogue entry point.
 */
public final class MonsterWaveTest {
    private MonsterWaveTest() { }

    public static void main(String[] args) throws Exception {
        var catalog = catalog();
        concurrencyAndTotal(catalog);
        spawnRate(catalog);
        generationDrop(catalog);
        refusals(catalog);
        System.out.println("PASS: wave concurrency/total/spawn caps, generation drop and despawn cleanup");
    }

    private static void concurrencyAndTotal(MonsterCatalog catalog) throws Exception {
        var world = scene(catalog);
        world.addWave(new MonsterGroup("wave_cap", "fixture_wave", new ScenePathing.Point(0, 0),
            6, 2, 0, 0, "wave_cap_cleared", 1));
        int stamp = 1000;
        world.advance(stamp, List.of());
        check(world.waveSpawned("wave_cap") == 2, "maxAlive bounds the first batch");
        check(world.waveAlive("wave_cap") == 2, "both concurrent slots are alive");
        for (int tick = 0; tick < 80; tick++, stamp += 50) world.advance(stamp, List.of());
        check(world.waveSpawned("wave_cap") == 2, "the wave never exceeds maxAlive while actors live");
        check(world.liveMonsters() == 2, "no extra wave actor was published");

        kill(world, stamp);
        for (int tick = 0; tick < 10; tick++, stamp += 50) world.advance(stamp, List.of());
        check(world.waveSpawned("wave_cap") == 4, "the next pair respawns after the delay");
        kill(world, stamp);
        stamp += 50;
        world.advance(stamp, List.of());
        for (int tick = 0; tick < 10; tick++, stamp += 50) world.advance(stamp, List.of());
        check(world.waveSpawned("wave_cap") == 6, "the wave reaches its total");
        kill(world, stamp);
        stamp += 50;
        world.advance(stamp, List.of());
        check(world.waveAlive("wave_cap") == 0, "all wave actors are down");
        check(world.waveCleared("wave_cap"), "the wave latches cleared after its total");
        check(world.liveMonsters() == 0, "no wave monster survives a completed wave");
    }

    private static void spawnRate(MonsterCatalog catalog) throws Exception {
        var world = scene(catalog);
        world.addWave(new MonsterGroup("wave_burst", "fixture_wave", new ScenePathing.Point(0, 0),
            10, 10, 0, 0, 1));
        int stamp = 1000;
        world.advance(stamp, List.of());
        check(world.waveSpawned("wave_burst") == SceneEntities.MAX_WAVE_SPAWNS_PER_TICK,
            "one tick admits at most four wave spawns");
        stamp += 50;
        world.advance(stamp, List.of());
        check(world.waveSpawned("wave_burst") == 8, "the second tick continues the burst");
        stamp += 50;
        world.advance(stamp, List.of());
        check(world.waveSpawned("wave_burst") == 10, "the burst finishes without exceeding total");
    }

    private static void generationDrop(MonsterCatalog catalog) throws Exception {
        var world = scene(catalog);
        world.addWave(new MonsterGroup("wave_old", "fixture_wave", new ScenePathing.Point(0, 0),
            2, 2, 0, 0, 1));
        int stamp = 1000;
        world.advance(stamp, List.of());
        check(world.waveAlive("wave_old") == 2, "the old generation ran its first batch");
        var id = world.actors().stream().filter(a -> a.entity() != null && "Monster".equals(a.entity().kind()))
            .findFirst().orElseThrow().id();
        var pulse = new SceneStatus.Pulse(new FireCatalog.DataValue(
            "com.threerings.projectx.dungeon.config.DamageConfig$Normal",
            Map.of("flags", 2, "amount", 40f)), 50f, 128);
        world.statusPulse(0, pulse, 0, 0, stamp);

        world.generation(2);
        check(world.generation() == 2, "the scene generation moved forward");
        check(world.waveSpawned("wave_old") == 0, "a stale wave is dropped");
        check(world.actor(id) == null, "its actors are removed without a death window");
        stamp += 50;
        world.advance(stamp, List.of());
        check(world.actors().isEmpty(), "no orphaned actor returns after the generation change");
        world.statusPulse(0, pulse, 0, 0, stamp);
        world.advance(stamp + 50, List.of());
        check(world.actors().isEmpty(), "a later pulse cannot resurrect a despawned wave actor");

        world.addWave(new MonsterGroup("wave_new", "fixture_wave", new ScenePathing.Point(0, 0),
            1, 1, 0, 0, 2));
        world.advance(stamp + 100, List.of());
        check(world.waveAlive("wave_new") == 1, "the new generation spawns normally");
        try { world.addWave(new MonsterGroup("wave_stale", "fixture_wave", new ScenePathing.Point(0, 0),
            1, 1, 0, 0, 1)); throw new AssertionError("A stale group was accepted"); }
        catch (IllegalArgumentException expected) { }
    }

    private static void refusals(MonsterCatalog catalog) throws Exception {
        var world = scene(catalog);
        try { world.addWave(new MonsterGroup("wave_unknown", "missing_type", new ScenePathing.Point(0, 0),
            1, 1, 0, 0, 1)); throw new AssertionError("Unknown wave type was accepted"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains("Unknown monster type"), "the refusal names the unknown type"); }
        check(world.actors().isEmpty() && world.waveSpawned("wave_unknown") == 0,
            "a refused wave creates no partial actor");
    }

    private static SceneEntities scene(MonsterCatalog catalog) throws Exception {
        return new SceneEntities(SceneContent.empty(), TestCollision.floor(-32, -32, 64, 64),
            SceneCollision.empty(), new Random(7), true, 1000, catalog);
    }

    private static void kill(SceneEntities world, int stamp) {
        var pulse = new SceneStatus.Pulse(new FireCatalog.DataValue(
            "com.threerings.projectx.dungeon.config.DamageConfig$Normal",
            Map.of("flags", 2, "amount", 1000f)), 100f, 128);
        world.statusPulse(0, pulse, 0, 0, stamp);
    }

    private static MonsterCatalog catalog() throws Exception {
        return MonsterCatalog.parse("""
            {
              "format": 1,
              "generatedFrom": {"actorSha256": "%s", "descriptionSha256": "%s"},
              "types": {
                "fixture_wave": {
                  "config": "Monster/Fixture/Wave",
                  "display": "Fixture Wave",
                  "family": "Gremlin",
                  "archetype": "melee-chaser",
                  "supported": true,
                  "capabilities": [],
                  "unsupported": [],
                  "actor": "Character/NPC/Monster/Fixture/Wave",
                  "configs": ["Character/NPC/Monster/Fixture/Wave"],
                  "spec": {
                    "config": "Character/NPC/Monster/Fixture/Wave",
                    "kind": "Monster",
                    "native-type": "com.threerings.projectx.dungeon.data.actor.Monster",
                    "health": "100",
                    "radius": ".4",
                    "damage-mask": "2",
                    "collision-mask": "1",
                    "collision-flags": "8",
                    "speed": "4",
                    "acquire-distance": "40",
                    "acquisition-interval": "0",
                    "attack-distance": "1",
                    "attack": "0",
                    "turn-rate": "100"
                  }
                }
              }
            }
            """.formatted("1".repeat(64), "2".repeat(64)));
    }
}
