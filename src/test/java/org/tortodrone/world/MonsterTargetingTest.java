package org.tortodrone.world;

import java.util.*;
import org.tortodrone.character.StarterCatalog;
import org.tortodrone.resources.Json;
import static org.tortodrone.support.TestWire.check;

/** Synthetic hostile/friendly bodies exercise authoritative scene actor targeting. */
public final class MonsterTargetingTest {
    public static void main(String[] args)throws Exception {
        var f=new HashMap<String,String>(Map.of("native-type","com.threerings.projectx.dungeon.data.actor.Monster","kind","Monster","config","Fixture/Helper","health","100","radius",".45","damage-mask","5","speed","2","turn-rate","174.5"));
        f.putAll(Map.of("targeting.kind","weighted-path-attacks","targeting.expansion","11.652","targeting.evaluation","500","targeting.interval","999","targeting.monster-mask","2","targeting.modes",Json.stringify(List.of(Map.of("attack",0,"minimum",0,"maximum",1000,"range",1.9927,"weight",1,"faces",true,"turn-snap",0)))));
        f.putAll(Map.of("attack.0.clear","200","attack.0.land","0","attack.0.fires","0","attack.0.fire.0.delay","0"));
        f.put("attack.0.fire.0.action",FireCatalog.actionJson(new FireCatalog.Action(FireCatalog.Kind.FIRE_EFFECT,new StarterCatalog.Model("Fixture/Attack",Map.of()),null,"Source","","",0,0,0,0,true,0,0,0,0,false,List.of(),"")));
        var friend=new SceneContent.Entry("friend",.5f,0,0,new SceneContent.Spec(Map.of("native-type","com.threerings.projectx.dungeon.data.actor.Monster","config","Fixture/Friend","health","100","radius",".45","damage-mask","5")));
        var enemy=new SceneContent.Entry("enemy",5,0,0,new SceneContent.Spec(Map.of("native-type","com.threerings.projectx.dungeon.data.actor.Monster","config","Fixture/Enemy","health","100","radius","1.25","damage-mask","2")));
        var helper=new SceneContent.Entry("helper",0,0,0,new SceneContent.Spec(f));
        var content=new SceneContent(List.of(helper,friend,enemy),Map.of());var world=new SceneEntities(content,SceneWeaponsTest.openTerrain());
        var attacks=new ArrayList<ActorState>();world.attacks((actor,action)->attacks.add(actor));
        var knight=new ActorState(219,1001,"Fixture",0,1,0,-.5f,0,0,0,new StarterCatalog.Model("Player",Map.of()));
        for(int stamp=1000;stamp<4000;stamp+=50)world.advance(stamp,List.of(knight));
        check(!attacks.isEmpty()&&attacks.getFirst().x()>1.5&&attacks.getFirst().rotation()==0,"helper ignores closer friend/player, pursues hostile monster and fires using its nearest body distance");
        var damage=new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$Normal",List.of(new FireCatalog.FieldValue("flags","int",2),new FireCatalog.FieldValue("amount","float",200f)));
        var region=new FireCatalog.DataValue("com.threerings.tudey.shape.config.ShapeConfig$Circle",List.of(new FireCatalog.FieldValue("radius","float",1f)));
        world.damage(new StarterCatalog.Model("DamageRegion",Map.of("damage",damage,"shape",region)),5,0,0,4000,219);
        world.advance(4050,List.of(knight));check((world.actors().getFirst().flags()&16)==0,"a genuinely killed hostile is released while friendly actors remain alive");
        var peaceful=new SceneEntities(new SceneContent(List.of(helper,friend),Map.of()),SceneWeaponsTest.openTerrain());
        var refused=new ArrayList<ActorState>();peaceful.attacks((actor,action)->refused.add(actor));
        for(int stamp=1000;stamp<4000;stamp+=50)peaceful.advance(stamp,List.of(knight));
        check(refused.isEmpty()&&(peaceful.actors().getFirst().flags()&16)==0,"no hostile monster means no invented player/friendly target");
        f.putAll(Map.of("follow.kind","nearest-party-member","follow.evaluation","500","follow.minimum","0","follow.maximum","3"));
        var follower=new SceneEntities(new SceneContent(List.of(new SceneContent.Entry("helper",0,0,0,new SceneContent.Spec(f))),Map.of()),SceneWeaponsTest.openTerrain());
        var distantKnight=new ActorState(219,1001,"Fixture",0,1,5,0,0,0,0,new StarterCatalog.Model("Player",Map.of()));
        for(int stamp=1000;stamp<4000;stamp+=50)follower.advance(stamp,List.of(distantKnight));
        check(follower.actors().getFirst().x()>=2&&follower.actors().getFirst().x()<4&&(follower.actors().getFirst().flags()&16)==0,"victimless helper follows its scene member at native evaluations and stops within three center units");
        var remote=new SceneEntities(new SceneContent(List.of(new SceneContent.Entry("helper",0,0,0,new SceneContent.Spec(f))),Map.of()),SceneWeaponsTest.openTerrain());
        var outOfPath=new ActorState(219,1001,"Fixture",0,1,20,0,0,0,0,new StarterCatalog.Model("Player",Map.of()));
        for(int stamp=1000;stamp<3000;stamp+=50)remote.advance(stamp,List.of(outOfPath));
        check(remote.actors().getFirst().x()==0,"idle Follow cannot bypass the native eight-unit path bound or teleport to the player");
        var circle=SceneCollision.pawnShape(new SceneContent.Spec(Map.of("radius","1.25")),null).at(5,0,0);
        check(circle.distance(3,0)==.75f&&circle.distance(5,0)==0,"native target distance is measured to the body, including points inside it");
        System.out.println("PASS: hostile scene-monster acquisition, friendly/player exclusion, pursuit/body range, idle Follow, distance/path bounds and no teleport (synthetic scene)");
    }
}
