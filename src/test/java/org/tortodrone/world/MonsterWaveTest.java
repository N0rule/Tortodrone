package org.tortodrone.world;

import java.util.*;
import static org.tortodrone.support.TestWire.check;
import org.tortodrone.core.world.FireCatalog;
import org.tortodrone.core.world.SceneStatus;
import org.tortodrone.core.world.MonsterCatalog;

/**
 * Phase 7 wave controller contract: concurrency, total, per-tick spawn cap, generation drop and
 * despawn cleanup. Waves are runtime state and spawn through the catalogue entry point.
 */
public final class MonsterWaveTest {
    private MonsterWaveTest() { }

    public static void main(String[] args) throws Exception {
        var catalog = MonsterFixture.catalog("fixture_wave", "Monster/Fixture/Wave", "Fixture Wave");
        concurrencyAndTotal(catalog);
        spawnRate(catalog);
        generationDrop(catalog);
        refusals(catalog);
        System.out.println("PASS: wave concurrency/total/spawn caps, generation drop and despawn cleanup");
    }

    private static void concurrencyAndTotal(MonsterCatalog catalog) throws Exception {
        var world = MonsterFixture.scene(catalog);
        world.addWave(new MonsterGroup("wave_cap", "fixture_wave", new ScenePathing.Point(0, 0),
            6, 2, 0, 0, "wave_cap_cleared", 1));
        int stamp = 1000;
        world.advance(stamp, List.of());
        check(world.waveSpawned("wave_cap") == 2, "maxAlive bounds the first batch");
        check(world.waveAlive("wave_cap") == 2, "both concurrent slots are alive");
        for (int tick = 0; tick < 80; tick++, stamp += 50) world.advance(stamp, List.of());
        check(world.waveSpawned("wave_cap") == 2, "the wave never exceeds maxAlive while actors live");
        check(world.liveMonsters() == 2, "no extra wave actor was published");

        MonsterFixture.kill(world, stamp);
        for (int tick = 0; tick < 10; tick++, stamp += 50) world.advance(stamp, List.of());
        check(world.waveSpawned("wave_cap") == 4, "the next pair respawns after the delay");
        MonsterFixture.kill(world, stamp);
        stamp += 50;
        world.advance(stamp, List.of());
        for (int tick = 0; tick < 10; tick++, stamp += 50) world.advance(stamp, List.of());
        check(world.waveSpawned("wave_cap") == 6, "the wave reaches its total");
        MonsterFixture.kill(world, stamp);
        stamp += 50;
        world.advance(stamp, List.of());
        check(world.waveAlive("wave_cap") == 0, "all wave actors are down");
        check(world.waveCleared("wave_cap"), "the wave latches cleared after its total");
        check(world.liveMonsters() == 0, "no wave monster survives a completed wave");
    }

    private static void spawnRate(MonsterCatalog catalog) throws Exception {
        var world = MonsterFixture.scene(catalog);
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
        var world = MonsterFixture.scene(catalog);
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
        var world = MonsterFixture.scene(catalog);
        try { world.addWave(new MonsterGroup("wave_unknown", "missing_type", new ScenePathing.Point(0, 0),
            1, 1, 0, 0, 1)); throw new AssertionError("Unknown wave type was accepted"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains("Unknown monster type"), "the refusal names the unknown type"); }
        check(world.actors().isEmpty() && world.waveSpawned("wave_unknown") == 0,
            "a refused wave creates no partial actor");
    }
}
