package org.tortodrone.world;

import java.util.Map;
import org.tortodrone.character.StarterCatalog;
import static org.tortodrone.support.TestWire.check;

/** Disposable scheduler fault, independent of native assets and player data. */
public final class SceneSimulationFailureTest {
    public static void main(String[] args)throws Exception {
        var map=new SceneCatalog.MapScene(999,3<<24,"Fixture","Fixture","","level",1,0,0,0,1,SceneWeaponsTest.openTerrain(),new byte[]{1},ScenePortals.EMPTY);
        var broken=new SceneRegistry.Instance(705,0,map).simulation();var healthy=new SceneRegistry.Instance(706,0,map).simulation();
        var expected=new IllegalStateException("Expected scheduler diagnostic fixture");int stamp=SceneClock.now();
        var model=new StarterCatalog.Model("Character/PC/Default",Map.of());
        try(var first=broken.join(new ActorState(219,1001,"Fixture-A",0,stamp,0,0,0,0,0,model),snapshot->{throw expected;});
            var second=healthy.join(new ActorState(220,1002,"Fixture-B",0,stamp,0,0,0,0,0,model),snapshot->{})){
            check(broken.failure()==null&&healthy.failure()==null,"new scenes start without a diagnostic failure");first.enter();second.enter();
            long until=System.nanoTime()+3_000_000_000L;while(broken.failure()==null&&System.nanoTime()<until)Thread.sleep(10);
            check(broken.failure()==expected,"scheduler retains the exact cause instead of hiding it in its future");
            long stopped=broken.tickCount(),running=healthy.tickCount();Thread.sleep(180);
            check(broken.tickCount()==stopped&&healthy.tickCount()>running&&healthy.failure()==null,"faulted scene stops without affecting another scene's clock");
        }
        check(broken.playerCount()==0&&healthy.playerCount()==0,"failed and healthy scenes both release their owned occupants on disconnect");
        System.out.println("PASS: scheduled scene failure is observable with its exact cause; clocks/ownership remain isolated and disposable memberships close cleanly");
    }
}
