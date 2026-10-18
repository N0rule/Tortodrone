package org.tortodrone.world;

import java.nio.file.Path;
import java.util.*;
import org.tortodrone.character.*;
import static org.tortodrone.support.TestWire.check;

/** Focused status and emergency revive checks against the prepared Training Hall rules. */
public final class SceneStatusTest {
    public static void main(String[] args)throws Exception{
        var map=SceneCatalog.load(org.tortodrone.support.ContentFixture.path("maps")).scene(294);var content=map.content();
        thrownVials(content);
        var owlite=new WorldProfile.Item(99,WorldProfile.Slot.SHIELD,"Gear/Shield/Grey Owlite Shield",null,0,2,false,false,false);
        for(String kind:List.of("Fire","Shock")){
            var knight=new SceneHealth(content);knight.equipment(new WorldProfile(219,1,1,0,0,1,List.of(owlite)));
            knight.hit(status(kind),1000);knight.advance(1001,1,false);
            check(!knight.status().conditions().isEmpty(),"lowered Grey Owlite Shield does not grant passive "+kind+" immunity");
        }
        var reactive=new SceneHealth(content);
        var blow=new FireCatalog.DataValue("Damage$Normal",Map.of("amount",10f,"flags",8,"shouldReact",true));
        check(reactive.hit(blow,1000).react()&&reactive.invulnerable(1999)&&reactive.hit(blow,1500).amount()==0,"authored hurt reaction grants one second of protection");
        check(reactive.hit(blow,2000).amount()>0,"hurt protection expires on its deadline");
        var hurt=new TownMovement(map.collision(),map.spawnX(),map.spawnY());hurt.combat(new KnightCombat(WeaponCatalog.empty()));hurt.resetInput(1000);hurt.hurt(1000);
        hurt.advance(1425,1425);check(hurt.activity()==TownMovement.HURT,"native hurt activity continues beyond the animation-only 425 ms cutoff");
        hurt.advance(1499,1499);check(hurt.activity()==TownMovement.HURT,"native hurt activity lasts 500 ms");
        hurt.advance(1500,1500);check(hurt.activity()==0,"hurt activity finishes at its native deadline without leaving the knight stuck");
        for(String kind:List.of("Fire","Shock")){
            var scaled=content.statuses().get(kind+" (Scaled)");
            var player=new SceneHealth(content);
            player.condition(new StarterCatalog.Model(kind+" (Scaled)",Map.of("Chance",1f,"Power",100)),scaled.model("data").arguments().get("data"),1000);
            player.advance(1001,1,false);
            check(!player.status().conditions().isEmpty()&&player.status().expires().getFirst()>1001,"resolved depth-scaled "+kind+" selects a lasting status");
            if(kind.equals("Shock"))check(player.status().modifiers().shocked(),"resolved shock produces player spasms");
            else check(player.state().current()<player.state().maximum(),"resolved fire burns the player");
        }
        var burn=new SceneHealth(content);burn.hit(status("Fire"),1000);burn.advance(1000,0,false);
        check(burn.hitTime()==1000&&burn.state().current()<burn.state().maximum(),"exclusive burn applies periodic damage");
        var poison=new SceneHealth(content);poison.hit(hit(20),1000);poison.hit(status("Poison"),1001);
        check(poison.status().modifiers().attackDamage()<1&&poison.status().modifiers().defense()<1&&!poison.heal(20),"poison weakens attack/defense and blocks player healing");
        var stun=new SceneHealth(content);stun.hit(status("Stun"),1000);
        check(stun.status().modifiers().speed()<1&&stun.status().modifiers().attackSpeed()<1,"stun slows movement and attacks");
        var frozen=new SceneHealth(content);frozen.hit(status("Freeze"),1000);
        var weapons=WeaponCatalog.load(org.tortodrone.support.ContentFixture.path("combat/weapons.tsv"));var combat=new KnightCombat(weapons);
        var sword=new WorldProfile.Item(10,WorldProfile.Slot.SWORD,"Weapon/Sword/Proto Sword",null,0,6,false,false,false);
        var shield=new WorldProfile.Item(11,WorldProfile.Slot.SHIELD,"Gear/Shield/Proto Shield",null,0,2,false,false,false);
        var profile=new WorldProfile(1,1,1,0,0,1,List.of(sword,shield));combat.equipment(profile);
        var movement=new TownMovement(map.collision(),map.spawnX(),map.spawnY());movement.combat(combat);movement.resetInput(1000);
        combat.impulse(new KnightCombat.Impulse(1000,500,5,0));movement.status(frozen.status().modifiers());
        combat.impulse(new KnightCombat.Impulse(1000,500,5,0));check(combat.state().impulse()==null,"freeze rejects newly applied impulses as well as existing ones");
        movement.accept(1000,1,0,1|64|1024|2048,1000,1000,1000);movement.advance(1200,1200);
        check(movement.x()==map.spawnX()&&movement.y()==map.spawnY()&&combat.activity()!=KnightCombat.DODGE&&combat.activity()!=KnightCombat.BASH,"freeze blocks walking, dodge, shield bash and existing impulses");
        frozen.hit(hit(1),1100);check(!frozen.status().modifiers().canMove(),"freeze cannot shatter in its first 250ms");
        frozen.hit(hit(1),1300);check(frozen.status().modifiers().canMove(),"later damage shatters freeze");
        var shock=new SceneHealth(content);shock.hit(status("Shock"),1000);shock.advance(1000,0,false);
        check(shock.status().modifiers().shocked()&&!shock.status().modifiers().canMove()&&!shock.takePulses().isEmpty(),"shock spasms stop movement and emit authored arc damage");
        shock.advance(3001,2001,false);check(!shock.status().modifiers().shocked(),"shock has a recovery interval between spasms");
        var sleeping=new SceneHealth(content);sleeping.hit(hit(100),1000);sleeping.hit(status("Sleep"),1000);int before=sleeping.state().current();
        Object sleepData=content.statuses().get("Sleep").model("data").arguments().get("data");
        int firstHeal=1000+(int)(SceneDamage.number(sleepData,"healthRegenInterval",0)+SceneDamage.number(sleepData,"healthRegenDelay",0));
        sleeping.advance(firstHeal-1,firstHeal-1001,false);check(sleeping.state().current()==before,"sleep respects the regeneration delay");
        sleeping.advance(firstHeal,1,false);check(sleeping.state().current()>before&&sleeping.status().modifiers().asleep(),"sleep uses player regeneration values");
        var cursed=new SceneHealth(content);cursed.equipment(profile);cursed.hit(status("Curse"),1000);
        check(cursed.status().cursedItems().contains(sword.id()),"curse marks an equipped weapon");before=cursed.state().current();cursed.curseItem(sword.id(),1200);
        check(cursed.state().current()<before,"using a cursed weapon inflicts the authored self damage");
        cursed.advance(22000,20800,false);check(cursed.status().cursedItems().isEmpty(),"curse expiry clears item markings");
        var health=new SceneHealth(content);health.hit(hit(10000),1000);
        check(health.state().reviveFlags()==9&&!health.canSparkRevive(),"first death reserves the free emergency revive and suppresses the Spark panel");
        health.advance(5999,4999,false);check(!health.alive(),"emergency revive waits five seconds");
        health.advance(6000,1,false);check(health.alive()&&health.state().current()==health.state().maximum()&&health.state().reviveFlags()==0&&health.invulnerable(10999),"emergency revive restores full HP, spends the one free charge and grants five seconds immunity");
        health.hit(hit(10000),11000);health.advance(17000,6000,false);
        check(!health.alive()&&health.canSparkRevive()&&health.state().reviveFlags()==0,"second death requires a Spark instead of another free revive");
        check(health.revive(17000)&&!health.revive(17000),"paid revival cannot raise a living player twice");
        System.out.println("PASS: seven status effects, frozen shield/dodge movement, curse use/expiry and one emergency revive");
    }
    private static void thrownVials(SceneContent prepared)throws Exception{
        var terrain=SceneWeaponsTest.openTerrain();
        var shape=new FireCatalog.DataValue("com.threerings.tudey.shape.config.ShapeConfig$Circle",Map.of("radius",.4f));
        var fields=new HashMap<>(Map.of("native-type","Monster","config","Monster/Fixture","health","10000","radius",".4","damage-mask","3","collision-flags","8"));
        fields.put("source-shape",FireCatalog.modelJson(new StarterCatalog.Model("shape",Map.of("value",shape))));
        for(String kind:List.of("Freeze","Fire","Shock")){
            var content=new SceneContent(List.of(new SceneContent.Entry("victim",2,0,0,new SceneContent.Spec(fields))),prepared.scales(),Map.of(),prepared.stats(),prepared.items(),Map.of(),prepared.statuses());
            var world=new SceneEntities(content,terrain);var weapons=new SceneWeapons(terrain);weapons.targets(world);
            int stamp=SceneClock.now();world.attacks((actor,action)->weapons.fire(action,actor,stamp));
            var player=new ActorState(10,1001,"Fixture",0,stamp,0,0,0,0,0,new StarterCatalog.Model("Fixture",Map.of()));
            world.advance(stamp,List.of(player));
            var item=content.items().get("Pickup/Vial/"+kind+" Vial");check(item!=null,"prepared "+kind+" vial exists");
            int vial=world.vial(player,item,stamp);check(vial!=0,"vial is held by its owner");world.throwLift(vial,player,stamp);
            boolean applied=false;
            for(int at=stamp+20;at<stamp+2000;at+=20){world.advance(at,List.of(player));
                var victim=world.actors().stream().filter(a->a.entity().kind().equals("Monster")).findFirst().orElseThrow();
                if(!victim.entity().status().conditions().isEmpty()){applied=true;break;}}
            check(applied,"thrown "+kind+" vial applies a status to the monster it hits");
        }
    }
    private static Object status(String name){return new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$ExclusiveStatus",List.of(
        new FireCatalog.FieldValue("flags","int",1),new FireCatalog.FieldValue("statusCondition","com.threerings.config.ConfigReference",new StarterCatalog.Model(name,Map.of("Chance",1f,"Power",100,"Duration",20000)))));}
    private static Object hit(float amount){return new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$Unblockable",List.of(new FireCatalog.FieldValue("amount","float",amount),new FireCatalog.FieldValue("flags","int",1)));}
}
