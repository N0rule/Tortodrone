package org.tortodrone.world;

import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.tortodrone.character.StarterCatalog;

/** Small scene-clock regression: native emissions, expiry and observer-independent ownership. */
public final class SceneWeaponsTest {
    public static void main(String[] args)throws Exception {
        authoredHeavyAttacks();
        var map=SceneCatalog.load(Path.of("content/packs/vanilla/maps")).entry();
        var model=new StarterCatalog.Model("Fixture",Map.of());
        var effect=new FireCatalog.Action(FireCatalog.Kind.FIRE_EFFECT,model,null,"Source","","",0,0,0,0,true,0,0,0,0,false,List.of(),"");
        var bullet=new FireCatalog.ActorSpec("BULLET",model,200,2,.1f,0,0,0,1,null);
        var bombSpawn=new FireCatalog.Action(FireCatalog.Kind.EMIT_PROJECTILE,null,bullet,"Source","","",0,0,0,0,true,0,0,0,0,false,List.of(),"");
        var bombDetonation=new FireCatalog.Action(FireCatalog.Kind.COMPOUND,null,null,"","","",0,0,0,0,false,0,0,0,0,false,List.of(effect,bombSpawn),"");
        var bomb=new FireCatalog.ActorSpec("BOMB",new StarterCatalog.Model("Bomb",Map.of()),200,0,0,0,0,0,0,bombDetonation);
        var gun=attack("Gun",bullet);var grenade=attack("Bomb",bomb);
        var weapons=new SceneWeapons(map.collision());weapons.catalog(new FireCatalog(List.of(gun,grenade),Map.of()));
        var owner=new ActorState(10,1001,"Fixture",257,100,map.spawnX(),map.spawnY(),0,0,0,292,100,model);
        weapons.attack(10,new KnightCombat.AttackStart("Gun",292,100,50,500));
        weapons.advance(150,List.of(owner));check(weapons.actors().isEmpty(),"stock fire deadline is strict");
        weapons.advance(151,List.of(owner));check(weapons.actors().size()==1,"one native gun bullet at land deadline");
        var first=weapons.actors().getFirst();check(first.projectile().faction()==10&&first.clientOid()==1001,"faction and controller derive from owned knight");
        check(first.projectile().z()==ProjectileState.UNSPECIFIED_Z,
            "bullet resolves floor Z in the client instead of adding authored height twice");
        weapons.advance(251,List.of(owner));check(weapons.actors().getFirst().x()>first.x(),"projectile advances once on scene clock");
        weapons.advance(351,List.of(owner));check(weapons.actors().getFirst().projectile().fizzle(),"fuse expiry marks bullet fizzle");
        check(weapons.effects().isEmpty(),"expired bullet cannot invent hit rewards or effects");
        weapons.advance(500,List.of(owner));check(weapons.actors().isEmpty(),"destroyed projectile is removed after publication");
        weapons.attack(10,new KnightCombat.AttackStart("Bomb",292,500,0,500));
        var later=new ActorState(10,1001,"Fixture",257,100,map.spawnX(),map.spawnY(),0,0,0,292,500,model);
        weapons.advance(501,List.of(later));check(weapons.actors().size()==1,"bomb drop is a native bomb actor");
        check(weapons.actors().getFirst().projectile().z()==ProjectileState.UNSPECIFIED_Z,
            "bomb preserves the native floor-resolution sentinel");
        weapons.advance(701,List.of(later));check(weapons.effects().size()==1&&weapons.effects().getFirst().ownerOid()==0,"bomb detonation emits authored native effect");
        check(weapons.actors().stream().anyMatch(a->a.projectile()!=null&&a.projectile().created()==700),"detonation child projectile is queued without mutating the active iteration");
        weapons.cancel(10);check(weapons.actors().isEmpty(),"detach clears only owner's ephemeral actors");
        weapons.attack(10,new KnightCombat.AttackStart("Gun",292,725,50,500));
        var transitioned=new ActorState(10,1001,"Fixture",257,100,map.spawnX(),map.spawnY(),0,0,0,293,750,model);
        weapons.advance(776,List.of(transitioned));check(weapons.actors().isEmpty(),"activity transition before land deadline cancels the pending projectile");
        var orbit=new FireCatalog.OrbitSpec(.1f,0,0,2,100,250,"SOURCE",false,null,null,null);
        var compoundOrbit=new FireCatalog.OrbitSpec(.1f,0,0,2,100,250,"SOURCE",false,.1f,0f,(float)(Math.PI/2));
        var disk=new FireCatalog.ActorSpec("ORBITAL_BULLET",model,250,2,0,2,0,0,1,null,orbit);
        var doubleDisk=new FireCatalog.ActorSpec("COMPOUND_ORBITAL_BULLET",model,250,2,0,2,0,0,1,null,compoundOrbit);
        weapons.catalog(new FireCatalog(List.of(attack("Disk",disk),attack("Double Disk",doubleDisk)),Map.of()));
        weapons.attack(10,new KnightCombat.AttackStart("Disk",292,800,0,500));
        weapons.attack(10,new KnightCombat.AttackStart("Double Disk",292,800,0,500));
        var home=new ActorState(10,1001,"Fixture",257,100,map.spawnX(),map.spawnY(),0,0,0,292,800,model);
        weapons.advance(801,List.of(home));
        weapons.advance(901,List.of(home));
        var baseAtOutbound=weapons.actors().stream().filter(a->a.projectile().nativeType().equals("ORBITAL_BULLET")).findFirst().orElseThrow();
        var compoundAtOutbound=weapons.actors().stream().filter(a->a.projectile().nativeType().equals("COMPOUND_ORBITAL_BULLET")).findFirst().orElseThrow();
        check(Math.abs(baseAtOutbound.projectile().orbit().relativeY())<.001f,"orbital bullet follows its authored single orbit");
        check(compoundAtOutbound.projectile().orbit().relativeX()>.09f&&compoundAtOutbound.projectile().orbit().relativeY()>.09f,"compound orbital bullet sums both authored orbit vectors");
        var movedHome=new ActorState(10,1001,"Fixture",257,100,map.spawnX()+1,map.spawnY(),0,0,0,292,1001,model);
        weapons.advance(1001,List.of(movedHome));
        var baseHoming=weapons.actors().stream().filter(a->a.projectile().nativeType().equals("ORBITAL_BULLET")).findFirst().orElseThrow();
        check((baseHoming.flags()&4)!=0&&baseHoming.x()>baseAtOutbound.x(),"orbital bullet enters return flight toward its current owner");
        weapons.advance(1051,List.of(movedHome));
        check(weapons.actors().stream().allMatch(a->(a.flags()&8)!=0),"orbital fuse expiry publishes native fizzle flags");
        var random=new FireCatalog.Action(FireCatalog.Kind.RANDOM_EQUAL,null,null,"","","",0,0,0,0,false,0,0,0,0,false,List.of(effect,effect),"");
        var randomRef=new FireCatalog.FireRef(model,0,0,true,0,0,0,random,null);
        weapons.catalog(new FireCatalog(List.of(new FireCatalog.AttackFire("Random",292,model,1,List.of(randomRef))),Map.of()));
        int before=weapons.effects().size();weapons.attack(10,new KnightCombat.AttackStart("Random",292,1100,0,500));
        var randomOwner=new ActorState(10,1001,"Fixture",257,100,map.spawnX(),map.spawnY(),0,0,0,292,1100,model);
        weapons.advance(1101,List.of(randomOwner));check(weapons.effects().size()==before+1,"equal-weight random fires exactly one authored branch");
        weapons.cancel(10);
        var modified=new FireCatalog.Action(FireCatalog.Kind.EMIT_PROJECTILE,null,bullet,"Source","","",0,0,0,0,true,0,0,0,0,false,List.of(),"","Pollen Color",List.of("Red","Blue"));
        var multi=new FireCatalog.Action(FireCatalog.Kind.MULTI_PRESPAWNED,null,null,"","","",0,0,0,0,false,0,0,0,0,false,List.of(modified,modified),"");
        weapons.catalog(new FireCatalog(List.of(new FireCatalog.AttackFire("Pollen",292,model,1,List.of(new FireCatalog.FireRef(model,0,0,true,0,0,0,multi,null)))),Map.of()));
        weapons.attack(10,new KnightCombat.AttackStart("Pollen",292,1200,0,500));
        var pollenOwner=new ActorState(10,1001,"Fixture",257,100,map.spawnX(),map.spawnY(),0,0,0,292,1200,model);
        weapons.advance(1201,List.of(pollenOwner));
        check(weapons.actors().get(0).config().arguments().get("Pollen Color").equals("Red")&&weapons.actors().get(1).config().arguments().get("Pollen Color").equals("Blue"),"prespawned arguments use fire timestamp and per-child seed");
        weapons.cancel(10);
        var recursive=new FireCatalog.ActorSpec("BOMB",model,1,0,0,0,0,0,0,null);
        var spawnAgain=new FireCatalog.Action(FireCatalog.Kind.EMIT_PROJECTILE,null,recursive,"Source","","",0,0,0,0,true,0,0,0,0,false,List.of(),"");
        var outer=new FireCatalog.ActorSpec("BOMB",model,1,0,0,0,0,0,0,spawnAgain);
        weapons.catalog(new FireCatalog(List.of(attack("Recursive",outer)),Map.of()));
        weapons.attack(10,new KnightCombat.AttackStart("Recursive",292,1300,0,500));
        var recursiveOwner=new ActorState(10,1001,"Fixture",257,100,map.spawnX(),map.spawnY(),0,0,0,292,1300,model);
        weapons.advance(1301,List.of(recursiveOwner));weapons.advance(1302,List.of(recursiveOwner));
        check(weapons.actors().stream().anyMatch(a->a.created()==1302),"recursive detonation resolves matching authored config without infinite catalogue expansion");
        blockedCellCannotBeSkipped();
        fireTracksAttackAnimationSpeed(map.collision());
        System.out.println("PASS: gun bullet emission/movement/fizzle, bomb fuse/effect, orbital collision and owned cleanup");
    }
    private static CollisionMap openTerrain()throws Exception {
        Path file=Files.createTempFile("tortodrone-weapon-open-",".gz");
        try{
            try(var out=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))){
                out.writeInt(0x54445731);out.writeInt(256);out.writeInt(256);
                out.writeFloat(-64);out.writeFloat(-64);out.writeFloat(.5f);
                byte[] cells=new byte[256*256/8];Arrays.fill(cells,(byte)255);out.write(cells);
            }
            return CollisionMap.read(file);
        }finally{Files.delete(file);}
    }
    private static void authoredHeavyAttacks()throws Exception {
        var catalog=FireCatalog.load(Path.of("content/packs/vanilla/combat/fires.json"));
        var charge=catalog.find("Weapon/Sword/Combuster",290);
        var carrier=charge.fireRefs().getLast().action().actor();
        var trail=carrier.timers().getFirst().action().actor();
        check(carrier.config().arguments().get("Color").equals(charge.attack().arguments().get("Burst Color"))
            &&carrier.config().arguments().get("Damage").equals(charge.attack().arguments().get("Burst Damage"))
            &&trail.config().arguments().get("Color").equals(carrier.config().arguments().get("Color"))
            &&trail.config().arguments().get("Damage").equals(carrier.config().arguments().get("Damage"))
            &&trail.config().arguments().get("Scale").equals(carrier.config().arguments().get("Burst Scale")),
            "burst trails retain the weapon's color, player damage and explosion size");
        var model=new StarterCatalog.Model("Fixture",Map.of());
        var owner=new ActorState(10,1001,"Fixture",257,100,0,0,0,0,0,290,100,model);
        var hits=new ArrayList<StarterCatalog.Model>();
        var scene=new SceneWeapons(openTerrain());scene.catalog(catalog);
        scene.targets(new SceneWeapons.Targets(){
            public boolean contact(float x,float y,int owner){return false;}
            public void damage(StarterCatalog.Model region,float x,float y,float rotation,int stamp,int owner){hits.add(region);}
        });
        scene.attack(10,new KnightCombat.AttackStart("Weapon/Sword/Combuster",290,100,0,2000));
        int trails=0;var ids=new HashSet<Integer>();
        for(int time=101;time<1000;time+=25){scene.advance(time,List.of(owner));for(var actor:scene.actors())
            if(actor.config().name().equals("Bullet/Sword/Parts/Burst Trail")&&ids.add(actor.id())){
                trails++;check(actor.clientOid()==0&&actor.projectile().faction()==10,"server-created trails preserve faction without client prediction ownership");
            }}
        check(trails==5,"Combuster's authored charge emits exactly five burst bombs");
        check(hits.size()>=5,"burst bombs execute their damage actions");
        scene.cancel(10);hits.clear();
        scene.attack(10,new KnightCombat.AttackStart("Weapon/Handgun/Tortafist/Grand Tortofist",290,1100,0,2000));
        var later=new ActorState(10,1001,"Fixture",257,100,0,0,0,0,0,290,1100,model);
        boolean missile=false;
        for(int time=1101;time<4100;time+=25){scene.advance(time,List.of(later));missile|=scene.actors().stream().anyMatch(a->a.config().name().endsWith("Tortodrone Missile"));}
        check(missile&&!hits.isEmpty(),"Tortofist carrier expiry emits a missile that detonates with damage");
        scene.cancel(10);
        scene.attack(10,new KnightCombat.AttackStart("Weapon/Handgun/Pulsar",292,4200,0,2000));
        var gunner=new ActorState(10,1001,"Fixture",257,100,0,0,0,0,0,292,4200,model);
        for(int time=4201;time<5400;time+=25)scene.advance(time,List.of(gunner));
        check(scene.actors().stream().filter(a->a.config().name().equals("Bullet/Handgun/Pulsar/Parts/Base Stage2")).count()==1,
            "Pulsar matures into one explosive second stage");
        scene.cancel(10);scene.advance(6000,List.of());check(scene.actors().isEmpty(),"travel cancels pending lifecycle actions and child actors");
        var alchemer=catalog.find("Weapon/Handgun/Voltech Alchemer Mk II",292).fireRefs().getFirst().action().actor();
        var split=alchemer.detonation().children().stream().filter(a->a.kind()==FireCatalog.Kind.EMIT_PROJECTILE).findFirst().orElseThrow();
        check(split.relative()&&split.rotationVariance()>0,"secondary Alchemer bounce uses a fan relative to its parent");
    }
    private static void fireTracksAttackAnimationSpeed(CollisionMap terrain) {
        var model=new StarterCatalog.Model("Fixture",Map.of());
        var bullet=new FireCatalog.ActorSpec("BULLET",model,1000,0,.1f,0,0,0,1,null);
        var action=new FireCatalog.Action(FireCatalog.Kind.EMIT_PROJECTILE,null,bullet,"Source","","",0,0,0,0,true,0,0,0,0,false,List.of(),"");
        var fire=new FireCatalog.AttackFire("Gun",292,model,2,List.of(new FireCatalog.FireRef(model,135,90,true,0,0,0,action,null)));
        var weapons=new SceneWeapons(terrain);weapons.catalog(new FireCatalog(List.of(fire),Map.of()));
        var hit=new WeaponCatalog.Attack(135,270,900,true,-1,-1,0,0);
        var gun=new WeaponCatalog.Weapon("Gun",1200,0,0,false,true,1,1,List.of(hit),null,null,null);
        var byClass=new EnumMap<EquipmentCatalog.WeaponClass,EquipmentCatalog.Modifiers>(EquipmentCatalog.WeaponClass.class);
        for(var type:EquipmentCatalog.WeaponClass.values())byClass.put(type,new EquipmentCatalog.Modifiers(0,0,.125f,0));
        var gear=new EquipmentCatalog(List.of(new EquipmentCatalog.Entry("Gun",org.tortodrone.character.WorldProfile.Slot.GUN,byClass)));
        var combat=new KnightCombat(new WeaponCatalog(List.of(gun),List.of(),2000),gear);
        combat.equipment(new org.tortodrone.character.WorldProfile(10,1,1,0,0,1,List.of(
            new org.tortodrone.character.WorldProfile.Item(1,org.tortodrone.character.WorldProfile.Slot.GUN,"Gun",null,0,6,true))));
        combat.attacks(start->weapons.attack(10,start));combat.reset(100);combat.input(4,100,100,0,0,true);
        var owner=new ActorState(10,1001,"Fixture",257,100,0,0,0,0,0,292,100,model);
        // Native fire animation speed is fireSpeed * attackSpeed: land=120,
        // delay=60 and refractory=40 after the 1.125 equipment multiplier.
        weapons.advance(280,List.of(owner));check(weapons.actors().isEmpty(),"scaled native fire deadline remains strict");
        weapons.advance(281,List.of(owner));check(weapons.actors().size()==1,"fire follows the equipment-scaled animation delay");
        weapons.advance(320,List.of(owner));check(weapons.actors().size()==1,"scaled native refractory deadline remains strict");
        weapons.advance(321,List.of(owner));check(weapons.actors().size()==2,"repeat fire follows the equipment-scaled animation rate");
    }
    private static void blockedCellCannotBeSkipped()throws Exception {
        Path file=Files.createTempFile("tortodrone-projectile-collision-",".gz");
        try {
            // A one-cell wall lies between two clear endpoints within one authored step.
            try(var out=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))){
                out.writeInt(0x54445731);out.writeInt(5);out.writeInt(1);
                out.writeFloat(0);out.writeFloat(0);out.writeFloat(.1f);out.writeByte(0b11011);
            }
            var terrain=CollisionMap.read(file);
            var model=new StarterCatalog.Model("Fixture",Map.of());
            var bullet=new FireCatalog.ActorSpec("BULLET",model,1000,8,1,0,0,0,1,null);
            var weapons=new SceneWeapons(terrain);weapons.catalog(new FireCatalog(List.of(attack("Fast",bullet)),Map.of()));
            var owner=new ActorState(10,1001,"Fixture",257,100,0,0,0,0,0,292,100,model);
            weapons.attack(10,new KnightCombat.AttackStart("Fast",292,100,0,500));
            weapons.advance(101,List.of(owner));weapons.advance(150,List.of(owner));
            var shot=weapons.actors().getFirst();
            check(shot.projectile().destroyed()>101&&shot.projectile().destroyed()<=150,"bullet cannot skip a blocked collision cell with a large maxStep");
            check(!shot.projectile().fizzle()&&shot.x()<.15f,"static impact stays on the near side and retains native impact state");
            weapons.cancel(10);
            var orbit=new FireCatalog.OrbitSpec(0,0,0,8,500,1000,"SOURCE",false,null,null,null);
            var disk=new FireCatalog.ActorSpec("ORBITAL_BULLET",model,1000,0,0,0,0,0,1,null,orbit);
            var passing=new FireCatalog.ActorSpec("ORBITAL_BULLET",model,1000,0,0,2,0,0,1,null,orbit);
            weapons.catalog(new FireCatalog(List.of(attack("Disk",disk),attack("Passing",passing)),Map.of()));
            weapons.attack(10,new KnightCombat.AttackStart("Disk",292,200,0,500));
            weapons.attack(10,new KnightCombat.AttackStart("Passing",292,200,0,500));
            var orbitOwner=new ActorState(10,1001,"Fixture",257,200,0,0,0,0,0,292,200,model);
            weapons.advance(201,List.of(orbitOwner));weapons.advance(250,List.of(orbitOwner));
            var collided=weapons.actors().stream().filter(a->(a.flags()&2)==0).findFirst().orElseThrow();
            var clear=weapons.actors().stream().filter(a->(a.flags()&2)!=0).findFirst().orElseThrow();
            check(collided.projectile().destroyed()<=250&&collided.projectile().orbit().collided()>0,
                "orbital terrain collision publishes native collision and destruction stamps");
            check(clear.projectile().destroyed()==Integer.MAX_VALUE&&clear.x()>.3f,
                "authored orbital wall-passing flag still bypasses static terrain");
        } finally {Files.delete(file);}
    }
    private static FireCatalog.AttackFire attack(String weapon,FireCatalog.ActorSpec actor){
        var model=new StarterCatalog.Model("Fixture",Map.of());
        var action=new FireCatalog.Action(FireCatalog.Kind.EMIT_PROJECTILE,null,actor,"Source","","",0,0,0,0,true,0,0,0,0,false,List.of(),"");
        return new FireCatalog.AttackFire(weapon,292,model,1,List.of(new FireCatalog.FireRef(model,0,0,true,0,0,0,action,null)));
    }
    private static void check(boolean condition,String label){if(!condition)throw new AssertionError(label);}
}
