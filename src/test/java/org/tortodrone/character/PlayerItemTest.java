package org.tortodrone.character;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.account.AccountStore;
import org.tortodrone.storage.Database;
import static org.tortodrone.support.TestWire.check;

/** Disposable schema-6 upgrade and player inventory ownership/stack/binding regression. */
public final class PlayerItemTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-player-items-");
        try(var accounts=new AccountStore(home)) {
            accounts.create("Alice","alice-password".toCharArray());accounts.create("Other","other-password".toCharArray());
            var db=new Database(home,false);var chars=new CharacterStore(db,1,"Alice");
            chars.create(new Knight(0,"Saved-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            var saved=db.worldProfile(1,1,1);var identity=db.identity("Alice");
            // Build the actual previous table instead of just lowering user_version.
            db.write(c->{try(var s=c.createStatement()) {
                s.execute("CREATE TABLE inventory_old(item_id INTEGER PRIMARY KEY CHECK(item_id>0),world_id INTEGER NOT NULL REFERENCES world_characters(world_id) ON DELETE CASCADE,slot TEXT NOT NULL CHECK(slot IN('HELM','ARMOR','SWORD','GUN','SHIELD','BOMB')),config TEXT NOT NULL,accessory TEXT,heat INTEGER NOT NULL DEFAULT 0 CHECK(heat>=0),equipped_slot INTEGER CHECK(equipped_slot IN(0,1,2,6,7)),bound INTEGER NOT NULL DEFAULT 0 CHECK(bound IN(0,1)),bind_on_equip INTEGER NOT NULL DEFAULT 1 CHECK(bind_on_equip IN(0,1)),starter INTEGER NOT NULL DEFAULT 0 CHECK(starter IN(0,1)))");
                s.execute("INSERT INTO inventory_old SELECT item_id,world_id,slot,config,accessory,heat,equipped_slot,bound,bind_on_equip,starter FROM inventory_items");
                s.execute("DROP TABLE inventory_items");s.execute("ALTER TABLE inventory_old RENAME TO inventory_items");
                s.execute("CREATE UNIQUE INDEX equipped_items ON inventory_items(world_id,equipped_slot) WHERE equipped_slot IS NOT NULL");
                s.execute("CREATE UNIQUE INDEX starter_items ON inventory_items(world_id,slot) WHERE starter=1");
                s.execute("CREATE TABLE inventory_player(blocker INTEGER)");s.execute("PRAGMA user_version=6");
            }return null;});
            refused(()->new Database(home,false));
            check(db.read(c->{try(var s=c.createStatement();var r=s.executeQuery("PRAGMA user_version")){r.next();return r.getInt(1);}})==6,"failed upgrade rolls back");
            db.write(c->{try(var s=c.createStatement()){s.execute("DROP TABLE inventory_player");}return null;});
            new Database(home,false);check(db.worldProfile(1,1,1).equals(saved),"schema-6 upgrade preserves starter IDs, loadout, flags and revisions");
            check(Arrays.equals(identity.verifier(),db.identity("Alice").verifier()),"upgrade preserves password verifier");
            final Database store=db;
            var material=new ItemCatalog.Entry("Material/Test",101,"Craft","m.material",1,true,WorldProfile.Slot.OTHER,false,Map.of());
            var p=store.grantItems("Alice","Saved-Knight",1,saved.worldId(),material,7,-1,-1);long id=p.items().get(5).id();
            check(p.items().size()==6&&p.items().get(5).count()==7,"stackable grant creates one stack");
            refused(()->store.equip(1,1,1,id,6));refused(()->store.equip(2,1,1,id,null));
            var workers=Executors.newFixedThreadPool(2);
            try{for(var f:workers.invokeAll(List.<Callable<WorldProfile>>of(
                ()->store.grantItems("Alice","Saved-Knight",1,saved.worldId(),material,3,-1,-1),
                ()->new Database(home,false).grantItems("Alice","Saved-Knight",1,saved.worldId(),material,4,-1,-1))))f.get();}
            finally{workers.shutdownNow();}
            p=store.worldProfile(1,1,1);check(p.items().size()==6&&p.items().get(5).id()==id&&p.items().get(5).count()==14,"concurrent grants merge without lost quantity or ID changes");
            final var before=p;
            store.write(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER reject_audit BEFORE INSERT ON item_events BEGIN SELECT RAISE(ABORT,'test'); END");}return null;});
            refused(()->store.grantItems("Alice","Saved-Knight",1,saved.worldId(),material,2,-1,-1));
            check(store.worldProfile(1,1,1).equals(before),"audit failure rolls back stack merge and revision");
            store.write(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER reject_audit");}return null;});
            for(var slot:List.of(WorldProfile.Slot.HELM_COSTUME,WorldProfile.Slot.ARMOR_COSTUME,WorldProfile.Slot.SHIELD_COSTUME,WorldProfile.Slot.TRINKET)) {
                var e=new ItemCatalog.Entry("Costume/"+slot,102,"Costume","m.costume",0,false,slot,true,true,Map.of("Helmet",new StarterCatalog.Model("test",Map.of())));
                p=store.grantItems("Alice","Saved-Knight",1,saved.worldId(),e,1,-1,-1);var item=p.items().get(p.items().size()-1);
                check(item.bound()&&item.permanent(),"permanent gear is bound at creation");
                refused(()->store.equip(1,1,1,item.id(),6));
                p=store.equip(1,1,1,item.id(),slot.initialIndex());check(p.equipment()[slot.initialIndex()]==item.id(),"costume/trinket uses verified slot");
                if(slot==WorldProfile.Slot.TRINKET)p=store.equip(1,1,1,item.id(),11);
                p=store.equip(1,1,1,item.id(),null);check(p.items().stream().filter(i->i.id()==item.id()).findFirst().orElseThrow().permanent(),"unequip retains permanent binding");
            }
            check(new Database(home,false).worldProfile(1,1,1).equals(p),"new item fields survive reconnect");
            System.out.println("PASS: schema-6 upgrade/rollback, persisted stacks, concurrent merging, audit rollback, ownership, costume/trinket slots and permanent binding");
        }finally{try(var paths=Files.walk(home)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
    private static void refused(Operation work)throws Exception{try{work.run();throw new AssertionError("Refusal expected");}catch(IOException expected){}}
    @FunctionalInterface interface Operation {void run()throws Exception;}
}
