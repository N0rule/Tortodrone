package org.tortodrone.world;

import java.util.*;
import org.tortodrone.character.StarterCatalog;
import org.tortodrone.resources.Json;
import static org.tortodrone.support.TestWire.check;

/** Synthetic geometry: real scene scripts, pathing, weapon damage and source-owned lifecycle. */
public final class SceneScriptTest {
    public static void main(String[] args)throws Exception {
        var replacement=Map.of("kind","Block","native-type","com.threerings.projectx.dungeon.data.actor.Block","config","Fixture/Replacement","shape","Global","collision-flags","0","collision-mask","0","tags","replacement");
        var program=List.of(Map.of("kind","condition","name","activate"),Map.of("kind","wait","millis",200),Map.of("kind","move","tag","goal","maxPath",.5f),Map.of("kind","rotate","direction",Math.PI/2,"variance",0,"relative",true),Map.of("kind","attack","index",0),
            Map.of("kind","action","action",Map.of("kind","delay","millis",500,"action",Map.of("kind","signal","tag","stale","name","open"))),Map.of("kind","action","action",Map.of("kind","destroy-source")));
        var fields=new HashMap<String,String>(Map.of("kind","Monster","native-type","com.threerings.projectx.dungeon.data.actor.Monster","config","Fixture/Scripted","health","0","speed","2","turn-rate",Float.toString((float)Math.PI*2),"tags","npc"));
        fields.put("script.program",Json.stringify(program));fields.put("script.activate-signal","activate");
        fields.put("attack.0.clear","400");fields.put("attack.0.land","50");fields.put("attack.0.fire-speed","1");fields.put("attack.0.fires","0,1");fields.put("attack.0.fire.0.delay","25");fields.put("attack.0.fire.1.delay","25");
        var damage=new StarterCatalog.Model("DamageRegion",Map.of("shape",new FireCatalog.DataValue("com.threerings.tudey.shape.config.ShapeConfig$Circle",Map.of("radius",1f)),"damage",new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$Normal",Map.of("flags",1,"amount",7f))));
        var fire=new FireCatalog.Action(FireCatalog.Kind.DAMAGE_REGION,damage,null,"Source","","",0,0,0,0,false,0,0,0,0,false,List.of(),"");fields.put("attack.0.fire.0.action",FireCatalog.actionJson(fire));
        String dialogue="@conversation/Fixture";fields.put("attack.0.fire.1.scene-action",Json.stringify(Map.of("kind","conversation","id",dialogue,"activator",false)));
        fields.put("scene-shutdown-action",Json.stringify(Map.of("kind","spawn","fields",replacement,"activate",false)));
        var npc=entry("1",0,0,fields);
        var goal=entry("2",2,0,Map.of("kind","SceneAnchor","config","Fixture/Goal","source-tags","goal"));
        var sensor=entry("3",0,-2,Map.of("kind","SceneTrigger","config","Fixture/Activation","event","signal-start","watch-tag","sensor","signal","intersection","scene-sensor","true","sensor-mask","4","radius",".4","action",Json.stringify(Map.of("kind","signal","tag","npc","name","activate"))));
        var stale=entry("4",8,0,Map.of("kind","Door","config","Fixture/Stale","native-type","com.threerings.projectx.dungeon.data.actor.Door","tags","stale","collision-flags","1","width","1","height","1","door-speed","0"));
        var conversation=new SceneConversation(dialogue,"n.fixture","mission","first",null,Map.of("first",new SceneConversation.Node("first","m.fixture",List.of(),null,null)));
        var content=new SceneContent(List.of(npc,goal,sensor,stale),Map.of(),Map.of(dialogue,conversation),Map.of("player.base-health",100f),Map.of(),Map.of(),Map.of());content.validateConversations();
        var world=new SceneEntities(content,SceneWeaponsTest.openTerrain());var isolated=new SceneEntities(content,SceneWeaponsTest.openTerrain());
        var health=new SceneHealth(content);world.playerHits(hit->health.hit(hit.damage(),hit.timestamp(),hit.multiplier()));
        var weapons=new SceneWeapons(SceneWeaponsTest.openTerrain());weapons.targets(world);int[] now={1000};var attackTimes=new ArrayList<Integer>();
        world.attacks((actor,action)->{check(now[0]>=actor.activityStarted()+75,"native land plus fire delay retained");attackTimes.add(now[0]);weapons.fire(action,actor,now[0]);});
        var talks=new ArrayList<Integer>();world.conversations((definition,source,stamp,target)->{check(definition==conversation&&target==0&&source.entity().key().equals("1"),"attack conversation keeps its NPC source and scene party target");talks.add(stamp);return true;});
        var trigger=player(21,0,-2);var victim=player(22,2.5f,0);float previous=0;
        for(int stamp=1000;stamp<=6000;stamp+=50){now[0]=stamp;world.advance(stamp,List.of(trigger,victim));weapons.advance(stamp,List.of(trigger,victim));
            var actor=world.actors().stream().filter(a->a.config().name().equals("Fixture/Scripted")).findFirst().orElse(null);
            if(actor!=null&&actor.entity().destroyed()==Integer.MAX_VALUE){check(actor.x()-previous<=.101f,"native script movement obeys speed, no target teleport");previous=actor.x();if(stamp<=1300)check(actor.x()==0,"strict authored wait boundary");}
        }
        check(attackTimes.size()==1&&talks.size()==1&&health.state().current()==93,"scripted attack fires real authoritative damage and native conversation exactly once");
        var replaced=world.actors().stream().filter(a->a.config().name().equals("Fixture/Replacement")).findFirst().orElseThrow();
        check(Math.abs(replaced.x()-2)<.0001&&Math.abs(replaced.rotation()-Math.PI/2)<.0001,"shutdown spawns at the removed actor's actual final transform");
        check(world.actors().stream().noneMatch(a->a.config().name().equals("Fixture/Scripted"))&&!world.walkable(8,0,21),"Source destruction removes its actor and cancels delayed script work");
        isolated.advance(6000,List.of());check(isolated.actors().stream().anyMatch(a->a.config().name().equals("Fixture/Scripted")&&a.x()==0),"another instance remains held at its activation condition");
        var noOp=new SceneActions.Sequence(List.of(),false);var events=new ArrayList<String>();var actions=new SceneActions(List.of(),(tag,name,stamp)->{events.add(name);return true;});var prepared=actions.prepare(new SceneActions.Limited(1,new SceneActions.Signal("out","once"),noOp));
        actions.perform(prepared,1,"actor:1","actor:1");actions.advance(1);actions.perform(prepared,2,"actor:1","actor:1");actions.advance(2);check(events.equals(List.of("once")),"prepared script steps retain condition state across loops");
        var foreign=new SceneActions(List.of(),(tag,name,stamp)->true);try{foreign.perform(prepared,2,"actor:1","actor:1");throw new AssertionError("Foreign scene program accepted");}catch(IllegalArgumentException expected){}
        // A stationary attack script needs no turning. Execute its real attack
        // through several Goto cycles, rather than merely accepting the metadata.
        var stationary=new HashMap<>(fields);stationary.put("turn-rate","0");stationary.remove("scene-shutdown-action");stationary.put("attack.0.fires","0");
        stationary.put("script.program",Json.stringify(List.of(Map.of("kind","wait","millis",100),Map.of("kind","attack","index",0),Map.of("kind","goto","step",0))));
        var loop=new SceneEntities(new SceneContent(List.of(entry("5",0,0,stationary)),Map.of()),SceneWeaponsTest.openTerrain());var loopFires=new ArrayList<Integer>();
        loop.attacks((actor,action)->loopFires.add(actor.activityStarted()));
        for(int stamp=1000;stamp<=4000;stamp+=50)loop.advance(stamp,List.of());
        check(loopFires.size()>=3&&new HashSet<>(loopFires).size()==loopFires.size()&&loop.actors().getFirst().x()==0,"zero-turn stationary scripts execute bounded attack/Goto cycles without duplicate fire or movement");
        var cannotTurn=new HashMap<>(fields);cannotTurn.put("turn-rate","0");try{SceneScript.read(new SceneContent.Spec(cannotTurn));throw new AssertionError("Immobile Move/Rotate script accepted");}catch(IllegalArgumentException expected){}
        for(String bad:List.of("[{\"kind\":\"goto\",\"step\":99}]","[{\"kind\":\"wait\",\"millis\":-1}]","[{\"kind\":\"attack\",\"index\":9}]","[{\"kind\":\"java\",\"class\":\"arbitrary\"}]")){var invalid=new HashMap<>(fields);invalid.put("script.program",bad);try{SceneScript.read(new SceneContent.Spec(invalid));throw new AssertionError("Invalid native script accepted");}catch(IllegalArgumentException expected){}}
        System.out.println("PASS: bounded native scripted behavior activation, strict waits, legal movement, relative rotation, actual attack/damage and dialogue, Source removal, shutdown replacement, cancellation, per-step counters and scene ownership (synthetic geometry)");
    }
    private static SceneContent.Entry entry(String key,float x,float y,Map<String,String> fields){return new SceneContent.Entry(key,x,y,0,new SceneContent.Spec(fields));}
    private static ActorState player(int id,float x,float y){return new ActorState(id,1001,"Fixture",0,1000,x,y,0,0,0,new StarterCatalog.Model("Character/PC/Default",Map.of()));}
}
