package org.tortodrone.world;

import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.character.StarterCatalog;
import org.tortodrone.resources.Json;
import static org.tortodrone.support.TestWire.check;

/** Synthetic area exercises the production tick and existing scene effect publication queue. */
public final class ScriptedSceneEffectTest {
    public static void main(String[] args)throws Exception {
        var effect=new StarterCatalog.Model("Basic",Map.of("Lifespan",1000));
        String json=Json.stringify(Map.of("kind","source-effect","config",FireCatalog.modelJson(effect)));
        var action=SceneActions.parse(json);check(action.equals(new SceneActions.SourceEffect(effect)),"allowlisted source effect retains config arguments");
        String graph=Json.stringify(Map.of("kind","limited","limit",1,"action",Map.of("kind","delay","millis",150,"action",Json.parse(json)),"otherwise",Map.of("kind","sequence","actions",List.of(),"stopOnFailure",false)));
        var sensor=new SceneContent.Entry("500",0,0,1.25f,new SceneContent.Spec(Map.ofEntries(Map.entry("config","Fixture/Area"),Map.entry("kind","SceneTrigger"),Map.entry("event","signal-start"),Map.entry("watch-tag","area"),Map.entry("signal","intersection"),Map.entry("scene-sensor","true"),Map.entry("sensor-mask","4"),Map.entry("width","2"),Map.entry("height","2"),Map.entry("action",graph))));
        var content=new SceneContent(List.of(sensor),Map.of());
        var map=new SceneCatalog.MapScene(900001,33554432,"Fixture","Fixture","fixture","training",1,0,0,0,1,SceneWeaponsTest.openTerrain(),new byte[0],ScenePortals.EMPTY,content);
        var scene=new SceneRegistry.Instance(7001,0,map).simulation();var isolated=new SceneRegistry.Instance(7002,0,map).simulation();
        var baseline=scene.snapshot();var frames=new LinkedBlockingQueue<SceneSimulation.Snapshot>();int stamp=SceneClock.now();
        var player=new ActorState(21,1001,"Fixture",0,stamp,0,0,0,0,0,new StarterCatalog.Model("Character/PC/Default",Map.of()));
        try(var member=scene.join(player,frames::add)){
            member.enter();SceneEffect published=null;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
            while(System.nanoTime()<until){var frame=frames.poll(250,TimeUnit.MILLISECONDS);if(frame!=null&&!frame.effectsSince(baseline).isEmpty()){published=frame.effectsSince(baseline).getFirst();break;}}
            check(published!=null&&published.targetEntry().equals(500)&&published.targetActor()==0&&published.ownerOid()==0,"scripted effect publishes the native entry target to its controlling client");
            check(published.config().equals(effect)&&published.x()==0&&published.y()==0&&published.rotation()==1.25f,"source transform and authored effect arguments survive the scene services");
            check(isolated.snapshot().effectsSince(baseline).isEmpty(),"another instance has no cosmetic effect");
            check(scene.snapshot().actors().stream().noneMatch(actor->actor.id()==0),"effect entry identity never creates a pawn");
        }
        check(scene.playerCount()==0,"effect publication leaves no occupants");
        var weapons=new SceneWeapons(map.collision());weapons.effect(effect,player,100);
        var actorEffect=weapons.effects().getFirst();check(actorEffect.targetEntry()==null&&actorEffect.targetActor()==21,"existing actor-targeted effects keep their native identity");
        weapons.advance(5101,List.of(player));check(weapons.effects().isEmpty(),"existing effect retention cleans expired entry/actor notifications");
        for(int mode=0;mode<2;mode++)try{new SceneEffect(1,effect,1,0,0,0,0,mode==0?1:0,mode==0?500:0);throw new AssertionError("Invalid effect target accepted");}catch(IllegalArgumentException expected){}
        int[] fired={0};var scheduler=new SceneActions(List.of(new SceneActions.Rule("entry:500","signal","in","go",false,new SceneActions.Delay(100,action))),
            (tag,name,time,source)->true,new Random(1),(tag,time,source)->false,(spawn,time,source)->false,(status,time,source)->false,(conversation,time,source,activator)->false,(operation,time,source)->{fired[0]++;return true;});
        scheduler.signal("in","go",100);scheduler.advance(100);scheduler.close();scheduler.advance(1000);check(fired[0]==0&&scheduler.pending()==0,"closing the scheduler cancels delayed effects");
        cosmeticBlocks(map.collision(),player);
        System.out.println("PASS: authored delayed source effect -> production scene tick/publication; entry identity, source transform, actor compatibility, isolation, cleanup and scheduler cancellation");
    }
    private static void cosmeticBlocks(CollisionMap terrain,ActorState player)throws Exception {
        var fields=Map.of("kind","Block","native-type","com.threerings.projectx.dungeon.data.actor.Block","config","Fixture/Music","shape","Global","collision-flags","0","collision-mask","0","damage-mask","3","tags","music");
        var spawn=Map.of("kind","spawn","fields",fields,"activate",false);
        var graph=Map.of("kind","limited","limit",1,"action",Map.of("kind","sequence","stopOnFailure",true,"actions",List.of(spawn,Map.of("kind","delay","millis",100,"action",Map.of("kind","destroy","tag","music")))),"otherwise",Map.of("kind","sequence","stopOnFailure",false,"actions",List.of()));
        var source=new HashMap<String,String>(Map.of("config","Fixture/Area","kind","SceneTrigger","event","signal-start","watch-tag","area","signal","intersection","scene-sensor","true","sensor-mask","4","radius","2"));source.put("action",Json.stringify(graph));
        var world=new SceneEntities(new SceneContent(List.of(new SceneContent.Entry("600",0,0,0,new SceneContent.Spec(source))),Map.of()),terrain);
        world.advance(100,List.of(player));var actor=world.actors().getFirst();
        check(actor.entity().kind().equals("Block")&&actor.entity().destroyed()==Integer.MAX_VALUE&&world.walkable(0,0,player.id()),"cosmetic global block spawns without physical collision");
        var hit=new StarterCatalog.Model("DamageRegion",Map.of("shape",new FireCatalog.DataValue("com.threerings.tudey.shape.config.ShapeConfig$Circle",Map.of("radius",1f)),"damage",new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$Normal",Map.of("flags",2,"amount",20f))));
        world.damage(hit,0,0,0,150,player.id());check(world.actor(actor.id())!=null&&world.actor(actor.id()).entity().hitTime()==0,"native block without damageAction ignores hits despite its damage mask");
        world.advance(200,List.of(player));check(world.actors().getFirst().entity().destroyed()==200,"tagged music destruction publishes the native timestamp");
        world.advance(201,List.of(player));check(world.actors().isEmpty(),"cosmetic block is removed on the next distinct tick");
        var colliding=new HashMap<>(fields);colliding.put("collision-flags","1");
        try{new SceneActions.Spawn(colliding,false);throw new AssertionError("Colliding cosmetic block accepted");}catch(IllegalArgumentException expected){}
        try{new SceneActions.Spawn(fields,true);throw new AssertionError("Cosmetic block accepted as monster activation");}catch(IllegalArgumentException expected){}
    }
}
