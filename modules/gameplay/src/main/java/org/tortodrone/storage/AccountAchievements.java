package org.tortodrone.storage;

import java.io.IOException;
import java.sql.*;
import java.util.Map;
import java.util.TreeMap;
import org.tortodrone.core.character.AchievementState;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.character.NativeAchievements;
import org.tortodrone.core.character.NativeStats;
import org.tortodrone.core.character.WorldProfile;

/**
 * Account-scoped achievement receipts and lifetime counters.
 *
 * <p>The stock client keeps both on {@code PlayerObject}, which exists before a knight
 * is selected, so the owner is the account rather than one world. Rows live in the
 * existing {@code metadata} table, like the other server-owned account markers; this
 * keeps the ordered schema at the 0.42.0 baseline while the values stay inside the
 * same SQLite transaction as the event that produced them.
 */
final class AccountAchievements {
    private static final String ACHIEVEMENT = "account.achievement.";
    private static final String STAT = "account.stat.";

    private AccountAchievements() { }

    static AchievementState load(Connection c, int accountId) throws SQLException, IOException {
        var unlocked = new TreeMap<Integer, Long>();
        try (var s = c.prepareStatement("SELECT key,value FROM metadata WHERE key LIKE ?")) {
            s.setString(1, ACHIEVEMENT + accountId + ".%");
            try (var rows = s.executeQuery()) {
                while (rows.next()) {
                    int type = suffix(rows.getString(1), ACHIEVEMENT + accountId + ".");
                    long at = rows.getLong(2);
                    if (type < 0 || type >= NativeAchievements.count() || at < 1)
                        throw new IOException("Invalid saved achievement receipt");
                    unlocked.put(type, at);
                }
            }
        }
        var counters = new TreeMap<Integer, Integer>();
        try (var s = c.prepareStatement("SELECT key,value FROM metadata WHERE key LIKE ?")) {
            s.setString(1, STAT + accountId + ".%");
            try (var rows = s.executeQuery()) {
                while (rows.next()) {
                    int stat = suffix(rows.getString(1), STAT + accountId + ".");
                    long value = rows.getLong(2);
                    if (stat < 0 || stat >= NativeStats.count() || value < 0 || value > Integer.MAX_VALUE)
                        throw new IOException("Invalid saved lifetime stat");
                    if (value != 0) counters.put(stat, (int) value);
                }
            }
        }
        return new AchievementState(unlocked, counters);
    }

    /** Add a counter inside the caller's transaction. Zero or negative deltas are refused. */
    static void increment(Connection c, int accountId, int stat, int delta) throws SQLException, IOException {
        if (stat < 0 || stat >= NativeStats.count() || delta < 1)
            throw new IOException("Invalid lifetime stat increment");
        try (var s = c.prepareStatement("INSERT INTO metadata(key,value) VALUES(?,?) "
                + "ON CONFLICT(key) DO UPDATE SET value=value+excluded.value "
                + "WHERE value+excluded.value BETWEEN 0 AND 2147483647")) {
            s.setString(1, STAT + accountId + "." + stat);
            s.setLong(2, delta);
            if (s.executeUpdate() != 1) throw new IOException("Lifetime stat limit reached");
        }
    }

    /**
     * Persist every newly qualifying native achievement. The caller owns the transaction,
     * so a craft or inventory change and its receipt commit together.
     */
    static WorldProfile reconcile(Connection c, WorldProfile profile, ItemCatalog items, int flags, long now)
            throws SQLException, IOException {
        var stats = NativeStats.values(profile, items, now);
        var desired = NativeAchievements.qualifying(stats, flags, profile.progress().progressFlags());
        var additions = new TreeMap<Integer, Long>();
        for (int type : desired)
            if (!profile.achievements().unlocked().containsKey(type)) additions.put(type, now);
        if (additions.isEmpty()) return profile;
        try (var s = c.prepareStatement("INSERT INTO metadata(key,value) VALUES(?,?) "
                + "ON CONFLICT(key) DO NOTHING")) {
            for (var entry : additions.entrySet()) {
                s.setString(1, ACHIEVEMENT + profile.accountId() + "." + entry.getKey());
                s.setLong(2, entry.getValue());
                s.executeUpdate();
            }
        }
        return profile.withAchievements(profile.achievements().withUnlocked(additions));
    }

    /** Remove every account-scoped row when the account itself is deleted. */
    static void delete(Connection c, int accountId) throws SQLException {
        try (var s = c.prepareStatement("DELETE FROM metadata WHERE key LIKE ? OR key LIKE ?")) {
            s.setString(1, ACHIEVEMENT + accountId + ".%");
            s.setString(2, STAT + accountId + ".%");
            s.executeUpdate();
        }
    }

    private static int suffix(String key, String prefix) throws IOException {
        try {
            String value = key.substring(prefix.length());
            if (!value.matches("[0-9]{1,3}")) throw new NumberFormatException();
            return Integer.parseInt(value);
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid account achievement key", invalid);
        }
    }
}
