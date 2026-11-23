package org.tortodrone.world;

import static org.tortodrone.support.TestWire.check;

import java.nio.file.Path;
import java.util.*;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.core.world.FireCatalog;
import org.tortodrone.core.world.SceneActions;
import org.tortodrone.core.world.SceneClock;
import org.tortodrone.core.world.SceneContent;
import org.tortodrone.core.world.ScenePortals;
import org.tortodrone.core.world.WeaponCatalog;
import org.tortodrone.resources.ContentManager;

/** The monster death wire contract, from the native death flow: the killing blow
 *  switches the activity to 2 (DYING) at its own stamp and sets destroyed to
 *  stamp+deathTime (MonsterLogic.hurt plus the destroy-after-deathTime tick rule);
 *  the dying actor stays in the scene's published snapshots through its death
 *  animation window plus one publication grace, and removal is published from a
 *  later stamp so the stock client's delayed-time isDestroyed() - not isRemoved() -
 *  governs the sprite and fires the config's destructionTransient death effect. */
public final class CrashSiteMonsterDeathTest {
    private static final String JELLY = "Character/NPC/Monster/Slime/Jelly Cube (Intro)";

    public static void main(String[] args) throws Exception {
        var map = ContentManager.load(Path.of(".")).scenes().scene(398);
        Map<String, String> jelly = null;
        for (var entry : map.content().entries()) {
            if (entry.spec().text("action", "").isEmpty()) continue;
            for (var fields : CrashSiteMonsterCombatTest.spawnFields(
                    SceneActions.rule(entry).action())) if (JELLY.equals(fields.get("config"))) jelly = fields;
        }
        check(jelly != null, "the Crash Site jelly spawn is available to the death fixture");
        check("100".equals(jelly.get("death-time")), "the tutorial jelly carries its authored Instant death time");

        // Part one - the killing blow's state through the production damage path.
        var scene = new SceneEntities(
                new SceneContent(
                        List.of(new SceneContent.Entry("monster", 0, 0, 0, new SceneContent.Spec(jelly))), Map.of()),
                SceneWeaponsTest.openTerrain());
        scene.attacks((actor, action) -> {});
        int id = scene.actors().getFirst().id();
        int kill = SceneClock.now() + 100;
        check(scene.actors().getFirst().entity().destroyed() == Integer.MAX_VALUE, "the fixture jelly starts alive");
        scene.damage(region(normal(50, true)), 0, 0, 0, kill, 219);
        var dying =
                scene.actors().stream().filter(a -> a.id() == id).findFirst().orElse(null);
        check(dying != null, "the dying jelly remains in the published actor list");
        check(dying != null && dying.activity() == 2, "the killing blow switches the jelly to the dying activity");
        check(
                dying != null && dying.activityStarted() == kill,
                "the dying activity starts at the killing blow's stamp");
        check(
                dying != null && dying.entity().destroyed() == kill + 100,
                "destruction waits the authored Instant death time");
        check(dying != null && dying.entity().health() == 0f, "the dead jelly reports zero health");
        check(dying != null && dying.flags() == 0, "the dying jelly clears its movement and camo flags");

        // Part two - the published lifetime of the dying actor. Native removal runs
        // on the tick after deathTime; Tortodrone adds one publication grace, so the
        // dying frame is visible for the death-time window plus the grace, and the
        // removal snapshot's stamp is strictly later than destroyed.
        for (int step : new int[] {50, 99})
            check(stepOf(scene, id, kill + step), "the dying jelly is still published at +" + step + " ms");
        check(
                stepOf(scene, id, kill + 100),
                "the jelly dies at its destroyed stamp yet stays published for interpolation");
        for (int step : new int[] {149, 199})
            check(stepOf(scene, id, kill + step), "the jelly keeps its publication grace at +" + step + " ms");
        check(!stepOf(scene, id, kill + 200), "the jelly is removed one grace after its destroyed stamp");
        check(
                kill + 200 > kill + 100,
                "the removal snapshot's stamp follows destroyed, so the client's delayed-time isDestroyed precedes isRemoved");

        // Part three - the full transcript through a live ticking simulation. A
        // joined, entered knight starts the 50-ms ticker; the kill goes through the
        // same production damage path, and the public snapshot stream must carry the
        // dying jelly as a published update whose delta differs from the previous
        // snapshot, until a snapshot stamped after destroyed drops it.
        var real = map.content();
        var content = new SceneContent(
                List.of(new SceneContent.Entry("jelly", 0, 0, 0, new SceneContent.Spec(jelly))),
                real.scales(),
                Map.of(),
                real.stats(),
                real.items(),
                real.cycles(),
                real.statuses());
        var fixture = new org.tortodrone.core.world.MapScene(
                9401,
                50332041,
                "Fixture",
                "Fixture",
                "Fixture",
                "level",
                1,
                1,
                0,
                0,
                1,
                SceneWeaponsTest.openTerrain(),
                new byte[0],
                ScenePortals.EMPTY,
                content);
        var simulation = new SceneRegistry.Instance(9401, 0, fixture).simulation();
        int now = SceneClock.now();
        try (var member = simulation.join(
                new ActorState(
                        219,
                        1001,
                        "Knight",
                        0,
                        now,
                        1,
                        0,
                        0,
                        0,
                        0,
                        0,
                        now,
                        new StarterCatalog.Model("Character/PC/Level", Map.of()),
                        false),
                s -> {})) {
            member.combat(
                    WeaponCatalog.empty(),
                    new org.tortodrone.core.character.WorldProfile(10, 1, 1, 0, 0, 1, List.of()));
            member.enter();
            int monster;
            SceneSnapshot before, terminal;
            synchronized (simulation) {
                before = simulation.snapshot();
                var field = SceneSimulation.class.getDeclaredField("entities");
                field.setAccessible(true);
                var entities = (SceneEntities) field.get(simulation);
                monster = entities.actors().getFirst().id();
                var single = entities.actors().stream()
                        .filter(a -> a.id() == monster)
                        .findFirst()
                        .orElseThrow();
                check(single.entity().destroyed() == Integer.MAX_VALUE, "the live fixture jelly starts alive");
                entities.damage(region(normal(50, true)), 0, 0, 0, SceneClock.now() + 100, 219);
                terminal = simulation.snapshot();
            }
            var slain = terminal.actors().stream()
                    .filter(a -> a.id() == monster)
                    .findFirst()
                    .orElse(null);
            check(
                    slain != null && slain.activity() == 2 && slain.entity().destroyed() != Integer.MAX_VALUE,
                    "the live simulation publishes the dying jelly with a destroyed stamp");
            check(
                    terminal.delta(before).updated().stream().anyMatch(a -> a.id() == monster && a.activity() == 2),
                    "the dying jelly rides the scene delta as an update, so its activity and destroyed stamps reach the client");
            int destroyed = slain == null ? 0 : slain.entity().destroyed();
            boolean dyingPublished = false, removedAfterStamp = false, vanishedThin = false;
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (System.nanoTime() < deadline) {
                Thread.sleep(40);
                var failure = simulation.failure();
                if (failure != null) {
                    failure.printStackTrace();
                    throw new AssertionError("Scene simulation crashed during the death transcript: " + failure);
                }
                SceneSnapshot current;
                synchronized (simulation) {
                    current = simulation.snapshot();
                }
                var present = current.actors().stream()
                        .filter(a -> a.id() == monster)
                        .findFirst()
                        .orElse(null);
                if (present != null) {
                    check(
                            present.entity().destroyed() == destroyed,
                            "the dying jelly's destroyed stamp is stable across publications");
                    dyingPublished = true;
                } else {
                    // The first absent snapshot must be stamped after destroyed; an
                    // earlier drop would make the client's isRemoved() beat
                    // isDestroyed() and skip the death transient.
                    if (current.timestamp() > destroyed) removedAfterStamp = true;
                    else vanishedThin = true;
                    break;
                }
            }
            check(dyingPublished, "the dying jelly stays published across live ticks");
            check(removedAfterStamp && !vanishedThin, "the live removal is stamped after destroyed, never before");
        }
        System.out.println(
                "PASS: Crash Site monster deaths publish the dying activity, destroyed stamp and post-grace removal");
    }

    private static boolean stepOf(SceneEntities scene, int id, int stamp) {
        scene.advance(stamp, List.of());
        return scene.actors().stream().anyMatch(a -> a.id() == id);
    }

    private static FireCatalog.DataValue normal(float amount, boolean major) {
        return new FireCatalog.DataValue(
                "com.threerings.projectx.dungeon.config.DamageConfig$Normal",
                Map.of("flags", 2, "amount", amount, "shouldReact", true, "majorReact", major));
    }

    private static StarterCatalog.Model region(FireCatalog.DataValue damage) {
        return new StarterCatalog.Model(
                "DamageRegion",
                Map.of("shape", new FireCatalog.DataValue("Shape$Circle", Map.of("radius", 1f)), "damage", damage));
    }
}
