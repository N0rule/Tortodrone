package org.tortodrone.world;

import java.io.IOException;
import java.util.*;
import org.tortodrone.character.StarterCatalog;
import org.tortodrone.resources.Json;
import static org.tortodrone.support.TestWire.check;

/** Synthetic geometry exercises scene-owned native variable and cleanup execution, not a mission playthrough. */
public final class SceneLogicTest {
    private static final SceneActions.LogicTarget SOURCE=new SceneActions.LogicTarget("source","");
    public static void main(String[] args)throws Exception {
        timers();variables();
        for(String invalid:List.of("{\"kind\":\"timer\",\"interval\":0,\"offset\":0,\"limit\":0,\"action\":{\"kind\":\"clear-dialog\",\"activator\":false}}",
                "{\"kind\":\"set-variable\",\"name\":\"firing\",\"value\":\"false\",\"target\":{\"mode\":\"source\",\"tag\":\"\"}}",
                "{\"kind\":\"clear-dialog\",\"activator\":false,\"knightId\":1}"))try{SceneActions.parse(invalid);throw new AssertionError("Invalid native logic accepted");}catch(IOException expected){}
        System.out.println("PASS: native timer ordering/offset/limits/late ticks/cancellation; source-scoped variables, exact target identity, missing/false reads, scene isolation and owned dialog cleanup (synthetic geometry)");
    }
    private static void timers(){
        var events=new ArrayList<String>();
        var signal=new SceneActions.Signal("out","tick");
        var runtime=new SceneActions(List.of(),(tag,name,stamp)->{events.add(name+":"+stamp);return false;});
        runtime.perform(new SceneActions.Timer(100,-25,3,signal),1000,"entry:1");runtime.advance(1000);runtime.advance(1074);
        check(events.isEmpty(),"timer respects initial interval plus offset");runtime.advance(1075);runtime.advance(1075);
        check(events.equals(List.of("tick:1075")),"same scene timestamp cannot refire a timer");
        runtime.advance(1500);check(events.equals(List.of("tick:1075","tick:1500")),"late tick does not burst missed timer intervals");
        runtime.advance(1599);check(events.size()==2,"reschedule uses actual current scene timestamp");runtime.advance(1600);runtime.advance(9000);
        check(events.size()==3&&runtime.pending()==0,"finite native timer counts failures as firings and expires");
        runtime.perform(new SceneActions.Timer(100,-500,0,signal),9100,"entry:1");runtime.advance(9100);
        check(events.getLast().equals("tick:9100")&&runtime.pending()==1,"negative initial offset clamps to zero; zero limit repeats");
        runtime.close();runtime.advance(10000);check(runtime.pending()==0&&events.size()==4,"closing a scene cancels repeating timers");
        var ids=SceneActions.conversationIds(new SceneActions.Timer(100,0,1,new SceneActions.VariableCondition("ready",SOURCE,new SceneActions.Conversation("dialog"),new SceneActions.Sequence(List.of(),false))));
        check(ids.equals(Set.of("dialog")),"binding validation traverses timer and both conditional branches");
    }
    private static void variables()throws Exception {
        var entries=new ArrayList<SceneContent.Entry>();
        entries.add(entry("1",0,Map.of("kind","SceneAnchor","source-tags","logic")));
        entries.add(trigger("1","read",Map.of("kind","variable-condition","name","firing","target",Map.of("mode","source","tag",""),
            "action",Map.of("kind","signal","tag","door","name","open"),"otherwise",Map.of("kind","signal","tag","door","name","close"))));
        entries.add(trigger("1","write",Map.of("kind","set-variable","name","firing","value",false,"target",Map.of("mode","source","tag",""))));
        entries.add(entry("2",2,Map.of("kind","SceneAnchor","source-tags","writer")));
        entries.add(trigger("2","enable",Map.of("kind","set-variable","name","firing","value",true,"target",Map.of("mode","tagged","tag","logic"))));
        entries.add(trigger("2","cleanup",Map.of("kind","clear-dialog","activator",false)));
        entries.add(entry("door",8,Map.of("kind","Door","native-type","com.threerings.projectx.dungeon.data.actor.Door","tags","door","collision-flags","1","width","1","height","1","door-speed","0")));
        var content=new SceneContent(entries,Map.of());var terrain=SceneWeaponsTest.openTerrain();var scene=new SceneEntities(content,terrain);var peer=new SceneEntities(content,terrain);
        var cleared=new ArrayList<Integer>();scene.dialogClears((target,stamp)->{cleared.add(target);return true;});
        int time=SceneClock.now()+100;
        // Sensor intersections use production action queues and source identities.
        scene.advance(time,List.of(player(0)));scene.advance(time+1,List.of(player(4)));scene.advance(time+2,List.of(player(2)));
        scene.advance(time+3,List.of(player(4)));scene.advance(time+4,List.of(player(0)));scene.advance(time+5,List.of(player(4)));
        check(scene.walkable(8,0,219),"Tagged writes are visible to the receiver's Source reference");
        peer.advance(time+5,List.of());check(!peer.walkable(8,0,219),"variables do not leak across instances");
        scene.advance(time+6,List.of(player(6)));scene.advance(time+7,List.of(player(4)));scene.advance(time+8,List.of(player(0)));scene.advance(time+9,List.of(player(4)));
        check(!scene.walkable(8,0,219),"boolean false is false, not a nonempty truthy string");
        scene.advance(time+10,List.of(player(10)));check(cleared.equals(List.of(0)),"owned scene cleanup targets only this scene's entered knights");
    }
    private static ActorState player(float x){return new ActorState(21,1001,"Fixture",0,1000,x,0,0,0,0,new StarterCatalog.Model("Player",Map.of()));}
    private static SceneContent.Entry trigger(String key,String name,Map<String,Object> action)throws IOException {
        float x=switch(name){case "read"->0;case "enable"->2;case "write"->6;default->10;};
        return entry(key,x,Map.of("kind","SceneTrigger","handler-index",name,"event","signal-start","watch-tag","sensor-"+name,"signal","intersection","scene-sensor","true","sensor-mask","4","radius",".4","action",Json.stringify(action)));
    }
    private static SceneContent.Entry entry(String key,float x,Map<String,String> fields){var spec=new HashMap<>(fields);spec.put("config","Fixture/"+key);return new SceneContent.Entry(key,x,0,0,new SceneContent.Spec(spec));}
}
