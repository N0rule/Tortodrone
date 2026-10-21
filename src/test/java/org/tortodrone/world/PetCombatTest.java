package org.tortodrone.world;

import java.nio.file.Path;
import java.util.*;
import org.tortodrone.character.*;
import org.tortodrone.town.*;
import static org.tortodrone.support.TestWire.check;

/** One isolated scene: owned companion, native skill intent, cooldown and departure cleanup. */
public final class PetCombatTest {
    public static void main(String[] args)throws Exception{
        var catalog=SceneCatalog.load(org.tortodrone.support.ContentFixture.path("maps"));var scenes=new SceneRegistry(catalog);
        scenes.town(TownCatalog.load(org.tortodrone.support.ContentFixture.path("town/catalog.json")));
        scenes.petFires(FireCatalog.load(org.tortodrone.support.ContentFixture.path("town/pet-fires.json")));
        var map=catalog.scenes().stream().filter(m->!m.town()).findFirst().orElseThrow();var instance=scenes.destination(map.id(),10);
        int stamp=SceneClock.now();var actor=new ActorState(10,1001,"Fixture",0,stamp,map.spawnX(),map.spawnY(),0,0,0,new StarterCatalog.Model("Character/PC/Level",Map.of()));
        var sprite=new TownState.Sprite(7,"Drakon","Sparky",0,0,null,0,0,Set.of(),null,Collections.nCopies(9,0),Collections.nCopies(3,0),0);
        var profile=new WorldProfile(10,1,1,0,0,1,List.of(),KnightProgress.RECRUIT,new TownState(List.of(),List.of(sprite),7,List.of(),List.of()));
        var member=instance.simulation().join(actor,snapshot->{});
        try{
            member.combat(WeaponCatalog.load(org.tortodrone.support.ContentFixture.path("combat/weapons.tsv")),profile);member.equipment(profile);member.enter();
            var initial=instance.simulation().snapshot();var companion=initial.actors().stream().filter(a->a.pet()!=null).findFirst().orElseThrow();
            check(!companion.player()&&companion.pet().knight()==10&&initial.players().size()==1,"pet is owned by its knight and never becomes a player occupant");
            stamp=SceneClock.now();check(member.input(stamp,0,0,128,stamp,stamp,System.nanoTime()/1_000_000),"native skill intent accepted");
            boolean fired=false,command=false,knightCommand=false;long deadline=System.nanoTime()+2_000_000_000L;
            while(System.nanoTime()<deadline&&!fired){Thread.sleep(25);var snapshot=instance.simulation().snapshot();
                command|=snapshot.actors().stream().anyMatch(a->a.pet()!=null&&a.activity()==1&&a.pet().nextSkill().getFirst()>snapshot.timestamp());
                knightCommand|=snapshot.players().stream().anyMatch(a->a.id()==10&&a.activity()==288&&a.sceneKnight().spriteAttack()==0);
                fired=snapshot.actors().stream().anyMatch(a->a.projectile()!=null&&a.config().name().equals("Bullet/Pet/Drakon/Firebolt")&&a.projectile().faction()==10);
            }
            check(instance.simulation().snapshot().actors().stream().filter(a->a.pet()!=null).allMatch(a->a.pet().nextSkill().stream().allMatch(t->t>SceneClock.now())),"using one skill places all three on their native recharge");
            check(command&&fired&&knightCommand,"native knight command produces an owned firebolt and publishes its cooldown");
            var cooldown=instance.simulation().snapshot().actors().stream().filter(a->a.pet()!=null).findFirst().orElseThrow().pet().nextSkill();
            check(new HashSet<>(cooldown).size()==1,"all three slots receive the used skill's cooldown, rather than each skill's own recharge");
            stamp=SceneClock.now();member.input(stamp,0,0,256,stamp,stamp,System.nanoTime()/1_000_000);Thread.sleep(100);
            check(instance.simulation().snapshot().actors().stream().filter(a->a.pet()!=null).allMatch(a->a.pet().nextSkill().get(1)>SceneClock.now()),"locked skill cannot start or consume a cooldown");
        }finally{member.close();}
        check(instance.simulation().snapshot().actors().stream().noneMatch(a->a.pet()!=null||a.projectile()!=null&&a.projectile().faction()==10),"departure removes companion and its projectiles");scenes.release(instance,10);
        System.out.println("PASS: dungeon companion, skill command, cooldown, locked skill and owned cleanup");
    }
}
