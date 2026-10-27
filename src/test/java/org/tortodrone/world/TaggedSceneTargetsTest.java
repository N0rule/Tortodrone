package org.tortodrone.world;

import java.util.*;
import org.tortodrone.character.StarterCatalog;
import org.tortodrone.resources.Json;
import static org.tortodrone.support.TestWire.check;

/** Synthetic targets exercise the production scene selector, spawn and destruction paths. */
public final class TaggedSceneTargetsTest {
    private static final StarterCatalog.Model EFFECT=new StarterCatalog.Model("Basic",Map.of());
    private static final Map<String,String> MONSTER=Map.of("config","Fixture/Monster","native-type","com.threerings.projectx.dungeon.data.actor.Monster","kind","Monster","health","10","radius",".4","collision-flags","1","damage-mask","2","tags","spawned");
    public static void main(String[] args)throws Exception {
        var effect=Map.of("kind","tagged-effect","config",FireCatalog.modelJson(EFFECT),"tag","spots");
        var spawn=Map.of("kind","spawn","fields",MONSTER,"activate",false,"tag","spots");
        var ending=sequence(List.of(Map.of("kind","destroy-many","tags",List.of("missing","spawned","spawned")),Map.of("kind","tagged-effect","config",FireCatalog.modelJson(EFFECT),"tag","spawned"),Map.of("kind","source-effect","config",FireCatalog.modelJson(EFFECT))),true);
        var graph=limited(sequence(List.of(effect,spawn,Map.of("kind","delay","millis",100,"action",ending)),true));
        var seedFields=new HashMap<>(MONSTER);seedFields.put("tags","spots");
        var seed=new SceneContent.Entry("seed",12,0,.9f,new SceneContent.Spec(seedFields));
        var content=new SceneContent(List.of(sensor("100",graph),anchor("200",5,.7f,"spots"),anchor("201",8,1.3f,"spots"),seed),Map.of());
        var terrain=SceneWeaponsTest.openTerrain();var world=new SceneEntities(content,terrain);var isolated=new SceneEntities(content,terrain);var effects=new SceneWeapons(terrain);world.effects(effects::effect);
        int now=SceneClock.now()+100;var player=player(0,0,now);world.advance(now,List.of(player));isolated.advance(now,List.of(player(20,20,now)));
        check(effects.effects().size()==3&&effects.effects().stream().filter(e->e.targetEntry()!=null).count()==2&&effects.effects().stream().filter(e->e.targetActor()!=0).count()==1,"Tagged effect selects original entries and live actors with the correct identity kinds");
        check(world.actors().size()==4&&world.actors().stream().noneMatch(a->a.id()==0),"markers and script handlers never create phantom actors");
        var spawned=world.actors().stream().filter(a->world.specification(a.id()).text("tags","").equals("spawned")).toList();
        check(spawned.size()==3&&spawned.stream().anyMatch(a->a.x()==5&&a.rotation()==.7f)&&spawned.stream().anyMatch(a->a.x()==8&&a.rotation()==1.3f)&&spawned.stream().anyMatch(a->a.x()==12&&a.rotation()==.9f),"one server actor is spawned at every authored entry/actor transform");
        check(!world.walkable(5,0,player.id()),"tagged spawned actor owns collision");
        check(isolated.actors().size()==1,"target lookup and spawning stay within their instance");
        world.advance(now+100,List.of(player));
        check(effects.effects().size()==7&&effects.effects().getLast().targetEntry().equals(100),"compound destruction succeeds when any target exists, and Tagged retains actors until removal");
        check(world.actors().stream().filter(a->a.entity().destroyed()==now+100).count()==3&&world.actors().stream().allMatch(a->a.entity().health()==1),"duplicate/overlapping destruction targets publish once without combat damage");
        world.advance(now+101,List.of(player));check(world.actors().size()==1&&world.walkable(5,0,player.id()),"normal removal releases all spawned collisions");
        missing(terrain,player,now);
        bounded(terrain,player,now);
        var metadata=new HashMap<>(MONSTER);for(int i=0;i<150;i++)metadata.put("attack.0.metadata."+i,"retained");
        try{Json.parse(Json.stringify(metadata));throw new AssertionError("Ordinary JSON field bound was widened");}catch(java.io.IOException expected){}
        check(SceneActions.parse(Json.stringify(Map.of("kind","spawn","fields",metadata,"activate",false))) instanceof SceneActions.Spawn,"native multi-attack metadata may exceed the ordinary JSON object's 128-field limit");
        for(int i=0;i<1025;i++)metadata.put("attack.0.metadata."+i,"retained");
        try{SceneActions.parse(Json.stringify(Map.of("kind","spawn","fields",metadata,"activate",false)));throw new AssertionError("Oversized actor metadata accepted");}catch(java.io.IOException expected){}
        for(var invalid:List.of(Map.of("kind","tagged-effect","config",FireCatalog.modelJson(EFFECT),"tag",""),Map.of("kind","spawn","fields",MONSTER,"activate",false,"tag",""),Map.of("kind","destroy-many","tags",Collections.nCopies(257,"a")))){
            try{SceneActions.parse(Json.stringify(invalid));throw new AssertionError("Invalid target descriptor accepted");}catch(java.io.IOException expected){}
        }
        try{new SceneEntities(new SceneContent(List.of(anchor("200",5,0,"spots"),anchor("200",6,0,"spots")),Map.of()),terrain);throw new AssertionError("Duplicate entry target accepted");}catch(IllegalArgumentException expected){}
        System.out.println("PASS: native-shaped Tagged entry/actor effects and spawning, exact transforms, collision, OR destruction, no phantom actors, instance isolation, missing targets and atomic selection bounds");
    }
    private static void missing(CollisionMap terrain,ActorState player,int now)throws Exception{
        var graph=limited(sequence(List.of(Map.of("kind","spawn","fields",MONSTER,"activate",false,"tag","absent"),Map.of("kind","tagged-effect","config",FireCatalog.modelJson(EFFECT),"tag","absent"),Map.of("kind","source-effect","config",FireCatalog.modelJson(EFFECT))),true));
        var world=new SceneEntities(new SceneContent(List.of(sensor("300",graph)),Map.of()),terrain);var effects=new SceneWeapons(terrain);world.effects(effects::effect);world.advance(now,List.of(player));
        check(world.actors().isEmpty()&&effects.effects().size()==1&&effects.effects().getFirst().targetEntry().equals(300),"empty Tagged spawn/effect succeeds without falling back to Source");
    }
    private static void bounded(CollisionMap terrain,ActorState player,int now)throws Exception{
        var entries=new ArrayList<SceneContent.Entry>();entries.add(sensor("400",limited(sequence(List.of(Map.of("kind","spawn","fields",MONSTER,"activate",false,"tag","many"),Map.of("kind","source-effect","config",FireCatalog.modelJson(EFFECT))),true))));
        for(int i=0;i<257;i++)entries.add(anchor(Integer.toString(1000+i),5,0,"many"));
        var world=new SceneEntities(new SceneContent(entries,Map.of()),terrain);var effects=new SceneWeapons(terrain);world.effects(effects::effect);world.advance(now,List.of(player));
        check(world.actors().isEmpty()&&effects.effects().isEmpty(),"oversized spawn selection fails before publishing any part or running the next guarded action");
    }
    private static SceneContent.Entry anchor(String key,float x,float rotation,String tag){return new SceneContent.Entry(key,x,0,rotation,new SceneContent.Spec(Map.of("kind","SceneAnchor","config","Fixture/Marker","source-tags",tag)));}
    private static SceneContent.Entry sensor(String key,Object action)throws java.io.IOException{return new SceneContent.Entry(key,0,0,0,new SceneContent.Spec(Map.ofEntries(Map.entry("config","Fixture/Area"),Map.entry("kind","SceneTrigger"),Map.entry("event","signal-start"),Map.entry("watch-tag","area:"+key),Map.entry("signal","intersection"),Map.entry("scene-sensor","true"),Map.entry("sensor-mask","4"),Map.entry("radius","1"),Map.entry("action",Json.stringify(action)))));}
    private static Map<String,Object> sequence(List<?> actions,boolean stop){return Map.of("kind","sequence","actions",actions,"stopOnFailure",stop);}
    private static Map<String,Object> limited(Object action){return Map.of("kind","limited","limit",1,"action",action,"otherwise",sequence(List.of(),false));}
    private static ActorState player(float x,float y,int stamp){return new ActorState(219,1001,"Fixture",0,stamp,x,y,0,0,0,new StarterCatalog.Model("Character/PC/Default",Map.of()));}
}
