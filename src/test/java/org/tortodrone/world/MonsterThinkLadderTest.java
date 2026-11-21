package org.tortodrone.world;

import java.util.*;
import org.tortodrone.core.character.StarterCatalog;
import static org.tortodrone.support.TestWire.check;
import org.tortodrone.core.world.FireCatalog;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.core.world.SceneContent;

/**
 * Phase 4: a monster wakes only when a player enters its activation radius (or it is hit),
 * and the scheduling intervals are deterministic and staggered.
 */
public final class MonsterThinkLadderTest {
    private MonsterThinkLadderTest() { }

    public static void main(String[] args) throws Exception {
        var fields = new HashMap<String, String>();
        fields.putAll(Map.of("config", "Fixture/Ladder", "kind", "Monster",
            "native-type", "com.threerings.projectx.dungeon.data.actor.Monster",
            "health", "10", "damage-mask", "2", "radius", ".4", "speed", "1"));
        fields.putAll(Map.of("acquire-distance", "2", "acquisition-interval", "0", "attack-distance", "2",
            "attack", "0", "turn-rate", "100", "activation-radius", "5"));
        fields.putAll(Map.of("attack.0.land", "0", "attack.0.clear", "100",
            "attack.0.fire-speed", "1", "attack.0.fires", ""));
        var world = scene(fields);
        int id = world.actors().getFirst().id();
        var clock = new int[]{1000};

        world.advance(clock[0], List.of());
        check(world.band(id) == SceneMonsterBand.DORMANT, "no player in range leaves the monster dormant");

        var player = player(3, 0);
        world.advance(clock[0] += 50, List.of(player));
        check(world.band(id) == SceneMonsterBand.ALERT, "a player inside the activation radius wakes the monster for acquisition");

        // Acquisition itself still honours the native acquire distance.
        player = player(1, 0);
        world.advance(clock[0] += 50, List.of(player));
        check(world.band(id) == SceneMonsterBand.ALERT, "alert reacquisition waits for its think cadence");
        world.advance(clock[0] += 200, List.of(player));
        check(world.band(id) == SceneMonsterBand.ENGAGED, "a target in range promotes the monster to engaged");

        // Leaving every radius returns it to dormancy after the owned attack finishes and
        // drops the stale target publication.
        for (int tick = 0; tick < 10 && world.band(id) != SceneMonsterBand.DORMANT; tick++)
            world.advance(clock[0] += 50, List.of(player(100, 0)));
        check(world.band(id) == SceneMonsterBand.DORMANT, "a player outside the radius lets the monster sleep");
        check((world.actor(id).flags() & 16) == 0, "sleeping clears the target flag");

        // Damage is authoritative and wakes a dormant monster even with no nearby player.
        world.damage(damage(3), 0, 0, 0, clock[0] += 50, 219);
        check(world.band(id) == SceneMonsterBand.ENGAGED, "damage forces the dormant monster into the engaged band");
        world.advance(clock[0] += 50, List.of());
        check(world.band(id) == SceneMonsterBand.ENGAGED, "the damage wake-up covers the following ticks");
        world.advance(clock[0] + SceneEntities.DAMAGE_ENGAGE_MILLIS + 50, List.of());
        check(world.band(id) == SceneMonsterBand.DORMANT, "the damage wake-up expires back into dormancy");

        // Stable ids give stable, staggered intervals inside the documented bands.
        for (int entity = 1; entity <= 32; entity++) {
            int interval = SceneMonsterBand.DORMANT.thinkInterval(entity * 1_000_000_007);
            check(interval >= 1000 && interval < 2000, "dormant intervals stay inside one to two seconds");
        }
        check(SceneMonsterBand.ALERT.thinkInterval(7) == 250 && SceneMonsterBand.ENGAGED.thinkInterval(7) == 50,
            "alert and engaged intervals are fixed");
        check(SceneMonsterBand.DORMANT.thinkInterval(1) != SceneMonsterBand.DORMANT.thinkInterval(2),
            "dormant wake-ups are staggered by entity id");

        // Two identical worlds replaying the same players and ticks stay identical.
        var first = scene(fields);
        var second = scene(fields);
        int firstId = first.actors().getFirst().id(), secondId = second.actors().getFirst().id();
        int stamp = 5000;
        for (int step = 0; step < 80; step++, stamp += 50) {
            var occupants = step < 20 ? List.<ActorState>of() : List.of(player(1 + step * .02f, 0));
            first.advance(stamp, occupants);
            second.advance(stamp, occupants);
            check(first.band(firstId) == second.band(secondId), "band decisions are deterministic");
            check(first.actor(firstId).x() == second.actor(secondId).x()
                && first.actor(firstId).y() == second.actor(secondId).y(), "movement integration is deterministic");
        }
        check(first.band(firstId) == second.band(secondId) && first.band(firstId) != SceneMonsterBand.DORMANT,
            "the replay ends awake in both worlds");

        System.out.println("PASS: dormant/alert/engaged transitions, deterministic staggering, immediate damage wake-up and deterministic movement");
    }

    private static SceneEntities scene(Map<String, String> fields) throws Exception {
        var content = new SceneContent(List.of(new SceneContent.Entry("monster", 0, 0, 0,
            new SceneContent.Spec(fields))), Map.of());
        return new SceneEntities(content, SceneWeaponsTest.openTerrain());
    }

    private static ActorState player(float x, float y) {
        return new ActorState(219, 1001, "Fixture", 256, 1, x, y, 0, 0, 0,
            new StarterCatalog.Model("Fixture", Map.of()));
    }

    private static StarterCatalog.Model damage(float amount) {
        return new StarterCatalog.Model("DamageRegion", Map.of(
            "shape", new FireCatalog.DataValue("Shape$Circle", Map.of("radius", 1f)),
            "damage", new FireCatalog.DataValue("Damage$Normal", Map.of("flags", 2, "amount", amount))));
    }
}
