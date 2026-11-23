package org.tortodrone.storage;

import java.sql.Connection;
import java.sql.SQLException;

/** Town-owned tables and columns; created once beside the baseline and guarded on reopen. */
final class TownSchema {
    private TownSchema() { }

    static void initialize(Connection c) throws SQLException {
        GiftStore.initialize(c);
        DepotStore.initialize(c);
        EnergyExchange.initialize(c);
        try (var s = c.createStatement()) {
            s.execute("ALTER TABLE inventory_items ADD COLUMN level INTEGER NOT NULL DEFAULT 0 CHECK(level BETWEEN 0 AND 99)");
            s.execute("ALTER TABLE inventory_items ADD COLUMN heat_progress REAL NOT NULL DEFAULT 0 CHECK(heat_progress BETWEEN 0 AND 1)");
            s.execute("ALTER TABLE inventory_items ADD COLUMN accessories TEXT NOT NULL DEFAULT '[]'");
            s.execute("ALTER TABLE inventory_items ADD COLUMN variants TEXT NOT NULL DEFAULT '[]'");
            s.execute("CREATE TABLE town_consumed_starters(world_id INTEGER NOT NULL REFERENCES world_characters(world_id) ON DELETE CASCADE,slot TEXT NOT NULL,PRIMARY KEY(world_id,slot)) STRICT");
            s.execute("CREATE TABLE town_purchases(world_id INTEGER NOT NULL REFERENCES world_characters(world_id) ON DELETE CASCADE,catalog TEXT NOT NULL,item TEXT NOT NULL,cycle INTEGER NOT NULL,purchased INTEGER NOT NULL,PRIMARY KEY(world_id,catalog,item,cycle)) STRICT");
            s.execute("CREATE TABLE town_recipes(id INTEGER PRIMARY KEY AUTOINCREMENT,world_id INTEGER NOT NULL REFERENCES world_characters(world_id) ON DELETE CASCADE,config TEXT NOT NULL,crafted INTEGER NOT NULL DEFAULT 0,expires INTEGER NOT NULL DEFAULT 0,UNIQUE(world_id,config)) STRICT");
            s.execute("CREATE TABLE town_sprites(id INTEGER PRIMARY KEY AUTOINCREMENT,world_id INTEGER NOT NULL REFERENCES world_characters(world_id) ON DELETE CASCADE,config TEXT NOT NULL,name TEXT,level INTEGER NOT NULL DEFAULT 0,heat REAL NOT NULL DEFAULT 0,last_full INTEGER,full_stamp INTEGER NOT NULL DEFAULT 0,perk_level INTEGER NOT NULL DEFAULT 0,perks TEXT NOT NULL DEFAULT '[]',perk INTEGER,powers TEXT NOT NULL DEFAULT '[0,0,0,0,0,0,0,0,0]',ultimates TEXT NOT NULL DEFAULT '[0,0,0]',harness INTEGER NOT NULL DEFAULT 0) STRICT");
            s.execute("CREATE TABLE town_harnesses(id INTEGER PRIMARY KEY AUTOINCREMENT,world_id INTEGER NOT NULL REFERENCES world_characters(world_id) ON DELETE CASCADE,config TEXT NOT NULL) STRICT");
            s.execute("CREATE TABLE town_equipped(world_id INTEGER PRIMARY KEY REFERENCES world_characters(world_id) ON DELETE CASCADE,sprite INTEGER NOT NULL DEFAULT 0) STRICT");
            AuctionSchema.create(c);
            MailCampaignStore.initialize(c);
            s.execute("CREATE TABLE town_mail(id INTEGER PRIMARY KEY AUTOINCREMENT,world_id INTEGER NOT NULL REFERENCES world_characters(world_id) ON DELETE CASCADE,sent INTEGER NOT NULL,subject TEXT NOT NULL,body TEXT NOT NULL,crowns INTEGER NOT NULL DEFAULT 0,item TEXT,claimed INTEGER NOT NULL DEFAULT 0,is_read INTEGER NOT NULL DEFAULT 0,auction INTEGER REFERENCES town_auctions(id),sender INTEGER,sender_label TEXT,energy INTEGER NOT NULL DEFAULT 0 CHECK(energy BETWEEN 0 AND 2147483647),attachments TEXT,mail_type INTEGER NOT NULL DEFAULT 3 CHECK(mail_type BETWEEN 0 AND 4),guild_invite_id INTEGER NOT NULL DEFAULT 0,guild_invite_name TEXT) STRICT");
            s.execute("CREATE TABLE town_auction_bids(auction INTEGER NOT NULL REFERENCES town_auctions(id),world_id INTEGER NOT NULL REFERENCES world_characters(world_id),watching INTEGER NOT NULL DEFAULT 0,has_bid INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(auction,world_id)) STRICT");
            knightRenameClock(c);
            guildMailColumns(c);
        }
    }

    /** Adds the guild invitation carrier; idempotent for fresh and upgraded databases. */
    static void guildMailColumns(Connection c) throws SQLException {
        for (String[] column : new String[][]{{"guild_invite_id", "INTEGER NOT NULL DEFAULT 0"}, {"guild_invite_name", "TEXT"}}) {
            try (var check = c.createStatement(); var r = check.executeQuery("SELECT 1 FROM pragma_table_info('town_mail') WHERE name='" + column[0] + "'")) {
                if (r.next()) continue;
            }
            try (var s = c.createStatement()) { s.execute("ALTER TABLE town_mail ADD COLUMN " + column[0] + " " + column[1]); }
        }
    }

    /** Stored thirty-day rename clock; 0 means the knight has never been renamed. The
     *  baseline adds the column once, so the create and migrate paths share one guard. */
    static void knightRenameClock(Connection c) throws SQLException {
        try (var r = c.createStatement().executeQuery("SELECT 1 FROM pragma_table_info('knights') WHERE name='last_renamed'")) {
            if (r.next()) return;
        }
        try (var s = c.prepareStatement("ALTER TABLE knights ADD COLUMN last_renamed INTEGER NOT NULL DEFAULT 0")) { s.execute(); }
    }
}
