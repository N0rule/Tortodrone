package org.tortodrone.storage;

import java.io.IOException;
import java.sql.*;

/**
 * Ordered schema steps from the 0.42.0 baseline.
 *
 * <p>The baseline is schema 1. Earlier releases used schema versions up to 18 and are not
 * converted: a database without the project {@code application_id} is refused untouched.
 * Every later change is a numbered step that runs in one transaction and is recorded in
 * {@code schema_migrations}; an unknown or newer version is refused rather than guessed.
 */
final class SchemaMigrations {
    /** Fixed project marker written to {@code PRAGMA application_id}; earlier releases wrote 0. */
    static final int APPLICATION_ID = 0x5444524E; // TDRN
    static final int BASELINE = 1;
    static final int LATEST = 4;

    @FunctionalInterface interface Step { void apply(Connection connection) throws SQLException, IOException; }
    record Migration(int version, String description, Step step) { }

    /** Ordered steps above the baseline. Add one entry per released schema change. */
    static final java.util.List<Migration> ALL = java.util.List.of(
        new Migration(2,"lookup indexes",SchemaMigrations::lookupIndexes),
        new Migration(3,"strict tables",SchemaMigrations::strictTables),
        new Migration(4,"foreign-key indexes",SchemaMigrations::foreignKeyIndexes));

    /** Every foreign-key column and hot lookup that previously scanned its table. */
    private static void lookupIndexes(Connection connection)throws SQLException {
        try(Statement statement=connection.createStatement()){
            statement.execute("CREATE INDEX IF NOT EXISTS inventory_items_world ON inventory_items(world_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS town_mail_world ON town_mail(world_id,sent)");
            statement.execute("CREATE INDEX IF NOT EXISTS town_mail_auction ON town_mail(auction)");
            statement.execute("CREATE INDEX IF NOT EXISTS town_sprites_world ON town_sprites(world_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS town_harnesses_world ON town_harnesses(world_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS currency_events_world ON currency_events(world_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS item_events_world ON item_events(world_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS account_events_account ON account_events(account_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS town_auction_bids_world ON town_auction_bids(world_id)");
        }
    }

    /** Child-side foreign keys whose tables still lacked a usable index. */
    private static void foreignKeyIndexes(Connection connection)throws SQLException {
        try(Statement statement=connection.createStatement()){
            statement.execute("CREATE INDEX IF NOT EXISTS town_auctions_seller ON town_auctions(seller)");
            statement.execute("CREATE INDEX IF NOT EXISTS town_auctions_bidder ON town_auctions(bidder)");
            statement.execute("CREATE INDEX IF NOT EXISTS mail_campaign_deliveries_world ON mail_campaign_deliveries(world_id)");
        }
    }

    /**
     * Rebuild every non-STRICT table with SQLite's documented table-replacement procedure.
     *
     * <p>A table cannot be altered to STRICT in place. Each table is copied into a temporary
     * STRICT twin, the original is dropped, the twin is renamed into its place, then its
     * explicit indexes, triggers and AUTOINCREMENT high-water mark are restored. The caller
     * turns foreign keys off for the transaction and validates with {@code foreign_key_check}
     * before committing; no other table ever references the temporary name.
     */
    static void strictTables(Connection connection)throws SQLException {
        var names=new java.util.ArrayList<String>();
        var tables=new java.util.LinkedHashMap<String,String>();
        var indexes=new java.util.LinkedHashMap<String,java.util.List<String>>();
        var triggers=new java.util.LinkedHashMap<String,java.util.List<String>>();
        var sequences=new java.util.LinkedHashMap<String,Long>();
        try(var statement=connection.createStatement();
            var rows=statement.executeQuery("SELECT name,sql FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")){
            while(rows.next()){
                var name=rows.getString(1);var sql=rows.getString(2);
                if(sql!=null&&!strict(sql)){names.add(name);tables.put(name,sql);}
            }
        }
        for(var name:names){
            indexes.put(name,objects(connection,"index",name));
            triggers.put(name,objects(connection,"trigger",name));
            if(tables.get(name).toUpperCase(java.util.Locale.ROOT).contains("AUTOINCREMENT")){
                try(var sequence=connection.prepareStatement("SELECT seq FROM sqlite_sequence WHERE name=?")){
                    sequence.setString(1,name);
                    try(var value=sequence.executeQuery()){if(value.next())sequences.put(name,value.getLong(1));}
                }
            }
        }
        for(var table:tables.entrySet()){
            var name=table.getKey();var temporary=name+"__strict";
            var create=replaceTableName(table.getValue(),name,temporary)+" STRICT";
            try(var statement=connection.createStatement()){
                statement.execute(create);
                statement.execute("INSERT INTO "+temporary+" SELECT * FROM "+name);
                statement.execute("DROP TABLE "+name);
                statement.execute("ALTER TABLE "+temporary+" RENAME TO "+name);
                for(var index:indexes.get(name))statement.execute(index);
                for(var trigger:triggers.get(name))statement.execute(trigger);
            }
            restoreSequence(connection,name,sequences.get(name));
        }
    }

    /** sqlite_master definitions that belong to one table and must survive its rebuild. */
    private static java.util.List<String> objects(Connection connection,String type,String table)throws SQLException{
        var statements=new java.util.ArrayList<String>();
        try(var query=connection.prepareStatement("SELECT sql FROM sqlite_master WHERE type=? AND tbl_name=? AND sql IS NOT NULL ORDER BY name")){
            query.setString(1,type);query.setString(2,table);
            try(var rows=query.executeQuery()){while(rows.next())statements.add(rows.getString(1));}
        }
        return statements;
    }

