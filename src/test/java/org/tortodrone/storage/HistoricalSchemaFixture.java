package org.tortodrone.storage;

import java.nio.file.*;
import java.sql.*;

/**
 * Test-only materialization of a representative pre-0.42.0 database.
 *
 * <p>The fixtures contain real tables and rows, not a bare version stamp. Newer tables and
 * columns are removed per known release boundary so each refused version looks like the
 * deployment that wrote it. They intentionally carry no {@code application_id}.
 */
final class HistoricalSchemaFixture {
    private HistoricalSchemaFixture() { }

    static final int[] VERSIONS = {9, 10, 11, 12, 13, 14, 15, 16, 17, 18};

    static Path create(Path directory, int version) throws Exception {
        if (version < 9 || version > 18) throw new IllegalArgumentException("Not a pre-0.42.0 version: " + version);
        Files.createDirectories(directory);
        Path file = directory.resolve("tortodrone.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement s = c.createStatement()) {
            try (var pragma = s.executeQuery("PRAGMA journal_mode=WAL")) { pragma.next(); }
            SchemaMigrations.baseline(c);
            s.execute("DELETE FROM schema_migrations");
            seed(s);
            if (version < 18) {
                s.execute("DROP TABLE guild_expansions");
                s.execute("DROP TABLE guild_log");
                s.execute("DROP TABLE guild_members");
                s.execute("DROP TABLE guilds");
                s.execute("ALTER TABLE town_mail DROP COLUMN guild_invite_name");
                s.execute("ALTER TABLE town_mail DROP COLUMN guild_invite_id");
            }
            if (version < 17) {
                s.execute("DROP TABLE mail_welcome_deliveries");
                s.execute("DROP TABLE mail_welcome");
                s.execute("DROP TABLE mail_campaign_deliveries");
                s.execute("DROP TABLE mail_campaigns");
                s.execute("ALTER TABLE town_mail DROP COLUMN sender_label");
            }
            if (version < 16) {
                s.execute("ALTER TABLE town_mail DROP COLUMN attachments");
                s.execute("ALTER TABLE town_mail DROP COLUMN mail_type");
                s.execute("ALTER TABLE town_mail DROP COLUMN energy");
                s.execute("ALTER TABLE town_mail DROP COLUMN sender");
            }
            if (version < 15) {
                s.execute("DROP TABLE town_depot_promotions");
                s.execute("ALTER TABLE town_depot_offers DROP COLUMN promotion_only");
                s.execute("ALTER TABLE town_auctions DROP COLUMN available_at");
                s.execute("DELETE FROM metadata WHERE key='supply-depot-defaults'");
            }
            if (version < 14) s.execute("ALTER TABLE knights DROP COLUMN last_renamed");
            if (version < 13) s.execute("DROP TABLE town_gifts");
            if (version < 12) {
                s.execute("DROP TABLE town_energy_offers");
                s.execute("DELETE FROM metadata WHERE key LIKE 'energy-exchange-%'");
            }
            if (version < 11) {
                s.execute("DROP TABLE town_depot_offers");
                s.execute("DELETE FROM metadata WHERE key LIKE 'supply-depot-%'");
            }
            s.execute("PRAGMA application_id=0");
            s.execute("PRAGMA user_version=" + version);
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement s = c.createStatement()) {
            s.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }
        return file;
    }

    private static void seed(Statement s) throws SQLException {
        s.execute("INSERT INTO accounts(id,username,name_key,salt,verifier,game_master,auth_revision,revision,created_at,next_knight_id) "
            + "VALUES(1,'Alice','alice',zeroblob(16),zeroblob(32),0,1,1,1000,2)");
        s.execute("INSERT INTO knights(account_id,id,name,name_key,helm,armor,helm_accessory,armor_accessory,costume,color,height,eye,created_at) "
            + "VALUES(1,1,'Alice-Knight','alice-knight','Gear/Helm/Spiral Sallet (Blue)','Gear/Armor/Spiral Culet (Blue)',NULL,NULL,'Blue',0,2,0,1000)");
        s.execute("INSERT INTO world_characters(world_id,account_id,knight_id,crowns,energy,state_revision) VALUES(1,1,1,4321,17,3)");
        s.execute("INSERT INTO inventory_items(item_id,world_id,slot,config,accessory,heat,equipped_slot,bound,bind_on_equip,starter,count,permanent,level,heat_progress,accessories,variants) "
            + "VALUES(1,1,'HELM','Gear/Helm/Spiral Sallet (Blue)',NULL,0,0,1,1,1,1,0,0,0,'[]','[]')");
        s.execute("INSERT INTO town_purchases(world_id,catalog,item,cycle,purchased) VALUES(1,'Supply Depot','Material/Gel Drop',0,2)");
    }
}
