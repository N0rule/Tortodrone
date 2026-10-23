package org.tortodrone.world;

import java.io.IOException;
import java.util.*;
import org.tortodrone.character.StarterCatalog;
import static org.tortodrone.support.TestWire.check;

/** Payment rollback and exactly one native Spark effect/monster impulse after commit. */
public final class SparkRevivalTest {
    public static void main(String[] args)throws Exception {
        var entries=new ArrayList<SceneContent.Entry>();
        for(int n=0;n<5;n++){
            var fields=new HashMap<>(Map.of("native-type","Monster","config","Fixture","health","100","radius",".5","damage-mask",n==4?"1":"2","impulse-level",n==3?"16":"1"));
            entries.add(new SceneContent.Entry("target-"+n,n==0?1.5f:n==1?2.5f:n==2?2.51f:1.7f,n>=3?1:0,0,new SceneContent.Spec(fields)));
        }
        var content=new SceneContent(entries,Map.of(),Map.of(),Map.of("player.base-health",100f),Map.of(),Map.of(),Map.of());
        var map=new SceneCatalog.MapScene(294,1,"Fixture","Fixture","","training",1,0,0,0,1,SceneWeaponsTest.openTerrain(),new byte[0],ScenePortals.EMPTY,content);
        var simulation=new SceneRegistry.Instance(7999,0,map).simulation();int now=SceneClock.now();
        var actor=new ActorState(42,1001,"Fixture",0,now,0,0,0,0,0,new StarterCatalog.Model("Fixture",Map.of()));
        try(var member=simulation.join(actor,s->{})){
            synchronized(simulation){
                member.enter();
                var healthField=SceneSimulation.Membership.class.getDeclaredField("health");healthField.setAccessible(true);var health=(SceneHealth)healthField.get(member);
                var emergency=SceneHealth.class.getDeclaredField("emergencyRevive");emergency.setAccessible(true);emergency.setBoolean(health,false);
                var immune=SceneHealth.class.getDeclaredField("invulnerableUntil");immune.setAccessible(true);immune.setInt(health,0);
                health.hit(new FireCatalog.DataValue("Damage$Unblockable",Map.of("amount",1000f,"flags",1)),now);
                var movement=SceneSimulation.Membership.class.getDeclaredField("movement");movement.setAccessible(true);((TownMovement)movement.get(member)).die(now);
                var entitiesField=SceneSimulation.class.getDeclaredField("entities");entitiesField.setAccessible(true);var entities=(SceneEntities)entitiesField.get(simulation);
                var mapField=SceneEntities.class.getDeclaredField("entities");mapField.setAccessible(true);var targets=(Map<?,?>)mapField.get(entities);
                var impulseField=Class.forName("org.tortodrone.world.SceneEntities$Entity").getDeclaredField("impulse");impulseField.setAccessible(true);
                var before=simulation.snapshot();
                try{simulation.revive(member,42,()->{throw new IOException("rollback");});throw new AssertionError("payment failure accepted");}catch(IOException expected){}
                check(!health.alive()&&simulation.snapshot().effectsSince(before).isEmpty(),"failed payment leaves the corpse and effects unchanged");
                for(var target:targets.values())check(impulseField.get(target)==null,"rollback cannot impart an impulse");
                check(simulation.revive(member,42,()->"committed").equals("committed"),"successful payment result retained");
                var effects=simulation.snapshot().effectsSince(before);
                check(effects.size()==1&&effects.getFirst().config().name().equals("Player/Spark Rez")&&effects.getFirst().targetActor()==42&&effects.getFirst().ownerOid()==0,"one targeted stock Spark VFX/SFX reaches every observer");
                int index=0;
                for(var target:targets.values()){
                    var impulse=(KnightCombat.Impulse)impulseField.get(target);
                    if(index<2)check(impulse!=null&&impulse.duration()==400&&impulse.x()==5&&impulse.y()==0&&impulse.started()==effects.getFirst().timestamp(),"radius-two shockwave gives eligible monsters five units over 400 ms at the effect timestamp");
                    else check(impulse==null,"outside radius, resistant and friendly entities are excluded");
                    index++;
                }
                try{simulation.revive(member,42,()->{throw new AssertionError("duplicate charged");});throw new AssertionError("live revive accepted");}catch(IOException expected){}
                check(simulation.snapshot().effectsSince(before).size()==1,"duplicate revive cannot repeat the shockwave");
            }
        }
        System.out.println("PASS: Spark rollback, commit-before-effect, radius/force/timing/resistance/faction guards and duplicate refusal");
    }
}
