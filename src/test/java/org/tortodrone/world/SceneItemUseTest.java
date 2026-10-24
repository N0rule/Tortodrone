package org.tortodrone.world;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import org.tortodrone.character.*;
import static org.tortodrone.support.TestWire.check;

/** Delayed capsule actions follow the owned use activity, including combat interruptions. */
public final class SceneItemUseTest {
    public static void main(String[] args)throws Exception {
        var map=SceneCatalog.load(org.tortodrone.support.ContentFixture.path("maps")).scene(294);
        var capsule=map.content().items().get("Pickup/Capsule/Health Capsule");
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
        System.out.println("PASS: delayed healing/buffs/Remedy consumption, duplicate refusal, hurt/shock cancellation and animated-item interruption");
    }
    private static Object damage(int amount,boolean react) {
        return new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$Normal",Map.of("flags",1,"amount",(float)amount,"shouldReact",react));
    }
    private static final class Fixture implements AutoCloseable {
        final SceneSimulation simulation;
        final SceneSimulation.Membership member;
        final SceneEntities entities;
        final BlockingQueue<SceneSimulation.Snapshot> publications=new LinkedBlockingQueue<>();
        Fixture(SceneCatalog.MapScene map)throws Exception {
            var content=new SceneContent(List.of(),map.content().scales(),Map.of(),Map.of("player.base-health",200f),map.content().items(),Map.of(),map.content().statuses());
            var isolated=new SceneCatalog.MapScene(map.id(),map.zone(),map.zoneName(),map.name(),map.image(),"training",map.version(),
                5.5f,-12.5f,0,1,map.collision(),map.layout(),ScenePortals.EMPTY,content);
            simulation=new SceneRegistry.Instance(790,1,isolated).simulation();
            var field=SceneSimulation.class.getDeclaredField("entities");field.setAccessible(true);entities=(SceneEntities)field.get(simulation);
            int stamp=SceneClock.now();var model=new StarterCatalog.Model("Character/PC/Default",Map.of());
            member=simulation.join(new ActorState(219,1001,"Fixture",256,stamp,5.5f,-12.5f,0,0,0,0,stamp,model,false),publications::add);
            member.combat(WeaponCatalog.empty(),new WorldProfile(219,1,1,0,0,1,List.of()));
            member.enter();
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
