package org.tortodrone.storage;

import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.resources.ContentManager;
import org.tortodrone.support.ContentFixture;
import static org.tortodrone.support.TestWire.check;
import org.tortodrone.core.character.Knight;
import org.tortodrone.core.character.WorldProfile;
import org.tortodrone.core.character.SlotUpgrades;
import org.tortodrone.core.character.Currency;

/** The nine official Depot offers that earlier releases withheld are deliverable and usable. */
public final class DepotSupportTest {
    private static final List<String> ITEMS=List.of(
            "Upgrade/Heat Bonus (7 days)","Upgrade/Heat Bonus (30 days)","Upgrade/Trinket Slot",
            "Upgrade/Coin Bonus","Upgrade/Heat Bonus","Upgrade/Weapon Slot",
            "Gear/Shield/Heater Shield","Gear/Shield/Omega Shell","Weapon/Bomb/Big Angry Bomb");
    public static void main(String[] args)throws Exception {
        var content=ContentManager.load(Path.of("."));var items=content.items();var rules=content.town();
        Path root=Files.createTempDirectory("tortodrone-depot-support-");
        try {
            for(var name:ITEMS)check(items.require(name).grantable(),"verified native grant support: "+name);
            check(items.require("Gear/Shield/Heater Shield").slot()==WorldProfile.Slot.SHIELD
                    &&items.require("Gear/Shield/Omega Shell").slot()==WorldProfile.Slot.SHIELD
                    &&items.require("Weapon/Bomb/Big Angry Bomb").slot()==WorldProfile.Slot.BOMB,"native equipment slots restored");
            check(content.weapons().findShield("Gear/Shield/Heater Shield")!=null
                    &&content.weapons().findShield("Gear/Shield/Omega Shell")!=null
                    &&content.weapons().find("Weapon/Bomb/Big Angry Bomb")!=null,"native combat/shield definitions retained");
            var db=new Database(root,false);var depot=new DepotStore(db,items,rules);var town=new TownStore(db,items,rules);
            int account=db.create("Buyer",new Passwords.Protection(new byte[16],new byte[32]),false);
            new CharacterStore(db,account,"Buyer",items).create(knight("Buyer-Knight"));
            db.currency("Buyer","Buyer-Knight",account,-1,org.tortodrone.core.character.Currency.ENERGY,100_000);
            var snapshot=depot.catalog();var bought=new HashMap<String,Long>();
            for(var name:ITEMS) {
                var offer=snapshot.offers().stream().filter(o->o.good().item().equals(name)).findFirst().orElseThrow();
                int price=offer.good().energy()>0?offer.good().energy():offer.good().price();
                var profile=town.depot(account,1,1,offer.id(),price,new DepotStore.Quote(snapshot.revision(),offer)).profile();
                bought.put(name,profile.items().stream().filter(i->i.config().equals(name)).findFirst().orElseThrow().id());
            }
            check(db.worldProfile(account,1,1).energy()==100_000-18_600,"all nine offers debit their official prices");

            var profile=town.activateUpgrade(account,1,1,bought.get("Upgrade/Weapon Slot")).profile();
            check(SlotUpgrades.weapons(profile)==3,"weapon slot upgrade adds a weapon slot");
            profile=town.activateUpgrade(account,1,1,bought.get("Upgrade/Trinket Slot")).profile();
            check(SlotUpgrades.trinkets(profile)==1,"trinket slot upgrade adds a trinket slot");
            profile=town.activateUpgrade(account,1,1,bought.get("Upgrade/Coin Bonus")).profile();
            check(SlotUpgrades.pvpTokenBonus(profile)==1,"coin bonus adds one capped Krogmo token");
            long heat=bought.get("Upgrade/Heat Bonus (7 days)");
            profile=town.activateUpgrade(account,1,1,heat).profile();
            check(SlotUpgrades.heatBonusActive(profile),"heat bonus activates");
            long before=profile.items().stream().filter(i->i.id()==heat).findFirst().orElseThrow().expiresAt();
            profile=town.extendUpgrade(account,1,1,heat,bought.get("Upgrade/Heat Bonus (30 days)")).profile();
            long after=profile.items().stream().filter(i->i.id()==heat).findFirst().orElseThrow().expiresAt();
            check(after-before>=30L*86_400_000L,"heat bonus extension adds the unused duration");
            refused(()->town.activateUpgrade(account,1,1,bought.get("Upgrade/Heat Bonus")));

            long shield=bought.get("Gear/Shield/Heater Shield");
            profile=db.equip(account,1,1,shield,2);
            check(profile.items().stream().anyMatch(i->i.id()==shield&&Integer.valueOf(2).equals(i.equippedSlot())),"Heater Shield equips in the shield slot");
            long bomb=bought.get("Weapon/Bomb/Big Angry Bomb");
            profile=db.equip(account,1,1,bomb,6);
            check(profile.items().stream().anyMatch(i->i.id()==bomb&&Integer.valueOf(6).equals(i.equippedSlot())),"Big Angry Bomb equips in a weapon slot");
            System.out.println("PASS: all nine formerly withheld offers debit, grant, activate/equip and preserve slot, heat and coin effects");
        }finally{ContentFixture.delete(root);}
    }
    private static Knight knight(String name){return new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",0,2,0,0);}
    private interface Action {void run()throws Exception;}
    private static void refused(Action action)throws Exception{try{action.run();throw new AssertionError("Expected refusal");}catch(java.io.IOException expected){}}
}
