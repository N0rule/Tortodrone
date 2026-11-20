package org.tortodrone.world;

import java.util.*;
import org.tortodrone.character.StarterCatalog;
import static org.tortodrone.support.TestWire.check;

/**
 * Phase 7 leash contract: a monster past its radius disengages, walks home and heals, while recent
 * damage still forces an answer first.
 */
public final class MonsterLeashTest {
    private MonsterLeashTest() { }

    public static void main(String[] args) throws Exception {
        var fields = new HashMap<String, String>();
        fields.putAll(Map.of("config", "Monster/Fixture/Leash", "kind", "Monster",
            "native-type", "com.threerings.projectx.dungeon.data.actor.Monster",
            "health", "100", "radius", ".4", "damage-mask", "2", "collision-mask", "1"));
        fields.putAll(Map.of("collision-flags", "8", "speed", "8", "acquire-distance", "64",
            "acquisition-interval", "0", "attack", "-1", "turn-rate", "720",
            "activation-radius", "32", "leash-radius", "3", "leash-heal", "20"));
        var entry = new SceneContent.Entry("leash-monster", 0, 0, 0, new SceneContent.Spec(fields));
        var world = new SceneEntities(new SceneContent(List.of(entry), Map.of()),
            TestCollision.floor(-32, -32, 128, 128), SceneCollision.empty(), new Random(5), true, 1000,
            MonsterCatalog.empty());
        int id = world.actors().getFirst().id();
        int stamp = 1000;
        var model = new StarterCatalog.Model("Fixture", Map.of());

        // Engage and damage the monster so it has health to regain later.
        for (int tick = 0; tick < 20; tick++, stamp += 50)
            world.advance(stamp, List.of(player(2, 0, stamp, model)));
        float engagedX = world.actor(id).x();
        check(engagedX > 0, "the monster leaves its anchor to engage");
        var pulse = new SceneStatus.Pulse(new FireCatalog.DataValue(
            "com.threerings.projectx.dungeon.config.DamageConfig$Normal",
            Map.of("flags", 2, "amount", 40f)), 2f, 128);
        world.statusPulse(0, pulse, world.actor(id).x(), world.actor(id).y(), stamp);
        float wounded = world.actor(id).entity().health();
        check(wounded <= 60.001f, "the pulse wounded the monster (" + wounded + ")");

        // Chase the distant player past the leash radius; recent damage must keep it fighting.
        float farthest = engagedX;
        for (int tick = 0; tick < 40; tick++, stamp += 50) {
            world.advance(stamp, List.of(player(10, 0, stamp, model)));
            farthest = Math.max(farthest, world.actor(id).x());
        }
        check(farthest > 3f, "the monster is pulled beyond its leash (" + farthest + ")");

        // Every player is now far away; after the damage grace the monster must walk home.
        for (int tick = 0; tick < 140; tick++, stamp += 50)
            world.advance(stamp, List.of(player(100, 0, stamp, model)));
        var home = world.actor(id);
        check(Math.hypot(home.x(), home.y()) <= .3f, "the monster returned to its anchor (" + home.x() + ")");
        check(home.entity().health() > wounded, "the monster regained health on the way home");
        check(world.band(id) == SceneMonsterBand.DORMANT, "an out-of-range monster settles back to dormant");

        // A mid-return hit still wins over the leash for the native damage grace.
        var returningPulse = new SceneStatus.Pulse(new FireCatalog.DataValue(
            "com.threerings.projectx.dungeon.config.DamageConfig$Normal",
            Map.of("flags", 2, "amount", 5f)), 2f, 128);
        world.statusPulse(0, returningPulse, home.x(), home.y(), stamp);
        world.advance(stamp + 50, List.of(player(2, 0, stamp + 50, model)));
        check(world.band(id) == SceneMonsterBand.ENGAGED, "damage forces engagement even at home");

        System.out.println("PASS: leash disengages past its radius, returns home, heals and yields to damage");
    }

    private static ActorState player(float x, float y, int stamp, StarterCatalog.Model model) {
        return new ActorState(219, 1001, "Leash", 256, 1000, x, y, 0, 0, 0, 0, stamp, model, false);
    }
}
