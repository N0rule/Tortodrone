package org.tortodrone.world;

import java.io.IOException;
import java.util.*;
import org.tortodrone.resources.Json;
import static org.tortodrone.support.TestWire.check;

/** Synthetic geometry: production counter scheduling and native door collision, not a floor drive. */
public final class SceneSignalCounterTest {
    public static void main(String[] args)throws Exception {
        var fired=new ArrayList<String>();
        var action=new SceneActions.Count(2,new SceneActions.Signal("door","open"));
        var rules=List.of(rule("a","a",action),rule("b","b",action));
        var runtime=new SceneActions(rules,(tag,name,stamp)->{fired.add(name+":"+stamp);return true;});
        runtime.signal("a","increment",1,"button:1");runtime.advance(1);check(fired.isEmpty(),"first button does not open the gate");
        runtime.signal("b","increment",2,"button:2");runtime.advance(2);check(fired.equals(List.of("open:2")),"aliases share the receiver's counter and open on the second button's timestamp");
        for(int i=3;i<30;i++){runtime.signal("a","increment",i);runtime.advance(i);}check(fired.size()==1,"increments beyond equality do not replay the action");
        var isolated=new SceneActions(rules,(tag,name,stamp)->{throw new AssertionError("Foreign scene counter fired");});isolated.signal("a","increment",30);isolated.advance(30);
        runtime.close();runtime.signal("a","increment",31);runtime.advance(31);check(runtime.pending()==0,"closed counter schedules no work");
        var codec=Json.stringify(Map.of("kind","count","threshold",2,"action",Map.of("kind","signal","tag","door","name","open")));
        check(SceneActions.parse(codec).equals(action),"typed counter graph round trip");
        for(String invalid:List.of(codec.replace("\"threshold\":2","\"threshold\":0"),codec.replace("\"threshold\":2","\"threshold\":1000001"),codec.replace("\"threshold\":2","\"threshold\":1.5")))
            try{SceneActions.parse(invalid);throw new AssertionError("Invalid counter bound accepted");}catch(IOException expected){}
        var map=SceneCatalog.load(org.tortodrone.support.ContentFixture.path("maps")).scene(294);
        doors(map.collision(),false);doors(map.collision(),true);levers(map.collision());
        System.out.println("PASS: increment-only native counters, equality timing, alias sharing, scene isolation, replay/close/bounds, direct door and marker-driven door animation/collision (synthetic geometry)");
    }
    private static SceneActions.Rule rule(String key,String tag,SceneActions.Action action){return new SceneActions.Rule(key,"signal",tag,"increment",false,action,"counter","entry:counter");}
    private static void doors(CollisionMap terrain,boolean marker)throws Exception {
        var door=new TreeMap<String,String>(Map.of("kind","Door","config","Fixture/Door","native-type","com.threerings.projectx.dungeon.data.actor.Door","tags","door","width","2","height","1","collision-flags","1","door-speed","100"));
        if(!marker)door.put("door-increments","2");
        var bindings=new ArrayList<SceneContent.Entry>();bindings.add(entry("door",3,0,door));
        if(marker)bindings.add(entry("counter",0,0,Map.of("kind","SceneTrigger","config","Fixture/Counter","event","signal","watch-tag","counter","signal","increment","action",Json.stringify(Map.of("kind","count","threshold",2,"action",Map.of("kind","signal","tag","door","name","open"))))));
        var signal=Map.of("kind","signal","tag",marker?"counter":"door","name","increment");
        bindings.add(entry("start",0,0,Map.of("kind","SceneTrigger","config","Fixture/Startup","event","signal-start","watch-tag","startup","signal","startup","scene-startup","true","action",Json.stringify(Map.of("kind","sequence","stopOnFailure",false,"actions",List.of(signal,Map.of("kind","delay","millis",1000,"action",signal)))))));
        var content=new SceneContent(bindings,Map.of());var scene=new SceneEntities(content,terrain);var peer=new SceneEntities(content,terrain);
        int now=scene.actors().getFirst().created();scene.advance(now,List.of());
        check(!scene.walkable(3,0,219),"first switch leaves physical door collision intact");
        scene.advance(now+999,List.of());check(!scene.walkable(3,0,219),"counter never opens early");
        scene.advance(now+1000,List.of());var actor=scene.actors().stream().filter(a->a.entity().kind().equals("Door")).findFirst().orElseThrow();
        check((actor.flags()&6)==6&&actor.entity().stateEntered()==now+1000&&!scene.walkable(3,0,219),"second switch publishes open transition at its timestamp while animation still blocks");
        scene.advance(now+1101,List.of());check(scene.walkable(3,0,219)&&!peer.walkable(3,0,219),"native animation completes before collision opens, isolated per scene");
    }
    private static SceneContent.Entry entry(String key,float x,float y,Map<String,String> fields){return new SceneContent.Entry(key,x,y,0,new SceneContent.Spec(fields));}
    private static void levers(CollisionMap terrain)throws Exception {
        var bindings=new ArrayList<SceneContent.Entry>();
        bindings.add(entry("door",3,0,Map.of("kind","Door","config","Fixture/Door","native-type","com.threerings.projectx.dungeon.data.actor.Door","tags","door","width","2","height","1","collision-flags","1","door-speed","0")));
        for(int i=0;i<2;i++)bindings.add(entry("lever"+i,i*2,0,Map.of("kind","Switch","config","Fixture/One-Time Lever","native-type","com.threerings.projectx.dungeon.data.actor.Switch","switch-mode","damage-once","switch-damage-mask","2","radius",".25","state.1.tag","counter","state.1.signal","increment")));
        bindings.add(entry("counter",0,1,Map.of("kind","SceneTrigger","config","Fixture/Counter","event","signal","watch-tag","counter","signal","increment","action",Json.stringify(Map.of("kind","count","threshold",2,"action",Map.of("kind","signal","tag","door","name","open"))))));
        var scene=new SceneEntities(new SceneContent(bindings,Map.of()),terrain);int now=scene.actors().getFirst().created()+100;
        var shape=new FireCatalog.DataValue("com.threerings.tudey.shape.config.ShapeConfig$Circle",Map.of("radius",.1f));
        var hit=new org.tortodrone.character.StarterCatalog.Model("DamageRegion",Map.of("shape",shape,"damage",new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$Normal",Map.of("flags",2,"amount",10f))));
        var wrong=new org.tortodrone.character.StarterCatalog.Model("DamageRegion",Map.of("shape",shape,"damage",new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$Normal",Map.of("flags",1,"amount",10f))));
        scene.damage(wrong,0,0,0,now,219);scene.advance(now,List.of());
        check(scene.actors().stream().filter(a->a.entity().kind().equals("Switch")).allMatch(a->a.entity().state()==0),"lever rejects the wrong native damage mask");
        scene.damage(hit,0,0,0,now+1,219);scene.advance(now+1,List.of());check(!scene.walkable(3,0,219),"one activated lever cannot open the two-control gate");
        scene.damage(hit,0,0,0,now+2000,219);scene.advance(now+2000,List.of());
        check(!scene.walkable(3,0,219)&&scene.actors().stream().filter(a->a.entity().key().equals("lever0")).findFirst().orElseThrow().entity().state()==1,"repeat weapon hits cannot reset a one-time lever or increment again");
        scene.damage(hit,2,0,0,now+2001,219);scene.advance(now+2001,List.of());scene.advance(now+2002,List.of());
        check(scene.walkable(3,0,219),"the distinct second lever opens the real native door/collision path");
    }
}
