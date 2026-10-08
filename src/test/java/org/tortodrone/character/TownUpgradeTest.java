package org.tortodrone.character;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.AccountStore;
import org.tortodrone.storage.Database;
import static org.tortodrone.support.TestWire.check;

public final class TownUpgradeTest {
    public static void main(String[] args) throws Exception {
        Path home=Files.createTempDirectory("tortodrone-town-upgrade-");
        try {
            try(var accounts=new AccountStore(home)) { accounts.create("Alice","alice-password".toCharArray()); }
            var db=new Database(home,false);
            var characters=new CharacterStore(db,1,"Alice");
            var knight=characters.create(new Knight(0,"Saved-Knight","Gear/Helm/Spiral Sallet (Blue)",
                "Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            var original=characters.worldProfile(knight.name(),1);
            db.write(c -> { try(var s=c.createStatement()) {
                s.execute("DROP TABLE currency_events");
                s.execute("CREATE TABLE inventory_old(item_id INTEGER PRIMARY KEY CHECK(item_id>0),world_id INTEGER NOT NULL REFERENCES world_characters(world_id) ON DELETE CASCADE,slot TEXT NOT NULL CHECK(slot IN('HELM','ARMOR','SWORD','GUN','SHIELD')),config TEXT NOT NULL,accessory TEXT,heat INTEGER NOT NULL DEFAULT 0 CHECK(heat>=0),UNIQUE(world_id,slot))");
                s.execute("INSERT INTO inventory_old SELECT item_id,world_id,slot,config,accessory,heat FROM inventory_items");
                s.execute("DROP TABLE inventory_items");s.execute("ALTER TABLE inventory_old RENAME TO inventory_items");
                s.execute("ALTER TABLE world_characters DROP COLUMN crowns");
                s.execute("ALTER TABLE world_characters DROP COLUMN energy");
                s.execute("ALTER TABLE world_characters DROP COLUMN state_revision");
                s.execute("DROP TABLE item_events");s.execute("DROP INDEX global_knight_names");s.execute("PRAGMA user_version=2");
                s.execute("CREATE TABLE currency_events(blocked INTEGER)");
            } return null; });
            try { new Database(home,false);throw new AssertionError("Blocked upgrade accepted"); }
            catch(IOException expected) { }
            check(db.read(c -> {try(var s=c.createStatement();var q=s.executeQuery("PRAGMA user_version")){q.next();return q.getInt(1);}})==2,
                "failed v2 upgrade rolls back schema version");
            check(db.read(c -> {try(var s=c.createStatement();var q=s.executeQuery("SELECT count(*) FROM pragma_table_info('world_characters') WHERE name='crowns'")){q.next();return q.getInt(1);}})==0,
                "failed upgrade rolls back added columns");
            db.write(c -> {try(var s=c.createStatement()){s.execute("DROP TABLE currency_events");}return null;});
            db=new Database(home,false);var restored=db.worldProfile(1,knight.id(),1);
            check(restored.worldId()==original.worldId() && restored.items().stream().map(WorldProfile.Item::id).toList()
                .equals(original.items().stream().map(WorldProfile.Item::id).toList()),"v2 upgrade preserves existing identities/items");
            check(restored.crowns()==0 && restored.energy()==0 && Arrays.stream(restored.equipment()).filter(v->v!=0).count()==5,
                "existing kit equipped once with zero starting counters");
            db.equip(1,knight.id(),1,restored.equipment()[0],null);
            check(new Database(home,false).worldProfile(1,knight.id(),1).equipment()[0]==0,"reopen does not re-equip inventory");
            System.out.println("PASS: schema 2 town upgrade, atomic rollback, stable IDs, counters and one-time equipment backfill");
        } finally {try(var paths=Files.walk(home)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
}
