package org.tortodrone.town;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.character.Currency;
import org.tortodrone.resources.Json;
import org.tortodrone.storage.*;
import static org.tortodrone.support.TestWire.check;

/** Constant catalogues and repeated item handles must share their persisted limit. */
public final class ShopLimitTest {
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("tortodrone-shop-limit-");
        try{
            var json=new LinkedHashMap<String,Object>();json.put("format",1);for(String key:List.of("recipes","items","sprites","perks","stations","variantTables","accessories","blockers","harnesses","catalogs","bonuses"))json.put(key,List.of());
            var good=Map.of("item","Material/Gel Drop","count",1,"price",10,"energy",0,"limit",2,"token","","tokenCount",0);json.put("catalogs",List.of(Map.of("config","Fixture","kind","Basic","includes",List.of(),"goods",List.of(good,good),"min",0,"max",0,"chance",1)));
            Files.writeString(root.resolve("rules.json"),Json.stringify(json));var rules=TownCatalog.load(root.resolve("rules.json"));var items=ItemCatalog.load(org.tortodrone.support.ContentFixture.path("items/catalog.json"));var db=new Database(root,true);var password=new Passwords.Protection(new byte[16],new byte[32]);
            for(String name:List.of("Alice","Bob")){int id=db.create(name,password,false);new CharacterStore(db,id,name,items).create(new Knight(0,name+"-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",0,2,0,0));db.currency(name,name+"-Knight",id,-1,Currency.CROWNS,100);}
            var shop=new TownCatalog.Station("fixture","OpenShop","CROWN","NORMAL","~","~","","",List.of(),new TownCatalog.Shop("Fixture",10,"BASIC","CONSTANT",true));var town=new TownStore(db,items,rules);var original=db.worldProfile(1,1,1);
            refused(()->town.shop(1,1,1,shop,0,Map.of(0,2,1,2),Map.of()));check(db.worldProfile(1,1,1).equals(original),"duplicate handles cannot bypass a shared purchase limit");
            town.shop(1,1,1,shop,0,Map.of(0,1,1,1),Map.of());check(town.availability(1,1,1,shop,0).equals(List.of(0,0)),"non-rotating shop persists each knight's purchase limit");var purchased=db.worldProfile(1,1,1);refused(()->town.shop(1,1,1,shop,0,Map.of(0,1),Map.of()));check(db.worldProfile(1,1,1).equals(purchased),"reopening cannot replenish a constant catalogue");
            check(new TownStore(new Database(root,false),items,rules).availability(1,1,1,shop,0).equals(List.of(0,0))&&town.availability(2,1,1,shop,0).equals(List.of(2,2)),"limits survive restart and remain separate between knights");
            long cycle=System.currentTimeMillis()+60000;check(town.availability(1,1,1,shop,cycle).equals(List.of(2,2)),"a genuine new shop cycle uses its own purchase history");
            System.out.println("PASS: constant shop limits, duplicate handle aggregation, per-knight ownership, rollback and restart persistence");
        }finally{try(var files=Files.walk(root)){for(var path:files.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
    @FunctionalInterface private interface Work{void run()throws Exception;}
    private static void refused(Work work)throws Exception{try{work.run();throw new AssertionError("Expected refusal");}catch(IOException expected){}}
}
