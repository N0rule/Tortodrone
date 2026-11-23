package org.tortodrone.storage;

import java.sql.Connection;
import java.sql.SQLException;

/** Mail campaign and welcome-letter tables for the 0.42.0 baseline. */
final class MailCampaignSchema {
    private MailCampaignSchema() { }

    static void initialize(Connection c) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS mail_campaigns("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "created INTEGER NOT NULL,updated INTEGER NOT NULL,"
                + "starts INTEGER NOT NULL,ends INTEGER NOT NULL,"
                + "enabled INTEGER NOT NULL DEFAULT 1 CHECK(enabled IN(0,1)),"
                + "sender INTEGER NOT NULL,sender_label TEXT,subject TEXT NOT NULL,body TEXT NOT NULL,"
                + "crowns INTEGER NOT NULL DEFAULT 0 CHECK(crowns>=0),"
                + "energy INTEGER NOT NULL DEFAULT 0 CHECK(energy>=0),"
                + "gifts TEXT NOT NULL,CHECK(ends>starts)) STRICT");
            s.execute("CREATE TABLE IF NOT EXISTS mail_campaign_deliveries("
                + "campaign INTEGER NOT NULL REFERENCES mail_campaigns(id) ON DELETE CASCADE,"
                + "world_id INTEGER NOT NULL REFERENCES world_characters(world_id) ON DELETE CASCADE,"
                + "sent INTEGER NOT NULL,PRIMARY KEY(campaign,world_id)) STRICT");
            s.execute("CREATE TABLE IF NOT EXISTS mail_welcome("
                + "id INTEGER PRIMARY KEY CHECK(id=1),"
                + "enabled INTEGER NOT NULL DEFAULT 0 CHECK(enabled IN(0,1)),"
                + "enabled_at INTEGER NOT NULL DEFAULT 0,"
                + "disabled_at INTEGER NOT NULL DEFAULT 0,"
                + "sender INTEGER NOT NULL DEFAULT 6,sender_label TEXT,"
                + "subject TEXT NOT NULL DEFAULT '',body TEXT NOT NULL DEFAULT '',"
                + "crowns INTEGER NOT NULL DEFAULT 0,energy INTEGER NOT NULL DEFAULT 0,"
                + "gifts TEXT NOT NULL DEFAULT '[]',updated INTEGER NOT NULL DEFAULT 0) STRICT");
            s.execute("CREATE TABLE IF NOT EXISTS mail_welcome_deliveries("
                + "world_id INTEGER PRIMARY KEY REFERENCES world_characters(world_id) ON DELETE CASCADE,"
                + "sent INTEGER NOT NULL) STRICT");
        }
        boolean townMail;
        try (var r = c.createStatement().executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='town_mail'")) {
            townMail = r.next();
        }
        if (townMail) try (var r = c.createStatement().executeQuery("SELECT 1 FROM pragma_table_info('town_mail') WHERE name='sender_label'")) {
            if (!r.next()) try (var s = c.createStatement()) {
                s.execute("ALTER TABLE town_mail ADD COLUMN sender_label TEXT");
            }
        }
    }
}
