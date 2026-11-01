package org.tortodrone.world;

import java.util.*;
import org.tortodrone.character.StarterCatalog;
import static org.tortodrone.support.TestWire.check;

/** Authored generator deadlines and the native warning/count animation signals. */
public final class RespawnLifecycleTest {
    public static void main(String[] args)throws Exception {
        var training=SceneCatalog.load(org.tortodrone.support.ContentFixture.path("maps")).scene(294);
        var vial=training.content().entries().stream().filter(e->e.spec().child().config().name().equals("Item/Pickups/Vial/Fire Vial")).findFirst().orElseThrow();
        check(vial.spec().integer("delay",-1)==5000&&vial.spec().integer("warning",-1)==0,"original Training vial delay is 5000 ms with no countdown override");
        for(int warning:new int[]{0,2000}){
            var fields=new HashMap<>(vial.spec().fields());fields.put("warning",Integer.toString(warning));
            var world=new SceneEntities(new SceneContent(List.of(new SceneContent.Entry("pad",0,0,0,new SceneContent.Spec(fields))),training.content().scales()),SceneWeaponsTest.openTerrain());
            int now=SceneClock.now();var player=new ActorState(219,1001,"Picker",0,now,0,0,0,0,0,new StarterCatalog.Model("Player",Map.of()));
            world.advance(now+1000,List.of(player));var claim=world.takePickups(player.id()).getFirst();
            world.completePickup(player.id(),claim.entityId(),true);
            int removed=world.actors().stream().filter(a->a.id()==claim.entityId()).findFirst().orElseThrow().entity().destroyed();
            check(removed==now+1000,"pickup settlement uses its authoritative scene tick, not wall time");
            int pad=world.actors().stream().filter(a->a.entity().kind().equals("Generator")).findFirst().orElseThrow().id();
            world.advance(removed+250,List.of());
            world.advance(removed+2999,List.of());check((world.actor(pad).flags()&2)==0,"no premature countdown");
            world.advance(removed+3000,List.of());check(((world.actor(pad).flags()&2)!=0)==(warning>0),"authored warning controls the stock pre-generation transient");
            world.advance(removed+4999,List.of());check(world.actors().size()==1&&world.actor(pad).entity().count()==1,"pickup remains absent until its deadline");
            world.advance(removed+5000,List.of());
            check(world.actors().size()==2&&world.actor(pad).entity().count()==2&&(world.actor(pad).flags()&2)==0,"exact deadline creates one pickup and increments the shared native generation counter");
            world.advance(removed+5100,List.of());check(world.actors().size()==2&&world.actor(pad).entity().count()==2,"publication cannot duplicate a spawn");
            world.advance(removed+5999,List.of(player));check(world.takePickups(player.id()).isEmpty(),"replacement remains visible during its activation delay");
            world.advance(removed+6000,List.of(player));check(world.takePickups(player.id()).size()==1,"replacement becomes collectible one second after its configured respawn");
        }
        System.out.println("PASS: original vial delay, native countdown/generation signals and one spawn per deadline");
    }
}
