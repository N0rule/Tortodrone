package org.tortodrone.character;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import org.tortodrone.account.AccountStore;
import org.tortodrone.storage.Database;
import static org.tortodrone.support.TestWire.check;

public final class ItemBindingTest {
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("tortodrone-binding-");
        try {
            try (var accounts = new AccountStore(home)) { accounts.create("Alice", "alice-password".toCharArray()); }
            var db = new Database(home, false);
            var characters = new CharacterStore(db, 1, "Alice");
            var knight = characters.create(new Knight(0,"Bound-Knight","Gear/Helm/Spiral Sallet (Blue)",
                "Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            var original = characters.worldProfile(knight.name(), 1);
            check(original.items().stream().allMatch(WorldProfile.Item::bound), "new equipped kit is bound");
            long helm = original.equipment()[0];
            var removed = db.equip(1, knight.id(), 1, helm, null);
            check(removed.items().stream().allMatch(WorldProfile.Item::bound), "unequip retains binding");
            check(new Database(home,false).worldProfile(1,knight.id(),1).items().equals(removed.items()),
                "restart preserves unequipped bound inventory");
            db.equip(1, knight.id(), 1, helm, 0);
            check(db.worldProfile(1,knight.id(),1).equipment()[0] == helm, "bound item can be re-equipped");
            db.equip(1, knight.id(), 1, helm, null);
            // Simulate a future unbound item: equipping binds transactionally; removing never clears it.
            db.write(c -> { try(var s=c.prepareStatement("UPDATE inventory_items SET bound=0 WHERE item_id=?")) {
                s.setLong(1,helm); s.executeUpdate(); } return null; });
            check(!db.worldProfile(1,knight.id(),1).items().get(0).bound(), "unbound fixture");
            check(db.equip(1,knight.id(),1,helm,0).items().get(0).bound(), "equipping binds the owned item");
            removed = db.equip(1,knight.id(),1,helm,null);
            var itemIds = removed.items().stream().map(WorldProfile.Item::id).toList();
            db.currency("Alice",knight.name(),1,original.worldId(),Currency.CROWNS,42);
            // Recreate an actual schema-3 shape, including a previously unequipped starter.
            db.write(c -> { try(var s=c.createStatement()) {
                s.execute("CREATE TABLE inventory_old(item_id INTEGER PRIMARY KEY CHECK(item_id>0),world_id INTEGER NOT NULL REFERENCES world_characters(world_id) ON DELETE CASCADE,slot TEXT NOT NULL CHECK(slot IN('HELM','ARMOR','SWORD','GUN','SHIELD')),config TEXT NOT NULL,accessory TEXT,heat INTEGER NOT NULL DEFAULT 0 CHECK(heat>=0),equipped_slot INTEGER CHECK(equipped_slot IN(0,1,2,6,7)),UNIQUE(world_id,slot))");
                s.execute("INSERT INTO inventory_old SELECT item_id,world_id,slot,config,accessory,heat,equipped_slot FROM inventory_items");
                s.execute("DROP TABLE inventory_items");s.execute("ALTER TABLE inventory_old RENAME TO inventory_items");
                s.execute("CREATE UNIQUE INDEX equipped_items ON inventory_items(world_id,equipped_slot) WHERE equipped_slot IS NOT NULL");
                s.execute("DROP TABLE item_events");
                s.execute("DROP INDEX global_knight_names");s.execute("PRAGMA user_version=3");
                s.execute("CREATE TRIGGER refuse_binding BEFORE UPDATE ON inventory_items BEGIN SELECT RAISE(ABORT,'test'); END");
            } return null; });
            try { new Database(home,false); throw new AssertionError("failed migration accepted"); }
            catch(IOException expected) { }
            check(db.read(c -> {try(var s=c.createStatement();var r=s.executeQuery("PRAGMA user_version")) {r.next();return r.getInt(1);}})==3,
                "failed migration retains schema version");
            check(db.read(c -> {try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM pragma_table_info('inventory_items') WHERE name='bound'")) {r.next();return r.getInt(1);}})==0,
                "failed migration rolls back column");
            db.write(c -> {try(var s=c.createStatement()){s.execute("DROP TRIGGER refuse_binding");}return null;});
            var migrated = new Database(home,false).worldProfile(1,knight.id(),1);
            check(migrated.worldId()==original.worldId() && migrated.items().stream().map(WorldProfile.Item::id).toList().equals(itemIds),
                "migration preserves identities");
            check(migrated.crowns()==42 && migrated.equipment()[0]==0 && migrated.items().stream().allMatch(WorldProfile.Item::bound),
                "migration binds previously worn starters without re-equipping or resetting balances");
            check(new Database(home,false).worldProfile(1,knight.id(),1).equals(migrated), "migration is one-time");
            System.out.println("PASS: binding on equip, durable unequip/re-equip, schema-3 migration, rollback and unchanged identities/balances");
        } finally { try(var paths=Files.walk(home)) {for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);} }
    }
}
