package org.tortodrone.world;

import java.util.*;
import org.tortodrone.character.WorldProfile;

/** Bounded input/state regression. Timings are fixture data, never production defaults. */
public final class KnightCombatTest {
    public static void main(String[] args)throws Exception {
        var hit=new WeaponCatalog.Attack(100,200,600,false,-1,-1,0,0);
        var gunHit=new WeaponCatalog.Attack(50,100,300,true,-1,-1,0,0);
        var sword=new WeaponCatalog.Weapon("Sword",1000,3,500,true,false,1,1,List.of(hit,hit,hit),hit,hit,hit);
        var gun=new WeaponCatalog.Weapon("Gun",1200,0,0,false,true,1,1,List.of(gunHit),gunHit,gunHit,null);
        var bomb=new WeaponCatalog.Weapon("Bomb",1500,0,0,true,true,1,.5f,List.of(),hit,hit,null);
        var catalog=new WeaponCatalog(List.of(sword,gun,bomb),List.of(new WeaponCatalog.Shield("Shield",40,5000,7000,-.5f,0,hit)),2000);
        idleSelection(catalog);
        var controller=new KnightCombat(catalog);
        controller.equipment(profile("Sword","Gun"));controller.reset(100);
        controller.input(4,100,100,0,0,true);
        check(controller.activity()==292&&controller.state().chainCount()==1,"fresh action starts native sword attack");
        controller.input(4,150,150,1,0,true);
        check(controller.started()==100,"attack cannot restart before its rearm");
        controller.input(4,300,300,1,0,true);
        check(controller.activity()==293&&controller.state().chainCount()==2,"new press chains after rearm");
        controller.input(4,300,500,1,0,true);
        check(controller.activity()==283&&controller.started()==500,"held action charges at exact rearm deadline");
        controller.input(0,300,1500,1,0,true);
        check(controller.activity()==290,"release after weapon charge duration uses charged activity");
        controller.reset(2000);
        controller.input(8,2000,2000,0,0,true);
        check(controller.state().weapon().config().equals("Gun"),"weapon selection maps to equipped slot seven");
        controller.input(8|4|1,2050,2050,0,0,true);
        check(controller.activity()==292&&controller.allowsMovement(),"gun uses its own movement-enabled attack definition");
        controller.reset(2500);
        controller.input(56,2500,2500,0,0,true);
        check(controller.state().weapon().config().equals("Gun"),"locked/out-of-range weapon slot cannot select foreign equipment");
        controller.input(8|64,2550,2550,0,0,true);
        check(controller.activity()==284,"owned shield enters native defense");
        check(controller.movementScale()==.5f&&controller.state().equipmentModifiers().defendingSpeed()==-.5f,"legacy weapon-only deployment retains shield slowdown and its stock modifier state");
        controller.input(8|64|2048,2600,2600,0,0,true);
        check(controller.activity()==287,"owned shield uses its configured bash");
        controller.reset(3000);controller.input(8|1024|1,3000,3000,0,(float)Math.PI/2,true);
        check(controller.activity()==286&&controller.state().nextDodge()==5000,"dodge uses exported cooldown");
        check(Math.abs(controller.state().impulse().y()-5)<.00001,"moving dodge follows movement direction");
        controller.advance(3625,0);controller.retireImpulse(3625);
        controller.input(8|1024,3700,3700,0,0,true);
        check(controller.activity()==0,"dodge cannot repeat within cooldown");
        controller.equipment(profile("Bomb","Gun"));controller.reset(6000);
        controller.input(0,6000,6000,0,0,true);
        controller.input(4,6001,6001,0,0,true);
        check(controller.activity()==283,"charge-only bomb begins charging");
        controller.input(0,6001,6100,0,0,true);
        check(controller.activity()==289,"early bomb release uses incomplete charged attack");
        controller.reset(7000);controller.input(4,7000,7000,0,0,false);
        check(controller.activity()==0,"inventory or another town activity blocks combat input");
        var zeroCooldown=new WeaponCatalog.Weapon("Sword",1000,1,0,false,false,1,1,List.of(hit),hit,hit,hit);
        var cooldownController=new KnightCombat(new WeaponCatalog(List.of(zeroCooldown),List.of(),2000));
        cooldownController.equipment(profile("Sword","Gun"));cooldownController.reset(8000);
        cooldownController.input(4,8000,8000,0,0,true);cooldownController.advance(8200,0);
        check(cooldownController.activity()==KnightCombat.COOLDOWN&&cooldownController.state().chainCount()==2&&cooldownController.state().chainReset()==8300,"cooldown resets the chain at its land deadline even with zero chain cooldown");
        var gearMod=new EquipmentCatalog.Modifiers(.25f,.08f,.2f,-.5f);
        var byClass=new EnumMap<EquipmentCatalog.WeaponClass,EquipmentCatalog.Modifiers>(EquipmentCatalog.WeaponClass.class);
        for(var type:EquipmentCatalog.WeaponClass.values())byClass.put(type,gearMod);
        var gear=new EquipmentCatalog(List.of(new EquipmentCatalog.Entry("Sword",WorldProfile.Slot.SWORD,byClass),
            new EquipmentCatalog.Entry("Gun",WorldProfile.Slot.GUN,byClass),new EquipmentCatalog.Entry("Shield",WorldProfile.Slot.SHIELD,byClass)));
        var geared=new KnightCombat(catalog,gear);geared.equipment(profile("Sword","Gun"));geared.reset(9000);
        check(Math.abs(geared.movementScale()-1.16f)<.00001,"equipped gear and selected weapon speed bonuses aggregate");
        geared.input(4,9000,9000,0,0,true);geared.input(4,9000,9167,0,0,true);
        check(geared.activity()==KnightCombat.CHARGE,"attack-speed bonus advances the held-charge rearm deadline");
        geared.input(0,9000,9687,0,0,true);check(geared.activity()==KnightCombat.CHARGED,"capped gear charge bonus shortens the authored charge duration");
        var stunned=new KnightCombat(catalog);stunned.equipment(profile("Sword","Gun"));stunned.reset(10000);
        stunned.status(new SceneStatus.Modifiers(1,.5f,1,1,1,true,true,true,false,false));
        var starts=new ArrayList<KnightCombat.AttackStart>();stunned.attacks(starts::add);
        stunned.input(4,10000,10000,0,0,true);
        check(starts.size()==1&&starts.getFirst().land()==200&&starts.getFirst().clear()==1200&&starts.getFirst().attackSpeed()==.5f,
            "status attack speed scales both attack animation and weapon fire deadlines");
        System.out.println("KnightCombatTest passed");
    }
    private static void idleSelection(WeaponCatalog catalog)throws Exception {
        var combat=new KnightCombat(catalog);combat.equipment(profile("Sword","Gun"));
        var movement=new TownMovement(SceneWeaponsTest.openTerrain(),0,0,0);movement.combat(combat);
        check(movement.accept(1000,0,0,8|1,1000,1000),"owned gun/movement intent accepted");movement.advance(1000,1000);
        check(combat.state().weapon().config().equals("Gun"),"native input selects the owned second weapon");
        movement.advance(1601,1601);
        check(combat.state().weapon().config().equals("Gun")&&(movement.flags()&1)==0&&movement.x()==0,"stale input stops walking without inventing a slot-zero selection");
        movement.accept(1602,0,0,8|64,1602,1602);movement.advance(1602,1602);
        check(combat.activity()==KnightCombat.DEFENSE,"shield held with the selected gun");movement.advance(2203,2203);
        check(combat.activity()==0&&combat.state().weapon().config().equals("Gun"),"timeout releases held defense while retaining weapon selection");
        movement.accept(2204,0,0,0,2204,2204);movement.advance(2204,2204);
        check(combat.state().weapon().config().equals("Sword"),"a real subsequent client slot-zero intent still selects the sword");
    }
    private static WorldProfile profile(String first,String second) {
        return new WorldProfile(10,1,1,0,0,1,List.of(
            new WorldProfile.Item(1,WorldProfile.Slot.SWORD,first,null,0,6,true),
            new WorldProfile.Item(2,WorldProfile.Slot.GUN,second,null,0,7,true),
            new WorldProfile.Item(3,WorldProfile.Slot.SHIELD,"Shield",null,0,2,true)));
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
