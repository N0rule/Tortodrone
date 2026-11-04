package org.tortodrone.world;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import org.tortodrone.character.StarterCatalog;
import static org.tortodrone.support.TestWire.check;

/** Synthetic geometry: production ride clock, membership authority and normal movement inputs. */
public final class SceneElevatorTest {
    private static int lastInput;
    public static void main(String[] args)throws Exception {
        var fields=new HashMap<String,String>(Map.of("kind","Elevator","native-type","com.threerings.tudey.data.actor.EntryState","config","Fixture/Elevator","shape","Rectangle","width","1.75","height","1.75","collision-flags","192","collision-mask","0","elevator-mode","standard"));
        fields.put("elevator-advance","1");fields.put("elevator-completes","true");
        var definition=SceneElevator.read(new SceneContent.Spec(fields));check(definition.advance()==1&&definition.completesGate(),"typed native elevator policy");
        var content=new SceneContent(List.of(new SceneContent.Entry("2930",0,0,0,new SceneContent.Spec(fields))),Map.of(),Map.of(),Map.of("player.base-health",100f),Map.of(),Map.of(),Map.of());
        deterministic(content);movement();
        var terrain=SceneWeaponsTest.openTerrain();
        var map=new SceneCatalog.MapScene(901,3<<24,"Fixture","Fixture","fixture","level",1,-3,0,0,1,terrain,new byte[]{1},ScenePortals.EMPTY,content);
        var simulation=new SceneRegistry.Instance(701,0,map).simulation();var isolated=new SceneRegistry.Instance(702,0,map).simulation();
        var frames=new LinkedBlockingQueue<SceneSimulation.Snapshot>();int now=SceneClock.now();
        var first=simulation.join(player(21,-3,now),frames::offer);var second=simulation.join(player(22,-3,now),s->{});var foreign=isolated.join(player(23,-3,now),s->{});
        try{
            first.enter();second.enter();
            check(!first.authorizeElevator("9999"),"unknown route entry cannot authorize an exit");
            walk(first,simulation,frames,21,0,0);
            await(simulation,frames,s->state(s)==1,"partial living party waits on the real elevator actor");
            check(first.takeFloorAdvance()==null,"partial party cannot advance");
            check(first.authorizeElevator("2930"),"first server-owned route grant");
            check(!second.authorizeElevator("2930",Set.of(21)),"a grant omitting its owner is rejected");
            walk(second,simulation,frames,22,0,0);Thread.sleep(100);
            check(state(simulation.snapshot())==0&&first.takeFloorAdvance()==null,"one missing grant refuses the whole ride without freezing or advancing");
            check(second.authorizeElevator("2930"),"second route grant");
            var activated=await(simulation,frames,s->state(s)==2,"fully authorized living party activates");
            check(activated.players().stream().allMatch(p->p.activity()==TownMovement.IDLE&&(p.flags()&2)==0),"ride freezes both authoritative pawns in native activity 285");
            check(!intent(first,0,1)&&!intent(second,0,1)&&first.takeFloorAdvance()==null,"transit rejects movement and offers no early travel request");
            var request=awaitRequest(first,frames);var other=awaitRequest(second,frames);
            int started=activated.actors().stream().filter(a->a.entity()!=null&&a.entity().kind().equals("EntryState")).findFirst().orElseThrow().entity().stateEntered();
            check(request.timestamp()-started>=SceneElevator.ACTIVATE_MILLIS&&request.source().entity().key().equals("2930"),"owned floor request retains original elevator identity and waits 1050 ms");
            check(first.ownsFloorAdvance(request)&&second.ownsFloorAdvance(other)&&!second.ownsFloorAdvance(request)&&!foreign.ownsFloorAdvance(request),"requests are per-member and per-instance despite identical entry/actor numbers");
            try{first.settleSoloFloor(request,()->Boolean.TRUE);throw new AssertionError("Cooperative ride settled as solo");}catch(java.io.IOException expected){}
            check(!foreign.cancelFloorAdvance(request)&&!second.dropElevator(request,false)&&first.dropElevator(request,false)&&!first.dropElevator(request,false),"only the owning request starts one drop; replays cannot reset its clock");
            check(state(simulation.snapshot())==2||state(simulation.snapshot())==3,"drop state publishes on the simulation boundary");
            check(!first.elevatorReady(request)&&first.elevatorConfiguration(request).equals(definition),"drop delay and native advance/completion policy remain authoritative");
            await(simulation,frames,s->first.elevatorReady(request),"native drop waits 2400 ms before settlement/travel may proceed");
            check(first.takeFloorAdvance()==null&&second.takeFloorAdvance()==null,"ride cannot offer duplicate completion work");
            second.close();check(!first.ownsFloorAdvance(request)&&!second.ownsFloorAdvance(other),"disconnect cancels the entire owned ride and both requests");
            Thread.sleep(100);check(state(simulation.snapshot())==0,"a changed roster cannot silently reuse the previous party's grant");
            first.revokeElevator();
            check(intent(first,(float)Math.PI,1),"cancelled ride releases the surviving pawn");
            walk(first,simulation,frames,21,-3,(float)Math.PI);
            check(first.authorizeElevator("2930"),"a fresh server preflight may retry");
            walkUntilRide(first,simulation,frames);
            var retry=awaitRequest(first,frames);check(retry.sequence()>request.sequence()&&!first.ownsFloorAdvance(request),"retry gets a fresh generation; stale settlement never revives");
            int[] payouts={0};
            try{foreign.settleSoloFloor(retry,()->++payouts[0]);throw new AssertionError("Foreign payout accepted");}catch(java.io.IOException expected){}
            try{first.settleSoloFloor(retry,()->{throw new java.io.IOException("fixture payout rollback");});throw new AssertionError("Failed payout accepted");}catch(java.io.IOException expected){}
            check(first.ownsFloorAdvance(retry)&&first.settleSoloFloor(retry,()->++payouts[0])==1,"payout failure is retryable; native NextFloor commits before any drop");
            try{first.settleSoloFloor(retry,()->++payouts[0]);throw new AssertionError("Duplicate payout accepted");}catch(java.io.IOException expected){}
            var report=new FloorReport(first,retry,SceneClock.now());check(report.valid()&&payouts[0]==1&&!first.elevatorReady(retry),"paid request remains owned and frozen for its report/drop phases");
            int voted=SceneClock.now();check(report.report(false,voted)&&report.votedOrTimedOut(voted)&&!report.ready(voted),"physical elevator owns the leave delay; portal reports retain their existing delay");
            int[] commits={0};
            try{first.settleSoloElevator(retry,()->++commits[0]);throw new AssertionError("Premature settlement accepted");}catch(java.io.IOException expected){}
            check(commits[0]==0&&first.dropElevator(retry,true),"premature preflight performs no storage work");
            await(simulation,frames,s->first.elevatorReady(retry),"solo return drop becomes ready");
            try{first.settleSoloElevator(retry,()->{throw new java.io.IOException("fixture rollback");});throw new AssertionError("Failed commit accepted");}catch(java.io.IOException expected){}
            check(first.ownsFloorAdvance(retry)&&first.elevatorReady(retry),"failed transaction preserves an owned retryable ride");
            check(first.settleSoloElevator(retry,()->++commits[0])==1&&!first.ownsFloorAdvance(retry),"successful commit retires ride before another completion can enter");
            try{first.settleSoloElevator(retry,()->++commits[0]);throw new AssertionError("Duplicate commit accepted");}catch(java.io.IOException expected){}
            check(commits[0]==1,"duplicate completion cannot execute storage twice");
            Thread.sleep(100);check(!intent(first,0,1)&&state(simulation.snapshot())==4,"settled pawn stays controlled through publication/travel preparation");
            first.pause();check(!first.ownsFloorAdvance(retry)&&first.takeFloorAdvance()==null,"travel/pause invalidates pending requests and clears the grant");
        }finally{first.close();second.close();foreign.close();}
        check(simulation.playerCount()==0&&isolated.playerCount()==0,"disposable scene memberships/timers released");
        var invalid=new HashMap<>(fields);invalid.put("elevator-advance","2");try{SceneElevator.read(new SceneContent.Spec(invalid));throw new AssertionError("Arbitrary elevator direction accepted");}catch(IllegalArgumentException expected){}
        System.out.println("PASS: native elevator phases/delays, legal movement, route grants, all-living-party activation, owned payout-before-report/drop, failure/replay protection, frozen pawns, disconnect/pause/retry and scene isolation (synthetic map; no full mission playthrough)");
    }
    private static void deterministic(SceneContent content)throws Exception {
        var world=new SceneEntities(content,SceneWeaponsTest.openTerrain());int[] offers={0},cancels={0};SceneEntities.ElevatorTrip[] trip={null};boolean[] allowed={true};
        world.elevators(new SceneEntities.ElevatorControl(){
            public boolean begin(SceneEntities.ElevatorTrip value,int stamp){trip[0]=value;return allowed[0];}
            public boolean valid(SceneEntities.ElevatorTrip value){return allowed[0]&&trip[0]==value;}
            public boolean advance(SceneEntities.ElevatorTrip value,int stamp){offers[0]++;return valid(value);}
            public void cancel(SceneEntities.ElevatorTrip value,int stamp){cancels[0]++;}
        });
        int start=Integer.MAX_VALUE-2000;var occupants=List.of(player(21,0,start));world.advance(start,occupants);
        world.advance(start+1049,occupants);check(offers[0]==0,"no offer before exact activation deadline");
        world.advance(start+1050,occupants);world.advance(start+1051,occupants);check(offers[0]==1,"exact 1050 ms offer occurs once");
        check(world.dropElevator(trip[0],true,start+1100)&&!world.elevatorReady(trip[0],start+3499)&&world.elevatorReady(trip[0],start+3500),"return drop has exact 2400 ms deadline");
        check(world.actors().getFirst().entity().state()==4,"returning elevator uses native drop state four");
        allowed[0]=false;world.advance(start+1900,occupants);check(cancels[0]==1&&world.actors().getFirst().entity().state()==0&&!world.elevatorReady(trip[0],start+4000),"revoked preflight restores idle and cancels pending drop");
    }
    private static void movement()throws Exception {
        var mover=new TownMovement(SceneWeaponsTest.openTerrain(),0,0);mover.resetInput(Integer.MAX_VALUE-500);
        check(mover.transit(true,Integer.MAX_VALUE-400),"server starts transit");mover.advance(Integer.MIN_VALUE+500,0);check(mover.x()==0&&mover.activity()==285&&!mover.accept(Integer.MIN_VALUE+501,0,0,1,Integer.MIN_VALUE+501,0),"transit stays frozen through scene clock wrap");
        check(mover.transit(false,Integer.MIN_VALUE+600)&&!mover.accept(Integer.MIN_VALUE+599,0,0,1,Integer.MIN_VALUE+600,0)&&mover.accept(Integer.MIN_VALUE+601,0,0,1,Integer.MIN_VALUE+601,0),"release rejects pre-release input while accepting fresh intent across wrap");
        mover.die(Integer.MIN_VALUE+700);check(!mover.transit(true,Integer.MIN_VALUE+701)&&mover.dead(),"dead pawn cannot acquire transit control");
        var interrupted=new TownMovement(SceneWeaponsTest.openTerrain(),0,0);interrupted.resetInput(1000);interrupted.transit(true,1100);interrupted.hurt(1200);interrupted.transit(false,1250);check(interrupted.activity()==TownMovement.HURT,"ride cancellation preserves an unrelated activity entered meanwhile");
    }
    private static int state(SceneSimulation.Snapshot snapshot){return snapshot.actors().stream().filter(a->a.entity()!=null&&a.entity().kind().equals("EntryState")).findFirst().orElseThrow().entity().state();}
    private static ActorState player(int id,float x,int now){return new ActorState(id,1000+id,"Fixture",0,now,x,0,0,0,0,new StarterCatalog.Model("Character/PC/Level",Map.of()));}
    private static SceneSimulation.Snapshot await(SceneSimulation simulation,LinkedBlockingQueue<SceneSimulation.Snapshot> frames,Predicate<SceneSimulation.Snapshot> condition,String message)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        do{var snapshot=simulation.snapshot();if(condition.test(snapshot))return snapshot;frames.poll(50,TimeUnit.MILLISECONDS);}while(System.nanoTime()<until);throw new AssertionError(message);
    }
    private static SceneSimulation.FloorAdvanceRequest awaitRequest(SceneSimulation.Membership member,LinkedBlockingQueue<SceneSimulation.Snapshot> frames)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);do{var request=member.takeFloorAdvance();if(request!=null)return request;frames.poll(50,TimeUnit.MILLISECONDS);}while(System.nanoTime()<until);throw new AssertionError("Elevator offered no owned request");
    }
    private static void walk(SceneSimulation.Membership member,SceneSimulation simulation,LinkedBlockingQueue<SceneSimulation.Snapshot> frames,int id,float target,float heading)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);do{
            intent(member,heading,1);var pawn=simulation.snapshot().players().stream().filter(a->a.id()==id).findFirst().orElseThrow();
            if(Math.abs(pawn.x()-target)<.2){intent(member,heading,0);return;}frames.poll(50,TimeUnit.MILLISECONDS);
        }while(System.nanoTime()<until);throw new AssertionError("Legal movement did not reach "+target);
    }
    private static void walkUntilRide(SceneSimulation.Membership member,SceneSimulation simulation,LinkedBlockingQueue<SceneSimulation.Snapshot> frames)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);do{intent(member,0,1);if(state(simulation.snapshot())==2)return;frames.poll(50,TimeUnit.MILLISECONDS);}while(System.nanoTime()<until);throw new AssertionError("Retry did not activate elevator");
    }
    private static boolean intent(SceneSimulation.Membership member,float heading,int flags){int now=SceneClock.now();lastInput=Math.max(lastInput+1,now);return member.input(lastInput,heading,heading,flags,now,System.nanoTime()/1_000_000L);}
}
