package org.tortodrone.world;

import java.io.*;
import java.util.*;
import org.tortodrone.character.*;
import org.tortodrone.resources.Json;
import static org.tortodrone.support.TestWire.check;

public final class SceneObjectiveTest {
    public static void main(String[] args)throws Exception {
        String action="{\"kind\":\"knight-objective\",\"name\":\"findRhendon\",\"text\":\"m.find_rhendon\"}";
        check(SceneActions.parse(action).equals(new SceneActions.Objective(new KnightObjective("findRhendon","m.find_rhendon"))),"typed allowlisted objective");
        for(String bad:List.of("{\"kind\":\"knight-objective\",\"name\":\"x\",\"text\":\"arbitrary dialogue\"}","{\"kind\":\"knight-objective\",\"name\":\"x\",\"text\":\"m.x\",\"knight\":22}"))try{SceneActions.parse(bad);throw new AssertionError("Bad objective accepted");}catch(IOException expected){}
        var fields=new HashMap<String,String>(Map.of("kind","SceneTrigger","config","Player Enter/Base","scene-sensor","true","sensor-mask","4","radius",".4","event","signal-start","watch-tag","area:1","signal","intersection","action",action));
        var terrain=SceneWeaponsTest.openTerrain();var scene=new SceneEntities(new SceneContent(List.of(new SceneContent.Entry("1",0,0,0,new SceneContent.Spec(fields))),Map.of()),terrain);
        var seen=new ArrayList<SceneSimulation.ObjectiveRequest>();scene.objectives((player,operation,source,stamp)->{check(player.id()==21,"only the actual living Activator receives the objective");seen.add(new SceneSimulation.ObjectiveRequest(operation,source,stamp));return true;});
        scene.advance(1000,List.of(player(21,0),player(22,5)));scene.advance(1050,List.of(player(21,0)));
        check(seen.size()==1&&seen.getFirst().source().entity().key().equals("1"),"sensor retains native source, identity and one-time activation");
        fields.put("action",Json.stringify(Map.of("kind","delay","millis",100,"action",Map.of("kind","knight-objective","name","findRhendon","text","m.find_rhendon"))));
        var delayed=new SceneEntities(new SceneContent(List.of(new SceneContent.Entry("1",0,0,0,new SceneContent.Spec(fields))),Map.of()),terrain);
        delayed.objectives((player,operation,source,stamp)->{throw new AssertionError("Departed activator received delayed objective");});
        delayed.advance(1000,List.of(player(21,0)));delayed.advance(1100,List.of());
        System.out.println("PASS: typed native objectives, exact scene Activator/source, delayed departure cancellation and arbitrary target/text refusal");
    }
    private static ActorState player(int id,float x){return new ActorState(id,1001,"Fixture",0,1000,x,0,0,0,0,new StarterCatalog.Model("Player",Map.of()));}
}
