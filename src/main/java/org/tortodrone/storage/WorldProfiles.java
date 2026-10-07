package org.tortodrone.storage;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import org.tortodrone.character.Knight;
import org.tortodrone.character.WorldProfile;
import org.tortodrone.character.WorldProfile.Slot;

/** Schema v2 character foundation. Called inside the same transaction as its roster mutation. */
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

    static void ensure(Connection c, int accountId, Knight knight) throws SQLException, IOException {
        try (PreparedStatement s = c.prepareStatement("SELECT world_id FROM world_characters WHERE account_id=? AND knight_id=?")) {
            s.setInt(1,accountId); s.setInt(2,knight.id());
            try (ResultSet r = s.executeQuery()) { if (r.next()) return; }
        }
        int worldId = Math.toIntExact(allocate(c,"next-world-id",Integer.MAX_VALUE));
        try (PreparedStatement s = c.prepareStatement("INSERT INTO world_characters VALUES(?,?,?,1)")) {
            s.setInt(1,worldId); s.setInt(2,accountId); s.setInt(3,knight.id()); s.executeUpdate();
        }
        add(c,worldId,Slot.HELM,knight.helm(),knight.helmAccessory());
        add(c,worldId,Slot.ARMOR,knight.armor(),knight.armorAccessory());
        add(c,worldId,Slot.SWORD,"Weapon/Sword/Proto Sword",null);
        add(c,worldId,Slot.GUN,"Weapon/Handgun/Proto Gun",null);
        add(c,worldId,Slot.SHIELD,"Gear/Shield/Proto Shield",null);
    }

    /** Creator edits replace chosen cosmetics, preserving IDs, heat and the rest of the kit. */
    static void appearance(Connection c,int accountId,Knight knight) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE inventory_items SET config=?,accessory=? WHERE world_id=(SELECT world_id FROM world_characters WHERE account_id=? AND knight_id=?) AND slot=?")) {
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

    private static long allocate(Connection c,String key,long limit) throws SQLException, IOException {
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
        int worldId;
        try (PreparedStatement s = c.prepareStatement("SELECT w.world_id FROM world_characters w JOIN accounts a ON a.id=w.account_id WHERE w.account_id=? AND w.knight_id=?" + (authRevision>=0 ? " AND a.auth_revision=?" : ""))) {
            s.setInt(1,accountId); s.setInt(2,knightId); if (authRevision>=0) s.setLong(3,authRevision);
            try (ResultSet r = s.executeQuery()) { if (!r.next()) throw new IOException("No such knight or account changed; reconnect"); worldId=r.getInt(1); }
        }
        List<WorldProfile.Item> items = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("SELECT * FROM inventory_items WHERE world_id=? ORDER BY item_id")) {
            s.setInt(1,worldId); try (ResultSet r = s.executeQuery()) {
                while (r.next()) items.add(new WorldProfile.Item(r.getLong("item_id"),Slot.valueOf(r.getString("slot")),r.getString("config"),r.getString("accessory"),r.getInt("heat")));
            }
        }
        if (items.size()!=Slot.values().length) throw new IOException("Incomplete starter inventory; restore the saved database");
        return new WorldProfile(worldId,accountId,knightId,items);
    }
}
