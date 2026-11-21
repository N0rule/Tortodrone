package org.tortodrone.world;

import java.util.*;
import org.tortodrone.core.character.StarterCatalog;
import static org.tortodrone.support.TestWire.check;
import org.tortodrone.core.world.FireCatalog;

/** Custom forward-only Diskgun saws: one disk per shot, normal pierces one enemy,
 *  charged pierces every unique enemy until its fuse. */
public final class DiskgunPierceTest {
    public static void main(String[] args)throws Exception {
        var terrain=SceneWeaponsTest.openTerrain();
        var model=new StarterCatalog.Model("Fixture",Map.of());
        var damage=new FireCatalog.Action(FireCatalog.Kind.DAMAGE_REGION,
            new StarterCatalog.Model("DamageRegionFromPoint",Map.of()),null,"Source","","",
            0,0,0,0,true,0,0,0,0,false,List.of(),"");
        var orbit=new FireCatalog.OrbitSpec(0,0,0,8,100,500,"NONE",false,null,null,null);
        var disk=new FireCatalog.ActorSpec("ORBITAL_BULLET",model,500,8,.05f,0,0,0,0,damage,orbit);
        var emit=new FireCatalog.Action(FireCatalog.Kind.EMIT_PROJECTILE,null,disk,"Source","","",
            0,0,0,0,true,0,0,0,0,false,List.of(),"");
        var normalMulti=new FireCatalog.Action(FireCatalog.Kind.MULTI_PRESPAWNED,null,null,"","","",
            0,0,0,0,false,0,0,0,0,false,List.of(emit,emit),"");
        var chargedMulti=new FireCatalog.Action(FireCatalog.Kind.MULTI_PRESPAWNED,null,null,"","","",
            0,0,0,0,false,0,0,0,0,false,List.of(emit,emit,emit,emit),"");
        var normalRef=new FireCatalog.FireRef(model,0,0,true,0,0,0,normalMulti,null);
        var chargedRef=new FireCatalog.FireRef(model,0,0,true,0,0,0,chargedMulti,null);
        var catalog=new FireCatalog(List.of(
            new FireCatalog.AttackFire("Weapon/Handgun/Celestial Orbitgun",292,model,1,List.of(normalRef)),
            new FireCatalog.AttackFire("Weapon/Handgun/Celestial Orbitgun",290,model,1,List.of(chargedRef)),
            new FireCatalog.AttackFire("Weapon/Handgun/Overcharged Mixmaster",292,model,1,List.of(normalRef)),
            new FireCatalog.AttackFire("Weapon/Handgun/Overcharged Mixmaster",290,model,1,List.of(chargedRef))),Map.of());
        var hits=new HashSet<Integer>();
        var targets=new SceneWeapons.Targets(){
            public boolean contact(float x,float y,int owner){return x>=1&&x<4;}
            public int contactActor(float x,float y,float rotation,int owner,Object shape,int mask){
                if(x>=1&&x<2)return 1;if(x>=2&&x<3)return 2;if(x>=3&&x<4)return 3;return 0;
            }
            public void damage(StarterCatalog.Model region,float x,float y,float rotation,int stamp,int owner){
                if(x>=1&&x<2)hits.add(1);else if(x>=2&&x<3)hits.add(2);else if(x>=3&&x<4)hits.add(3);
            }
        };
        var owner=new ActorState(10,1001,"Fixture",0,100,0,0,0,0,0,292,100,model);
        var weapons=new SceneWeapons(terrain);weapons.catalog(catalog);weapons.targets(targets);
        weapons.attack(10,new KnightCombat.AttackStart("Weapon/Handgun/Fiery Diskgun",292,100,0,1000));
        weapons.advance(101,List.of(owner));
        check(weapons.actors().size()==1,"normal Diskgun spawns exactly one saw");
        for(int stamp=110;stamp<=800;stamp+=10)weapons.advance(stamp,List.of(owner));
        check(hits.equals(Set.of(1,2)),"normal saw pierces one enemy and dies on the second");
        hits.clear();
        weapons.attack(10,new KnightCombat.AttackStart("Weapon/Handgun/Static Diskgun",290,900,0,1000));
        weapons.advance(901,List.of(owner));
        check(weapons.actors().size()==1,"charged Diskgun spawns exactly one saw");
        for(int stamp=910;stamp<=1600;stamp+=10)weapons.advance(stamp,List.of(owner));
        check(hits.equals(Set.of(1,2,3)),"charged saw pierces every unique enemy");
        System.out.println("PASS: normal saw pierces one then dies; charged saw pierces all");
    }
}
