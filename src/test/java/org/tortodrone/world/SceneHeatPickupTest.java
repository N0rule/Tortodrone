package org.tortodrone.world;

import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.character.StarterCatalog;
import static org.tortodrone.support.TestWire.check;

/** Synthetic scene: real ticking pickup claims and scene-owned party credit. */
public final class SceneHeatPickupTest {
    public static void main(String[] args)throws Exception {
        var spec=new SceneContent.Spec(Map.of("native-type","com.threerings.projectx.dungeon.data.actor.Pickup","kind","Pickup","config","Item/Consumable/Heat/Ember 3","radius",".4","pickup-heat","10","vacuum-expansion","2","vacuum-speed","8","collision-mask","3"));
        var content=new SceneContent(List.of(new SceneContent.Entry("ember",1,0,0,spec)),Map.of(),Map.of(),Map.of("player.collision-flags",4f),Map.of(),Map.of(),Map.of());
        var map=new SceneCatalog.MapScene(398,50332041,"Fixture","Fixture","Fixture","level",1,0,0,0,1,SceneWeaponsTest.openTerrain(),new byte[0],ScenePortals.EMPTY,content);
        var simulation=new SceneRegistry.Instance(700,0,map).simulation();
        var separate=new SceneRegistry.Instance(701,1,map).simulation();
        int now=SceneClock.now();var model=new StarterCatalog.Model("Character/PC/Level",Map.of());
        try(var owner=simulation.join(new ActorState(219,1001,"Owner",0,now,0,0,0,0,0,model),s->{});
            var peer=simulation.join(new ActorState(220,1002,"Peer",0,now,0,.5f,0,0,0,model),s->{});
            var other=separate.join(new ActorState(221,1003,"Other",0,now,0,0,0,0,0,model),s->{})){
            check(!owner.collectHeat(999),"unentered or unknown claims cannot award heat");
            owner.enter();peer.enter();
            List<SceneEntities.Pickup> claims=List.of();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
            while(claims.isEmpty()&&System.nanoTime()<end){Thread.sleep(50);claims=owner.takePickups();}
            check(claims.size()==1&&claims.getFirst().heat()==10&&claims.getFirst().item().isEmpty(),"authored vacuum produces a heat claim, not an inventory item");
            int entity=claims.getFirst().entityId();
            check(!peer.collectHeat(entity)&&!other.collectHeat(entity),"another occupant/instance cannot acknowledge the claimant's reward");
            var start=new CountDownLatch(1);var results=new LinkedBlockingQueue<Boolean>();
            Runnable collect=()->{try{start.await();results.add(owner.collectHeat(entity));}catch(InterruptedException e){throw new AssertionError(e);}};
            var a=Thread.ofVirtual().start(collect);var b=Thread.ofVirtual().start(collect);start.countDown();a.join();b.join();
            check(results.size()==2&&results.stream().filter(Boolean::booleanValue).count()==1,"concurrent duplicate collection consumes one claim");
            check(owner.floorHeat()==10&&peer.floorHeat()==10&&other.floorHeat()==0,"native party heat is shared once inside its scene instance");
            check(!owner.collectHeat(entity)&&owner.takePickups().isEmpty(),"consumed heat cannot be replayed");
            owner.pause();check(!owner.collectHeat(entity),"paused membership cannot collect scene rewards");
        }
        check(simulation.playerCount()==0&&separate.playerCount()==0,"disconnect releases every scene membership");
        System.out.println("PASS: real scene ticks/vacuum, native heat claim, shared floor credit, ownership, concurrent replay, pause and instance isolation (synthetic scene)");
    }
}
