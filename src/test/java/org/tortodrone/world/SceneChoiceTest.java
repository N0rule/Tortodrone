package org.tortodrone.world;

import java.util.*;
import org.tortodrone.resources.Json;
import static org.tortodrone.support.TestWire.check;

public final class SceneChoiceTest {
    public static void main(String[] args)throws Exception {
        var hits=new ArrayList<String>();
        var choice=new SceneActions.Choice(List.of(new SceneActions.Signal("a","open"),new SceneActions.Signal("b","open")),List.of(0f,3f));
        var runtime=new SceneActions(List.of(new SceneActions.Rule("start","signal-start","start","activate",false,choice)),(tag,name,stamp,source)->{hits.add(tag+":"+source+":"+stamp);return true;},new Random(1));
        runtime.signal("start","activate",100,"entry:12");runtime.advance(100);runtime.signal("start","activate",100,"entry:12");runtime.advance(101);
        check(hits.equals(List.of("b:start:100")),"zero-weight branch never selected and duplicate startup preserves its handler source/time: "+hits);
        for(var weights:List.of(List.of(-1f,1f),List.of(0f,0f),List.of(Float.NaN,1f)))try{new SceneActions.Choice(choice.actions(),weights);throw new AssertionError("Invalid weight accepted");}catch(IllegalArgumentException expected){}
        try{SceneActions.parse("{\"kind\":\"weighted-choice\",\"weights\":[1,1],\"actions\":[{\"kind\":\"next-floor\"},{\"kind\":\"unknown\"}]}");throw new AssertionError("Unsupported sibling silently dropped");}catch(java.io.IOException expected){}
        var fields=Map.of("kind","Block","native-type","com.threerings.projectx.dungeon.data.actor.Block","config","Fixture/Block","width","1","height","1","collision-flags","1","damage-action","destroy");
        var graph=Map.of("kind","weighted-choice","weights",List.of(1,0),"actions",List.of(Map.of("kind","spawn","fields",fields,"activate",false),Map.of("kind","sequence","actions",List.of(),"stopOnFailure",false)));
        var sensor=new SceneContent.Entry("2141",2,3,0,new SceneContent.Spec(Map.of("kind","SceneTrigger","config","Fixture/Startup","scene-startup","true","event","signal-start","watch-tag","startup:2141","signal","startup","action",Json.stringify(graph))));
        var content=new SceneContent(List.of(sensor),Map.of());
        var first=new SceneEntities(content,SceneWeaponsTest.openTerrain());var second=new SceneEntities(content,SceneWeaponsTest.openTerrain());
        check(first.actors().size()==1&&second.actors().size()==1&&first.actors().getFirst().x()==2&&first.actors().getFirst().y()==3,"native startup selects and spawns once per scene at the original source transform");
        first.advance(SceneClock.now()+100,List.of());first.advance(SceneClock.now()+200,List.of());
        check(first.actors().size()==1,"scene ticks never resample startup choices");
        check(first.penetration(2,3,99).present(),"spawned breakable participates in authoritative collision");
        runtime.close();check(runtime.pending()==0,"closed scheduler retains no delayed choices");
        System.out.println("PASS: authored weighted choices, zero/invalid weights, unsupported siblings, source/time, one startup per scene, collision and no resampling");
    }
}
