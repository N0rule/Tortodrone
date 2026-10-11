package org.tortodrone.character;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.account.AccountStore;
import org.tortodrone.storage.Database;
import static org.tortodrone.support.TestWire.check;

/** Independent stores race through SQLite, with exact audit/revision/rollback acceptance. */
public final class AdministrationTest {
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("tortodrone-administration-");
        var stack=new ItemCatalog.Entry("Material/Test",10,"Material","m.test",0,true,WorldProfile.Slot.OTHER,false,Map.of());
        var entries=new ArrayList<>(ItemCatalog.starter().entries());entries.add(stack);
        var catalog=new ItemCatalog(entries);
        try(var accounts=new AccountStore(root,false,catalog)) {
            accounts.create("Alice","alice-password".toCharArray());
            var db=new Database(root,false);var characters=new CharacterStore(db,1,"Alice",catalog);
            var knight=characters.create(new Knight(0,"Jane","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            var original=accounts.inspectKnight("jane");int world=original.worldId();
            check(accounts.setCurrency(null,"Jane",Currency.CROWNS,100,1,world).crowns()==100,"global name absolute set");
            check(accounts.setCurrency("Alice","Jane",Currency.ENERGY,0,1,world).energy()==0,"absolute zero accepted");
            for(long invalid:new long[]{-1,2147483648L,Long.MAX_VALUE})refuse(()->accounts.setCurrency(null,"Jane",Currency.CROWNS,invalid,1,world));
            refuse(()->accounts.setCurrency(null,"Jane",Currency.CROWNS,1,99,world));
            refuse(()->accounts.setCurrency(null,"Jane",Currency.CROWNS,1,1,world+1));
            var workers=Executors.newFixedThreadPool(4);
            try {
                var barrier=new CyclicBarrier(2);
                var set=workers.submit(()->{barrier.await();return db.setCurrency(null,"Jane",1,world,Currency.CROWNS,500);});
                var grant=workers.submit(()->{barrier.await();return new Database(root,false).currency(null,"Jane",1,world,Currency.CROWNS,10);});
                set.get();grant.get();int balance=accounts.inspectKnight("Jane").crowns();
                check(balance==500||balance==510,"set/grant linearize through shared SQLite write transaction");
                List<Callable<Void>> jobs=new ArrayList<>();for(int n=0;n<20;n++)jobs.add(()->{new Database(root,false).currency(null,"Jane",1,world,Currency.ENERGY,1);return null;});
                for(var result:workers.invokeAll(jobs))result.get();
                check(accounts.inspectKnight("Jane").energy()==20,"independent concurrent currency updates preserved");
            } finally {workers.shutdownNow();}
            db.write(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER reject_currency BEFORE INSERT ON currency_events BEGIN SELECT RAISE(ABORT,'fixture'); END");}return null;});
            var before=accounts.inspectKnight("Jane");refuse(()->accounts.setCurrency(null,"Jane",Currency.CROWNS,123,1,world));check(accounts.inspectKnight("Jane").equals(before),"audit rejection rolls back balance/revision");
            db.write(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER reject_currency");}return null;});
            var added=accounts.grantItems("Alice","Jane",stack.config(),5,1,world);var item=added.items().stream().filter(i->i.config().equals(stack.config())).findFirst().orElseThrow();
            check(accounts.itemQuantity("Alice","Jane",item.id(),9,1,world).items().stream().filter(i->i.id()==item.id()).findFirst().orElseThrow().count()==9,"stack adjustment persists");
            refuse(()->accounts.itemQuantity("Alice","Jane",item.id(),1,2,world));
            refuse(()->accounts.itemQuantity("Alice","Jane",original.items().getFirst().id(),0,1,world));
            check(accounts.itemQuantity("Alice","Jane",item.id(),0,1,world).items().stream().noneMatch(i->i.id()==item.id()),"ordinary inventory removal");
            var sword=accounts.grantItems("Alice","Jane","Weapon/Sword/Proto Sword",1,1,world).items().stream().filter(i->!i.starter()).findFirst().orElseThrow();
            refuse(()->accounts.itemQuantity("Alice","Jane",sword.id(),2,1,world));
            db.equip(1,knight.id(),1,sword.id(),6);refuse(()->accounts.itemQuantity("Alice","Jane",sword.id(),0,1,world));
            db.equip(1,knight.id(),1,sword.id(),null);accounts.itemQuantity("Alice","Jane",sword.id(),0,1,world);
            check(db.read(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM currency_events")){r.next();return r.getInt(1);}})==24,"only committed balance operations audited");
            check(new Database(root,false).inspectKnight(null,"Jane").equals(accounts.inspectKnight("Jane")),"reopen restores inventory and balances");
            System.out.println("PASS: atomic balance set/grant concurrency, audits, rollback, global targeting and guarded inventory administration");
        } finally {try(var files=Files.walk(root)){for(var file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}}
    }
    private static void refuse(Action action) throws Exception {try{action.run();throw new AssertionError("Invalid operation accepted");}catch(IOException expected){ }}
    @FunctionalInterface private interface Action {void run() throws Exception;}
}
