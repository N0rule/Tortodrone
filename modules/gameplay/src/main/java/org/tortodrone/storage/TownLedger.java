package org.tortodrone.storage;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import org.tortodrone.core.Json;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.character.WorldProfile;

/** The town inventory and currency ledger shared by every town mutation. */
final class TownLedger {
    private TownLedger() {}

    /** Bump the world revision so readers observe the committed change. */
    static void touch(Connection c, int world) throws SQLException {
        try (var s =
                c.prepareStatement("UPDATE world_characters SET state_revision=state_revision+1 WHERE world_id=?")) {
            s.setInt(1, world);
            if (s.executeUpdate() != 1) throw new SQLException("Missing Town owner");
        }
    }

    static WorldProfile.Item owned(WorldProfile p, long id) throws IOException {
        return p.items().stream()
                .filter(i -> i.id() == id)
                .findFirst()
                .orElseThrow(() -> new IOException("m.internal_error"));
    }

    static void consume(Connection c, WorldProfile p, long id, int count) throws SQLException, IOException {
        var i = owned(p, id);
        if (count < 1 || count > i.count() || i.equippedSlot() != null || i.permanent())
            throw new IOException("m.internal_error");
        if (i.starter())
            try (var s = c.prepareStatement("INSERT INTO town_consumed_starters(world_id,slot) VALUES(?,?)")) {
                s.setInt(1, p.worldId());
                s.setString(2, i.slot().name());
                s.executeUpdate();
            }
        try (var s = c.prepareStatement(
                count == i.count()
                        ? "DELETE FROM inventory_items WHERE world_id=? AND item_id=?"
                        : "UPDATE inventory_items SET count=count-" + count + " WHERE world_id=? AND item_id=?")) {
            s.setInt(1, p.worldId());
            s.setLong(2, id);
            if (s.executeUpdate() != 1) throw new IOException("Item changed");
        }
    }

    static void pay(Connection c, WorldProfile p, int crowns, int energy) throws SQLException, IOException {
        if (crowns < 0 || energy < 0) throw new IOException("Invalid purchase price");
        if (p.crowns() < crowns) throw new Database.Refused("m.nsf_crowns");
        if (p.energy() < energy) throw new Database.Refused("m.nsf_energy");
        credit(c, p.worldId(), -crowns, -energy);
    }

    static void credit(Connection c, int world, int crowns, int energy) throws SQLException, IOException {
        try (var s = c.prepareStatement(
                "UPDATE world_characters SET crowns=crowns+?,energy=energy+?,state_revision=state_revision+1 WHERE world_id=? AND crowns+? BETWEEN 0 AND 2147483647 AND energy+? BETWEEN 0 AND 2147483647")) {
            s.setInt(1, crowns);
            s.setInt(2, energy);
            s.setInt(3, world);
            s.setInt(4, crowns);
            s.setInt(5, energy);
            if (s.executeUpdate() != 1) throw new IOException("Currency balance exceeds supported limits");
        }
        for (var delta : Map.of("crowns", crowns, "energy", energy).entrySet())
            if (delta.getValue() != 0)
                try (var s = c.prepareStatement(
                        "INSERT INTO currency_events(world_id,currency,delta,balance,created_at) SELECT world_id,?,?,"
                                + delta.getKey() + ",? FROM world_characters WHERE world_id=?")) {
                    s.setString(1, delta.getKey());
                    s.setInt(2, delta.getValue());
                    s.setLong(3, System.currentTimeMillis());
                    s.setInt(4, world);
                    s.executeUpdate();
                }
    }

    static long create(
            Connection c,
            WorldProfile p,
            ItemCatalog items,
            String config,
            int count,
            boolean locked,
            int level,
            float heat,
            List<StarterCatalog.Model> variants)
            throws SQLException, IOException {
        return create(c, p, items, config, count, locked, level, heat, variants, true);
    }

    static long create(
            Connection c,
            WorldProfile p,
            ItemCatalog items,
            String config,
            int count,
            boolean locked,
            int level,
            float heat,
            List<StarterCatalog.Model> variants,
            boolean merge)
            throws SQLException, IOException {
        var def = items.require(config);
        if (count < 1 || count > 9999) throw new IOException("Invalid item count");
        Long stack = null;
        if (merge
                && def.stackable()
                && def.expireDays() == 0
                && def.slot() == WorldProfile.Slot.OTHER
                && level == 0
                && heat == 0
                && variants.isEmpty()) {
            var current = WorldProfiles.load(c, p.accountId(), p.knightId(), -1);
            stack = current.items().stream()
                    .filter(i -> i.config().equals(config)
                            && i.slot() == def.slot()
                            && i.bound() == (locked || def.locked())
                            && i.bindOnEquip() == def.bindOnEquip()
                            && i.permanent() == def.permanent()
                            && !i.starter()
                            && !i.cursed()
                            && i.equippedSlot() == null
                            && i.expiresAt() == null
                            && i.gift() == null
                            && i.heat() == 0
                            && i.level() == 0
                            && i.heatProgress() == 0
                            && i.variants().isEmpty()
                            && i.accessory() == null
                            && i.additionalAccessories().isEmpty()
                            && i.count() + count <= 9999)
                    .map(WorldProfile.Item::id)
                    .findFirst()
                    .orElse(null);
        }
        if (stack != null) {
            try (var s =
                    c.prepareStatement("UPDATE inventory_items SET count=count+? WHERE world_id=? AND item_id=?")) {
                s.setInt(1, count);
                s.setInt(2, p.worldId());
                s.setLong(3, stack);
                if (s.executeUpdate() != 1) throw new IOException("Item changed");
            }
            try (var s = c.prepareStatement(
                    "INSERT INTO item_events(world_id,config,quantity,first_item_id,created_at) VALUES(?,?,?,?,?)")) {
                s.setInt(1, p.worldId());
                s.setString(2, config);
                s.setInt(3, count);
                s.setLong(4, stack);
                s.setLong(5, System.currentTimeMillis());
                s.executeUpdate();
            }
            return stack;
        }
        long first = 0;
        for (int n = 0; n < (def.stackable() ? 1 : count); n++) {
            long id = WorldProfiles.allocate(c, "next-item-id", Long.MAX_VALUE);
            if (first == 0) first = id;
            try (var s = c.prepareStatement(
                    "INSERT INTO inventory_items(item_id,world_id,slot,config,count,bound,bind_on_equip,permanent,level,heat_progress,variants) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
                s.setLong(1, id);
                s.setInt(2, p.worldId());
                s.setString(3, def.slot().persistedName());
                s.setString(4, config);
                s.setInt(5, def.stackable() ? count : 1);
                s.setBoolean(6, locked || def.locked());
                s.setBoolean(7, def.bindOnEquip());
                s.setBoolean(8, def.permanent());
                s.setInt(9, level);
                s.setFloat(10, heat);
                s.setString(
                        11,
                        Json.stringify(variants.stream()
                                .map(StarterCatalog.Model::name)
                                .toList()));
                s.executeUpdate();
            }
            InventoryExpiry.save(c, id, InventoryExpiry.deadline(def.expireDays()));
            try (var s = c.prepareStatement(
                    "INSERT INTO item_events(world_id,config,quantity,first_item_id,created_at) VALUES(?,?,?,?,?)")) {
                s.setInt(1, p.worldId());
                s.setString(2, config);
                s.setInt(3, def.stackable() ? count : 1);
                s.setLong(4, id);
                s.setLong(5, System.currentTimeMillis());
                s.executeUpdate();
            }
        }
        return first;
    }
}