    /** Keep the AUTOINCREMENT counter so a rebuild never reuses a previously issued id. */
    private static void restoreSequence(Connection connection,String table,Long sequence)throws SQLException{
        if(sequence==null)return;
        try(var statement=connection.createStatement()){
            statement.execute("DELETE FROM sqlite_sequence WHERE name='"+table+"__strict'");
        }
        try(var update=connection.prepareStatement("UPDATE sqlite_sequence SET seq=? WHERE name=? AND seq<?")){
            update.setLong(1,sequence);update.setString(2,table);update.setLong(3,sequence);
            if(update.executeUpdate()>0)return;
        }
        try(var found=connection.prepareStatement("SELECT 1 FROM sqlite_sequence WHERE name=?")){
            found.setString(1,table);
            try(var rows=found.executeQuery()){if(rows.next())return;}
        }
        try(var insert=connection.prepareStatement("INSERT INTO sqlite_sequence(name,seq) VALUES(?,?)")){
            insert.setString(1,table);insert.setLong(2,sequence);insert.executeUpdate();
        }
    }

    private static boolean strict(String create){
        return create.matches("(?is).*\\)\\s*STRICT\\s*$");
    }

    /** Rewrite the declared name while leaving every other byte of the definition intact. */
    private static String replaceTableName(String create,String name,String replacement)throws SQLException{
        var pattern=java.util.regex.Pattern.compile("(?is)\\bCREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?[\\\"`\\[]?"
            +java.util.regex.Pattern.quote(name)+"[\\\"`\\]]?(?=\\s*\\()");
        var matcher=pattern.matcher(create);
        if(!matcher.find())throw new SQLException("Cannot rebuild table "+name+": unexpected definition");
        return create.substring(0,matcher.start())+"CREATE TABLE "+replacement+create.substring(matcher.end());
    }

    private SchemaMigrations() { }

    /** Fresh baseline: one transaction creates every table this release needs. */
    static void baseline(Connection connection) throws SQLException, IOException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY, description TEXT NOT NULL, applied_at INTEGER NOT NULL) STRICT");
            statement.execute("CREATE TABLE metadata(key TEXT PRIMARY KEY, value INTEGER NOT NULL) STRICT");
            statement.execute("INSERT INTO metadata VALUES('next-account-id',1)");
            statement.execute("CREATE TABLE accounts(id INTEGER PRIMARY KEY CHECK(id>0), username TEXT NOT NULL, name_key TEXT NOT NULL UNIQUE, salt BLOB NOT NULL CHECK(length(salt)=16), verifier BLOB NOT NULL CHECK(length(verifier)=32), game_master INTEGER NOT NULL DEFAULT 0 CHECK(game_master IN(0,1)), auth_revision INTEGER NOT NULL DEFAULT 1, revision INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL, next_knight_id INTEGER NOT NULL DEFAULT 1 CHECK(next_knight_id>0)) STRICT");
            statement.execute("CREATE TABLE knights(account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE, id INTEGER NOT NULL CHECK(id>0), name TEXT NOT NULL, name_key TEXT NOT NULL, helm TEXT NOT NULL, armor TEXT NOT NULL, helm_accessory TEXT, armor_accessory TEXT, costume TEXT NOT NULL, color INTEGER NOT NULL CHECK(color BETWEEN 0 AND 255), height INTEGER NOT NULL CHECK(height BETWEEN 0 AND 4), eye INTEGER NOT NULL CHECK(eye BETWEEN 0 AND 46), created_at INTEGER NOT NULL, PRIMARY KEY(account_id,id), UNIQUE(account_id,name_key)) STRICT");
            statement.execute("CREATE TABLE account_events(id INTEGER PRIMARY KEY, account_id INTEGER NOT NULL, action TEXT NOT NULL, created_at INTEGER NOT NULL) STRICT");
        }
        WorldProfiles.initialize(connection);
        TownStore.initialize(connection);
        GuildStore.initialize(connection);
        DepotStore.upgrade(connection, DepotSeed.bundled());
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE UNIQUE INDEX global_knight_names ON knights(name_key)");
            statement.execute("INSERT INTO schema_migrations(version,description,applied_at) VALUES("
                + BASELINE + ",'0.42.0 baseline'," + System.currentTimeMillis() + ")");
        }
    }

    /** Apply every step above {@code from} in order; the caller owns the transaction. */
    static void apply(Connection connection, int from) throws SQLException, IOException {
        apply(connection, from, ALL);
    }
    /** Testable form of {@link #apply(Connection, int)} with an explicit ordered step list. */
    static void apply(Connection connection, int from, java.util.List<Migration> steps) throws SQLException, IOException {
        if (from < BASELINE) throw new IOException("Unsupported database schema " + from);
        for (Migration migration : steps) {
            if (migration.version() <= from) continue;
            migration.step().apply(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO schema_migrations(version,description,applied_at) VALUES(?,?,?)")) {
                statement.setInt(1, migration.version());
                statement.setString(2, migration.description());
                statement.setLong(3, System.currentTimeMillis());
                statement.executeUpdate();
            }
        }
    }
}
