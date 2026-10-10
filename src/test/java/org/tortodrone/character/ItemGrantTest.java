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
        try(var accounts=new AccountStore(home)) {
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
                    ()->new AccountStore(home).grantItems("Alice",knight.name(),entry.config(),2,-1,-1),
                    ()->new AccountStore(home).grantItems("Alice",knight.name(),entry.config(),2,-1,-1))))future.get();
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
            System.out.println("PASS: duplicate equipment, atomic grants/audits, rollback, binding, current GM/auth checks, ownership, concurrency, capacity and stale target guards");
        }finally{try(var paths=Files.walk(home)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
    private static Knight choices(String name){return new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0);}
    private static long counter(Database db)throws IOException{return db.read(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT value FROM metadata WHERE key='next-item-id'")){r.next();return r.getLong(1);}});}
    private static void refused(Operation work)throws Exception{try{work.run();throw new AssertionError("Refusal expected");}catch(IOException expected){}}
    @FunctionalInterface interface Operation {void run()throws Exception;}
}
