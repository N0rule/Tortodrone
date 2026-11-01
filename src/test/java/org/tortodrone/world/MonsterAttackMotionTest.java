package org.tortodrone.world;

import java.util.*;
import org.tortodrone.character.StarterCatalog;
import static org.tortodrone.support.TestWire.check;

public final class MonsterAttackMotionTest {
    public static void main(String[] args)throws Exception {
        var fields=new HashMap<String,String>(Map.of("native-type","com.threerings.projectx.dungeon.data.actor.Monster","kind","Monster","config","Fixture/Motion","health","100","radius",".4","speed","0","turn-rate","174.5","collision-flags","8","collision-mask","1"));
        fields.putAll(Map.of("targeting.kind","single-path-attack","targeting.expansion","8","targeting.evaluation","1000","targeting.attack","0","targeting.interval","1999","targeting.range","2","targeting.turn-snap","1.5"));
        fields.putAll(Map.of("attack.0.land","0","attack.0.clear","1000","attack.0.impulse-delay","100","attack.0.impulse-duration","400","attack.0.impulse-x","-1.5","attack.0.impulse-y","0","source-shape",shape(.4f)));
        var actor=new SceneContent.Entry("enemy",0,0,0,new SceneContent.Spec(fields));
        var world=new SceneEntities(new SceneContent(List.of(actor),Map.of()),SceneWeaponsTest.openTerrain());
        var player=new ActorState(219,1001,"Fixture",0,1,1,0,0,0,0,new StarterCatalog.Model("Player",Map.of()));
        int start=1000;world.advance(start,List.of(player));var enemy=world.actors().getFirst();start=enemy.activityStarted();
        world.advance(start+99,List.of(player));check(world.actors().getFirst().x()==0,"native attack motion waits for its authored delay");
        world.advance(start+100,List.of(player));check(world.actors().getFirst().x()==0&&world.actors().getFirst().entity().kind().equals("Monster"),"motion begins without an instantaneous position jump");
        world.advance(start+300,List.of(player));check(Math.abs(world.actors().getFirst().x()+1.125f)<.001,"native eased impulse integrates the authored displacement");
        world.advance(start+500,List.of(player));check(Math.abs(world.actors().getFirst().x()+1.5f)<.001&&(world.actors().getFirst().flags()&64)!=0,"one completed native impulse retains its applied guard");
        world.advance(start+600,List.of(player));check(Math.abs(world.actors().getFirst().x()+1.5f)<.001,"later publication cannot replay attack movement");
        var wall=new SceneContent.Entry("wall",-.75f,0,0,new SceneContent.Spec(Map.of("native-type","com.threerings.projectx.dungeon.data.actor.Block","kind","Block","config","Fixture/Wall","radius",".25","collision-flags","1","source-shape",shape(.25f))));
        var blocked=new SceneEntities(new SceneContent(List.of(actor,wall),Map.of()),SceneWeaponsTest.openTerrain());
        blocked.advance(1000,List.of(player));int blockedStart=blocked.actors().getFirst().activityStarted();
        blocked.advance(blockedStart+600,List.of(player));
        check(blocked.actors().getFirst().x()>=-.101f,"attack-owned impulses obey native body collision instead of crossing a wall");
        var legacyFields=new HashMap<>(fields);legacyFields.keySet().removeIf(k->k.startsWith("targeting."));
        legacyFields.putAll(Map.of("acquire-distance","8","attack","0","attack-distance","2","attack-interval","1999"));
        var legacy=new SceneEntities(new SceneContent(List.of(new SceneContent.Entry("enemy",0,0,0,new SceneContent.Spec(legacyFields))),Map.of()),SceneWeaponsTest.openTerrain());
        legacy.advance(1000,List.of(player));int legacyStart=legacy.actors().getFirst().activityStarted();
        legacy.advance(legacyStart+500,List.of(player));legacy.advance(legacyStart+600,List.of(player));
        check((legacy.actors().getFirst().flags()&64)!=0,"legacy attack targeting also preserves the native impulse-applied flag across ticks");
        fields.remove("attack.0.impulse-duration");
        try{SceneAttackMotion.readAll(new SceneContent.Spec(fields));throw new AssertionError("Incomplete movement accepted");}catch(IllegalArgumentException expected){}
        try{new SceneAttackMotion(0,0,1,0);throw new AssertionError("Nonzero instantaneous movement accepted");}catch(IllegalArgumentException expected){}
        System.out.println("PASS: delayed native monster impulse, eased displacement, one application, collision and malformed-motion refusal");
    }
    private static String shape(float radius)throws Exception{return FireCatalog.modelJson(new StarterCatalog.Model("shape",Map.of("value",new FireCatalog.DataValue("com.threerings.tudey.shape.config.ShapeConfig$Circle",List.of(new FireCatalog.FieldValue("radius","float",radius))))));}
}
