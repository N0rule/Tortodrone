package org.tortodrone.world;

import static org.tortodrone.support.TestWire.check;

import java.util.*;
import org.tortodrone.core.world.MonsterCatalog;

/**
 * Phase 10: per-scene monster gauges, spawn/despawn counters and bounded tick percentiles.
 */
public final class MonsterMetricsTest {
    private MonsterMetricsTest() {}

    public static void main(String[] args) throws Exception {
        var catalog = MonsterFixture.catalog("fixture_metrics", "Monster/Fixture/Metrics", "Fixture Metrics");
        gauges(catalog);
        tickWindow();

        System.out.println(
                "PASS: monster gauges, by-type population, spawn/despawn balance and bounded tick percentiles");
    }

    private static void gauges(MonsterCatalog catalog) throws Exception {
        var world = MonsterFixture.scene(catalog);
        var empty = world.metrics();
        check(
                empty.total() == 0 && empty.awake() == 0 && empty.engaged() == 0,
                "an empty scene reports no live monsters");
        check(empty.spawned() == 0 && empty.despawned() == 0, "an empty scene has balanced counters");
        check(empty.byType().isEmpty(), "an empty scene has no types");

        world.addWave(new MonsterGroup("metrics_wave", "fixture_metrics", new ScenePathing.Point(0, 0), 3, 3, 0, 0, 1));
        world.advance(1000, List.of());
        var live = world.metrics();
        check(live.total() == 3 && live.spawned() == 3 && live.despawned() == 0, "wave spawns count once each");
        check(live.byType().equals(Map.of("fixture_metrics", 3)), "the live population is grouped by catalogue type");
        check(live.awake() == 0 && live.engaged() == 0, "new wave monsters start dormant");

        MonsterFixture.kill(world, 1050);
        for (int tick = 0; tick < 4; tick++) world.advance(1100 + tick * 50, List.of());
        var cleared = world.metrics();
        check(
                cleared.total() == 0 && cleared.awake() == 0 && cleared.engaged() == 0,
                "dead monsters leave every gauge");
        check(cleared.spawned() == 3 && cleared.despawned() == 3, "spawn and despawn counters balance after cleanup");
        check(cleared.byType().isEmpty(), "the by-type map drops emptied types");
        try {
            cleared.byType().put("forged", 1);
            throw new AssertionError("Metrics exposed mutable population state");
        } catch (UnsupportedOperationException expected) {
        }
    }

    private static void tickWindow() {
        var metrics = new MonsterMetrics();
        for (int sample = 0; sample < 200; sample++) metrics.tick(2_000_000);
        for (int sample = 0; sample < 56; sample++) metrics.tick(6_000_000);
        var snapshot = metrics.snapshot(0, 0, 0, Map.of(), 0, 0);
        check(snapshot.tickP50Millis() == 2.0, "the median ignores the tail");
        check(snapshot.tickP99Millis() == 6.0, "the p99 reports the tail");
        var bounded = metrics.snapshot(0, 0, 0, Map.of(), 0, 0);
        check(bounded.tickP50Millis() == 2.0, "the window does not grow without bound");
        check(
                new MonsterMetrics().snapshot(0, 0, 0, Map.of(), 0, 0).tickP99Millis() == 0,
                "an unsampled scene reports zero instead of a fabricated percentile");
    }
}
