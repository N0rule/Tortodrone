package org.tortodrone.world;

import java.nio.file.Path;
import java.util.*;
import org.tortodrone.character.StarterCatalog;
import org.tortodrone.resources.ContentManager;
import static org.tortodrone.support.TestWire.check;

/** Probe: place a real Crash Site Jelly Cube and an AFK knight at 3.0 distance, tick 30s,
 * log distances and damage. */
public final class JellyAfkProbeTest {
    public static void main(String[] args) throws Exception {
        var map = ContentManager.load(Path.of(".")).scenes().scene(398);
        var content = map.content();
        // Find a Jelly Cube spawn
        var jellyFields = new ArrayList<Map<String,String>>();
        for (var entry : content.entries()) {
            if (entry.spec().text("action","").isEmpty()) continue;
            for (var fields : CrashSiteMonsterCombatTest.spawnFields(SceneActions.rule(entry).action()))
                if ("Monster".equals(fields.get("kind")) && fields.get("config").contains("Jelly Cube"))
                    jellyFields.add(fields);
        }
        check(!jellyFields.isEmpty(), "Jelly spawn found");
        var fields = jellyFields.get(0);
        System.out.println("Jelly: speed=" + fields.get("speed") + " targeted-speed=" + fields.get("targeted-speed")
            + " targeting.range=" + fields.get("targeting.range") + " impulse-x=" + fields.get("attack.0.impulse-x"));
        // Build a scene with the Jelly at (0,0)
        var scene = new SceneEntities(
            new SceneContent(List.of(new SceneContent.Entry("jelly",0,0,0,new SceneContent.Spec(fields))), Map.of()),
            SceneWeaponsTest.openTerrain());
        var weapons = new SceneWeapons(SceneWeaponsTest.openTerrain());
        weapons.targets(scene);
        var health = new SceneHealth(content);
        var stampRef = new int[]{SceneClock.now()};
        // AFK knight at (3, 0) — edge distance 3 - 0.333 = 2.667
        var player = new ActorState(219, 1001, "Fixture", 0, stampRef[0], 3, 0, 0, 0, 0, 0, stampRef[0], new StarterCatalog.Model("Fixture", Map.of()), false);
        var damageHits = new ArrayList<Object>();
        scene.playerHits(hit -> { damageHits.add(hit.damage()); });
        scene.attacks((actor, action) -> { weapons.fire(action, actor, stampRef[0]); });
        // Tick 30 seconds
        int attacks = 0;
        for (int tick = 0; tick < 600; tick++) {
            stampRef[0] += 50;
            int ts = stampRef[0];
            scene.advance(ts, List.of(player));
            weapons.advance(ts, List.of(player));
            var actor = scene.actors().getFirst();
            double dist = Math.hypot(actor.x(), actor.y());
            var ent = scene.actor(actor.id());
            boolean moving = (actor.flags() & 2) != 0;
            if (actor.activity() == 4 && !moving) {
                attacks++;
                if (damageHits.size() > 0) break;
            }
        }
        System.out.println("Result: attacks=" + attacks + " damageHits=" + damageHits + " finalHealth=" + health.state().current());
        check(damageHits.size() > 0, "Jelly Cube eventually damages the AFK knight within 30s");
        System.out.println("PASS: Jelly hits after " + attacks + " attacks");
    }
}
