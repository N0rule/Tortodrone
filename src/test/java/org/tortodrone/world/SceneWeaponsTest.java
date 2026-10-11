package org.tortodrone.world;

import java.nio.file.*;
import java.util.*;
import org.tortodrone.character.StarterCatalog;

/** Small scene-clock regression: native emissions, expiry and observer-independent ownership. */
public final class SceneWeaponsTest {
    public static void main(String[] args)throws Exception {
        var map=SceneCatalog.load(Path.of("defaults/maps")).entry();
        var model=new StarterCatalog.Model("Fixture",Map.of());
        var effect=new FireCatalog.Action(FireCatalog.Kind.FIRE_EFFECT,model,null,"Source","","",0,0,0,0,true,0,0,0,0,false,List.of(),"");
        var bullet=new FireCatalog.ActorSpec("BULLET",model,200,2,.1f,0,0,0,1,null);
        var bombSpawn=new FireCatalog.Action(FireCatalog.Kind.EMIT_PROJECTILE,null,bullet,"Source","","",0,0,0,0,true,0,0,0,0,false,List.of(),"");
        var bombDetonation=new FireCatalog.Action(FireCatalog.Kind.COMPOUND,null,null,"","","",0,0,0,0,false,0,0,0,0,false,List.of(effect,bombSpawn),"");
        var bomb=new FireCatalog.ActorSpec("BOMB",model,200,0,0,0,0,0,0,bombDetonation);
        var gun=attack("Gun",bullet);var grenade=attack("Bomb",bomb);
        var weapons=new SceneWeapons(map.collision());weapons.catalog(new FireCatalog(List.of(gun,grenade),Map.of()));
        var owner=new ActorState(10,1001,"Fixture",257,100,map.spawnX(),map.spawnY(),0,0,0,292,100,model);
        weapons.attack(10,new KnightCombat.AttackStart("Gun",292,100,50,500));
        weapons.advance(150,List.of(owner));check(weapons.actors().isEmpty(),"stock fire deadline is strict");
        weapons.advance(151,List.of(owner));check(weapons.actors().size()==1,"one native gun bullet at land deadline");
        var first=weapons.actors().getFirst();check(first.projectile().faction()==10&&first.clientOid()==1001,"faction and controller derive from owned knight");
        weapons.advance(251,List.of(owner));check(weapons.actors().getFirst().x()>first.x(),"projectile advances once on scene clock");
        weapons.advance(351,List.of(owner));check(weapons.actors().getFirst().projectile().fizzle(),"fuse expiry marks bullet fizzle");
        check(weapons.effects().isEmpty(),"expired bullet cannot invent hit rewards or effects");
        weapons.advance(500,List.of(owner));check(weapons.actors().isEmpty(),"destroyed projectile is removed after publication");
        weapons.attack(10,new KnightCombat.AttackStart("Bomb",292,500,0,500));
        var later=new ActorState(10,1001,"Fixture",257,100,map.spawnX(),map.spawnY(),0,0,0,292,500,model);
        weapons.advance(501,List.of(later));check(weapons.actors().size()==1,"bomb drop is a native bomb actor");
        weapons.advance(701,List.of(later));check(weapons.effects().size()==1&&weapons.effects().getFirst().ownerOid()==1001,"bomb detonation emits authored native effect");
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
        System.out.println("PASS: gun bullet emission/movement/fizzle, bomb fuse/effect and owned cleanup");
    }
    private static FireCatalog.AttackFire attack(String weapon,FireCatalog.ActorSpec actor){
        var model=new StarterCatalog.Model("Fixture",Map.of());
        var action=new FireCatalog.Action(FireCatalog.Kind.EMIT_PROJECTILE,null,actor,"Source","","",0,0,0,0,true,0,0,0,0,false,List.of(),"");
        return new FireCatalog.AttackFire(weapon,292,model,1,List.of(new FireCatalog.FireRef(model,0,0,true,0,0,0,action,null)));
    }
    private static void check(boolean condition,String label){if(!condition)throw new AssertionError(label);}
}
