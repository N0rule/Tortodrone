package org.tortodrone.town;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.storage.*;
import static org.tortodrone.support.TestWire.check;

/** Authenticated wrapping, restart, transfer, unwrapping and rollback on disposable storage. */
public final class GiftWrappingTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-gifts-");
        try{
            var db=new Database(home,true);var protection=new Passwords.Protection(new byte[16],new byte[32]);int a=db.create("Alice",protection,false),b=db.create("Bob",protection,false);
            var items=ItemCatalog.load(org.tortodrone.support.ContentFixture.path("items/catalog.json"));var rules=TownCatalog.load(org.tortodrone.support.ContentFixture.path("town/catalog.json"));
            var alice=new CharacterStore(db,a,"Alice",items);var bob=new CharacterStore(db,b,"Bob",items);alice.create(knight("Gift-Alice"));bob.create(knight("Gift-Bob"));
            String paper="Upgrade/Wrapping Paper/Checker Wrapping (Blue)";
            db.grantItems("Alice","Gift-Alice",-1,-1,items.require(paper),2,-1,-1);db.grantItems("Alice","Gift-Alice",-1,-1,items.require("Weapon/Sword/Calibur"),1,-1,-1);
            var initial=db.worldProfile(a,1,1);long wrapper=id(initial,paper),sword=id(initial,"Weapon/Sword/Calibur");
            db.write(c->{try(var st=c.prepareStatement("UPDATE inventory_items SET level=2,heat_progress=0.4,variants='[\"Weapon/Damage 1\"]' WHERE item_id=?")){st.setLong(1,sword);st.executeUpdate();}return null;});
            var town=new TownStore(db,items,rules);var before=db.worldProfile(a,1,1);
            refused(()->town.wrap(b,1,1,wrapper,Map.of(sword,1),"hello","Blue"));refused(()->town.wrap(a,1,999,wrapper,Map.of(sword,1),"hello","Blue"));
            refused(()->town.wrap(a,1,1,wrapper,Map.of(before.items().getFirst().id(),1),"hello","Blue"));refused(()->town.wrap(a,1,1,wrapper,Map.of(sword,2),"hello","Blue"));
            check(db.worldProfile(a,1,1).equals(before),"foreign, stale-auth, bound/equipped and over-quantity wrapping rolls back");
            var wrapped=town.wrap(a,1,1,wrapper,Map.of(sword,1),"A gift for you","Blue").profile();
            var box=wrapped.items().stream().filter(i->i.gift()!=null).findFirst().orElseThrow();
            check(box.config().equals("Upgrade/Gift Box/Checker Gift Box (Blue)")&&box.gift().wrapperName().equals("Gift-Alice")&&wrapped.items().stream().noneMatch(i->i.id()==sword),"stock paper determines the persistent native box and removes its contents");
            check(wrapped.items().stream().filter(i->i.id()==wrapper).findFirst().orElseThrow().count()==1,"exactly one paper is consumed");
            check(new Database(home,true).worldProfile(a,1,1).equals(wrapped),"gift contents and author survive reopening");
            var other=db.worldProfile(b,1,1);db.trade(wrapped,1,Map.of(box.id(),1),0,0,other,1,Map.of(),0,0,items);
            refused(()->town.unwrap(a,1,1,box.id()));var recipient=db.worldProfile(b,1,1);
            check(recipient.items().stream().anyMatch(i->i.id()==box.id()&&i.gift()!=null),"trade transfers the same box and its durable contents");
            var opened=town.unwrap(b,1,1,box.id()).profile();var restored=opened.items().stream().filter(i->i.config().equals("Weapon/Sword/Calibur")).findFirst().orElseThrow();
            check(restored.level()==2&&restored.heatProgress()==.4f&&restored.variants().size()==1&&opened.items().stream().noneMatch(i->i.id()==box.id()),"unwrapping preserves equipment state and consumes the box");
            refused(()->town.unwrap(b,1,1,box.id()));check(db.worldProfile(b,1,1).equals(opened),"replayed unwrap cannot duplicate contents");
            // Known predecessor upgrade is additive; no player rows or original item state change.
            db.write(c->{try(var st=c.createStatement()){org.tortodrone.support.SchemaFixture.depotBefore15(c);st.execute("DROP TABLE town_gifts");st.execute("PRAGMA user_version=12");}return null;});
            check(org.tortodrone.support.SchemaFixture.upgrade(home).worldProfile(b,1,1).equals(opened),"known schema-12 upgrade preserves inventory");
            System.out.println("PASS: gift ownership/rollback, native pairing, persistence, trade, properties, duplicate unwrap and additive schema upgrade");
        }finally{try(var paths=Files.walk(home)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
    private static long id(WorldProfile p,String name){return p.items().stream().filter(i->i.config().equals(name)).findFirst().orElseThrow().id();}
    private static Knight knight(String name){return new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",0,2,0,0);}
    private interface Work{void run()throws Exception;}
    private static void refused(Work work)throws Exception{try{work.run();throw new AssertionError("Expected refusal");}catch(IOException expected){}}
}
