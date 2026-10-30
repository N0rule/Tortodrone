package org.tortodrone.world;

import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.character.StarterCatalog;
import org.tortodrone.resources.Json;
import static org.tortodrone.support.TestWire.check;

/** Synthetic sensor exercises real membership input, scheduling and native floor-request authority. */
public final class SceneNextFloorTest {
    private static int lastInput;
    public static void main(String[] args)throws Exception{
        check(SceneActions.parse("{\"kind\":\"next-floor\"}").equals(new SceneActions.NextFloor()),"typed NextFloor carries no client-selected mission or destination");
        for(String invalid:List.of("{\"kind\":\"next-floor\",\"scene\":217}","{\"kind\":\"next-floor\",\"target\":\"peer\"}"))try{SceneActions.parse(invalid);throw new AssertionError("Client-style floor routing fields accepted");}catch(java.io.IOException expected){}
        var area=new SceneContent.Entry("1943",1,0,0,new SceneContent.Spec(Map.ofEntries(Map.entry("kind","SceneTrigger"),Map.entry("config","Portals/Next Floor"),Map.entry("scene-sensor","true"),Map.entry("sensor-mask","4"),Map.entry("radius",".4"),Map.entry("event","signal-start"),Map.entry("watch-tag","area:1943"),Map.entry("signal","intersection"),Map.entry("action",Json.stringify(Map.of("kind","next-floor"))))));
        var content=new SceneContent(List.of(area),Map.of(),Map.of(),Map.of("player.base-health",100f),Map.of(),Map.of(),Map.of());
        absentActivator(content,area);
        var map=new SceneCatalog.MapScene(900001,50332041,"Fixture","Fixture","fixture","level",1,-1,0,0,1,SceneWeaponsTest.openTerrain(),new byte[0],ScenePortals.EMPTY,content);
        var scene=new SceneRegistry.Instance(7001,0,map).simulation();var other=new SceneRegistry.Instance(7002,0,map).simulation();
        int now=SceneClock.now();var frames=new LinkedBlockingQueue<SceneSimulation.Snapshot>();
        var one=scene.join(player(21,1001,-1,now),frames::offer);var peer=scene.join(player(22,1002,-3,now),snapshot->{});
        var inactive=scene.join(player(23,1003,1,now),snapshot->{});var isolated=other.join(player(24,1004,-3,now),snapshot->{});
        try{
            check(one.takeFloorAdvance()==null&&inactive.takeFloorAdvance()==null,"selection without entry cannot activate the floor");
            one.enter();peer.enter();isolated.enter();
            walk(one,frames,21,1,0);
            var first=awaitRequest(one,frames);
            check(first.source().entity().entrySource()&&first.source().entity().key().equals("1943")&&first.source().x()==1&&first.source().y()==0&&first.sequence()==1,"native intersection preserves its exact entry identity, transform and request generation");
            check(one.ownsFloorAdvance(first)&&!peer.ownsFloorAdvance(first)&&!isolated.ownsFloorAdvance(first),"only the activating membership owns the request, including across scenes");
            int reportStamp=Integer.MAX_VALUE-1000;
            try{new FloorReport(peer,first,reportStamp);throw new AssertionError("Peer floor report accepted");}catch(IllegalArgumentException expected){}
            var report=new FloorReport(one,first,reportStamp);
            check(!report.ready(reportStamp)&&report.report(false,reportStamp)&&!report.report(true,reportStamp+1),"report is pending until a vote and duplicate votes cannot turn leave into immediate descent");
            check(!report.ready(reportStamp+FloorReport.LEAVE_DELAY-1)&&report.ready(reportStamp+FloorReport.LEAVE_DELAY),"native leave vote waits 2400ms, including scene timestamp wrap");
            report.finish();check(!report.valid()&&!report.ready(reportStamp+FloorReport.TIMEOUT),"finished floor report never settles twice");
            var timeout=new FloorReport(one,first,reportStamp);
            check(!timeout.ready(reportStamp+FloorReport.TIMEOUT-1)&&timeout.ready(reportStamp+FloorReport.TIMEOUT),"last-floor fallback waits the native 70000ms");
            var immediate=new FloorReport(one,first,reportStamp);check(immediate.report(true,reportStamp)&&immediate.ready(reportStamp),"native continue report completes the last floor immediately");
            check(!one.ownsFloorAdvance(new SceneSimulation.FloorAdvanceRequest(first.sequence(),first.source(),first.timestamp())),"a reconstructed value cannot forge the server-owned request object");
            check(peer.takeFloorAdvance()==null&&inactive.takeFloorAdvance()==null&&isolated.takeFloorAdvance()==null,"no peer, unentered knight or other scene inherits the request");
            walk(one,frames,21,-1,(float)Math.PI);walk(one,frames,21,1,0);
            check(one.takeFloorAdvance()==null&&one.ownsFloorAdvance(first),"reentering an exit cannot enqueue a second settlement while its request is active");
            check(!peer.cancelFloorAdvance(first)&&one.cancelFloorAdvance(first)&&!one.ownsFloorAdvance(first),"only the owner can retire the exact preflight request");
            check(!timeout.valid()&&!immediate.ready(reportStamp+FloorReport.TIMEOUT),"cancelled membership requests invalidate all reports");
            walk(one,frames,21,-1,(float)Math.PI);walk(one,frames,21,1,0);
            var second=awaitRequest(one,frames);check(second.sequence()>first.sequence()&&!one.ownsFloorAdvance(first),"a retried preflight gets a new generation and never revives its old token");
            one.pause();check(!one.ownsFloorAdvance(second)&&one.takeFloorAdvance()==null,"departure invalidates and clears pending floor work before travel publication");
        }finally{one.close();peer.close();inactive.close();isolated.close();}
        check(scene.playerCount()==0&&other.playerCount()==0,"floor requests leave no memberships or simulation timer behind");
        System.out.println("PASS: native-shaped NextFloor sensor -> legal movement -> membership-owned pending request; exact source, no arbitrary destination, ownership, replay/preflight generations, departure and instance isolation");
    }
    private static ActorState player(int id,int oid,float x,int now){return new ActorState(id,oid,"Fixture",0,now,x,0,0,0,0,new StarterCatalog.Model("Character/PC/Default",Map.of()));}
    private static void absentActivator(SceneContent content,SceneContent.Entry area)throws Exception{
        var dead=new SceneEntities(content,SceneWeaponsTest.openTerrain());int[] calls={0};dead.floorAdvances((player,source,stamp)->{calls[0]++;return true;});
        var corpse=new ActorState(21,1001,"Fixture",0,1000,1,0,0,0,0,TownMovement.DEAD,1000,new StarterCatalog.Model("Character/PC/Default",Map.of()));
        dead.advance(1000,List.of(corpse));check(calls[0]==0,"dead knight cannot activate a floor portal");
        var fields=new HashMap<>(area.spec().fields());fields.put("action",Json.stringify(Map.of("kind","delay","millis",150,"action",Map.of("kind","next-floor"))));
        var delayed=new SceneEntities(new SceneContent(List.of(new SceneContent.Entry(area.key(),area.x(),area.y(),0,new SceneContent.Spec(fields))),Map.of()),SceneWeaponsTest.openTerrain());
        delayed.floorAdvances((player,source,stamp)->{calls[0]++;return true;});delayed.advance(1000,List.of(player(21,1001,1,1000)));
        delayed.advance(1150,List.of(player(22,1002,-3,1150)));
        check(calls[0]==0,"a delayed departed Activator cannot be replaced by an entered peer");
    }
    private static SceneSimulation.FloorAdvanceRequest awaitRequest(SceneSimulation.Membership member,LinkedBlockingQueue<SceneSimulation.Snapshot> frames)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(System.nanoTime()<until){var request=member.takeFloorAdvance();if(request!=null)return request;frames.poll(100,TimeUnit.MILLISECONDS);}
        throw new AssertionError("Native floor request was not offered");
    }
    private static void walk(SceneSimulation.Membership member,LinkedBlockingQueue<SceneSimulation.Snapshot> frames,int id,float x,float heading)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        frames.clear();
        while(System.nanoTime()<until){intent(member,heading,1);
            var frame=frames.poll(100,TimeUnit.MILLISECONDS);var actor=frame==null?null:frame.players().stream().filter(a->a.id()==id).findFirst().orElse(null);
            if(actor!=null&&Math.abs(actor.x()-x)<.22){intent(member,heading,0);return;}
        }throw new AssertionError("Legal movement never reached the floor sensor");
    }
    private static void intent(SceneSimulation.Membership member,float heading,int flags){int now=SceneClock.now();lastInput=Math.max(now,lastInput+1);check(member.input(lastInput,heading,heading,flags,now,System.nanoTime()/1_000_000L),"entered pawn accepts monotonic normal movement intent");}
}
