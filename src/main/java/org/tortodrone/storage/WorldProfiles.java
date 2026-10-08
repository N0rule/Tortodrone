package org.tortodrone.storage;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import org.tortodrone.character.Knight;
import org.tortodrone.character.WorldProfile;
import org.tortodrone.character.WorldProfile.Slot;

/** Character schema and transactional upgrade helpers. Called inside the same transaction as its roster mutation. */
final class WorldProfiles {
    private WorldProfiles() { }

    static void initialize(Connection c) throws SQLException, IOException {
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE world_characters(world_id INTEGER PRIMARY KEY CHECK(world_id>0 AND world_id<2147483647), account_id INTEGER NOT NULL, knight_id INTEGER NOT NULL, starter_version INTEGER NOT NULL CHECK(starter_version=1), UNIQUE(account_id,knight_id), FOREIGN KEY(account_id,knight_id) REFERENCES knights(account_id,id) ON DELETE CASCADE)");
            s.execute("CREATE TABLE inventory_items(item_id INTEGER PRIMARY KEY CHECK(item_id>0), world_id INTEGER NOT NULL REFERENCES world_characters(world_id) ON DELETE CASCADE, slot TEXT NOT NULL CHECK(slot IN('HELM','ARMOR','SWORD','GUN','SHIELD')), config TEXT NOT NULL, accessory TEXT, heat INTEGER NOT NULL DEFAULT 0 CHECK(heat>=0), UNIQUE(world_id,slot))");
            s.execute("INSERT INTO metadata VALUES('next-world-id',1),('next-item-id',1)");
        }
        // Deterministic assignment, without changing any account-local IDs or saved appearance.
        List<int[]> identities = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT account_id,id FROM knights ORDER BY account_id,id")) {
            while (r.next()) identities.add(new int[] {r.getInt(1),r.getInt(2)});
        }
        for (int[] identity : identities) ensure(c, identity[0], Database.knight(c,identity[0],identity[1]));
    }

    static void upgrade(Connection c) throws SQLException {
        try(Statement s=c.createStatement()) {
            s.execute("ALTER TABLE world_characters ADD COLUMN crowns INTEGER NOT NULL DEFAULT 0 CHECK(crowns BETWEEN 0 AND 2147483647)");
            s.execute("ALTER TABLE world_characters ADD COLUMN energy INTEGER NOT NULL DEFAULT 0 CHECK(energy BETWEEN 0 AND 2147483647)");
            s.execute("ALTER TABLE world_characters ADD COLUMN state_revision INTEGER NOT NULL DEFAULT 1 CHECK(state_revision>0)");
            s.execute("ALTER TABLE inventory_items ADD COLUMN equipped_slot INTEGER CHECK(equipped_slot IN(0,1,2,6,7))");
            s.execute("UPDATE inventory_items SET equipped_slot=CASE slot WHEN 'HELM' THEN 0 WHEN 'ARMOR' THEN 1 WHEN 'SHIELD' THEN 2 WHEN 'SWORD' THEN 6 WHEN 'GUN' THEN 7 END");
            s.execute("CREATE UNIQUE INDEX equipped_items ON inventory_items(world_id,equipped_slot) WHERE equipped_slot IS NOT NULL");
            s.execute("CREATE TABLE currency_events(id INTEGER PRIMARY KEY,world_id INTEGER NOT NULL,currency TEXT NOT NULL CHECK(currency IN('crowns','energy')),delta INTEGER NOT NULL,balance INTEGER NOT NULL,created_at INTEGER NOT NULL)");
        }
    }

    static void ensure(Connection c, int accountId, Knight knight) throws SQLException, IOException {
        try (PreparedStatement s = c.prepareStatement("SELECT world_id FROM world_characters WHERE account_id=? AND knight_id=?")) {
            s.setInt(1,accountId); s.setInt(2,knight.id());
            try (ResultSet r = s.executeQuery()) { if (r.next()) return; }
        }
        int worldId = Math.toIntExact(allocate(c,"next-world-id",Integer.MAX_VALUE));
        try (PreparedStatement s = c.prepareStatement("INSERT INTO world_characters(world_id,account_id,knight_id,starter_version) VALUES(?,?,?,1)")) {
            s.setInt(1,worldId); s.setInt(2,accountId); s.setInt(3,knight.id()); s.executeUpdate();
        }
        add(c,worldId,Slot.HELM,knight.helm(),knight.helmAccessory());
        add(c,worldId,Slot.ARMOR,knight.armor(),knight.armorAccessory());
        add(c,worldId,Slot.SWORD,"Weapon/Sword/Proto Sword",null);
        add(c,worldId,Slot.GUN,"Weapon/Handgun/Proto Gun",null);
        add(c,worldId,Slot.SHIELD,"Gear/Shield/Proto Shield",null);
    }

    static void upgradeBinding(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("ALTER TABLE inventory_items ADD COLUMN bound INTEGER NOT NULL DEFAULT 0 CHECK(bound IN(0,1))");
            // Every existing item is from the starter kit, granted equipped. An unequipped
            // starter was previously worn too: retain that binding during the upgrade.
            s.execute("UPDATE inventory_items SET bound=1");
        }
    }

    static void upgradeInventory(Connection c)throws SQLException {
        try(var s=c.createStatement()) {
            s.execute("CREATE TABLE inventory_new(item_id INTEGER PRIMARY KEY CHECK(item_id>0),world_id INTEGER NOT NULL REFERENCES world_characters(world_id) ON DELETE CASCADE,slot TEXT NOT NULL CHECK(slot IN('HELM','ARMOR','SWORD','GUN','SHIELD','BOMB')),config TEXT NOT NULL,accessory TEXT,heat INTEGER NOT NULL DEFAULT 0 CHECK(heat>=0),equipped_slot INTEGER CHECK(equipped_slot IN(0,1,2,6,7)),bound INTEGER NOT NULL DEFAULT 0 CHECK(bound IN(0,1)),bind_on_equip INTEGER NOT NULL DEFAULT 1 CHECK(bind_on_equip IN(0,1)),starter INTEGER NOT NULL DEFAULT 0 CHECK(starter IN(0,1)))");
            s.execute("INSERT INTO inventory_new(item_id,world_id,slot,config,accessory,heat,equipped_slot,bound,starter) SELECT item_id,world_id,slot,config,accessory,heat,equipped_slot,bound,1 FROM inventory_items");
            s.execute("DROP TABLE inventory_items");s.execute("ALTER TABLE inventory_new RENAME TO inventory_items");
            s.execute("CREATE UNIQUE INDEX equipped_items ON inventory_items(world_id,equipped_slot) WHERE equipped_slot IS NOT NULL");
            s.execute("CREATE UNIQUE INDEX starter_items ON inventory_items(world_id,slot) WHERE starter=1");
            s.execute("CREATE TABLE item_events(id INTEGER PRIMARY KEY,world_id INTEGER NOT NULL REFERENCES world_characters(world_id) ON DELETE CASCADE,actor_account INTEGER,config TEXT NOT NULL,quantity INTEGER NOT NULL,first_item_id INTEGER NOT NULL,created_at INTEGER NOT NULL)");
        }
    }

    static void upgradePlayerItems(Connection c)throws SQLException {
        try(var s=c.createStatement()) {
            s.execute("CREATE TABLE inventory_player(item_id INTEGER PRIMARY KEY CHECK(item_id>0),world_id INTEGER NOT NULL REFERENCES world_characters(world_id) ON DELETE CASCADE,slot TEXT NOT NULL CHECK(slot IN('HELM','ARMOR','SWORD','GUN','SHIELD','BOMB','HELM_COSTUME','ARMOR_COSTUME','SHIELD_COSTUME','TRINKET','OTHER')),config TEXT NOT NULL,accessory TEXT,heat INTEGER NOT NULL DEFAULT 0 CHECK(heat>=0),equipped_slot INTEGER CHECK(equipped_slot IN(0,1,2,3,4,6,7,10,11,15)),bound INTEGER NOT NULL DEFAULT 0 CHECK(bound IN(0,1)),bind_on_equip INTEGER NOT NULL DEFAULT 1 CHECK(bind_on_equip IN(0,1)),starter INTEGER NOT NULL DEFAULT 0 CHECK(starter IN(0,1)),count INTEGER NOT NULL DEFAULT 1 CHECK(count BETWEEN 1 AND 9999),permanent INTEGER NOT NULL DEFAULT 0 CHECK(permanent IN(0,1)),CHECK(slot!='OTHER' OR equipped_slot IS NULL),CHECK(slot='OTHER' OR count=1),CHECK(permanent=0 OR bound=1))");
            s.execute("INSERT INTO inventory_player(item_id,world_id,slot,config,accessory,heat,equipped_slot,bound,bind_on_equip,starter) SELECT item_id,world_id,slot,config,accessory,heat,equipped_slot,bound,bind_on_equip,starter FROM inventory_items");
            s.execute("DROP TABLE inventory_items");s.execute("ALTER TABLE inventory_player RENAME TO inventory_items");
            s.execute("CREATE UNIQUE INDEX equipped_items ON inventory_items(world_id,equipped_slot) WHERE equipped_slot IS NOT NULL");
            s.execute("CREATE UNIQUE INDEX starter_items ON inventory_items(world_id,slot) WHERE starter=1");
        }
    }

    /** Creator edits replace chosen cosmetics, preserving IDs, heat and the rest of the kit. */
    static void appearance(Connection c,int accountId,Knight knight) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE inventory_items SET config=?,accessory=? WHERE world_id=(SELECT world_id FROM world_characters WHERE account_id=? AND knight_id=?) AND slot=? AND starter=1")) {
            for (Slot slot : List.of(Slot.HELM,Slot.ARMOR)) {
                s.setString(1,slot==Slot.HELM ? knight.helm() : knight.armor());
                s.setString(2,slot==Slot.HELM ? knight.helmAccessory() : knight.armorAccessory());
                s.setInt(3,accountId); s.setInt(4,knight.id()); s.setString(5,slot.name()); s.executeUpdate();
            }
        }
    }

    private static void add(Connection c,int worldId,Slot slot,String config,String accessory) throws SQLException, IOException {
        long itemId = allocate(c,"next-item-id",Long.MAX_VALUE);
        try (PreparedStatement s = c.prepareStatement("INSERT INTO inventory_items(item_id,world_id,slot,config,accessory) VALUES(?,?,?,?,?)")) {
            s.setLong(1,itemId); s.setInt(2,worldId); s.setString(3,slot.name()); s.setString(4,config); s.setString(5,accessory); s.executeUpdate();
        }
    }

    static long allocate(Connection c,String key,long limit) throws SQLException, IOException {
        long id;
        try (PreparedStatement s = c.prepareStatement("SELECT value FROM metadata WHERE key=?")) {
            s.setString(1,key); try (ResultSet r = s.executeQuery()) {
                if (!r.next() || (id = r.getLong(1)) < 1 || id >= limit) throw new IOException("World identity space exhausted or invalid counter");
            }
        }
        try (PreparedStatement s = c.prepareStatement("UPDATE metadata SET value=? WHERE key=?")) { s.setLong(1,id+1); s.setString(2,key); s.executeUpdate(); }
        return id;
    }

    static WorldProfile load(Connection c,int accountId,int knightId,long authRevision) throws SQLException, IOException {
        int worldId, crowns, energy; long revision;
        try (PreparedStatement s = c.prepareStatement("SELECT w.world_id,w.crowns,w.energy,w.state_revision FROM world_characters w JOIN accounts a ON a.id=w.account_id WHERE w.account_id=? AND w.knight_id=?" + (authRevision>=0 ? " AND a.auth_revision=?" : ""))) {
            s.setInt(1,accountId); s.setInt(2,knightId); if (authRevision>=0) s.setLong(3,authRevision);
            try (ResultSet r = s.executeQuery()) { if (!r.next()) throw new IOException("No such knight or account changed; reconnect"); worldId=r.getInt(1); crowns=r.getInt(2); energy=r.getInt(3); revision=r.getLong(4); }
        }
        List<WorldProfile.Item> items = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("SELECT * FROM inventory_items WHERE world_id=? ORDER BY item_id")) {
            s.setInt(1,worldId); try (ResultSet r = s.executeQuery()) {
                while (r.next()) items.add(new WorldProfile.Item(r.getLong("item_id"),Slot.valueOf(r.getString("slot")),r.getString("config"),r.getString("accessory"),r.getInt("heat"),(Integer)r.getObject("equipped_slot"),r.getBoolean("bound"),r.getBoolean("bind_on_equip"),r.getBoolean("starter"),r.getInt("count"),r.getBoolean("permanent")));
            }
        }
        if (items.stream().filter(WorldProfile.Item::starter).count()!=5) throw new IOException("Incomplete starter inventory; restore the saved database");
        return new WorldProfile(worldId,accountId,knightId,crowns,energy,revision,items);
    }
}
