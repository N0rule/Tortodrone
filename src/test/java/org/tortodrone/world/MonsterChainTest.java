package org.tortodrone.world;

import java.util.*;
import org.tortodrone.character.StarterCatalog;
import static org.tortodrone.support.TestWire.check;

/** Native attack rearm/chain timing through the production entity tick, without a server timer. */
public final class MonsterChainTest {
    public static void main(String[] args)throws Exception {
        var fields=new HashMap<String,String>();
        fields.putAll(Map.of("config","Fixture/ChainedMonster","native-type","com.threerings.projectx.dungeon.data.actor.Monster","kind","Monster","health","10","damage-mask","2","radius",".2","speed","0","acquire-distance","3","attack-distance","2","attack","0"));
        fields.putAll(Map.of("acquisition-interval","0","attack-interval","100000","attack.0.land","0","attack.0.clear","2000","attack.0.fire-speed","1","attack.0.chain-next","1","attack.0.chain-rearm","1000","attack.0.fires","0","attack.0.fire.0.delay","0","attack.0.fire.0.action",effect("Warning")));
        fields.putAll(Map.of("attack.1.land","2335","attack.1.clear","5669","attack.1.fire-speed","1","attack.1.fires","0","attack.1.fire.0.delay","0","attack.1.fire.0.action",effect("Followup")));
        new SceneActions.Spawn(fields);
        for(var invalid:List.of(Map.of("attack.0.chain-next","255"),Map.of("attack.0.chain-rearm","-1"),Map.of("attack.0.chain-unsupported","Tagged"))){
            var rejected=new HashMap<>(fields);rejected.putAll(invalid);
            try{new SceneActions.Spawn(rejected);throw new AssertionError("Unsupported/invalid prepared chain accepted");}catch(IllegalArgumentException expected){}
        }
        var content=new SceneContent(List.of(new SceneContent.Entry("monster",0,0,0,new SceneContent.Spec(fields))),Map.of());
        var scene=new SceneEntities(content,SceneWeaponsTest.openTerrain());var events=new ArrayList<String>();
        scene.attacks((actor,action)->events.add(action.config().name()+":"+actor.activity()));
        int start=SceneClock.now()+100;
        var player=new ActorState(219,1001,"Fixture",256,start,.5f,0,0,0,0,0,start,new StarterCatalog.Model("Fixture",Map.of()),false);
        int id=scene.actors().getFirst().id();scene.advance(start,List.of(player));
        check(scene.actor(id).activity()==4,"normal targeting starts the chain head");
        scene.advance(start+1000,List.of(player));
        check(scene.actor(id).activity()==4&&events.equals(List.of("Warning:4")),"native rearm uses a strict greater-than test and fires the head once");
        scene.advance(start+1001,List.of());int follow=start+1001;
        check(scene.actor(id).activity()==5&&scene.actor(id).activityStarted()==follow,"authored chain advances without reacquiring or requiring a new target");
        scene.advance(follow+2334,List.of());check(events.size()==1,"follow-up retains its own land timing");
        scene.advance(follow+2335,List.of());check(events.equals(List.of("Warning:4","Followup:5")),"follow-up uses its own action table exactly once");
        scene.advance(follow+5669,List.of());check(scene.actor(id).activity()==0,"follow-up clears at its own authored time");
        scene.advance(follow+10000,List.of(player));check(scene.actor(id).activity()==0&&events.size()==2,"chaining preserves the head cooldown and cannot replay fire receipts");

        var killed=new SceneEntities(content,SceneWeaponsTest.openTerrain());var killedEvents=new ArrayList<String>();
        killed.attacks((actor,action)->killedEvents.add(action.config().name()));killed.advance(start,List.of(player));
        var damage=new StarterCatalog.Model("DamageRegion",Map.of("shape",new FireCatalog.DataValue("Shape$Circle",Map.of("radius",1f)),"damage",new FireCatalog.DataValue("Damage$Normal",Map.of("flags",2,"amount",20f))));
        killed.damage(damage,0,0,0,start+1,219);killed.advance(start+1001,List.of(player));
        check(killedEvents.isEmpty(),"death cancels both pending chain and attacks");
        System.out.println("PASS: strict native chain rearm, follow-up table/timing, target loss, cooldown, duplicate-fire guards and death cancellation");
    }
    private static String effect(String name)throws java.io.IOException{return FireCatalog.actionJson(new FireCatalog.Action(FireCatalog.Kind.FIRE_EFFECT,new StarterCatalog.Model(name,Map.of()),null,"Source","","",0,0,0,0,true,0,0,0,0,false,List.of(),""));}
}
