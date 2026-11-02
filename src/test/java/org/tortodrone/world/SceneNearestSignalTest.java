package org.tortodrone.world;

import java.io.IOException;
import java.util.*;
import org.tortodrone.character.StarterCatalog;
import static org.tortodrone.support.TestWire.check;

/** Synthetic geometry isolates native subset addressing; original Generator export is checked separately. */
public final class SceneNearestSignalTest {
    public static void main(String[] args)throws Exception {
        String action="{\"kind\":\"nearest-signal\",\"tag\":\"chain\",\"name\":\"activate\",\"count\":2,\"excludeSource\":true}";
        check(SceneActions.parse(action).equals(new SceneActions.NearestSignal("chain","activate",2,true)),"typed nearest subset metadata");
        for(String bad:List.of(action.replace("\"count\":2","\"count\":257"),action.replace("\"count\":2","\"count\":1.5"),action.replace("\"count\":2","\"count\":-1"),action.replace("\"excludeSource\":true","\"excludeSource\":true,\"clientTarget\":7")))try{SceneActions.parse(bad);throw new AssertionError("Invalid selection accepted");}catch(IOException expected){}
        var entries=new ArrayList<SceneContent.Entry>();
        // Source is also a tagged target. A distinct colocated receiver must NOT be excluded.
        for(int i=0;i<5;i++){
            String key=Integer.toString(100+i);float x=new float[]{0,0,2,5,8}[i];
            entries.add(entry(key,x,Map.of("kind","SceneAnchor","source-tags","chain")));
            entries.add(entry(key,x,Map.of("kind","SceneTrigger","handler-index","0","event","signal","watch-tag","chain","signal","activate","action","{\"kind\":\"signal\",\"tag\":\"door"+i+"\",\"name\":\"open\"}")));
            entries.add(entry("door"+i,x,Map.of("kind","Door","native-type","com.threerings.projectx.dungeon.data.actor.Door","tags","door"+i,"width",".5","height",".5","door-speed","0","collision-flags","1")));
        }
        entries.add(entry("100",0,Map.of("kind","SceneTrigger","handler-index","sensor","event","signal-start","watch-tag","entry-area","signal","intersection","scene-sensor","true","sensor-mask","4","radius",".4","action","{\"kind\":\"delay\",\"millis\":100,\"action\":"+action+"}")));
        var content=new SceneContent(entries,Map.of());var terrain=SceneWeaponsTest.openTerrain();
        var scene=new SceneEntities(content,terrain);var peer=new SceneEntities(content,terrain);
        var player=new ActorState(21,1001,"Fixture",0,1000,0,0,0,0,0,new StarterCatalog.Model("Player",Map.of()));
        scene.advance(1000,List.of(player));scene.advance(1099,List.of(player));
        check(opened(scene).isEmpty(),"authored delayed propagation cannot execute early");
        scene.advance(1100,List.of(player));peer.advance(1100,List.of());
        check(opened(scene).equals(Set.of("door1","door2")),"exactly two closest receiver identities execute; Source excluded, colocated neighbor retained, distant same-tag handlers untouched");
        check(opened(peer).isEmpty(),"selection and delayed handlers stay inside the scene instance");
        scene.advance(1150,List.of(player));check(opened(scene).size()==2,"held sensor does not duplicate activation");
        var selected=new ArrayList<String>();
        var scheduler=new SceneActions(List.of(new SceneActions.Rule("a","signal","same","activate",false,new SceneActions.Signal("a","open"),"a","entry:1"),new SceneActions.Rule("b","signal","same","activate",false,new SceneActions.Signal("b","open"),"b","entry:2")),(tag,name,stamp)->{selected.add(tag);return true;});
        scheduler.signalTo("same","activate",1,"entry:9","entry:2");scheduler.advance(1);check(selected.equals(List.of("b")),"subset addressing never broadcasts to every handler with the same tag");
        scheduler.close();scheduler.signalTo("same","activate",2,"entry:9","entry:1");scheduler.advance(2);check(selected.size()==1,"closed scheduler cannot retain targeted work");
        System.out.println("PASS: nearest tagged signal subset, identity exclusion, colocated receiver, exact delayed handler addressing, source isolation, bounds and cleanup (synthetic geometry)");
    }
    private static SceneContent.Entry entry(String key,float x,Map<String,String> fields){var spec=new HashMap<>(fields);spec.put("config","Fixture/"+key);return new SceneContent.Entry(key,x,0,0,new SceneContent.Spec(spec));}
    private static Set<String> opened(SceneEntities scene){var result=new HashSet<String>();for(var actor:scene.actors())if(actor.entity()!=null&&actor.entity().kind().equals("Door")&&(actor.flags()&2)!=0)result.add(actor.entity().key());return result;}
}
