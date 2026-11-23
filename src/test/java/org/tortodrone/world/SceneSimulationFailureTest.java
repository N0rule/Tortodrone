package org.tortodrone.world;
import org.tortodrone.core.world.SceneClock;

import java.util.Map;
import org.tortodrone.core.character.StarterCatalog;
import static org.tortodrone.support.TestWire.check;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.core.world.ScenePortals;

/** Disposable scheduler fault, independent of native assets and player data. */
public final class SceneSimulationFailureTest {
    public static void main(String[] args)throws Exception {
        var map=new org.tortodrone.core.world.MapScene(999,3<<24,"Fixture","Fixture","","level",1,0,0,0,1,SceneWeaponsTest.openTerrain(),new byte[]{1},ScenePortals.EMPTY);
        var broken=new SceneRegistry.Instance(705,0,map).simulation();var healthy=new SceneRegistry.Instance(706,0,map).simulation();
        var expected=new IllegalStateException("Expected scheduler diagnostic fixture");int stamp=SceneClock.now();
        var model=new StarterCatalog.Model("Character/PC/Default",Map.of());
        try(var first=broken.join(new ActorState(219,1001,"Fixture-A",0,stamp,0,0,0,0,0,model),snapshot->{throw expected;});
            var second=healthy.join(new ActorState(220,1002,"Fixture-B",0,stamp,0,0,0,0,0,model),snapshot->{})){
            check(broken.failure()==null&&healthy.failure()==null,"new scenes start without a diagnostic failure");first.enter();second.enter();
            await("the faulted lane records its cause",()->broken.failure()!=null);
            check(broken.failure()==expected,"scheduler retains the exact cause instead of hiding it in its future");
            long stopped=broken.tickCount(),running=healthy.tickCount();
            // A faulted lane must stop while its neighbour keeps ticking; the neighbour's advance
            // proves time passed, so no fixed sleep is used as an assertion.
            await("the healthy lane advances",()->healthy.tickCount()>=running+2);
            check(healthy.tickCount()>running,"the healthy scene keeps ticking after a neighbour fails");
            check(broken.tickCount()==stopped&&healthy.failure()==null,"faulted scene stops without affecting another scene's clock");
        }
        check(broken.playerCount()==0&&healthy.playerCount()==0,"failed and healthy scenes both release their owned occupants on disconnect");
        System.out.println("PASS: scheduled scene failure is observable with its exact cause; clocks/ownership remain isolated and disposable memberships close cleanly");
    }

    /** Bounded observation of a real scheduler: a poll with a deadline, never a fixed delay. */
    private static void await(String what,java.util.function.BooleanSupplier condition)throws Exception {
        long deadline=System.nanoTime()+3_000_000_000L;
        while(!condition.getAsBoolean()) {
            if(System.nanoTime()>=deadline)throw new AssertionError("not observed within 3 s: "+what);
            Thread.sleep(5);
        }
    }
}
