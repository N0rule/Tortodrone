package org.tortodrone.world;
import org.tortodrone.core.world.SceneClock;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import org.tortodrone.character.*;
import static org.tortodrone.support.TestWire.check;
import org.tortodrone.core.character.WorldProfile;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.character.AnimatedItems;
import org.tortodrone.core.world.FireCatalog;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.core.world.SceneHealth;
import org.tortodrone.core.world.SceneContent;
import org.tortodrone.core.world.ScenePortals;
import org.tortodrone.core.world.WeaponCatalog;
import org.tortodrone.resources.SceneCatalog;

/** Delayed capsule actions follow the owned use activity, including combat interruptions. */
public final class SceneItemUseTest {
    public static void main(String[] args)throws Exception {
        var catalog=SceneCatalog.load(org.tortodrone.support.ContentFixture.path("maps"));
        var map=catalog.scene(294);
        var haven=catalog.scene(1);
        var capsule=map.content().items().get("Pickup/Capsule/Health Capsule");
        check(capsule.text("stat","").equals("HEALTH_CAPSULES_USED")&&capsule.integer("stat-delta",0)==1,
            "the native health capsule carries its exact lifetime counter");
        for(String config:List.of("Pickup/Capsule/Super Health Capsule","Pickup/Capsule/Ultra Health Capsule"))
            check(map.content().items().get(config).text("stat","").equals("HEALTH_CAPSULES_USED"),
                "stronger health capsules share the native health counter: "+config);
        check(map.content().items().get("Pickup/Capsule/Remedy Capsule").text("stat","").equals("REMEDY_CAPSULES_USED"),
            "the native remedy capsule carries its own lifetime counter");
        check(map.content().items().get("Pickup/Capsule/Buffs/Attack Booster").text("stat","").isBlank(),
            "gameplay-only capsules carry no invented lifetime counter");
        check(haven.content().items().values().stream().noneMatch(item->!item.text("stat","").isBlank()),
            "towns receive no capsule lifetime counters");
        try(var fixture=new Fixture(map)) {
            var before=fixture.simulation.snapshot();int started;
            synchronized(fixture.simulation){
                check(fixture.member.useItem(-1,capsule),"full-health capsule attempt is confirmed without a client-visible refusal");
                check(fixture.simulation.snapshot().players().getFirst().activity()==TownMovement.USE_ITEM,"full-health attempt publishes the normal hand-raising activity");
                check(!fixture.member.useItem(-1,capsule),"animation prevents repeated use requests");
                started=SceneClock.now();
            }
            fixture.await(s->s.timestamp()>=started+TownMovement.USE_ITEM_MILLIS+100);
            check(fixture.member.health().current()==200&&fixture.member.takeUsedItems().isEmpty(),"full-health attempt changes neither HP nor inventory");
            check(fixture.simulation.snapshot().effectsSince(before).isEmpty(),"animation-only use emits no healing VFX");
            check(fixture.member.useItem(-1,capsule),"retained capsule can be attempted again after the normal activity completes");
            fixture.hit(damage(20,false));
            fixture.await(s->s.timestamp()>=started+2*TownMovement.USE_ITEM_MILLIS+200);
            check(fixture.member.health().current()==180&&fixture.member.takeUsedItems().isEmpty(),"animation-only attempt cannot become a delayed inventory transaction after taking damage");
        }
        try(var fixture=new Fixture(map)) {
            int started;
            synchronized(fixture.simulation) {
                fixture.hit(damage(160,false));
                check(fixture.member.useItem(-1,capsule),"injured knight starts a capsule");
                check(!fixture.member.useItem(-1,capsule),"pending use cannot be duplicated");
                fixture.hit(damage(5,false));
                started=SceneClock.now();
                // Hold off the next tick until both the action and animation are due.
                Thread.sleep(TownMovement.USE_ITEM_MILLIS+100);
            }
            fixture.await(s->s.timestamp()>=started+capsule.integer("delay",0)+150);
            check(fixture.member.health().current()==155,"non-reacting damage and a late tick preserve the delayed capsule heal");
            check(fixture.member.takeUsedItems().equals(List.of(-1L)),"successful capsule consumes exactly one item");
        }
        try(var fixture=new Fixture(map)) {
            int started;
            synchronized(fixture.simulation) {
                fixture.hit(damage(160,false));
                check(fixture.member.useItem(-2,capsule),"capsule starts before the interrupting hit");
                fixture.hit(damage(10,true));
                check(fixture.simulation.snapshot().players().getFirst().activity()==TownMovement.HURT,"reacting hit interrupts use with hurt");
                started=SceneClock.now();
            }
            fixture.await(s->s.timestamp()>=started+capsule.integer("delay",0)+150);
            check(fixture.member.health().current()==30,"interrupted capsule cannot heal after the hurt animation");
            check(fixture.member.takeUsedItems().isEmpty(),"interrupted capsule is not consumed");
            check(fixture.member.useItem(-2,capsule),"interrupted capsule can be used again");
            fixture.await(s->s.health(219).current()>30);
            check(fixture.member.health().current()==150&&fixture.member.takeUsedItems().equals(List.of(-2L)),"retry heals and consumes the retained capsule once");
        }
        try(var fixture=new Fixture(map)) {
            int started;
            synchronized(fixture.simulation) {
                fixture.hit(damage(160,false));
                check(fixture.member.useItem(-3,capsule),"capsule starts before shock");
                fixture.hit(SceneLifeTest.condition(map.content(),"Shock"));
                started=SceneClock.now();
            }
            fixture.await(s->s.players().getFirst().activity()==TownMovement.SHOCKED);
            fixture.await(s->s.timestamp()>=started+capsule.integer("delay",0)+150);
            check(fixture.member.health().current()==40&&fixture.member.takeUsedItems().isEmpty(),"shock interrupts the pending heal without consuming the capsule");
        }
        try(var fixture=new Fixture(map)) {
            String config="Usable/Jar O Fireflies";var baseline=fixture.simulation.snapshot();
            check(fixture.member.animatedAction(config),"fireflies start their native animation");
            check(fixture.simulation.snapshot().effectsSince(baseline).isEmpty(),"effect waits for animation land time");
            fixture.hit(damage(5,true));
            fixture.await(s->s.timestamp()-s.players().getFirst().activityStarted()>AnimatedItems.land(config));
            check(fixture.simulation.snapshot().effectsSince(baseline).isEmpty(),"hurt interrupts the delayed cosmetic effect");
            fixture.member.resetInput();check(fixture.member.animatedAction(config),"cosmetic use can restart after interruption");
            fixture.await(s->!s.effectsSince(baseline).isEmpty());
            check(fixture.simulation.snapshot().effectsSince(baseline).size()==1,"one native firefly effect fires after its delay");
            fixture.await(s->s.players().getFirst().activity()==0);
            check(fixture.simulation.snapshot().effectsSince(baseline).size()==1,"completed animation cannot repeat its effect");
        }
        for(String config:List.of("Pickup/Capsule/Buffs/Attack Booster","Pickup/Capsule/Buffs/Defense Booster","Pickup/Capsule/Shadow Espresso"))try(var fixture=new Fixture(map)){
            var spec=map.content().items().get(config);
            check(SceneWeapons.usable(FireCatalog.actionJson(spec.text("use-action",""))),"native gameplay graph is executable: "+config);
            check(fixture.member.useItem(-4,spec),"a buff capsule can be used at full health");
            fixture.await(s->!fixture.member.status().conditions().isEmpty());
            check(fixture.member.takeUsedItems().equals(List.of(-4L)),"buff action consumes one unit at land");
            check(!fixture.member.status().conditions().isEmpty(),"native capsule status reaches the knight");
        }
        try(var fixture=new Fixture(map)){
            fixture.hit(SceneLifeTest.condition(map.content(),"Poison"));
            check(!fixture.member.status().conditions().isEmpty(),"poison applied before Remedy");
            check(fixture.member.useItem(-5,map.content().items().get("Pickup/Capsule/Remedy Capsule")),"Remedy starts its native cleansing action");
            fixture.await(s->fixture.member.status().conditions().isEmpty());
            check(fixture.member.takeUsedItems().equals(List.of(-5L)),"Remedy removes native status and consumes exactly once");
        }
        try(var fixture=new Fixture(map)){
            for(String name:List.of("Pickup/Capsule/Artillery Beacon","Pickup/Capsule/Orbital Barrier/Fire Barrier","Pickup/Capsule/Slime/Luck Potion"))
                check(!fixture.member.useItem(-6,map.content().items().get(name)),"stripped or unsupported gameplay graph is refused before consumption: "+name);
            check(fixture.member.takeUsedItems().isEmpty(),"unsupported capsule actions consume no items");
        }
        check(map.content().items().containsKey("Instant/Heart/Heart 1"),"the global item-use catalogue reaches every scene");
        check(haven.content().items().containsKey("Instant/Heart/Heart 1"),"towns receive owned item-use definitions too");
        check(haven.content().items().values().stream().noneMatch(item->item.text("kind","").equals("vial")||item.text("kind","").equals("capsule")),
            "towns keep no native pickup catalogue, so the party hasPickups flag stays false");
        try(var fixture=new Fixture(map)){
            var heart=map.content().items().get("Instant/Heart/Heart 1");int[] payments={0};
            check(heart.text("config","").equals("Instant/Heart/Heart 1"),"prepared item uses carry their native item config for the use activity");
            fixture.hit(damage(100,false));
            check(fixture.member.health().current()==100,"heart fixture starts injured");
            var used=fixture.member.useOwnedItem(7001,heart,()->{payments[0]++;return Boolean.TRUE;});
            check(used.consumed()&&payments[0]==1,"owned heart charges exactly one unit");
            check(fixture.member.health().current()==140,"native Heart 1 restores 40 health");
        }
        try(var fixture=new Fixture(map)){
            var heart=map.content().items().get("Instant/Heart/Heart 3");int[] payments={0};
            var used=fixture.member.useOwnedItem(7002,heart,()->{payments[0]++;return Boolean.TRUE;});
            check(!used.consumed()&&payments[0]==0,"full-health heart is accepted without consuming the item");
            check(fixture.member.health().current()==200,"full-health heart changes nothing");
        }
        try(var fixture=new Fixture(map)){
            var lethal=map.content().items().get("Instant/Lethal Damage");int[] payments={0};
            var used=fixture.member.useOwnedItem(7003,lethal,()->{payments[0]++;return Boolean.TRUE;});
            check(used.consumed()&&payments[0]==1,"Lethal Damage charges its one native self-damage use");
            check(fixture.member.health().current()==0,"Lethal Damage applies the native 100000 self-damage");
        }
        try(var fixture=new Fixture(map)){
            var bucket=map.content().items().get("Usable/Bloon Bucket");String actorConfig=bucket.text("actor.config","");
            var used=fixture.member.useOwnedItem(7004,bucket,()->Boolean.TRUE);
            var spawned=fixture.simulation.snapshot().actors().stream()
                .filter(a->a.entity()!=null&&a.entity().kind().equals("Actor")&&a.config().name().equals(actorConfig)).findFirst().orElse(null);
            check(used.consumed()&&spawned!=null&&spawned.x()==5.5f&&spawned.y()==-12.5f,"spawn-actor item places its native static prop at the knight");
        }
        try(var fixture=new Fixture(map)){
            var snowball=map.content().items().get("Usable/Flawed Snowball");
            var used=fixture.member.useOwnedItem(7005,snowball,()->Boolean.TRUE);
            var carried=fixture.simulation.snapshot().actors().stream()
                .filter(a->a.entity()!=null&&a.entity().kind().equals("Lift")).findFirst().orElse(null);
            check(used.consumed()&&carried!=null&&carried.entity().lift()!=null,"lift item creates the native carryable snowball");
            check(carried.entity().nativeClass().equals("com.threerings.projectx.data.actor.Lift"),"item lift uses the non-dungeon native Lift class");
            check(carried.x()==5.5f&&carried.y()==-12.5f,"carried item lift follows the knight");
        }
        try(var fixture=new Fixture(map)){
            var bucket=map.content().items().get("Usable/Bloon Bucket");String actorConfig=bucket.text("actor.config","");
            boolean refused=false;
            try{fixture.member.useOwnedItem(7006,bucket,()->{throw new java.io.IOException("refused");});}
            catch(java.io.IOException expected){refused=true;}
            check(refused,"refused spawn-actor payment propagates the refusal");
            check(fixture.simulation.snapshot().actors().stream().noneMatch(a->a.entity()!=null&&a.entity().kind().equals("Actor")&&a.config().name().equals(actorConfig)),
                "refused spawn-actor payment reclaims the spawned prop");
        }
        try(var fixture=new Fixture(map)){
            var snowball=map.content().items().get("Usable/Flawed Snowball");
            boolean refused=false;
            try{fixture.member.useOwnedItem(7007,snowball,()->{throw new java.io.IOException("refused");});}
            catch(java.io.IOException expected){refused=true;}
            check(refused,"refused lift payment propagates the refusal");
            check(fixture.simulation.snapshot().actors().stream().noneMatch(a->a.entity()!=null&&a.entity().kind().equals("Lift")),
                "refused lift payment reclaims the carryable");
            check(fixture.simulation.snapshot().players().getFirst().activity()==0,"refused lift payment clears the use activity");
        }
        System.out.println("PASS: delayed healing/buffs/Remedy consumption, owned hearts/lethal/spawn/lift items, refused-payment cleanup, duplicate refusal, hurt/shock cancellation and animated-item interruption");
    }
    private static Object damage(int amount,boolean react) {
        return new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$Normal",Map.of("flags",1,"amount",(float)amount,"shouldReact",react));
    }
    private static final class Fixture implements AutoCloseable {
        final SceneSimulation simulation;
        final SceneSimulation.Membership member;
        final SceneEntities entities;
        final BlockingQueue<SceneSimulation.Snapshot> publications=new LinkedBlockingQueue<>();
        Fixture(org.tortodrone.core.world.MapScene map)throws Exception {
            var content=new SceneContent(List.of(),map.content().scales(),Map.of(),Map.of("player.base-health",200f),map.content().items(),Map.of(),map.content().statuses());
            var isolated=new org.tortodrone.core.world.MapScene(map.id(),map.zone(),map.zoneName(),map.name(),map.image(),"training",map.version(),
                5.5f,-12.5f,0,1,map.collision(),map.layout(),ScenePortals.EMPTY,content);
            simulation=new SceneRegistry.Instance(790,1,isolated).simulation();
            var field=SceneSimulation.class.getDeclaredField("entities");field.setAccessible(true);entities=(SceneEntities)field.get(simulation);
            int stamp=SceneClock.now();var model=new StarterCatalog.Model("Character/PC/Default",Map.of());
            member=simulation.join(new ActorState(219,1001,"Fixture",256,stamp,5.5f,-12.5f,0,0,0,0,stamp,model,false),publications::add);
            member.combat(WeaponCatalog.empty(),new WorldProfile(219,1,1,0,0,1,List.of()));
            member.enter();
            // A loaded client ends the native loading pose with its first input frame.
            int loaded=SceneClock.now();check(member.input(loaded,0,0,0,loaded,loaded,System.nanoTime()/1_000_000L),"fixture client finishes loading");
            // This fixture tests damage/use interruption, outside the entry protection window.
            var healthField=SceneSimulation.Membership.class.getDeclaredField("health");healthField.setAccessible(true);
            var immunity=SceneHealth.class.getDeclaredField("invulnerableUntil");immunity.setAccessible(true);immunity.setInt(healthField.get(member),0);
            try{await(s->true);}catch(Exception|Error failure){close();throw failure;}
        }
        void hit(Object damage) {
            entities.damage(new StarterCatalog.Model("DamageRegion",Map.of("damage",damage)),5.5f,-12.5f,0,SceneClock.now(),0);
        }
        void await(Predicate<SceneSimulation.Snapshot> ready)throws Exception {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
            while(System.nanoTime()<deadline) {
                var snapshot=publications.poll(100,TimeUnit.MILLISECONDS);
                if(snapshot!=null&&ready.test(snapshot))return;
            }
            throw new AssertionError("Missing capsule lifecycle publication");
        }
        @Override public void close(){member.close();}
    }
}
