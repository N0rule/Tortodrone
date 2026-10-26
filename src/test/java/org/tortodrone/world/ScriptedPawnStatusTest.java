package org.tortodrone.world;

import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.character.StarterCatalog;
import org.tortodrone.resources.Json;
import static org.tortodrone.support.TestWire.check;

/** Synthetic pressure trigger exercises production scene scheduling, health, movement and publication. */
public final class ScriptedPawnStatusTest {
    public static void main(String[] args)throws Exception {
        var stop=new StarterCatalog.Model("Scripted/Stop",Map.of());
        var data=new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.StatusConditionConfig$Stop",Map.of("flags",262144,"mask",262144,"power",5000f,"minDuration",999999f,"maxDuration",999999f,"chance",1f,"cooloff",true));
        String payload=FireCatalog.modelJson(new StarterCatalog.Model("Status",Map.of("reference",stop,"data",data)));
        String apply=Json.stringify(Map.of("kind","pawn-status","config",payload));
        String clear=Json.stringify(Map.of("kind","pawn-clear","mask",Integer.MAX_VALUE));
        SceneActions.parse(apply);SceneActions.parse(clear);
        for(String invalid:List.of(clear.replace("2147483647","-1"),clear.replace("2147483647","0.5"),apply.replace("$Stop","$Unknown")))try{SceneActions.parse(invalid);throw new AssertionError("Invalid pawn operation accepted");}catch(java.io.IOException expected){}
        var emptyContent=SceneContent.empty();var receipt=new SceneHealth(emptyContent);
        check(receipt.applyCondition(stop,data,100),"status service reports real application");
        check(!receipt.applyCondition(stop,data,101),"status cooloff reports rejection without resetting the condition");
        check(!receipt.status().modifiers().canMove()&&!receipt.status().modifiers().canAttack(),"Stop locks native movement and attacks");
        receipt.clearStatus(Integer.MAX_VALUE);check(receipt.status().modifiers().canMove(),"native mask clear releases Stop");

        String graph=Json.stringify(Map.of("kind","sequence","stopOnFailure",true,"actions",List.of(Map.of("kind","pawn-status","config",payload),Map.of("kind","delay","millis",350,"action",Map.of("kind","pawn-clear","mask",Integer.MAX_VALUE)))));
        var plate=new HashMap<String,String>(Map.of("config","Fixture/Pressure","native-type","com.threerings.projectx.dungeon.data.actor.Switch","kind","Switch","switch-mode","pressure","width","2","height","2","state.2.tag","stop","state.2.signal","activate"));
        var trigger=Map.of("config","Fixture/Trigger","kind","SceneTrigger","event","signal","signal","activate","watch-tag","stop","action",graph);
        var content=new SceneContent(List.of(new SceneContent.Entry("plate",0,0,0,new SceneContent.Spec(plate)),new SceneContent.Entry("script",0,0,0,new SceneContent.Spec(trigger))),Map.of(),Map.of(),Map.of("player.base-health",100f),Map.of(),Map.of(),Map.of());
        var map=new SceneCatalog.MapScene(900001,33554432,"Fixture","Fixture","fixture","training",1,0,0,0,1,SceneWeaponsTest.openTerrain(),new byte[0],ScenePortals.EMPTY,content);
        var firstScene=new SceneRegistry.Instance(7001,0,map).simulation();var otherScene=new SceneRegistry.Instance(7002,0,map).simulation();
        var frames=new LinkedBlockingQueue<SceneSimulation.Snapshot>();int now=SceneClock.now();
        var one=firstScene.join(player(21,1001,0,now),frames::add);var two=firstScene.join(player(22,1002,.5f,now),snapshot->{});
        var inactive=firstScene.join(player(23,1003,0,now),snapshot->{});var isolated=otherScene.join(player(24,1004,5,now),snapshot->{});
        try{
            check(!one.input(now,0,0,1,now,System.nanoTime()/1_000_000L),"unentered knight cannot submit movement");
            one.enter();two.enter();isolated.enter();
            var locked=await(frames,snapshot->!one.status().modifiers().canMove());
            check(!two.status().modifiers().canMove()&&inactive.status().modifiers().canMove()&&isolated.status().modifiers().canMove(),"authored PawnLogic status targets only entered knights in its own scene");
            check(locked.players().stream().filter(actor->actor.id()==21).findFirst().orElseThrow().sceneKnight().status().conditions().stream().anyMatch(ref->ref.name().equals("Scripted/Stop")),"Stop is present in the native knight snapshot");
            float x=locked.players().stream().filter(actor->actor.id()==21).findFirst().orElseThrow().x();
            int time=SceneClock.now();check(one.input(time,0,0,1,time,System.nanoTime()/1_000_000L),"locked movement input remains intent");
            var held=await(frames,snapshot->snapshot.timestamp()>locked.timestamp()+75&&snapshot.timestamp()<locked.timestamp()+300);
            check(held.players().stream().filter(actor->actor.id()==21).findFirst().orElseThrow().x()==x,"locked knight cannot move despite accepted intent");
            await(frames,snapshot->one.status().modifiers().canMove());
            check(two.status().modifiers().canMove(),"authored delayed clear restores all entered pawns");
            var walked=await(frames,snapshot->snapshot.players().stream().anyMatch(actor->actor.id()==21&&actor.x()>x));
            check(walked.players().stream().anyMatch(actor->actor.id()==21&&actor.x()>x),"normal movement resumes after the scripted clear");
        }finally{one.close();two.close();inactive.close();isolated.close();}
        check(firstScene.playerCount()==0&&otherScene.playerCount()==0,"scripted control leaves no scene occupants");
        System.out.println("PASS: authored scene Stop/clear, application receipts, movement lock/release, native status publication, ownership/readiness and instance isolation");
    }
    private static ActorState player(int id,int oid,float x,int stamp){return new ActorState(id,oid,"Fixture",0,stamp,x,0,0,0,0,0,stamp,new StarterCatalog.Model("Character/PC/Default",Map.of()),false);}
    private static SceneSimulation.Snapshot await(LinkedBlockingQueue<SceneSimulation.Snapshot> frames,java.util.function.Predicate<SceneSimulation.Snapshot> condition)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(System.nanoTime()<until){var frame=frames.poll(250,TimeUnit.MILLISECONDS);if(frame!=null&&condition.test(frame))return frame;}
        throw new AssertionError("Timed out waiting for scripted scene state");
    }
}
