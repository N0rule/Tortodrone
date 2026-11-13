package org.tortodrone.world;

import java.nio.file.Path;
import java.util.*;
import org.tortodrone.character.StarterCatalog;
import org.tortodrone.resources.ContentManager;
import static org.tortodrone.support.TestWire.check;

/** Crash Site cannot be left before its exit, so a knight with no Spark must not stay dead:
 * after the free emergency revive, tutorial deaths heal in place after the original 4 s. */
public final class TutorialRespawnTest {
    public static void main(String[] args)throws Exception {
        var content=new SceneContent(List.of(),Map.of(),Map.of(),Map.of("player.base-health",200f),Map.of(),Map.of(),Map.of());
        var health=new SceneHealth(content);health.tutorialRespawn(true);
        health.hit(damage(1000),100);
        check(!health.alive()&&health.state().reviveFlags()==9,"the first tutorial death still uses the native emergency revive");
        health.advance(5100,5000,false);check(health.alive()&&health.state().current()==200,"emergency revive restores the knight");
        health.hit(damage(1000),20000);
        check(!health.alive()&&health.state().reviveFlags()==0&&health.canSparkRevive(),"a later death publishes no emergency flags and still accepts a Spark");
        health.advance(23999,3999,false);check(!health.alive(),"no respawn before the four-second tutorial delay");
        health.advance(24000,1,false);
        check(health.alive()&&health.state().current()==200&&health.invulnerable(24000),"the tutorial heals in place to full after four seconds");
        health.hit(damage(1000),30000);health.advance(31000,1000,false);
        check(health.revive(31000)&&health.state().current()==200,"a Spark revive answers the pending respawn");
        health.advance(36000,5000,false);check(health.alive()&&health.state().current()==200,"a spent respawn deadline cannot fire again");
        health.hit(damage(1000),40000);health.advance(44000,4000,false);check(health.alive(),"every subsequent tutorial death respawns");

        var ordinary=new SceneHealth(content);ordinary.hit(damage(1000),100);ordinary.advance(5100,5000,false);
        ordinary.hit(damage(1000),20000);ordinary.advance(60000,40000,false);
        check(!ordinary.alive()&&ordinary.canSparkRevive(),"outside the tutorial a spent charge still requires a Spark");

        var catalog=ContentManager.load(Path.of(".")).scenes();var registry=new SceneRegistry(catalog);
        check(tutorialRespawn(registry.instance(398,0))&&!tutorialRespawn(registry.instance(294,0)),"only Crash Site instances enable tutorial respawn");
        System.out.println("PASS: tutorial death after the emergency revive heals in place after 4 s; Spark revive and ordinary levels unchanged");
    }
    private static boolean tutorialRespawn(SceneRegistry.Instance instance)throws Exception {
        int stamp=SceneClock.now();
        var actor=new ActorState(219,1001,"Respawn",256,stamp,instance.map().spawnX(),instance.map().spawnY(),0,0,0,0,stamp,new StarterCatalog.Model("Character/PC/Level",Map.of()),false);
        try(var member=instance.simulation().join(actor,s->{})){
            var field=SceneSimulation.Membership.class.getDeclaredField("health");field.setAccessible(true);
            var flag=SceneHealth.class.getDeclaredField("tutorialRespawn");flag.setAccessible(true);
            return flag.getBoolean(field.get(member));
        }
    }
    private static Object damage(float amount){return new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$Normal",List.of(
        new FireCatalog.FieldValue("flags","int",8),new FireCatalog.FieldValue("amount","float",amount)));}
}
