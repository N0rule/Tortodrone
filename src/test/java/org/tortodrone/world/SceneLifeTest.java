package org.tortodrone.world;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.character.*;
import static org.tortodrone.support.TestWire.check;

/** Basic lifecycle regressions using disposable scene state and authoritative input. */
public final class SceneLifeTest {
    public static void main(String[] args)throws Exception {
        var map=SceneCatalog.load(Path.of("content/packs/vanilla/maps")).scene(294);
        health(map.content());
        shields();
        movement(map);
        training(map);
        System.out.println("PASS: death, once-per-level Emergency Revive, input/instance isolation and health/status invariants");
    }
    private static void shields()throws Exception{
        var content=new SceneContent(List.of(),Map.of(),Map.of(),Map.of("player.base-health",200f),Map.of(),Map.of(),Map.of());
        var health=new SceneHealth(content);
        var catalog=new WeaponCatalog(List.of(),List.of(new WeaponCatalog.Shield("Fixture",100,5000,2000,0,0,null,8000)));
        var profile=new WorldProfile(10,1,1,0,0,1,List.of(new WorldProfile.Item(1,WorldProfile.Slot.SHIELD,"Fixture",null,0,2,true)));
        var combat=new KnightCombat(catalog);combat.equipment(profile);health.equipment(profile);combat.reset(1000);
        combat.input(64,1000,1000,0,0,true);
        check(health.hit(damage(40),1000,1,combat).amount()==0&&health.state().current()==200&&combat.state().shieldHealth()==60,"raised shield absorbs blockable damage");
        check(combat.activity()==KnightCombat.HURT_DEFENSE&&combat.state().shieldRegenStart()==2000,"shield hit enters native defense reaction and hit delay");
        check(health.hit(damage(40),1100,1,combat).amount()==0&&combat.state().shieldHealth()==60,"shield reaction cannot be damaged again");
        combat.advance(1411,0);combat.input(64,1411,1411,0,0,true);
        check(health.hit(damage(80),1411,1,combat).amount()==20&&combat.state().shieldHealth()==0&&combat.state().shieldRegenStart()==8000,"broken shield spills excess damage and uses its distinct break delay");
        combat.advance(9411,0);check(!combat.defending(9411),"broken shield cannot block before regeneration begins");
        combat.advance(11911,0);check(combat.defending(11911),"shield regenerates from its recorded hit and delay");
        var unblockable=new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$Unblockable",List.of(
            new FireCatalog.FieldValue("flags","int",8),new FireCatalog.FieldValue("amount","float",7f)));
        float shield= combat.state().shieldHealth();
        check(health.hit(unblockable,11911,1,combat).amount()==7&&combat.state().shieldHealth()==shield,"unblockable damage bypasses shield health");
        var mixedHealth=new SceneHealth(content);var mixedCombat=new KnightCombat(catalog);mixedCombat.equipment(profile);mixedCombat.reset(12000);mixedCombat.input(64,12000,12000,0,0,true);
        var compound=new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$Compound",List.of(
            new FireCatalog.FieldValue("damages","[Lcom.threerings.projectx.dungeon.config.DamageConfig;",new FireCatalog.ListValue("com.threerings.projectx.dungeon.config.DamageConfig",List.of(damage(40),unblockable)))));
        check(mixedHealth.hit(compound,12000,1,mixedCombat).amount()==7&&mixedCombat.state().shieldHealth()==60,
            "mixed damage blocks only its blockable component");
        var statusData=new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.StatusConditionConfig$Poison",List.of(
            new FireCatalog.FieldValue("shieldDamage","com.threerings.config.ConfigReference",new StarterCatalog.Model("shield-cost",Map.of()))));
        var statusSpec=new SceneContent.Spec(Map.of("config","Poison","class","Poison","flags","2","effective-chance","1","effective-power","100","min-duration","10000","max-duration","10000",
            "data",FireCatalog.modelJson(new StarterCatalog.Model("status",Map.of("data",statusData)))));
        var statusContent=new SceneContent(List.of(),Map.of("shield-cost",40f),Map.of(),Map.of("player.base-health",200f),Map.of(),Map.of(),Map.of("Poison",statusSpec));
        var statusHealth=new SceneHealth(statusContent);var statusCombat=new KnightCombat(catalog);statusCombat.equipment(profile);statusCombat.reset(13000);
        Object poison=condition(statusContent,"Poison");
        for(int stamp:new int[]{13000,13411,14000}){
            statusCombat.advance(stamp,0);statusCombat.input(64,stamp,stamp,0,0,true);statusHealth.hit(poison,stamp,1,statusCombat);
            check(statusHealth.status().conditions().isEmpty(),"surviving shield blocks status application");
            check(statusCombat.state().shieldHealth()==(stamp<14000?60:20),"status shield cost respects its one-second cooloff");
        }
        statusCombat.advance(15000,0);statusCombat.input(64,15000,15000,0,0,true);statusHealth.hit(poison,15000,1,statusCombat);
        check(statusCombat.state().shieldHealth()==0&&!statusHealth.status().conditions().isEmpty(),"status damage can break a shield and apply the condition");
    }
    private static void health(SceneContent prepared) {
        var content=new SceneContent(List.of(),prepared.scales(),Map.of(),
            Map.of("player.base-health",200f,"gear.Fixture.health-bonus",40f),Map.of(),Map.of(),prepared.statuses());
        var health=new SceneHealth(content);
        health.hit(condition(content,"Sleep"),100);
        check(!health.status().conditions().isEmpty(),"fixture applies a guaranteed native sleep condition");
        health.hit(damage(1000),101);
        check(!health.alive()&&health.status().conditions().isEmpty(),"lethal damage clears conditions");
        check(!health.heal(200),"normal healing cannot resurrect");
        var profile=new WorldProfile(10,1,1,0,0,1,List.of(new WorldProfile.Item(1,WorldProfile.Slot.HELM,"Fixture",null,0,0,true)));
        health.equipment(profile);
        check(health.state().current()==0&&health.state().maximum()==240,"equipping bonus health cannot resurrect");
        check(health.revive(1000)&&health.state().current()==240,"explicit revival restores maximum health");
        check(health.state().reviveFlags()==0,"manual revival consumes the same pending emergency charge");
        check(!health.revive(1001),"a live knight cannot reset revival protection");
        check(health.hit(damage(10),2999).amount()==0&&health.invulnerable(2999),"revival protection lasts two seconds");
        check(health.hit(damage(10),3000).amount()==10&&!health.invulnerable(3000),"damage resumes at the protection deadline");
        health.hit(damage(1000),3001);health.advance(9000,5999,false);
        check(!health.alive()&&health.canSparkRevive()&&health.state().reviveFlags()==0,"a second death cannot schedule another free revive");
        var automatic=new SceneHealth(content);automatic.hit(damage(10000),100);
        check(automatic.state().reviveFlags()==9&&!automatic.canSparkRevive(),"automatic revival publishes its native pending flags without a Spark offer");
        automatic.advance(5099,4999,false);check(!automatic.alive(),"emergency delay has not elapsed");
        automatic.advance(5100,1,false);check(automatic.alive()&&automatic.state().reviveFlags()==0&&automatic.invulnerable(10099),"automatic revival restores full health and five-second protection");
        automatic.equipment(profile);automatic.heal(10000);automatic.hit(damage(10000),10100);automatic.advance(16000,5900,false);
        check(!automatic.alive()&&automatic.canSparkRevive(),"healing and equipment cannot replenish a spent charge");
        // A status tick can kill while SceneStatus is iterating its active conditions.
        var tiny=new SceneHealth(new SceneContent(List.of(),prepared.scales(),Map.of(),Map.of("player.base-health",1f),Map.of(),Map.of(),prepared.statuses()));
        tiny.hit(condition(content,"Fire"),100);
        tiny.advance(101,1,false);
        check(!tiny.alive()&&tiny.status().conditions().isEmpty(),"burn death safely clears statuses after their tick");
    }
    private static void movement(SceneCatalog.MapScene map) {
        var movement=new TownMovement(map.collision(),5.5f,-12.5f);
        movement.combat(new KnightCombat(WeaponCatalog.empty()));
        int[] revivals={0};movement.revival(stamp->{revivals[0]++;return true;});
        movement.die(1000);movement.resetInput(1100);
        check(movement.activity()==TownMovement.DYING&&movement.activityStarted()==1000,"readiness reset preserves death timing");
        float x=movement.x(),y=movement.y();
        movement.accept(1200,1,1,5,1200,1200,1200);movement.advance(1200,1200);
        check(revivals[0]==0&&!movement.sit(1200,1200),"dying knight cannot revive early or emote");
        movement.advance(2290,2290);
        check(movement.activity()==TownMovement.DEAD&&movement.activityStarted()==2290,"native dying duration ends in a persistent corpse");
        movement.accept(2300,1,1,5,1200,2300,2300);movement.advance(2300,2300);
        check(revivals[0]==0&&movement.x()==x&&movement.y()==y&&movement.flags()==0,"held pre-death input cannot move or revive a corpse");
        movement.accept(2350,1,1,5,2350,2350,2350);movement.advance(2350,2350);
        check(revivals[0]==1&&movement.activity()==TownMovement.RESURRECT&&movement.flags()==0,"fresh action revives once without attacking or moving");
        movement.advance(2749,2749);check(movement.activity()==TownMovement.RESURRECT,"revival animation holds for 400 ms");
        movement.advance(2750,2750);check(movement.activity()==0,"revival animation returns to idle");
    }
    private static void training(SceneCatalog.MapScene map)throws Exception {
        var spike=map.content().entries().stream().filter(e->e.spec().text("config","").endsWith("/Spikes")).findFirst().orElseThrow();
        var fields=new HashMap<>(spike.spec().fields());fields.put("cycle","UP");
        var content=new SceneContent(List.of(new SceneContent.Entry("trap",5.5f,-12.5f,0,new SceneContent.Spec(fields))),
            map.content().scales(),Map.of(),Map.of("player.base-health",1f),Map.of(),Map.of(),Map.of());
        var fixture=new SceneCatalog.MapScene(map.id(),map.zone(),map.zoneName(),map.name(),map.image(),"training",map.version(),
            5.5f,-12.5f,0,1,map.collision(),map.layout(),ScenePortals.EMPTY,content);
        var simulation=new SceneRegistry.Instance(799,1,fixture).simulation();
        var other=new SceneRegistry.Instance(800,2,fixture).simulation();
        var queue=new LinkedBlockingQueue<SceneSimulation.Snapshot>();
        int start=SceneClock.now();var model=new StarterCatalog.Model("Character/PC/Default",Map.of());
        var actor=new ActorState(219,1001,"Fixture",256,start,5.5f,-12.5f,0,0,0,0,start,model,false);
        try(var player=simulation.join(actor,queue::add);var isolated=other.join(actor,s->{})){
            player.combat(WeaponCatalog.empty(),new WorldProfile(219,1,1,0,0,1,List.of()));player.enter();
            var dead=await(queue,s->s.actors().getFirst().activity()==TownMovement.DEAD);
            var corpse=dead.actors().getFirst();
            check(dead.health(219).current()==0&&corpse.interaction()==null&&dead.health(219).reviveFlags()==9,"pending Emergency Revive has no manual resurrection prompt");
            check(!player.useItem(1,new SceneContent.Spec(Map.of("kind","vial"))),"dead knight cannot use temporary items");
            check(other.snapshot().health(219).current()==1,"death does not affect another instance of the map");
            int stamp=SceneClock.now();check(player.input(stamp,0,0,4,stamp,stamp,System.nanoTime()/1_000_000L),"action intent reaches the corpse");
            var revived=await(queue,s->s.health(219).current()>0);
            check(revived.actors().getFirst().activity()==TownMovement.RESURRECT&&(revived.actors().getFirst().flags()&64)!=0,"Training revival restores health and streams the native protection flag");
            check(revived.actors().getFirst().interaction()==null,"revival clears the corpse prompt");
            check(revived.health(219).reviveFlags()==0,"the pending automatic charge is consumed exactly once");
        }
    }
    private static SceneSimulation.Snapshot await(BlockingQueue<SceneSimulation.Snapshot> queue,java.util.function.Predicate<SceneSimulation.Snapshot> ready)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(7);
        while(System.nanoTime()<deadline){var snapshot=queue.poll(100,TimeUnit.MILLISECONDS);if(snapshot!=null&&ready.test(snapshot))return snapshot;}
        throw new AssertionError("Scene lifecycle publication missing");
    }
    static Object damage(float amount){return new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$Normal",List.of(
        new FireCatalog.FieldValue("flags","int",8),new FireCatalog.FieldValue("amount","float",amount)));}
    static Object condition(SceneContent content,String kind){
        String name=content.statuses().entrySet().stream().filter(e->e.getValue().text("class","").equals(kind)).map(Map.Entry::getKey).findFirst().orElseThrow();
        return new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$Status",List.of(
            new FireCatalog.FieldValue("flags","int",1),new FireCatalog.FieldValue("statusCondition","com.threerings.config.ConfigReference",
                new StarterCatalog.Model(name,Map.of("Chance",1f,"Power",100)))));
    }
}
