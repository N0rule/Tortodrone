package org.tortodrone.world;

import java.nio.file.*;
import java.util.*;
import org.tortodrone.resources.ContentManager;
import org.tortodrone.character.StarterCatalog;
import static org.tortodrone.support.TestWire.check;

/** Original Sealed Sword table/predicate and generic weighted action execution. */
public final class SpawnTableActionsTest {
    public static void main(String[] args)throws Exception {
        var content=ContentManager.load(Path.of("."));var attack=content.fires().find("Weapon/Sword/Sealed Sword",290);
        check(attack!=null,"native Sealed Sword charge exists");var tables=new ArrayList<FireCatalog.Action>();attack.fireRefs().forEach(ref->collect(ref.action(),tables));
        var weighted=tables.stream().filter(a->a.kind()==FireCatalog.Kind.RANDOM_WEIGHTED).findFirst().orElseThrow();
        check(weighted.children().size()==5,"original table has five status choices");
        var condition=tables.stream().filter(a->a.kind()==FireCatalog.Kind.CONDITIONAL).findFirst().orElseThrow();
        var data=condition.config().arguments().get("condition");
        check(((FireCatalog.DataValue)data).type().endsWith("$Random")&&SceneDamage.number(data,"probability",0)==.85f,"source-backed original 85% predicate restored from archive attack.xml");
        var statuses=new HashSet<String>();
        for(var action:weighted.children()){
            check(action.kind()==FireCatalog.Kind.EMIT_PROJECTILE&&action.actor().nativeType().equals("BOMB"),"each table choice emits its original bomb actor");
            var damage=action.actor().config().arguments().get("Damage");var status=SceneDamage.field(damage,"statusCondition");
            check(status instanceof StarterCatalog.Model,"native status bound into the emitted actor");statuses.add(((StarterCatalog.Model)status).name());
        }
        check(statuses.equals(Set.of("Fire 2","Freeze 2","Shock 2","Stun 2","Poison 2")),"all five original statuses retained");
        var weapons=new SceneWeapons(SceneWeaponsTest.openTerrain());
        var a=new FireCatalog.Action(FireCatalog.Kind.FIRE_EFFECT,new StarterCatalog.Model("Forbidden",Map.of()),null,"Source","","",0,0,0,0,false,0,0,0,0,false,List.of(),"");
        var b=new FireCatalog.Action(FireCatalog.Kind.FIRE_EFFECT,new StarterCatalog.Model("Selected",Map.of()),null,"Source","","",0,0,0,0,false,0,0,0,0,false,List.of(),"");
        var choice=new FireCatalog.Action(FireCatalog.Kind.RANDOM_WEIGHTED,new StarterCatalog.Model("Weights",Map.of("weights",new FireCatalog.ListValue("float",List.of(0f,1f)))),null,"Source","","",0,0,0,0,false,0,0,0,0,false,List.of(a,b),"");
        var owner=new ActorState(219,1001,"Owner",0,100,0,0,0,0,0,new StarterCatalog.Model("Player",Map.of()));
        check(weapons.fire(choice,owner,100)&&weapons.effects().size()==1&&weapons.effects().getFirst().config().name().equals("Selected"),"zero-weight entries cannot execute and exactly one weighted child fires");
        System.out.println("PASS: original five-way Sealed Sword statuses/predicate and weighted action selection");
    }
    private static void collect(FireCatalog.Action action,List<FireCatalog.Action> result){if(action==null)return;result.add(action);action.children().forEach(a->collect(a,result));}
}
