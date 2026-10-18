package org.tortodrone.world;

import java.nio.file.Path;
import java.util.*;
import org.tortodrone.character.StarterCatalog;
import static org.tortodrone.support.TestWire.check;

/** Exercise every retained shard graph against the authoritative projectile clock. */
public final class FragmentationTest {
    public static void main(String[] args)throws Exception {
        var catalog=FireCatalog.load(org.tortodrone.support.ContentFixture.path("combat/fires.json"));int checked=0;
        for(var attack:catalog.entries()){
            if(attack.activity()!=290||attack.fireRefs().size()!=1)continue;
            var action=attack.fireRefs().getFirst().action();
            if(action.actor()==null||!action.actor().config().name().startsWith("Bomb/Parts/Shard/Bomb (Timer)"))continue;
            var parent=action.actor();var timer=parent.timers().getFirst();
            check(timer.limit()==1&&timer.endAction()!=null,"one detonation per bomb: "+attack.weaponConfig());
            // The three retained native graphs contain six, eight and one spawn actions.
            int expected=parent.config().name().endsWith("(Sloppy)")?1:parent.config().name().endsWith("(T3)")?8:6;
            long branches=timer.action().children().stream().filter(a->a.kind()==FireCatalog.Kind.DELAYED&&a.children().getFirst().kind()==FireCatalog.Kind.EMIT_PROJECTILE).count();
            check(branches==expected,"retained native branch count: "+attack.weaponConfig());
            var scene=new SceneWeapons(SceneWeaponsTest.openTerrain());scene.catalog(catalog);
            var owner=new ActorState(71,1001,"Fixture",0,100,0,0,0,0,0,290,100,new StarterCatalog.Model("Fixture",Map.of()));
            var hits=new ArrayList<Integer>();
            scene.targets(new SceneWeapons.Targets(){public boolean contact(float x,float y,int owner){return false;}
                public void damage(StarterCatalog.Model region,float x,float y,float rotation,int stamp,int source){check(source==71,"damage retains knight ownership");hits.add(stamp);}});
            scene.attack(71,new KnightCombat.AttackStart(attack.weaponConfig(),290,100,0,5000));
            scene.advance(101,List.of(owner));int burst=100+timer.delay();scene.advance(burst+1,List.of(owner));
            var bullets=scene.actors().stream().filter(a->a.projectile().nativeType().equals("BULLET")).toList();
            check(bullets.size()==expected&&bullets.stream().map(ActorState::id).distinct().count()==expected,"independent children: "+attack.weaponConfig());
            check(bullets.stream().allMatch(a->a.projectile().faction()==71&&a.projectile().ownerOid()==0),"server-owned children retain the knight faction");
            check(bullets.stream().allMatch(a->Objects.equals(a.config().arguments().get("Damage"),parent.config().arguments().get("Shard Damage"))),"every branch inherits shard damage, including the broken native parameter path");
            scene.advance(burst+200,List.of(owner));
            var seeds=scene.actors().stream().filter(a->a.config().name().equals("Bomb/Parts/Shard/Shard Base (Timer)")).toList();
            check(seeds.size()==expected&&seeds.stream().allMatch(a->Math.hypot(a.x(),a.y())>0),"children travel and plant: "+attack.weaponConfig());
            check(hits.size()==1,"secondary damage waits for visible lifetime");
            scene.advance(burst+4000,List.of(owner));scene.advance(burst+14000,List.of(owner));
            check(hits.size()==expected+1&&scene.actors().isEmpty(),"single secondary hits and finite cleanup: "+attack.weaponConfig());
            checked++;
        }
        check(checked==26,"all affected catalogue entries exercised");
        System.out.println("PASS: "+checked+" fragmentation graphs restore children, movement, ownership, one-shot damage and cleanup; missing retail angles remain unverified");
    }
}
