package org.tortodrone.world;

import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.character.StarterCatalog;
import static org.tortodrone.support.TestWire.check;

/** Real ticks: shared publication/diff, authoritative movement, isolation and idle lifecycle. */
public final class SceneSimulationTest {
    public static void main(String[] args)throws Exception {
        var map=SceneRegistry.system().entry().map();
        var a=new SceneRegistry.Instance(700,0,map).simulation();
        var b=new SceneRegistry.Instance(701,1,map).simulation();
        var first=new LinkedBlockingQueue<SceneSimulation.Snapshot>();
        var second=new LinkedBlockingQueue<SceneSimulation.Snapshot>();
        var other=new LinkedBlockingQueue<SceneSimulation.Snapshot>();
        var model=new StarterCatalog.Model("Character/PC/Default",Map.of());
        int start=SceneClock.now();
        var one=a.join(actor(1,start,map,model),first::add);
        var two=a.join(actor(2,start,map,model),second::add);
        var isolated=b.join(actor(3,start,map,model),other::add);
        try {
            check(a.playerCount()==2&&a.snapshot().actors().size()==2,"selected actors are scene-owned before readiness");
            check(!one.input(start,0,0,1,start,System.nanoTime()/1_000_000L),"movement is gated by entry");
            one.enter();two.enter();isolated.enter();
            var previous=next(first);var peer=next(second);
            check(previous==peer,"recipients receive the exact same immutable scene publication");
            var away=next(other);check(away.occupants().equals(Set.of(3)),"independent instances isolate actors");
            var initial=previous.delta(null);check(initial.added().size()==2&&initial.updated().isEmpty(),"first recipient baseline is independent");
            int time=SceneClock.now();
            check(one.input(time,0,0,1,time,System.nanoTime()/1_000_000L),"scene owns accepted movement");
            var current=next(first);var same=next(second);
            check(current==same&&current.delta(previous)==same.delta(peer),"one delta partition is shared across observers");
            check(current.actors().getFirst().x()>previous.actors().getFirst().x(),"movement advances centrally before publication");
            check(current.actors().getFirst().config()==model,"ordinary ticks retain cached appearance identity");
            check(current.delta(previous).updated().size()==2,"prediction repair fields remain in every actor delta");
            // Pause clears movement; resuming after an idle interval cannot integrate it.
            one.pause();float frozen=a.snapshot().actors().getFirst().x();Thread.sleep(160);
            check(a.snapshot().actors().getFirst().x()==frozen,"unsubscribed actors stop advancing");
            one.enter();Thread.sleep(70);check(a.snapshot().actors().getFirst().x()==frozen,"re-entry cannot replay stale input");
            two.close();var removed=awaitMissing(first,2);
            var removedIds=removed.delta(current).removed();removedIds[0]=999;
            check(removed.delta(current).removed()[0]==2,"removal arrays cannot mutate a shared publication");
            check(removed.delta(current).removed().length==1,"coalesced recipient baselines still remove departed actors");
            check(a.playerCount()==1&&b.playerCount()==1,"closing membership cannot remove another instance's actor");
            one.close();long count=a.tickCount();Thread.sleep(160);
            check(a.tickCount()==count&&a.snapshot().actors().isEmpty(),"empty scene releases its timer and entities");
            check(b.tickCount()>1,"other scene continues while first is idle");
            try(var rejoin=a.join(actor(1,SceneClock.now(),map,model),first::add)) {
                rejoin.enter();Thread.sleep(80);check(a.tickCount()>count,"idle scene restarts on entry");
                one.close();check(a.playerCount()==1,"late close cannot detach a replacement membership");
            }
        }finally{one.close();two.close();isolated.close();}
        System.out.println("PASS: shared scene ticks/snapshots/deltas, cached appearance, movement readiness, pause/re-entry, instance isolation and idle lifecycle");
    }
    private static ActorState actor(int id,int start,SceneCatalog.MapScene map,StarterCatalog.Model model) {
        return new ActorState(id,1000+id,"Test-"+id,257,start,map.spawnX(),map.spawnY(),0,0,0,0,start,model,false);
    }
    private static SceneSimulation.Snapshot next(BlockingQueue<SceneSimulation.Snapshot> queue)throws Exception {
        var value=queue.poll(3,TimeUnit.SECONDS);if(value==null)throw new AssertionError("Missing scene tick");return value;
    }
    private static SceneSimulation.Snapshot awaitMissing(BlockingQueue<SceneSimulation.Snapshot> queue,int id)throws Exception {
        for(int i=0;i<20;i++){var value=next(queue);if(!value.occupants().contains(id))return value;}throw new AssertionError("Actor did not leave");
    }
}
