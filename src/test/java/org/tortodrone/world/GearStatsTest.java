package org.tortodrone.world;

import java.nio.file.Path;
import java.util.*;
import org.tortodrone.character.*;
import org.tortodrone.resources.ContentManager;
import static org.tortodrone.support.TestWire.check;

/** Leveled equipment must change authoritative damage, mitigation and timing. */
public final class GearStatsTest {
    private static WorldProfile.Item item(long id,String config,WorldProfile.Slot slot,int equipped,int level,String... variants){
        return new WorldProfile.Item(id,slot,config,null,0,equipped,true,true,false,1,false,level,0,Arrays.stream(variants).map(v->new StarterCatalog.Model(v,Map.of())).toList());
    }
    public static void main(String[] args)throws Exception {
        var content=ContentManager.load(Path.of("."));var scene=content.scenes().scene(294).content();var stats=scene.stats();var town=content.town();
        var missing=new HashMap<>(stats);missing.put("gear.Gear/Armor/Wolver Coat.table",900001f);
        try{GearStats.validate(missing);throw new AssertionError("Missing table accepted");}catch(IllegalArgumentException expected){}
        var sword=item(1,"Weapon/Sword/Proto Sword",WorldProfile.Slot.SWORD,6,9);
        var armor=item(2,"Gear/Armor/Wolver Coat",WorldProfile.Slot.ARMOR,1,9);
        var profile=new WorldProfile(10,1,1,0,0,1,List.of(sword,armor));
        check(GearStats.value(stats,armor,"health-bonus")==40,"leveled armor health includes the native level table");
        check(GearStats.value(stats,armor,"defense.normal")==35&&GearStats.value(stats,armor,"defense.piercing")==33,"level ten dual defenses replace level-one stats");
        var variant=item(3,"Gear/Armor/Wolver Coat",WorldProfile.Slot.ARMOR,1,9,"Gear/Normal Defense 1");
        check(GearStats.defense(stats,variant,"normal",town)==37&&GearStats.defense(stats,variant,"elemental",town)==0,"defense UV uses the item's rarity and its own damage type");
        var damage=GearStats.damage(stats,profile,sword,town);
        check(Math.abs(damage.apply(100,"")-156)<.001,"uncapped weapon level and capped sword-class armor bonus use relative damage arithmetic");
        var gun=item(4,"Weapon/Handgun/Proto Gun",WorldProfile.Slot.GUN,7,0);
        check(GearStats.damage(stats,new WorldProfile(10,1,1,0,0,1,List.of(gun,armor)),gun,town).apply(100,"")==100,"sword armor bonus cannot modify a handgun");
        var tagged=item(5,"Weapon/Sword/Proto Sword",WorldProfile.Slot.SWORD,6,0,"Weapon/Beast Damage 1");
        var taggedDamage=GearStats.damage(stats,new WorldProfile(10,1,1,0,0,1,List.of(tagged)),tagged,town);
        check(Math.abs(taggedDamage.apply(100,"fam_beast")-104)<.001&&taggedDamage.apply(100,"fam_slime")==100,"tagged damage applies only to matching monster families");
        var costume=item(6,"Gear/Armor/Wolver Coat",WorldProfile.Slot.ARMOR,4,9);
        check(GearStats.damage(stats,new WorldProfile(10,1,1,0,0,1,List.of(gun,costume)),gun,town).apply(100,"")==100,"costume slots do not contribute gear stats");
        var calibur=item(7,"Weapon/Sword/Calibur",WorldProfile.Slot.SWORD,6,9);
        check(GearStats.damage(stats,new WorldProfile(10,1,1,0,0,1,List.of(calibur)),calibur,town).apply(100,"")==100,"higher-tier level bonus retains its depth gate in Training");
        var registry=content.createScenes();var pair=registry.equipment().forKnight(profile,sword);
        check(Math.abs(pair.weapon().chargeTime()-.16f)<.001,"weapon level charge reduction reaches the authoritative controller");
        check(EquipmentCatalog.chargeTimeMillis(1000,pair.equipment(),pair.weapon())==840,"level charge reduction changes charge readiness");
        var health=new SceneHealth(scene);health.equipment(profile,town);
        check(health.state().maximum()==240,"native level health changes the HUD maximum");
        var incoming=new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$Normal",Map.of("amount",100f,"flags",4));
        check(health.hit(incoming,100).amount()==83,"leveled normal defense participates in damage mitigation");
        projectileSnapshot(content.scenes().scene(294),damage);
        System.out.println("PASS: imported levels, health, defenses/UVs, class/tag/depth damage, costume exclusion and charge readiness");
    }
    private static void projectileSnapshot(SceneCatalog.MapScene map,GearStats.Damage damage){
        var region=new StarterCatalog.Model("Fixture",Map.of());
        var hit=new FireCatalog.Action(FireCatalog.Kind.DAMAGE_REGION,region,null,"Source","","",0,0,0,0,true,0,0,0,0,false,List.of(),"");
        var bomb=new FireCatalog.ActorSpec("BOMB",region,200,0,0,0,0,0,0,hit);
        var spawn=new FireCatalog.Action(FireCatalog.Kind.EMIT_PROJECTILE,null,bomb,"Source","","",0,0,0,0,true,0,0,0,0,false,List.of(),"");
        var fire=new FireCatalog.AttackFire("Fixture",292,region,1,List.of(new FireCatalog.FireRef(region,0,0,true,0,0,0,spawn,null)));
        var weapons=new SceneWeapons(map.collision());weapons.catalog(new FireCatalog(List.of(fire),Map.of()));
        var current=new java.util.concurrent.atomic.AtomicReference<>(damage);weapons.damageModifiers((owner,start)->current.get());
        var hits=new ArrayList<GearStats.Damage>();
        weapons.targets(new SceneWeapons.Targets(){public boolean contact(float x,float y,int owner){return false;}
            public void damage(StarterCatalog.Model config,float x,float y,float rotation,int stamp,int owner){hits.add((GearStats.Damage)config.arguments().getOrDefault("equipment-damage",GearStats.Damage.NONE));}});
        var owner=new ActorState(10,1001,"Fixture",0,100,map.spawnX(),map.spawnY(),0,0,0,292,100,region);
        weapons.attack(10,new KnightCombat.AttackStart("Fixture",292,100,0,500));
        current.set(GearStats.Damage.NONE);weapons.advance(101,List.of(owner));weapons.advance(301,List.of(owner));
        check(hits.equals(List.of(damage)),"projectile retains its original gear snapshot after equipment changes");
        weapons.cancel(10);hits.clear();current.set(damage);
        weapons.attack(10,new KnightCombat.AttackStart("Fixture",292,400,0,500),fire);
        var pet=new ActorState(10,1001,"Fixture",0,100,map.spawnX(),map.spawnY(),0,0,0,292,400,region);
        weapons.advance(401,List.of(pet));weapons.advance(601,List.of(pet));
        check(hits.equals(List.of(GearStats.Damage.NONE)),"explicit companion attacks do not inherit the knight's weapon bonuses");
    }
}
