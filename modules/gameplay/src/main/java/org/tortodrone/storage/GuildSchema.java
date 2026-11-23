package org.tortodrone.storage;

import java.sql.Connection;
import java.sql.SQLException;

/** Guild tables for the 0.42.0 baseline. */
final class GuildSchema {
    private GuildSchema() { }

    static void initialize(Connection c) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS guilds("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "name TEXT NOT NULL,name_key TEXT NOT NULL UNIQUE,formed INTEGER NOT NULL,"
                + "motd TEXT NOT NULL DEFAULT '',hall_open INTEGER NOT NULL DEFAULT 0,"
                + "energy INTEGER NOT NULL DEFAULT 0 CHECK(energy>=0),"
                + "upkeep_cost INTEGER NOT NULL DEFAULT 0,upkeep_time INTEGER NOT NULL DEFAULT 0,"
                + "in_default INTEGER NOT NULL DEFAULT 0 CHECK(in_default IN(0,1)),"
                + "member_limit INTEGER NOT NULL DEFAULT " + GuildStore.MAX_MEMBERS + ","
                + "theme TEXT,environment TEXT,hall_open_at INTEGER NOT NULL DEFAULT 0,renamed INTEGER NOT NULL DEFAULT 0) STRICT");
            s.execute("CREATE TABLE IF NOT EXISTS guild_members("
                + "world_id INTEGER PRIMARY KEY REFERENCES world_characters(world_id) ON DELETE CASCADE,"
                + "guild_id INTEGER NOT NULL REFERENCES guilds(id) ON DELETE CASCADE,"
                + "rank INTEGER NOT NULL CHECK(rank BETWEEN 0 AND 4),"
                + "command_order INTEGER NOT NULL DEFAULT -2147483647,"
                + "joined INTEGER NOT NULL,last_withdrawal INTEGER NOT NULL DEFAULT 0) STRICT");
            s.execute("CREATE INDEX IF NOT EXISTS guild_members_guild ON guild_members(guild_id,rank,command_order)");
            s.execute("CREATE TABLE IF NOT EXISTS guild_log("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "guild_id INTEGER NOT NULL REFERENCES guilds(id) ON DELETE CASCADE,"
                + "type INTEGER NOT NULL,knight_name TEXT,when_ms INTEGER NOT NULL,"
                + "args TEXT NOT NULL DEFAULT '[]') STRICT");
            s.execute("CREATE INDEX IF NOT EXISTS guild_log_guild ON guild_log(guild_id,id)");
            s.execute("CREATE TABLE IF NOT EXISTS guild_expansions("
                + "guild_id INTEGER NOT NULL REFERENCES guilds(id) ON DELETE CASCADE,"
                + "expansion INTEGER NOT NULL,PRIMARY KEY(guild_id,expansion)) STRICT");
        }
    }
}
