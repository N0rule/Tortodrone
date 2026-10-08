package org.tortodrone.character;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.AccountStore;
import org.tortodrone.storage.Database;
import static org.tortodrone.support.TestWire.check;

public final class InventoryMigrationTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-inventory-upgrade-");
        try(var accounts=new AccountStore(home)) {
            accounts.create("Alice","alice-password".toCharArray());var db=new Database(home,false);
            var characters=new CharacterStore(db,1,"Alice");characters.create(new Knight(0,"Saved-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            var original=db.worldProfile(1,1,1);db.equip(1,1,1,original.equipment()[0],null);
            db.currency("Alice","Saved-Knight",1,original.worldId(),Currency.CROWNS,42);var saved=db.worldProfile(1,1,1);
            var identity=db.identity("Alice");
            db.write(c->{try(var s=c.createStatement()) {
                s.execute("CREATE TABLE inventory_old(item_id INTEGER PRIMARY KEY CHECK(item_id>0),world_id INTEGER NOT NULL REFERENCES world_characters(world_id) ON DELETE CASCADE,slot TEXT NOT NULL CHECK(slot IN('HELM','ARMOR','SWORD','GUN','SHIELD')),config TEXT NOT NULL,accessory TEXT,heat INTEGER NOT NULL DEFAULT 0 CHECK(heat>=0),equipped_slot INTEGER CHECK(equipped_slot IN(0,1,2,6,7)),bound INTEGER NOT NULL DEFAULT 0 CHECK(bound IN(0,1)),UNIQUE(world_id,slot))");
                s.execute("INSERT INTO inventory_old SELECT item_id,world_id,slot,config,accessory,heat,equipped_slot,bound FROM inventory_items");
                s.execute("DROP TABLE inventory_items");s.execute("ALTER TABLE inventory_old RENAME TO inventory_items");
                s.execute("CREATE UNIQUE INDEX equipped_items ON inventory_items(world_id,equipped_slot) WHERE equipped_slot IS NOT NULL");
                s.execute("DROP INDEX global_knight_names");s.execute("PRAGMA user_version=4"); // Existing item_events blocks the last migration DDL.
            }return null;});
            try{new Database(home,false);throw new AssertionError("Broken migration accepted");}catch(IOException expected){}
            check(db.read(c->{try(var s=c.createStatement();var r=s.executeQuery("PRAGMA user_version")){r.next();return r.getInt(1);}})==4,"failed upgrade rolls back version/table rebuild");
            check(db.read(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM pragma_table_info('inventory_items') WHERE name='starter'")){r.next();return r.getInt(1);}})==0,"failed upgrade preserves actual schema-4 inventory");
            db.write(c->{try(var s=c.createStatement()){s.execute("DROP TABLE item_events");}return null;});
            var migrated=new Database(home,false).worldProfile(1,1,1);
            check(migrated.equals(saved),"migration preserves IDs, equipment, binding, balances and revisions");
            check(Arrays.equals(identity.salt(),db.identity("Alice").salt())&&Arrays.equals(identity.verifier(),db.identity("Alice").verifier()),"account credentials unchanged");
            accounts.grantItems("Alice","Saved-Knight","Weapon/Sword/Proto Sword",2,-1,-1);
            check(new Database(home,false).worldProfile(1,1,1).items().size()==7,"schema-5 removes one-per-type restriction and persists duplicates");
            System.out.println("PASS: actual schema-4 inventory migration, DDL rollback, durable IDs/binding/loadout/balances/credentials and duplicate grants");
        }finally{try(var paths=Files.walk(home)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
}
