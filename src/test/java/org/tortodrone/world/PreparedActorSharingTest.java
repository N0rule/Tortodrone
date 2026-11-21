package org.tortodrone.world;

import java.util.*;
import org.tortodrone.core.character.StarterCatalog;
import static org.tortodrone.support.TestWire.check;
import org.tortodrone.core.world.FireCatalog;

/**
 * Phase 3: native behaviour is parsed once per prepared actor and shared by every instance.
 * Mutable combat state must stay per entity, and the shared object must be deeply immutable.
 */
public final class PreparedActorSharingTest {
    private PreparedActorSharingTest() { }

    public static void main(String[] args) throws Exception {
        var fields = new TreeMap<String, String>();
        fields.put("config", "Monster/Fixture/Shared");
        fields.put("kind", "Monster");
        fields.put("native-type", "com.threerings.projectx.dungeon.data.actor.Monster");
        fields.put("health", "10");
        fields.put("speed", "1");
        fields.put("radius", "0.4");
        fields.put("attack-distance", "1");
        fields.put("attacks", "0");
        fields.put("damage-mask", "2");
        fields.put("attack.0.land", "100");
        fields.put("attack.0.clear", "100");
        fields.put("attack.0.fire-speed", "1");
        fields.put("attack.0.fires", "");
        fields.put("targeting.kind", "single-path-attack");
        fields.put("targeting.expansion", "0");
        fields.put("targeting.evaluation", "100");
        fields.put("targeting.attack", "0");
        fields.put("targeting.interval", "100");
        fields.put("targeting.range", "1.5");
        fields.put("targeting.turn-snap", "0");
        fields.put("turn-rate", "100");
        var spec = new SceneContent.Spec(fields);

        var entries = new ArrayList<SceneContent.Entry>();
        int count = 100;
        for (int i = 1; i <= count; i++)
            entries.add(new SceneContent.Entry(Integer.toString(i), i, 0, 0, spec));
        var content = new SceneContent(entries, Map.of());
        var world = new SceneEntities(content, SceneWeaponsTest.openTerrain());

        var spawned = world.actors();
        check(spawned.size() == count, "every fixture actor spawned");
        var shared = world.prepared(spawned.getFirst().id());
        check(shared != null, "the first spawned monster exposes its prepared actor");
        check(shared == PreparedActor.of(spec), "the cache reuses the parsed actor for the same spec");
        for (var actor : spawned)
            check(world.prepared(actor.id()) == shared, "all " + count + " instances share one parsed actor");
        check(shared.targeting() != null, "the parsed targeting policy is available");
        check(shared.config().name().equals("Monster/Fixture/Shared"), "the parsed native config is shared");

        // Sharing is only safe while every exposed collection stays immutable.
        expectImmutable(() -> shared.attackMotion().put(0, new SceneAttackMotion(0, 1, 0, 0)), "attack motion");
        expectImmutable(() -> shared.meleeRangeCaps().put(0, 1f), "melee range caps");
        expectImmutable(() -> shared.sceneFireActions().put("attack.0.fire.0.scene-action", null), "scene fire actions");

        // Entity state remains per instance: damaging one monster must not leak.
        var damage = new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$Normal",
            Map.of("flags", 2, "amount", 3f, "shouldReact", true, "majorReact", false));
        var region = new StarterCatalog.Model("DamageRegion",
            Map.of("shape", new FireCatalog.DataValue("Shape$Circle", Map.of("radius", 1f)), "damage", damage));
        int first = spawned.getFirst().id(), second = spawned.get(1).id();
        world.damage(region, 0, 0, 0, SceneClock.now() + 100, 219);
        check(world.actor(first).entity().health() == 0.7f, "damage mutates only the target instance");
        check(world.actor(second).entity().health() == 1f, "the shared prepared actor carries no health state");
        check(world.prepared(second) == shared, "combat does not replace the shared actor");

        System.out.println("PASS: prepared monster behaviour is parsed once, shared immutably across 100 instances and keeps mutable state per entity");
    }

    private static void expectImmutable(Runnable mutation, String what) {
        try { mutation.run(); throw new AssertionError("Shared prepared " + what + " is mutable"); }
        catch (UnsupportedOperationException expected) { }
    }
}
