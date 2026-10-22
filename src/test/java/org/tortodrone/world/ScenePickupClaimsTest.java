package org.tortodrone.world;

import java.util.*;
import org.tortodrone.character.StarterCatalog;
import static org.tortodrone.support.TestWire.check;

/** Shared actor removal happens only after the claimant acknowledges its committed grant. */
public final class ScenePickupClaimsTest {
    public static void main(String[] args)throws Exception {
        var fields=new HashMap<>(Map.of("native-type","Pickup","config","Item/Consumable/Crowns/Copper Crown","radius",".2","pickup-item","Instant/Crown/Copper Crown","lifespan","500"));
        var world=new SceneEntities(new SceneContent(List.of(new SceneContent.Entry("coin",0,0,0,new SceneContent.Spec(fields))),Map.of()),SceneWeaponsTest.openTerrain());
        int now=SceneClock.now();var model=new StarterCatalog.Model("Player",Map.of());
        var a=new ActorState(219,1001,"Alice",0,now,0,0,0,0,0,model);var b=new ActorState(220,1002,"Bob",0,now,0,0,0,0,0,model);
        world.advance(now,List.of(a,b));var claims=world.takePickups(a.id());check(claims.size()==1&&world.takePickups(b.id()).isEmpty(),"one shared coin has one claimant");int coin=claims.getFirst().entityId();
        world.cancelClaims(a.id());world.advance(now+100,List.of(b));
        check(world.takePickups(b.id()).isEmpty()&&world.actor(coin)!=null,"departure cannot reopen an in-flight durable reward for another account");
        world.completePickup(b.id(),coin,true);check(world.actor(coin)!=null,"another player cannot acknowledge a foreign claim");
        world.completePickup(a.id(),coin,true);check(world.actor(coin)==null,"successful acknowledgement removes the same actor for every observer");
        var expiring=new SceneEntities(new SceneContent(List.of(new SceneContent.Entry("coin",0,0,0,new SceneContent.Spec(fields))),Map.of()),SceneWeaponsTest.openTerrain());
        expiring.advance(now+1000,List.of());check(expiring.actors().stream().allMatch(x->x.entity().destroyed()!=Integer.MAX_VALUE),"unclaimed pickup expires at its authored lifespan");
        System.out.println("PASS: single shared claim, commit acknowledgement, departure/foreign-claim safety and pickup expiry");
    }
}
