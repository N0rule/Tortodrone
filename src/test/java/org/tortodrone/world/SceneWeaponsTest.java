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
        var bomb=new FireCatalog.ActorSpec("BOMB",model,200,0,0,0,0,0,0,effect);
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
        weapons.cancel(10);check(weapons.actors().isEmpty(),"detach clears only owner's ephemeral actors");
        System.out.println("PASS: gun bullet emission/movement/fizzle, bomb fuse/effect and owned cleanup");
    }
    private static FireCatalog.AttackFire attack(String weapon,FireCatalog.ActorSpec actor){
        var model=new StarterCatalog.Model("Fixture",Map.of());
        var action=new FireCatalog.Action(FireCatalog.Kind.EMIT_PROJECTILE,null,actor,"Source","","",0,0,0,0,true,0,0,0,0,false,List.of(),"");
        return new FireCatalog.AttackFire(weapon,292,model,1,List.of(new FireCatalog.FireRef(model,0,0,true,0,0,0,action,null)));
    }
    private static void check(boolean condition,String label){if(!condition)throw new AssertionError(label);}
}
