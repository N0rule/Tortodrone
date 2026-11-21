package org.tortodrone.world;

import java.util.*;
import org.tortodrone.core.character.StarterCatalog;
import static org.tortodrone.support.TestWire.check;

/**
 * Phase 10 soak: keep a full wave population and eight observers running for thirty
 * simulated minutes, then prove the policy caps held, counters balanced and every actor
 * left with the generation that owned it.
 */
public final class MonsterSoakTest {
    private static final int TICKS = 36_000;

    private MonsterSoakTest() { }

    public static void main(String[] args) throws Exception {
        var world = scene();
        var anchors = new ArrayList<ScenePathing.Point>();
        for (int index = 0; index < 8; index++)
            anchors.add(new ScenePathing.Point(12 * (float) Math.cos(index * Math.PI / 4),
                12 * (float) Math.sin(index * Math.PI / 4)));
        world.addWave(new MonsterGroup("soak", "fixture_soak", anchors, 64, 64, 0, 0, 1));
        var players = players();

        int stamp = 1000, peakAwake = 0, peakEngaged = 0, peakPath = 0;
        long started = System.nanoTime();
        for (int tick = 0; tick < TICKS; tick++) {
            world.advance(stamp += 50, players);
            var metrics = world.metrics();
            check(metrics.total() <= SceneEntities.MAX_ALIVE_MONSTERS, "the live population stays inside its cap");
            check(metrics.engaged() <= SceneEntities.MAX_ENGAGED_MONSTERS, "the engaged population stays inside its cap");
            check(world.pathSearches() <= ScenePathBudget.PER_TICK, "no tick exceeds the path budget");
            peakAwake = Math.max(peakAwake, metrics.awake());
            peakEngaged = Math.max(peakEngaged, metrics.engaged());
            peakPath = Math.max(peakPath, world.pathSearches());
        }
        long elapsed = System.nanoTime() - started;

        var metrics = world.metrics();
        check(metrics.total() == SceneEntities.MAX_ALIVE_MONSTERS, "all sixty-four wave slots stayed live");
        check(metrics.spawned() == SceneEntities.MAX_ALIVE_MONSTERS && metrics.despawned() == 0,
            "no wave actor was silently replaced during the run");
        check(peakAwake > 0 && peakEngaged > 0, "observers kept the population awake and engaged");
        check(metrics.byType().equals(Map.of("fixture_soak", 64)), "every live actor still reports its owning type");
        check(metrics.pathDropped() == 0 && metrics.pathFailures() == 0,
            "open terrain needed neither deferred admissions nor failed searches");

        world.generation(2);
        var drained = world.metrics();
        check(drained.total() == 0 && drained.awake() == 0 && drained.engaged() == 0,
            "a generation change clears every live monster");
        check(drained.spawned() == 64 && drained.despawned() == 64,
            "spawn and despawn counters balance after the drain");
        check(world.actors().stream().noneMatch(a -> a.entity().kind().equals("Monster")),
            "no monster actor survives the drain");

        double ticksPerSecond = TICKS / Math.max(1e-9, elapsed / 1_000_000_000d);
        System.out.printf(java.util.Locale.ROOT,
            "PASS: 30-minute %.1fs soak, %d peak awake, %d peak engaged, %d peak path/tick, %.0f ticks/s%n",
            elapsed / 1_000_000_000d, peakAwake, peakEngaged, peakPath, ticksPerSecond);
    }

    private static SceneEntities scene() throws Exception {
        return new SceneEntities(SceneContent.empty(), TestCollision.floor(-64, -64, 128, 128),
            SceneCollision.empty(), new Random(7), true, 1000, catalog());
    }

    private static List<ActorState> players() {
        var players = new ArrayList<ActorState>();
        for (int index = 0; index < 8; index++) {
            float angle = (float) (index * Math.PI / 4);
            players.add(new ActorState(1000 + index, 2000 + index, "Soak " + index, 256, 1,
                8 * (float) Math.cos(angle), 8 * (float) Math.sin(angle), 0, 0, 0,
                new StarterCatalog.Model("Fixture", Map.of())));
        }
        return players;
    }

    private static MonsterCatalog catalog() throws Exception {
        return MonsterCatalog.parse("""
            {
              "format": 1,
              "generatedFrom": {"actorSha256": "%s", "descriptionSha256": "%s"},
              "types": {
                "fixture_soak": {
                  "config": "Monster/Fixture/Soak",
                  "display": "Fixture Soak",
                  "family": "Gremlin",
                  "archetype": "melee-chaser",
                  "supported": true,
                  "capabilities": [],
                  "unsupported": [],
                  "actor": "Character/NPC/Monster/Fixture/Soak",
                  "configs": ["Character/NPC/Monster/Fixture/Soak"],
                  "spec": {
                    "config": "Character/NPC/Monster/Fixture/Soak",
                    "kind": "Monster",
                    "native-type": "com.threerings.projectx.dungeon.data.actor.Monster",
                    "health": "100",
                    "radius": ".4",
                    "damage-mask": "2",
                    "collision-mask": "1",
                    "collision-flags": "8",
                    "speed": "2",
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
