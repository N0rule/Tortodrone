package org.tortodrone.world;
import org.tortodrone.core.world.SceneClock;

import java.nio.file.Path;
import java.util.*;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.resources.ContentManager;
import static org.tortodrone.support.TestWire.check;
import org.tortodrone.core.character.Knight;
import org.tortodrone.core.world.SceneActions;
import org.tortodrone.core.world.FireCatalog;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.core.world.SceneContent;

/** Crash Site destructible tutorial boxes: heart treasure blocks break and drop a
 * healing heart, and explosive blocks apply their authored detonation damage. */
public final class CrashSiteBoxesTest {
    private static final String HEART="Item/Consumable/Hearts/Heart";
    public static void main(String[] args)throws Exception {
        var map=ContentManager.load(Path.of(".")).scenes().scene(398);
        var explosive=entry(map,"Block/Explosive");
        var health=entry(map,"Block/Treasure, Health (Tutorial)");
        int stamp=SceneClock.now()+5000;

        var hits=new ArrayList<SceneEntities.PlayerHit>();
        var world=new SceneEntities(map.content(),map.collision());
        world.playerHits(hits::add);
        var weapons=new SceneWeapons(map.collision());weapons.targets(world);
        world.attacks((actor,action)->weapons.fire(action,actor,stamp));
        int explosiveId=world.actors().stream().filter(a->a.config().name().equals("Block/Explosive"))
            .findFirst().orElseThrow().id();
        var player=new ActorState(219,1001,"Knight",0,stamp,explosive.x()+0.6f,explosive.y(),0,0,0,0,stamp,
            new StarterCatalog.Model("Character/PC/Level",Map.of()),false);
        world.damage(hit(1,false),explosive.x(),explosive.y(),0,stamp,219);
        world.advance(stamp+1,List.of(player));
        check(world.actor(explosiveId).entity().state()==1,"the explosive block arms on its first hit");
        check(hits.isEmpty(),"the arming hit does not damage the knight");
        // Point-blank and the live capture's bracket: a knight hugging the solid block cannot
        // enter its 1x1 footprint (his circle stops at 0.833 off-centre), so the official blast
        // must reach past the footprint; the live capture pins a hit 1.48/0.41 off-centre and
        // misses at 0.83/2.25. The 0.6 placement above overlaps the block and only exists to
        // keep the arming knight in the scene.
        var pointBlank=new ActorState(220,1002,"Knight",0,stamp,explosive.x()+0.9f,explosive.y(),0,0,0,0,stamp,
            new StarterCatalog.Model("Character/PC/Level",Map.of()),false);
        var captureHit=new ActorState(221,1003,"Knight",0,stamp,explosive.x()+1.48f,explosive.y()+0.41f,0,0,0,0,stamp,
            new StarterCatalog.Model("Character/PC/Level",Map.of()),false);
        var captureMiss=new ActorState(222,1004,"Knight",0,stamp,explosive.x()+0.83f,explosive.y()+2.25f,0,0,0,0,stamp,
            new StarterCatalog.Model("Character/PC/Level",Map.of()),false);
        world.advance(stamp+101,List.of(player,pointBlank,captureHit,captureMiss));
        check(world.actor(explosiveId)==null,"the explosive block detonates after its fuse");
        check(hits.stream().anyMatch(h->h.actor()==player.id()),"the detonation damages an adjacent knight");
        check(hits.stream().anyMatch(h->h.actor()==pointBlank.id()),"the blast damages a knight hugging the solid block at point-blank");
        check(hits.stream().anyMatch(h->h.actor()==captureHit.id()),"the blast damages the capture's knight 1.48/0.41 off-centre");
        check(hits.stream().noneMatch(h->h.actor()==captureMiss.id()),"the capture's knight 0.83/2.25 off-centre is outside the blast");

        var healed=new ArrayList<Integer>();
        var healthWorld=new SceneEntities(map.content(),map.collision());
        healthWorld.playerHeal((actor,amount)->{healed.add(amount);return true;});
        int healthId=healthWorld.actors().stream().filter(a->a.config().name().equals("Block/Treasure, Health (Tutorial)"))
            .findFirst().orElseThrow().id();
        healthWorld.damage(hit(1,false),health.x(),health.y(),0,stamp,219);
        check(healthWorld.actor(healthId)==null,"the tutorial heart block is destroyed by a normal hit");
        // The official tutorial table (depth 0 band {2,2}, value offset -1, spent largest tier
        // first) draws value 1, so the block's whole loot is exactly one tier-1 Heart.
        var hearts=healthWorld.actors().stream().filter(a->a.config().name().startsWith("Item/Consumable/Hearts/Heart")
            &&a.entity().destroyed()==Integer.MAX_VALUE).toList();
        check(hearts.size()==1,"the official tutorial table drops exactly one heart at depth 0");
        var heart=hearts.getFirst();
        check(Math.abs(heart.x()-health.x())<1e-3f&&Math.abs(heart.y()-health.y())<1e-3f,"the heart drops where the block stood");
        var knight=new ActorState(219,1001,"Knight",0,stamp,health.x(),health.y(),0,0,0,0,stamp,
            new StarterCatalog.Model("Character/PC/Level",Map.of()),false);
        healthWorld.advance(stamp+1,List.of(knight));
        healthWorld.advance(stamp+1100,List.of(knight));
        check(healed.equals(List.of(40)),"walking over the dropped heart heals 40");
        check(healthWorld.actor(heart.id())==null,"the collected heart is consumed");

        // A zero-speed turret must never translate, even while it acquires, turns and fires.
        Map<String,String> puppy=null;
        for(var entry:map.content().entries())if(!entry.spec().text("action","").isEmpty())
            for(var fields:CrashSiteMonsterCombatTest.spawnFields(SceneActions.rule(entry).action()))
                if("Character/NPC/Monster/Construct/Gun Puppy/Gun Puppy (TG)".equals(fields.get("config")))puppy=fields;
        check(puppy!=null,"the bundled Crash Site spawns its Gun Puppy");
        var real=map.content();
        var puppyContent=new SceneContent(List.of(new SceneContent.Entry("puppy",0,0,0,new SceneContent.Spec(puppy))),
            real.scales(),Map.of(),real.stats(),real.items(),real.cycles(),real.statuses());
        var puppyWorld=new SceneEntities(puppyContent,map.collision());
        int puppyId=puppyWorld.actors().getFirst().id();
        var target=new ActorState(219,1001,"Knight",0,stamp,2f,0,0,0,0,0,stamp,
            new StarterCatalog.Model("Character/PC/Level",Map.of()),false);
        for(int t=0;t<6000;t+=100)puppyWorld.advance(stamp+t,List.of(target));
        check(Math.hypot(puppyWorld.actor(puppyId).x(),puppyWorld.actor(puppyId).y())<1e-3,
            "a zero-speed Gun Puppy never translates while tracking and firing");
        System.out.println("PASS: Crash Site heart blocks drop hearts, explosive detonations damage adjacent knights and turrets stay put");
    }
    private static SceneContent.Entry entry(org.tortodrone.core.world.MapScene map,String config){
        return map.content().entries().stream().filter(e->e.spec().text("config","").equals(config)).findFirst().orElseThrow();
    }
    private static StarterCatalog.Model hit(int flags,boolean major){
        var damage=new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$Normal",
            Map.of("flags",flags,"amount",10f,"shouldReact",true,"majorReact",major));
        return new StarterCatalog.Model("DamageRegion",Map.of(
            "shape",new FireCatalog.DataValue("Shape$Circle",Map.of("radius",1f)),"damage",damage));
    }
}
