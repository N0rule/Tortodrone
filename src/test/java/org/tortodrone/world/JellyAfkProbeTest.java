package org.tortodrone.world;

import java.nio.file.Path;
import java.util.*;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.resources.ContentManager;
import static org.tortodrone.support.TestWire.check;
import org.tortodrone.core.world.SceneActions;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.core.world.SceneContent;

/** The tutorial Jelly Cube's Roundhouse resolves Damage Region=Medium (Circle 1.5), but
 *  the exported fire action carries the choice-less base circle (1.0), so the resolved
 *  radius is restored at runtime. The range cap stops the monster at its damage reach
 *  (radius + PAWN_RADIUS), so the restoration is observable in the standoff distance at
 *  which the AFK knight is hit: ~1.833 centre-to-centre for Medium versus ~1.333 for the
 *  blank base circle. */
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
        var stampRef = new int[]{SceneClock.now()};
        // AFK knight at (3, 0): the jelly must close in to its restored Medium reach and attack
        var player = new ActorState(219, 1001, "Fixture", 0, stampRef[0], 3, 0, 0, 0, 0, 0, stampRef[0], new StarterCatalog.Model("Fixture", Map.of()), false);
        var hitDistances = new ArrayList<Float>();
        scene.playerHits(hit -> { hitDistances.add((float)Math.hypot(scene.actors().getFirst().x()-player.x(), scene.actors().getFirst().y()-player.y())); });
        scene.attacks((actor, action) -> { weapons.fire(action, actor, stampRef[0]); });
        int ticks = 0;
        for (; ticks < 600 && hitDistances.isEmpty(); ticks++) {
            stampRef[0] += 50;
            int ts = stampRef[0];
            scene.advance(ts, List.of(player));
            weapons.advance(ts, List.of(player));
        }
        var actor = scene.actors().getFirst();
        double standoff = Math.hypot(actor.x()-player.x(), actor.y()-player.y());
        System.out.println("Result: ticks=" + ticks + " hitDistances=" + hitDistances + " finalStandoff=" + standoff);
        check(!hitDistances.isEmpty(), "Jelly Cube eventually damages the AFK knight within 30s");
        float hit = hitDistances.getFirst();
        // Medium reach stops the jelly at 1.5 + PAWN_RADIUS = 1.833 centre distance; the
        // blank base circle would stop it at 1.333. Allow one 50ms approach step inward.
        check(hit >= 1.5f, "hit lands from Medium standoff (>= 1.5 centre distance, was " + hit + ")");
        check(hit <= 2.0f, "hit lands from at most the Medium stop distance (<= 2.0 centre distance, was " + hit + ")");
        System.out.println("PASS: Jelly hits the AFK knight from the restored Medium standoff " + hit);
    }
}
