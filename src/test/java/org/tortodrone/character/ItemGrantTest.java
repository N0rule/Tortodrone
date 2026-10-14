package org.tortodrone.character;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.account.AccountStore;
import org.tortodrone.storage.Database;
import static org.tortodrone.support.TestWire.check;

/** Owns disposable state: multi-item storage, transactional grants, permission/identity checks. */
public final class ItemGrantTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-item-grants-");
        try(var accounts=new AccountStore(home,false,org.tortodrone.character.ItemCatalog.starter())) {
            accounts.create("Staff","staff-password".toCharArray(),true);accounts.create("Alice","alice-password".toCharArray());
            var db=new Database(home,false);var characters=new CharacterStore(db,2,"Alice");
            var knight=characters.create(choices("Alice-Knight"));var before=characters.worldProfile(knight.name(),1);
            var entry=accounts.catalog().require("weapon/sword/proto sword");
            var after=db.grantItems(null,knight.name().toLowerCase(Locale.ROOT),2,before.worldId(),entry,2,1,1);
            check(after.items().size()==7&&Arrays.equals(before.equipment(),after.equipment()),"grants add distinct unequipped instances, preserving loadout");
            var added=after.items().stream().filter(i->!i.starter()).toList();
            check(added.size()==2&&added.stream().noneMatch(WorldProfile.Item::bound),"granted items start unbound and are not part of creator kit");
            check(db.equip(2,1,1,added.get(0).id(),6).items().stream().filter(i->i.id()==added.get(0).id()).findFirst().orElseThrow().bound(),"grant binds on equip");
            refused(()->db.equip(1,1,1,added.get(0).id(),6));
            db.equip(2,1,1,added.get(0).id(),null);
            accounts.setGameMaster("Staff",false);var unchanged=db.worldProfile(2,1,1);long counter=counter(db);
            refused(()->db.grantItems(null,knight.name(),2,before.worldId(),entry,1,1,1));
            check(db.worldProfile(2,1,1).equals(unchanged)&&counter(db)==counter,"revocation denies mutation/allocation");
            accounts.setGameMaster("Staff",true);accounts.setPassword("Staff","changed-password".toCharArray());
            refused(()->db.grantItems(null,knight.name(),2,before.worldId(),entry,1,1,1));
            refused(()->accounts.grantItems("Alice",knight.name(),"Not/An Item",1,-1,-1));
            refused(()->db.grantItems("Alice",knight.name(),99,before.worldId(),entry,1,-1,-1));
            refused(()->db.grantItems("Alice",knight.name(),2,999,entry,1,-1,-1));
            refused(()->db.grantItems("Alice",knight.name(),2,before.worldId(),entry,21,-1,-1));
            db.write(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER reject_audit BEFORE INSERT ON item_events BEGIN SELECT RAISE(ABORT,'test'); END");}return null;});
            refused(()->accounts.grantItems("Alice",knight.name(),entry.config(),2,-1,-1));
            check(db.worldProfile(2,1,1).equals(unchanged)&&counter(db)==counter,"failed audit rolls back items, revisions and IDs");
            db.write(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER reject_audit");}return null;});
            var workers=Executors.newFixedThreadPool(2);
            try {
                for(var future:workers.invokeAll(List.<Callable<WorldProfile>>of(
                    ()->new AccountStore(home,false,org.tortodrone.character.ItemCatalog.starter()).grantItems("Alice",knight.name(),entry.config(),2,-1,-1),
                    ()->new AccountStore(home,false,org.tortodrone.character.ItemCatalog.starter()).grantItems("Alice",knight.name(),entry.config(),2,-1,-1))))future.get();
            }finally{workers.shutdownNow();}
            var saved=new Database(home,false).worldProfile(2,1,1);
            check(saved.items().size()==11&&saved.items().stream().map(WorldProfile.Item::id).distinct().count()==11,"concurrent grants persist without ID collisions");
            characters.update(knight.name(),choices("Edited-Knight"),1);
            var edited=characters.worldProfile("Edited-Knight",1);
            check(edited.items().stream().filter(i->!i.starter()).toList().equals(saved.items().stream().filter(i->!i.starter()).toList()),"picker edits only change original starter cosmetics");
            while(edited.items().size()<ItemCatalog.INVENTORY_LIMIT)edited=accounts.grantItems("Alice","Edited-Knight",entry.config(),Math.min(20,ItemCatalog.INVENTORY_LIMIT-edited.items().size()),-1,-1);
            final var full=edited;long fullCounter=counter(db);
            refused(()->accounts.grantItems("Alice","Edited-Knight",entry.config(),1,-1,-1));
            check(db.worldProfile(2,1,1).equals(full)&&counter(db)==fullCounter,"capacity rejection preserves items/IDs");
            accounts.deleteKnight("Alice","Edited-Knight",2,full.worldId());
            check(db.read(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM item_events")){r.next();return r.getInt(1);}})==0,"knight deletion cascades item audit records");
            characters.refresh();characters.create(choices("Edited-Knight"),2);
            refused(()->accounts.grantItems("Alice","Edited-Knight",entry.config(),1,2,full.worldId()));
            lockedGrantScenario();
            System.out.println("PASS: duplicate equipment, atomic and locked grants, matching-stack binding, trade rejection, rollback, GM/auth checks, ownership, concurrency, capacity and stale target guards");
        }finally{try(var paths=Files.walk(home)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
    private static void lockedGrantScenario()throws Exception {
        Path home=Files.createTempDirectory("tortodrone-locked-item-grant-");
        try {
            var entries=new ArrayList<>(ItemCatalog.starter().entries());
            var token=new ItemCatalog.Entry("Item/Locked Token Fixture",9001,"Token","m.token",1,true,WorldProfile.Slot.OTHER,
                false,false,true,ItemCatalog.Variant.ITEM,null,Map.of());
            entries.add(token);var catalog=new ItemCatalog(entries);
            try(var accounts=new AccountStore(home,false,catalog)) {
                accounts.create("Alice","alice-password".toCharArray());
                accounts.create("Bob","bob-password".toCharArray());
                var db=new Database(home,false);
                int alice=db.identity("Alice").id(),bob=db.identity("Bob").id();
                var aliceCharacters=new CharacterStore(db,alice,"Alice",catalog);
                var bobCharacters=new CharacterStore(db,bob,"Bob",catalog);
                var aliceKnight=aliceCharacters.create(choices("Alice-Knight"));
                bobCharacters.create(choices("Bob-Knight"));
                var before=aliceCharacters.worldProfile(aliceKnight.name(),1);
                var unlocked=new ItemCatalog.Entry(token.config(),token.stockId(),token.type(),token.nameKey(),token.rarity(),true,
                    WorldProfile.Slot.OTHER,false,false,false,ItemCatalog.Variant.ITEM,null,Map.of());
                db.grantItems("Alice",aliceKnight.name(),alice,before.worldId(),unlocked,4,-1,-1);
                var first=db.grantItems("Alice",aliceKnight.name(),alice,before.worldId(),token,2,-1,-1);
                var locked1=first.items().stream().filter(i->i.config().equals(token.config())&&i.bound()).findFirst().orElseThrow();
                var second=db.grantItems("Alice",aliceKnight.name(),alice,before.worldId(),token,3,-1,-1);
                var locked2=second.items().stream().filter(i->i.config().equals(token.config())&&i.bound()).findFirst().orElseThrow();
                var unlockedSaved=second.items().stream().filter(i->i.config().equals(token.config())&&!i.bound()).findFirst().orElseThrow();
                check(locked1.id()==locked2.id()&&locked2.count()==5&&unlockedSaved.count()==4,
                    "repeat locked grants stack only with matching persisted lock/binding flags");
                var saved=new Database(home,false).worldProfile(alice,1,1);
                var lockedPersisted=saved.items().stream().filter(i->i.id()==locked2.id()).findFirst().orElseThrow();
                check(lockedPersisted.bound()&&lockedPersisted.count()==5,"locked grant binding and stacked count survive database reopen");
                var bobProfile=db.worldProfile(bob,1,1);
                refused(()->db.trade(saved,1,Map.of(lockedPersisted.id(),1),0,0,bobProfile,1,Map.of(),0,0,catalog));
                check(new Database(home,false).worldProfile(alice,1,1).equals(saved),"trade refusal leaves locked grant unchanged");
                db.write(c->{try(var s=c.prepareStatement("UPDATE inventory_items SET bound=0 WHERE item_id=? AND world_id=?")) {
                    s.setLong(1,lockedPersisted.id());s.setInt(2,saved.worldId());s.executeUpdate();
                }return null;});
                var legacyUnbound=aliceCharacters.tradeProfile(aliceKnight.id(),1);
                var bobBefore=db.worldProfile(bob,1,1);
                check(!legacyUnbound.items().stream().filter(i->i.id()==lockedPersisted.id()).findFirst().orElseThrow().bound(),
                    "legacy fixture retains its pre-catalog unbound storage flag without migration");
                refused(()->db.trade(legacyUnbound,1,Map.of(lockedPersisted.id(),1),0,0,bobBefore,1,Map.of(),0,0,catalog));
                check(db.worldProfile(alice,1,1).equals(legacyUnbound)&&db.worldProfile(bob,1,1).equals(bobBefore),
                    "catalog-locked legacy item trade is rejected atomically without rewriting existing data");
            }
        } finally {try(var paths=Files.walk(home)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
    private static Knight choices(String name){return new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0);}
    private static long counter(Database db)throws IOException{return db.read(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT value FROM metadata WHERE key='next-item-id'")){r.next();return r.getLong(1);}});}
    private static void refused(Operation work)throws Exception{try{work.run();throw new AssertionError("Refusal expected");}catch(IOException expected){}}
    @FunctionalInterface interface Operation {void run()throws Exception;}
}
