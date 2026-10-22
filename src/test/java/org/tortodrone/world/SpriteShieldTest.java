package org.tortodrone.world;

import java.nio.file.*;
import java.util.*;
import org.tortodrone.character.*;
import org.tortodrone.town.*;
import org.tortodrone.resources.*;
import static org.tortodrone.support.TestWire.check;

/** Real Aura actions in one isolated shared scene, with direct authoritative damage. */
public final class SpriteShieldTest {
    public static void main(String[] args)throws Exception {
        var content=ContentManager.load(Path.of("."));var map=content.scenes().scene(294);
        var sceneContent=new SceneContent(List.of(),map.content().scales(),Map.of(),map.content().stats(),map.content().items(),Map.of(),map.content().statuses());
        var isolated=new SceneCatalog.MapScene(map.id(),map.zone(),map.zoneName(),map.name(),map.image(),"training",map.version(),map.spawnX(),map.spawnY(),0,1,map.collision(),map.layout(),ScenePortals.EMPTY,sceneContent);
        var simulation=new SceneRegistry.Instance(990,1,isolated).simulation();simulation.town(content.town());simulation.petFires(content.petFires());
        int stamp=SceneClock.now();var model=new StarterCatalog.Model("Character/PC/Default",Map.of());
        var member=simulation.join(new ActorState(219,1001,"Aura",0,stamp,map.spawnX(),map.spawnY(),0,0,0,0,stamp,model),snapshot->{});
        var peer=simulation.join(new ActorState(220,1002,"Peer",0,stamp,map.spawnX()+.2f,map.spawnY(),0,0,0,0,stamp,model),snapshot->{});
        var sprite=new TownState.Sprite(7,"Seraphynx","Aura",99,0,null,0,0,Set.of(),null,Collections.nCopies(9,0),List.of(0,0,2),0);
        var profile=new WorldProfile(219,1,1,0,0,1,List.of(),KnightProgress.RECRUIT,new TownState(List.of(),List.of(sprite),7,List.of(),List.of()));
        try{
            member.combat(content.weapons(),profile);member.equipment(profile);peer.combat(content.weapons(),new WorldProfile(220,2,1,0,0,1,List.of()));member.enter();peer.enter();
            var healthField=SceneSimulation.Membership.class.getDeclaredField("health");healthField.setAccessible(true);
            var health=(SceneHealth)healthField.get(member);var otherHealth=(SceneHealth)healthField.get(peer);
            var immunity=SceneHealth.class.getDeclaredField("invulnerableUntil");immunity.setAccessible(true);immunity.setInt(health,0);immunity.setInt(otherHealth,0);
            stamp=SceneClock.now();check(member.input(stamp,0,0,384,stamp,stamp,System.nanoTime()/1_000_000),"native Aura input accepted");
            long deadline=System.nanoTime()+2_000_000_000L;ActorState pet=null;
            while(System.nanoTime()<deadline){Thread.sleep(25);pet=simulation.snapshot().actors().stream().filter(a->a.pet()!=null).findFirst().orElseThrow();if(pet.pet().shieldHealth()>0)break;}
            check(pet!=null&&pet.pet().shieldHealth()>0,"Aura raises the native pet shield after land");
            int before=pet.pet().shieldHealth(),hp=health.state().current();
            var normal=new FireCatalog.DataValue("Damage$Normal",Map.of("flags",1,"amount",50f));
            synchronized(simulation){
                int expected=(int)Math.ceil(SceneDamage.amount(normal,sceneContent,new SceneContent.Spec(Map.of("defense.normal",Float.toString(sceneContent.scales().get("PC/Defense/Shield/Shield Base"))))));
                check(health.hit(normal,SceneClock.now()).amount()==0&&health.state().current()==hp,"Aura holds a blockable hit before knight mitigation");
                var changed=simulation.snapshot().actors().stream().filter(a->a.pet()!=null).findFirst().orElseThrow();
                check(changed.pet().shieldHealth()==before-expected,"pet shield applies its original depth-scaled defense to its charge");
                var unblockable=new FireCatalog.DataValue("Damage$Unblockable",Map.of("flags",1,"amount",10f));
                check(health.hit(unblockable,SceneClock.now()).amount()==10,"unblockable damage reaches the knight through Aura");
                check(simulation.snapshot().actors().stream().filter(a->a.pet()!=null).findFirst().orElseThrow().pet().shieldHealth()==before-expected,"unblockable damage never spends Sprite shield HP");
                member.equipment(new WorldProfile(219,1,1,0,0,2,List.of()));
                check(simulation.snapshot().actors().stream().noneMatch(a->a.pet()!=null||a.projectile()!=null),"unequipping removes the pet and its active projectiles");
                check(health.hit(normal,SceneClock.now()).amount()>0,"unequipping cannot leave an invisible active shield");
            }
            System.out.println("PASS: native Aura timing, shield defense/absorption, unblockable bypass and unequip cleanup");
        }finally{member.close();peer.close();}
    }
}
