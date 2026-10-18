package org.tortodrone.world;

import java.nio.file.Path;
import java.util.*;
import org.tortodrone.character.*;
import static org.tortodrone.support.TestWire.check;

/** Basic scene-only checks against the shipped native-derived shield graphs. */
public final class ShieldActionsTest {
    public static void main(String[] args)throws Exception{
        var map=SceneCatalog.load(org.tortodrone.support.ContentFixture.path("maps")).scene(294);
        var fires=FireCatalog.load(org.tortodrone.support.ContentFixture.path("combat/fires.json"));
        var weapons=WeaponCatalog.load(org.tortodrone.support.ContentFixture.path("combat/weapons.tsv"));
        var shield=weapons.shields().stream().filter(s->s.health()>0&&s.bash()!=null&&!s.config().contains("(Dev)")&&fires.find(s.config(),284)!=null&&
            ((Number)fires.find(s.config(),284).attack().arguments().get("rarity")).intValue()>=2).findFirst().orElseThrow();
        var spec=new SceneContent.Spec(Map.of("native-type","com.threerings.projectx.dungeon.data.actor.Monster","config","Fixture","kind","Monster","health","10000","damage-mask","2","radius",".2","collision-flags","8"));
        var content=new SceneContent(List.of(new SceneContent.Entry("cube",6.3f,-12.5f,0,spec)),map.content().scales());
        var world=new SceneEntities(content,map.collision());var cube=world.actors().getFirst();
        var damage=new ArrayList<SceneCombatEvent>();world.feedback(damage::add);
        var sceneWeapons=new SceneWeapons(map.collision());sceneWeapons.catalog(fires);sceneWeapons.targets(world);
        var state=new KnightCombat.State(null,shield.config(),null,shield.health(),0,0,0,null);
        var model=new StarterCatalog.Model("Character/PC/Level",Map.of());
        var player=new ActorState(219,1001,"Fixture",256,1000,5.5f,-12.5f,0,0,0,287,1000,model,false,state);
        world.advance(1000,List.of(player));sceneWeapons.attack(219,new KnightCombat.AttackStart(shield.config(),287,1000,500,1600));
        sceneWeapons.advance(1549,List.of(player));check(damage.isEmpty(),"bash sweep is not armed before its configured delay");
        sceneWeapons.advance(1551,List.of(player));check(damage.size()==1&&damage.getFirst().amount()>0,"bash inflicts actual damage through its contact handler");
        sceneWeapons.advance(1601,List.of(player));sceneWeapons.advance(1651,List.of(player));check(damage.size()==1,"one bash cannot damage one target repeatedly");
        world.advance(1700,List.of(player));check(world.actor(cube.id()).x()>cube.x(),"bash applies native radial knockback");
        var bumpWorld=new SceneEntities(content,map.collision());sceneWeapons=new SceneWeapons(map.collision());sceneWeapons.catalog(fires);sceneWeapons.targets(bumpWorld);
        var defense=new ActorState(219,1001,"Fixture",256,1000,5.5f,-12.5f,0,0,0,284,2000,model,false,state);
        bumpWorld.advance(2000,List.of(defense));sceneWeapons.attack(219,new KnightCombat.AttackStart(shield.config(),284,2000,0,600000));
        sceneWeapons.advance(2001,List.of(defense));bumpWorld.advance(2100,List.of(defense));
        check(bumpWorld.actor(cube.id()).x()>cube.x(),"raising an eligible shield executes its guarded push-back action");
        int effects=sceneWeapons.effects().size();check(effects>0,"successful shield bump emits its authored effect");
        var effect=sceneWeapons.effects().getLast();
        check(effect.ownerOid()==0&&effect.targetActor()==defense.id(),"shield bump reaches the controlling client with its native source actor target");
        check(effect.config().arguments().get("Model") instanceof StarterCatalog.Model m&&m.name().equals("particle/shield/fx_shieldpush.dat"),"shield bump uses the stock particle effect");
        bumpWorld=new SceneEntities(content,map.collision());sceneWeapons.targets(bumpWorld);bumpWorld.advance(2200,List.of(defense));
        sceneWeapons.attack(219,new KnightCombat.AttackStart(shield.config(),284,2200,0,600000));
        var repeated=new ActorState(219,1001,"Fixture",256,1000,5.5f,-12.5f,0,0,0,284,2200,model,false,state);
        sceneWeapons.advance(2201,List.of(repeated));check(sceneWeapons.effects().size()==effects,"rapid shield toggling cannot bypass the native bump cooldown");
        var cooled=new ActorState(219,1001,"Fixture",256,1000,5.5f,-12.5f,0,0,0,284,3600,model,false,state);
        sceneWeapons.attack(219,new KnightCombat.AttackStart(shield.config(),284,3600,0,600000));
        sceneWeapons.advance(3601,List.of(cooled));check(sceneWeapons.effects().size()==effects+1,"shield bump becomes available after its cooldown");
        var empty=new SceneWeapons(map.collision());empty.catalog(fires);empty.targets(new SceneEntities(new SceneContent(List.of(),Map.of()),map.collision()));
        empty.attack(219,new KnightCombat.AttackStart(shield.config(),284,2000,0,600000));empty.advance(2001,List.of(defense));
        check(empty.effects().isEmpty(),"shield bump has no effect without a nearby monster");
        var basic=weapons.shields().stream().filter(s->fires.find(s.config(),284)!=null&&((Number)fires.find(s.config(),284).attack().arguments().get("rarity")).intValue()<2).findFirst().orElseThrow();
        var basicState=new KnightCombat.State(null,basic.config(),null,basic.health(),0,0,0,null);
        var basicOwner=new ActorState(219,1001,"Fixture",256,1000,5.5f,-12.5f,0,0,0,284,4000,model,false,basicState);
        empty.targets(new SceneEntities(content,map.collision()));empty.attack(219,new KnightCombat.AttackStart(basic.config(),284,4000,0,600000));empty.advance(4001,List.of(basicOwner));
        check(empty.effects().isEmpty(),"low-rarity shields cannot execute the guarded bump");
        var plain=new SceneContent.Spec(Map.of("defense.normal","40"));
        var hit=SceneLifeTest.damage(20);
        var compound=new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$Compound",List.of(new FireCatalog.FieldValue("damages","array",new FireCatalog.ListValue("damage",List.of(hit,hit)))));
        check(SceneDamage.amount(compound,content,plain)==20,"same-type compound damage is combined before defense mitigation");
        System.out.println("PASS: real shield bash damage, one-hit contact, knockback, guarded shield bump and compound damage mitigation");
    }
}
