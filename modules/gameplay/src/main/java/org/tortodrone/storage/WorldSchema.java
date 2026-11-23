package org.tortodrone.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * World and inventory tables, indexes and identity counters for the 0.42.0 baseline.
 *
 * <p>{@link WorldProfiles} keeps starter seeding, identity allocation, profile reassembly and
 * retention cleanup. This class owns only the durable table layout and the counter seed those
 * runtime paths read.
 */
final class WorldSchema {
    private WorldSchema() { }

    static void initialize(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE world_characters(world_id INTEGER PRIMARY KEY CHECK(world_id>0 AND world_id<2147483647), account_id INTEGER NOT NULL, knight_id INTEGER NOT NULL, crowns INTEGER NOT NULL DEFAULT 0 CHECK(crowns BETWEEN 0 AND 2147483647), energy INTEGER NOT NULL DEFAULT 0 CHECK(energy BETWEEN 0 AND 2147483647), state_revision INTEGER NOT NULL DEFAULT 1 CHECK(state_revision>0), UNIQUE(account_id,knight_id), FOREIGN KEY(account_id,knight_id) REFERENCES knights(account_id,id) ON DELETE CASCADE) STRICT");
            s.execute("CREATE TABLE inventory_items(item_id INTEGER PRIMARY KEY CHECK(item_id>0),world_id INTEGER NOT NULL REFERENCES world_characters(world_id) ON DELETE CASCADE,slot TEXT NOT NULL CHECK(slot IN('HELM','ARMOR','SWORD','GUN','SHIELD','BOMB','HELM_COSTUME','ARMOR_COSTUME','SHIELD_COSTUME','TRINKET','OTHER')),config TEXT NOT NULL,accessory TEXT,heat INTEGER NOT NULL DEFAULT 0 CHECK(heat>=0),equipped_slot INTEGER CHECK(equipped_slot IN(0,1,2,3,4,6,7,10,11,15)),bound INTEGER NOT NULL DEFAULT 0 CHECK(bound IN(0,1)),bind_on_equip INTEGER NOT NULL DEFAULT 1 CHECK(bind_on_equip IN(0,1)),starter INTEGER NOT NULL DEFAULT 0 CHECK(starter IN(0,1)),count INTEGER NOT NULL DEFAULT 1 CHECK(count BETWEEN 1 AND 9999),permanent INTEGER NOT NULL DEFAULT 0 CHECK(permanent IN(0,1)),CHECK(slot!='OTHER' OR equipped_slot IS NULL),CHECK(slot='OTHER' OR count=1),CHECK(permanent=0 OR bound=1)) STRICT");
            s.execute("CREATE UNIQUE INDEX equipped_items ON inventory_items(world_id,equipped_slot) WHERE equipped_slot IS NOT NULL");
            s.execute("CREATE UNIQUE INDEX starter_items ON inventory_items(world_id,slot) WHERE starter=1");
            s.execute("CREATE TABLE currency_events(id INTEGER PRIMARY KEY,world_id INTEGER NOT NULL,currency TEXT NOT NULL CHECK(currency IN('crowns','energy')),delta INTEGER NOT NULL,balance INTEGER NOT NULL,created_at INTEGER NOT NULL) STRICT");
            s.execute("CREATE TABLE item_events(id INTEGER PRIMARY KEY,world_id INTEGER NOT NULL REFERENCES world_characters(world_id) ON DELETE CASCADE,actor_account INTEGER,config TEXT NOT NULL,quantity INTEGER NOT NULL,first_item_id INTEGER NOT NULL,created_at INTEGER NOT NULL) STRICT");
            s.execute("INSERT INTO metadata VALUES('next-world-id',1),('next-item-id',1)");
        }
    }
}
