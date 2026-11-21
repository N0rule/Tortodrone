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
    static final int LATEST = 1;

    @FunctionalInterface interface Step { void apply(Connection connection) throws SQLException, IOException; }
    record Migration(int version, String description, Step step) { }

    /** Ordered steps above the baseline. Add one entry per released schema change. */
    static final java.util.List<Migration> ALL = java.util.List.of();

    private SchemaMigrations() { }

    /** Fresh baseline: one transaction creates every table this release needs. */
    static void baseline(Connection connection) throws SQLException, IOException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY, description TEXT NOT NULL, applied_at INTEGER NOT NULL)");
            statement.execute("CREATE TABLE metadata(key TEXT PRIMARY KEY, value INTEGER NOT NULL)");
            statement.execute("INSERT INTO metadata VALUES('next-account-id',1)");
            statement.execute("CREATE TABLE accounts(id INTEGER PRIMARY KEY CHECK(id>0), username TEXT NOT NULL, name_key TEXT NOT NULL UNIQUE, salt BLOB NOT NULL CHECK(length(salt)=16), verifier BLOB NOT NULL CHECK(length(verifier)=32), game_master INTEGER NOT NULL DEFAULT 0 CHECK(game_master IN(0,1)), auth_revision INTEGER NOT NULL DEFAULT 1, revision INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL, next_knight_id INTEGER NOT NULL DEFAULT 1 CHECK(next_knight_id>0))");
            statement.execute("CREATE TABLE knights(account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE, id INTEGER NOT NULL CHECK(id>0), name TEXT NOT NULL, name_key TEXT NOT NULL, helm TEXT NOT NULL, armor TEXT NOT NULL, helm_accessory TEXT, armor_accessory TEXT, costume TEXT NOT NULL, color INTEGER NOT NULL CHECK(color BETWEEN 0 AND 255), height INTEGER NOT NULL CHECK(height BETWEEN 0 AND 4), eye INTEGER NOT NULL CHECK(eye BETWEEN 0 AND 46), created_at INTEGER NOT NULL, PRIMARY KEY(account_id,id), UNIQUE(account_id,name_key))");
            statement.execute("CREATE TABLE account_events(id INTEGER PRIMARY KEY, account_id INTEGER NOT NULL, action TEXT NOT NULL, created_at INTEGER NOT NULL)");
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
