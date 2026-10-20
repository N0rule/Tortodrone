package org.tortodrone.town;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.storage.*;
import static org.tortodrone.support.TestWire.check;

/** Ownership, crystal/heat atomicity and UV retention using disposable inventory. */
public final class GearProgressionTest {
    public static void main(String[] args)throws Exception{
        Path home=Files.createTempDirectory("tortodrone-gear-");
        try{
            var db=new Database(home,true);var password=new Passwords.Protection(new byte[16],new byte[32]);int staff=db.create("Master",password,true),other=db.create("Other",password,false);
            var items=ItemCatalog.load(org.tortodrone.support.ContentFixture.path("items/catalog.json"));var rules=TownCatalog.load(org.tortodrone.support.ContentFixture.path("town/catalog.json"));
            new CharacterStore(db,staff,"Master",items).create(knight("Master-Knight"));new CharacterStore(db,other,"Other",items).create(knight("Other-Knight"));
            var town=new TownStore(db,items,rules);var profile=db.worldProfile(staff,1,1);var sword=profile.items().stream().filter(i->Integer.valueOf(6).equals(i.equippedSlot())).findFirst().orElseThrow();
            refused(()->town.adminGear(other,1,1,41,List.of(6,1f)));refused(()->town.adminGear(staff,1,999,41,List.of(6,1f)));
            refused(()->town.adminGear(staff,1,1,41,List.of(6,Float.NaN)));check(db.worldProfile(staff,1,1).equals(profile),"invalid/stale heat setters preserve all inventory and revisions");
            var heated=town.addHeat(staff,1,1,25).profile();
            check(heated.items().stream().filter(i->i.equippedSlot()!=null&&Set.of(0,1,2,6,7).contains(i.equippedSlot())).allMatch(i->i.heatProgress()==.5f),"server heat splits across the five eligible equipped starters using their native thresholds");
            town.adminGear(staff,1,1,42,List.of(6,9));var maxed=town.addHeat(staff,1,1,40).profile();
            check(maxed.items().stream().filter(i->Integer.valueOf(6).equals(i.equippedSlot())).allMatch(i->i.heatProgress()==0),"maximum-level equipment receives no heat and does not dilute the award");
            town.adminGear(staff,1,1,42,List.of(6,0));town.adminGear(staff,1,1,41,List.of(6,1f));
            var partial=db.worldProfile(staff,1,1);
            check(item(partial,sword.id()).heatProgress()==.01f,"GM heat accepts percentages rather than normalized progress");
            refused(()->town.forge(staff,1,1,sword.id(),0,0));
            check(db.worldProfile(staff,1,1).equals(partial),"partial heat forge refusal preserves inventory and revisions");
            town.adminGear(staff,1,1,41,List.of(6,100f));
            var ready=db.worldProfile(staff,1,1);refused(()->town.forge(other,1,1,sword.id(),0,0));refused(()->town.forge(staff,1,1,sword.id(),999,0));
            check(db.worldProfile(staff,1,1).equals(ready),"foreign inventory and forged crystal tiers are refused atomically");
            int starterCost=rules.gear().forge(rules.gear().gear(sword.config()),0).crystals().getFirst();
            db.grantItems("Master","Master-Knight",staff,-1,items.require("Rarity/Fire Crystal/Cracked"),starterCost,-1,-1);
            var forged=town.forge(staff,1,1,sword.id(),starterCost,0);check(forged.result().equals("m.forge_success")&&item(forged.profile(),sword.id()).level()==1&&item(forged.profile(),sword.id()).heatProgress()==0,"native starter forge consumes crystals/full heat and advances exactly one level");
            refused(()->town.forge(staff,1,1,sword.id(),0,0));refused(()->town.forge(staff,1,1,sword.id(),0,1));
            db.grantItems("Master","Master-Knight",staff,-1,items.require("Weapon/Sword/Calibur"),1,-1,-1);
            var calibur=db.worldProfile(staff,1,1).items().stream().filter(i->i.config().equals("Weapon/Sword/Calibur")).findFirst().orElseThrow();
            db.write(c->{try(var s=c.prepareStatement("UPDATE inventory_items SET heat_progress=1 WHERE item_id=?")){s.setLong(1,calibur.id());s.executeUpdate();}return null;});
            int cost=rules.gear().forge(rules.gear().gear(calibur.config()),0).crystals().get(2);
            db.grantItems("Master","Master-Knight",staff,-1,items.require("Rarity/Fire Crystal/Warm"),cost,-1,-1);
            var before=db.worldProfile(staff,1,1);town.forge(staff,1,1,calibur.id(),cost,0);var after=db.worldProfile(staff,1,1);
            check(after.items().stream().noneMatch(i->i.config().equals("Rarity/Fire Crystal/Warm"))&&item(after,calibur.id()).level()==1&&after.crowns()==before.crowns(),"paid forge consumes the correct rarity crystal without an invented Crown cost");
            var kept=new StarterCatalog.Model("Gear/Curse Resist 1",Map.of());var uv=rules.roll("Shop/Variant 3","ARMOR",List.of(kept));
            check(uv.size()==3&&uv.stream().filter(v->v.name().startsWith("Gear/Curse Resist ")).count()==1&&uv.contains(kept),"locked UV excludes every other tier in its category");
            for(int count=1;count<=3;count++)check(rules.roll("Shop/Variant "+count,"SWORD").size()==count,"parameterized UV table returns the selected count");
            check(new Database(home,false).worldProfile(staff,1,1).equals(after),"forge state survives reopening");
            System.out.println("PASS: native heat thresholds, GM setters, forge ownership/atomicity, crystal costs, UV counts/retention and persistence");
        }finally{try(var paths=Files.walk(home)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
    private static WorldProfile.Item item(WorldProfile p,long id){return p.items().stream().filter(i->i.id()==id).findFirst().orElseThrow();}
    private static Knight knight(String name){return new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",0,2,0,0);}
    private interface Checked{void run()throws Exception;}
    private static void refused(Checked work)throws Exception{try{work.run();throw new AssertionError("Expected refusal");}catch(IOException expected){}}
}
