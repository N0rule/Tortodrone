package org.tortodrone.world;

import java.util.*;
import org.tortodrone.character.WorldProfile;

/** Bounded input/state regression. Timings are fixture data, never production defaults. */
public final class KnightCombatTest {
    public static void main(String[] args) {
        var hit=new WeaponCatalog.Attack(100,200,600,false,-1,-1,0,0);
        var gunHit=new WeaponCatalog.Attack(50,100,300,true,-1,-1,0,0);
        var sword=new WeaponCatalog.Weapon("Sword",1000,3,500,true,false,1,1,List.of(hit,hit,hit),hit,hit,hit);
        var gun=new WeaponCatalog.Weapon("Gun",1200,0,0,false,true,1,1,List.of(gunHit),gunHit,gunHit,null);
        var bomb=new WeaponCatalog.Weapon("Bomb",1500,0,0,true,true,1,.5f,List.of(),hit,hit,null);
        var catalog=new WeaponCatalog(List.of(sword,gun,bomb),List.of(new WeaponCatalog.Shield("Shield",40,5000,7000,hit)),2000);
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
        System.out.println("KnightCombatTest passed");
    }
    private static WorldProfile profile(String first,String second) {
        return new WorldProfile(10,1,1,0,0,1,List.of(
            new WorldProfile.Item(1,WorldProfile.Slot.SWORD,first,null,0,6,true),
            new WorldProfile.Item(2,WorldProfile.Slot.GUN,second,null,0,7,true),
            new WorldProfile.Item(3,WorldProfile.Slot.SHIELD,"Shield",null,0,2,true)));
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
