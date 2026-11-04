package org.tortodrone.world;

import java.util.*;
import org.tortodrone.character.StarterCatalog;
import static org.tortodrone.support.TestWire.check;

/** Targeting authority/lifecycle on synthetic terrain; native attack data has a stock oracle. */
public final class SceneTargetingTest {
    public static void main(String[] args)throws Exception {
        var fields=new HashMap<String,String>();
        fields.putAll(Map.of("native-type","com.threerings.projectx.dungeon.data.actor.Monster","kind","Monster","config","Fixture","health","100","speed","2","radius",".4","turn-rate","174.5","damage-mask","2"));
        fields.putAll(Map.of("targeting.kind","single-path-attack","targeting.expansion","5.9647","targeting.evaluation","1000","targeting.attack","0","targeting.interval","1999","targeting.range","1.9997","targeting.turn-snap","1.541"));
        fields.putAll(Map.of("attack.0.land","0","attack.0.clear","250","attack.0.fires","0","attack.0.fire.0.delay","0"));
        var effect=new FireCatalog.Action(FireCatalog.Kind.FIRE_EFFECT,new StarterCatalog.Model("Fixture/Attack",Map.of()),null,"Source","","",0,0,0,0,true,0,0,0,0,false,List.of(),"");
        fields.put("attack.0.fire.0.action",FireCatalog.actionJson(effect));
        var content=new SceneContent(List.of(new SceneContent.Entry("enemy",0,0,0,new SceneContent.Spec(fields))),Map.of());
        var world=new SceneEntities(content,SceneWeaponsTest.openTerrain());int enemy=world.actors().getFirst().id();
        int[] stamp={1000};var attacks=new ArrayList<Integer>();world.attacks((actor,action)->attacks.add(stamp[0]));
        var near=player(219,6.69f);world.advance(stamp[0],List.of(near));stamp[0]+=50;world.advance(stamp[0],List.of(near));
        check((world.actor(enemy).flags()&16)!=0,"acquisition expands the original body shape, including player radius");
        for(;stamp[0]<8000;stamp[0]+=50)world.advance(stamp[0],List.of(near));
        check(world.actor(enemy).x()>3&&attacks.size()>=2,"native targeting pursues and emits repeated attacks");
        for(int i=1;i<attacks.size();i++)check(attacks.get(i)-attacks.get(i-1)>1999,"strict authored minimum interval is respected");
        var distant=player(219,30);stamp[0]+=50;world.advance(stamp[0],List.of(distant));
        check((world.actor(enemy).flags()&16)!=0,"living victims are retained beyond the acquisition area");
        world.playerActive(id->false);stamp[0]+=50;world.advance(stamp[0],List.of(distant));
        check((world.actor(enemy).flags()&16)==0,"departed victims lose targeting immediately");
        var far=new SceneEntities(content,SceneWeaponsTest.openTerrain());far.advance(1000,List.of(player(220,6.71f)));far.advance(1050,List.of(player(220,6.71f)));
        check((far.actors().getFirst().flags()&16)==0,"outside the expanded shape is not acquired");
        var hitWorld=new SceneEntities(content,SceneWeaponsTest.openTerrain());var damager=player(219,20);var peer=player(220,-2);
        hitWorld.advance(1000,List.of(damager,peer));hitWorld.advance(1050,List.of(damager,peer));
        hitWorld.damage(hit(),0,0,0,1100,damager.id());hitWorld.advance(1150,List.of(damager,peer));hitWorld.advance(1200,List.of(damager,peer));
        check((hitWorld.actors().getFirst().flags()&16)!=0&&hitWorld.actors().getFirst().x()>0,"actual incoming damage participates in authoritative targeting");
        try{new SceneTargeting(Float.NaN,1000,0,1999,2,1);throw new AssertionError("Non-finite policy accepted");}catch(IllegalArgumentException expected){}
        stationary(fields);
        fields.put("targeting.kind","client-code");
        try{SceneTargeting.read(new SceneContent.Spec(fields));throw new AssertionError("Unknown behavior accepted");}catch(IllegalArgumentException expected){}
        System.out.println("PASS: bounded native targeting, shape acquisition, pursuit, real attack emission/cooldown, victim retention/departure, damage admission and unknown-policy refusal");
    }
    private static void stationary(Map<String,String> source)throws Exception {
        var fields=new HashMap<>(source);fields.put("speed","0");fields.put("targeted-speed","0");
        var turret=new SceneContent.Entry("turret",0,0,0,new SceneContent.Spec(fields));
        var wall=new SceneContent.Entry("wall",2,0,0,new SceneContent.Spec(Map.of("native-type","Door","config","Fixture/Wall","width",".5","height","4","collision-flags","1")));
        var terrain=SceneWeaponsTest.openTerrain();var blocked=new SceneEntities(new SceneContent(List.of(turret,wall),Map.of()),terrain);
        var target=new ActorState(219,1001,"Fixture",0,1,5,1,0,0,0,new StarterCatalog.Model("Player",Map.of()));
        var emissions=new ArrayList<Integer>();blocked.attacks((actor,action)->emissions.add(actor.activityStarted()));
        for(int time=1000;time<2000;time+=50)blocked.advance(time,List.of(target));
        var actor=blocked.actors().getFirst();
        check((actor.flags()&16)!=0&&(actor.flags()&2)==0&&actor.x()==0&&actor.y()==0&&emissions.isEmpty(),"stationary acquired enemy neither pursues nor advertises movement through an occluding wall");
        check(Math.abs(actor.rotation()-Math.atan2(1,5))<.001,"stationary enemy turns toward its knight instead of a route around the wall");
        var clear=new SceneEntities(new SceneContent(List.of(turret),Map.of()),terrain);clear.attacks((pose,action)->emissions.add(pose.activityStarted()));
        for(int time=1000;time<2000;time+=50)clear.advance(time,List.of(player(219,1.5f)));
        check(!emissions.isEmpty()&&(clear.actors().getFirst().flags()&2)==0,"zero movement speed preserves native attack execution in range");
    }
    private static ActorState player(int id,float x){return new ActorState(id,1001,"Fixture",0,1,x,0,0,0,0,new StarterCatalog.Model("Player",Map.of()));}
    private static StarterCatalog.Model hit(){
        var shape=new FireCatalog.DataValue("com.threerings.tudey.shape.config.ShapeConfig$Circle",List.of(new FireCatalog.FieldValue("radius","float",1f)));
        var damage=new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$Normal",List.of(new FireCatalog.FieldValue("flags","int",2),new FireCatalog.FieldValue("amount","float",1f)));
        return new StarterCatalog.Model("DamageRegion",Map.of("shape",shape,"damage",damage));
    }
}
