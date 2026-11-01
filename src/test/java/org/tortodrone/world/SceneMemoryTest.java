package org.tortodrone.world;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.character.*;
import org.tortodrone.resources.Json;
import static org.tortodrone.support.TestWire.check;

/** Synthetic native-shaped sensor; real scene 2977 is covered by tutorial-opening-check. */
public final class SceneMemoryTest {
    public static void main(String[] args)throws Exception {
        String action="{\"kind\":\"knight-memory\",\"name\":\"talkto_rhendon_icon\",\"remember\":true}";
        check(SceneActions.parse(action).equals(new SceneActions.Memory(new KnightMemory("talkto_rhendon_icon",true))),"typed native memory operation");
        for(String bad:List.of("{\"kind\":\"knight-memory\",\"name\":\"x\",\"remember\":true,\"knight\":2}","{\"kind\":\"knight-memory\",\"name\":\"../x\\n\",\"remember\":true}","{\"kind\":\"knight-memory\",\"name\":\"x\",\"remember\":1}"))try{SceneActions.parse(bad);throw new AssertionError("Bad memory node accepted");}catch(IOException expected){}
        try{TutorialRoute.sourceVersion(new byte[10]);throw new AssertionError("Unverified source revision accepted");}catch(IllegalArgumentException expected){}
        var entry=new SceneContent.Entry("2977",0,0,0,new SceneContent.Spec(Map.of("kind","SceneTrigger","config","Player Enter/Base","scene-sensor","true","sensor-mask","4","radius",".4","event","signal-start","watch-tag","area:2977","signal","intersection","action",action)));
        var content=new SceneContent(List.of(entry),Map.of());var terrain=SceneWeaponsTest.openTerrain();
        var entities=new SceneEntities(content,terrain);var seen=new ArrayList<SceneSimulation.MemoryRequest>();
        entities.memories((player,operation,source,stamp)->{check(player.id()==21,"exact Activator, never peer");seen.add(new SceneSimulation.MemoryRequest(operation,source,stamp));return true;});
        var pawn=player(21,1001,0,1000);entities.advance(1000,List.of(pawn,player(22,1002,5,1000)));entities.advance(1050,List.of(pawn));
        check(seen.size()==1&&seen.getFirst().source().entity().key().equals("2977")&&seen.getFirst().source().entity().entrySource()&&seen.getFirst().timestamp()==1000,"one-time intersection retains authored source and scene clock");
        var fields=new HashMap<>(entry.spec().fields());fields.put("action",Json.stringify(Map.of("kind","delay","millis",100,"action",Map.of("kind","knight-memory","name","talkto_rhendon_icon","remember",true))));
        var delayed=new SceneEntities(new SceneContent(List.of(new SceneContent.Entry("2977",0,0,0,new SceneContent.Spec(fields))),Map.of()),terrain);
        delayed.memories((player,operation,source,stamp)->{throw new AssertionError("Departed Activator was replaced");});
        delayed.advance(1000,List.of(pawn));delayed.advance(1100,List.of(player(22,1002,5,1100)));
        var dead=new ActorState(21,1001,"Fixture",0,1000,0,0,0,0,0,TownMovement.DEAD,1000,new StarterCatalog.Model("Character/PC/Default",Map.of()));
        var noDead=new SceneEntities(content,terrain);noDead.memories((player,operation,source,stamp)->{throw new AssertionError("Dead pawn set memory");});noDead.advance(1000,List.of(dead));
        var map=new SceneCatalog.MapScene(900001,50332041,"Fixture","Fixture","fixture","level",1,0,0,0,1,terrain,new byte[0],ScenePortals.EMPTY,content);
        var scene=new SceneRegistry.Instance(7001,0,map).simulation();var frames=new LinkedBlockingQueue<SceneSimulation.Snapshot>();int now=SceneClock.now();
        var owner=scene.join(player(21,1001,0,now),frames::offer);var peer=scene.join(player(22,1002,5,now),snapshot->{});
        try {
            check(owner.takeMemories().isEmpty(),"selection without entry cannot mutate memories");owner.enter();peer.enter();
            List<SceneSimulation.MemoryRequest> requests=List.of();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(requests.isEmpty()&&System.nanoTime()<until){frames.poll(100,TimeUnit.MILLISECONDS);requests=owner.takeMemories();}
            check(requests.size()==1&&requests.getFirst().operation().equals(new KnightMemory("talkto_rhendon_icon",true)),"production membership queues the authored operation without storage under simulation lock");
            check(owner.takeMemories().isEmpty()&&peer.takeMemories().isEmpty(),"drain is once and a peer inherits no changes");
            owner.pause();check(owner.takeMemories().isEmpty(),"paused subscription has no pending memory writes");
        }finally{owner.close();peer.close();}
        check(scene.playerCount()==0,"memory requests leave no memberships or timer behind");
        System.out.println("PASS: typed memory action, exact sensor source/Activator, one-time intersection, delay/death/departure isolation and bounded membership delivery");
    }
    private static ActorState player(int id,int oid,float x,int stamp){return new ActorState(id,oid,"Fixture",0,stamp,x,0,0,0,0,new StarterCatalog.Model("Character/PC/Default",Map.of()));}
}
