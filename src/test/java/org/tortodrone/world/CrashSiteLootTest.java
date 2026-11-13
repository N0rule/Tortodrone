package org.tortodrone.world;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import org.tortodrone.character.StarterCatalog;
import org.tortodrone.resources.ContentManager;
import static org.tortodrone.support.TestWire.check;

/** The tutorial Ironwood Giant's authored Shutdown payout: 12 Copper and 1 Copper-Silver
 * Crown plus 2 Ember 5 and 1 Ember 3, through the production death and pickup paths. */
public final class CrashSiteLootTest {
    private static final String GIANT="Character/NPC/Monster/Boss/Tutorial, Ironwood Giant/Ironwood Giant";
    public static void main(String[] args)throws Exception {
        var map=ContentManager.load(Path.of(".")).scenes().scene(398);
        Map<String,String> giant=null;
        for(var entry:map.content().entries())if(!entry.spec().text("action","").isEmpty())
            for(var fields:CrashSiteMonsterCombatTest.spawnFields(SceneActions.rule(entry).action()))if(GIANT.equals(fields.get("config")))giant=fields;
        check(giant!=null,"the bundled Crash Site spawns its Ironwood Giant");

        var solo=kill(giant,true);
        check(solo.equals(Map.of("Item/Consumable/Crowns/Copper Crown",12,"Item/Consumable/Crowns/Copper-Silver Crown",1,
            "Item/Consumable/Heat/Ember 5",2,"Item/Consumable/Heat/Ember 3",1)),"the private tutorial pays the authored Shutdown spawns: "+solo);
        var shared=kill(giant,false);
        check(shared.equals(Map.of("Item/Consumable/Heat/Ember 5",2,"Item/Consumable/Heat/Ember 3",1)),
            "a shareable instance still refuses single-claimant party crowns but keeps party heat: "+shared);

        var registry=new SceneRegistry(ContentManager.load(Path.of(".")).scenes());
        check(soloRewards(registry.instance(398,0))&&!soloRewards(registry.instance(294,0)),"only tutorial instances enable solo party rewards");
        System.out.println("PASS: tutorial Ironwood Giant Shutdown payout (17 crowns, 110 heat) in the private instance; party crowns stay refused elsewhere");
    }
    /** Kills a fresh copy of the bundled Giant and counts the pickups its death leaves. */
    private static Map<String,Integer> kill(Map<String,String> giant,boolean solo)throws Exception {
        var terrain=SceneWeaponsTest.openTerrain();
        var entities=new SceneEntities(new SceneContent(List.of(new SceneContent.Entry("giant",0,0,0,new SceneContent.Spec(giant))),Map.of()),terrain);
        entities.soloRewards(solo);
        var weapons=new SceneWeapons(terrain);weapons.targets(entities);int stamp=SceneClock.now()+100;
        entities.attacks((actor,action)->weapons.fire(action,actor,stamp));
        var damage=new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$Normal",Map.of("flags",2,"amount",1_000_000f,"shouldReact",true,"majorReact",true));
        entities.damage(new StarterCatalog.Model("DamageRegion",Map.of("shape",new FireCatalog.DataValue("Shape$Circle",Map.of("radius",2f)),"damage",damage)),0,0,0,stamp,219);
        entities.advance(stamp+1,List.of());
        var pickups=entities.actors().stream().filter(a->a.entity()!=null&&a.entity().kind().equals("Pickup")).toList();
        check(pickups.stream().allMatch(p->Math.hypot(p.x(),p.y())<1e-3),"every Shutdown spawn appears at the Giant's position");
        return pickups.stream().collect(Collectors.toMap(p->p.config().name(),p->1,Integer::sum));
    }
    private static boolean soloRewards(SceneRegistry.Instance instance)throws Exception {
        var field=SceneSimulation.class.getDeclaredField("entities");field.setAccessible(true);
        var flag=SceneEntities.class.getDeclaredField("soloRewards");flag.setAccessible(true);
        return flag.getBoolean(field.get(instance.simulation()));
    }
}
