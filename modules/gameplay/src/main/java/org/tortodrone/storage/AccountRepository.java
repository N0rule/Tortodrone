package org.tortodrone.storage;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.core.character.Knight;

/** Account identity and administration rows, split from the transaction/schema owner.
 *
 * <p>Every method takes a connection through {@link Database#read} or
 * {@link Database#write}; the repository holds no transaction of its own, so the
 * writer pool, rollback, audit and expiry sweep stay in one place.
 */
final class AccountRepository {
    private final Database database;

    AccountRepository(Database database) { this.database = database; }

    AccountIdentity identity(String username) throws IOException {
        return database.read(c -> { try (PreparedStatement s = c.prepareStatement("SELECT * FROM accounts WHERE name_key=?")) {
            s.setString(1, username.toLowerCase(Locale.ROOT)); try (ResultSet r = s.executeQuery()) { return r.next() ? identity(r) : null; }
        } });
    }

    boolean current(int id, long revision) throws IOException {
        return database.read(c -> { try (PreparedStatement s = c.prepareStatement("SELECT auth_revision FROM accounts WHERE id=?")) {
            s.setInt(1,id); try (ResultSet r = s.executeQuery()) { return r.next() && r.getLong(1) == revision; }
        } });
    }

    List<AccountSummary> list() throws IOException {
        return database.read(c -> { List<AccountSummary> result = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT a.id,a.username,a.game_master,a.created_at,count(k.id) AS knights FROM accounts a LEFT JOIN knights k ON k.account_id=a.id GROUP BY a.id ORDER BY a.id")) {
                while (r.next()) result.add(new AccountSummary(r.getInt("id"),r.getString("username"),r.getBoolean("game_master"),r.getInt("knights"),r.getLong("created_at")));
            } return List.copyOf(result);
        });
    }

    int create(String username, Passwords.Protection protection, boolean gameMaster) throws IOException {
        return database.write(c -> {
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT count(*) FROM accounts")) {
                r.next(); if (r.getInt(1) >= 1000) throw new IOException("Account limit reached");
            }
            int id;
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT value FROM metadata WHERE key='next-account-id'")) { r.next(); id = r.getInt(1); }
            if (id >= Integer.MAX_VALUE) throw new IOException("Account ID space exhausted");
            insertAccount(c,id,username,protection.salt(),protection.verifier(),gameMaster,1);
            try (Statement s = c.createStatement()) { s.executeUpdate("UPDATE metadata SET value=value+1 WHERE key='next-account-id'"); }
            event(c,id,"created"); return id;
        });
    }

    void password(String username, Passwords.Protection protection,int expectedId) throws IOException {
        database.write(c -> {
            int id = requireId(c,username);
            if (expectedId >= 0 && id != expectedId) throw new IOException("Account changed; refresh the account list");
            try (PreparedStatement s = c.prepareStatement("UPDATE accounts SET salt=?,verifier=?,auth_revision=auth_revision+1,revision=revision+1 WHERE id=?")) {
                s.setBytes(1,protection.salt()); s.setBytes(2,protection.verifier()); s.setInt(3,id); s.executeUpdate();
            } event(c,id,"password changed"); return null;
        });
    }

    void gameMaster(String username, boolean gameMaster,int expectedId) throws IOException {
        database.write(c -> { int id = requireId(c,username);
            if (expectedId >= 0 && id != expectedId) throw new IOException("Account changed; refresh the account list");
            try (PreparedStatement s = c.prepareStatement("UPDATE accounts SET game_master=?,revision=revision+1 WHERE id=?")) { s.setBoolean(1,gameMaster); s.setInt(2,id); s.executeUpdate(); }
            event(c,id,gameMaster ? "game master granted" : "game master removed"); return null;
        });
    }

    /** Toggle atomically with other local tools; no stale read/modify/write window. */
    boolean toggleGameMaster(String username) throws IOException {
        return database.write(c -> {
            int id=requireId(c,username);
            boolean enabled;
            try(var s=c.prepareStatement("UPDATE accounts SET game_master=1-game_master,revision=revision+1 WHERE id=? RETURNING game_master")) {
                s.setInt(1,id);try(var r=s.executeQuery()){r.next();enabled=r.getBoolean(1);}
            }
            event(c,id,enabled ? "game master granted" : "game master removed");
            return enabled;
        });
    }

    void delete(String username,int expectedId) throws IOException {
        database.write(c -> { int id = requireId(c,username);
            if (expectedId >= 0 && id != expectedId) throw new IOException("Account changed; refresh the account list");
            AccountAchievements.delete(c,id);
            try (PreparedStatement s = c.prepareStatement("DELETE FROM accounts WHERE id=?")) { s.setInt(1,id); s.executeUpdate(); }
            WorldProfiles.cleanProgress(c);
            try(var s=c.prepareStatement("DELETE FROM metadata WHERE key=?")){s.setString(1,"account."+id+".inspection-hidden");s.executeUpdate();}
            event(c,id,"deleted"); return null;
        });
    }

    int nextKnight(int id) throws IOException {
        return database.read(c -> { try (PreparedStatement s = c.prepareStatement("SELECT next_knight_id FROM accounts WHERE id=?")) {
            s.setInt(1,id); try (ResultSet r = s.executeQuery()) { if (!r.next()) throw new IOException("Account no longer exists"); return r.getInt(1); }
        } });
    }

    private static void insertAccount(Connection c, int id, String username, byte[] salt, byte[] verifier, boolean gameMaster, int next) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO accounts(id,username,name_key,salt,verifier,game_master,created_at,next_knight_id) VALUES(?,?,?,?,?,?,?,?)")) {
            s.setInt(1,id); s.setString(2,username); s.setString(3,username.toLowerCase(Locale.ROOT)); s.setBytes(4,salt); s.setBytes(5,verifier); s.setBoolean(6,gameMaster); s.setLong(7,System.currentTimeMillis()); s.setInt(8,next); s.executeUpdate();
        }
    }

    private static AccountIdentity identity(ResultSet r) throws SQLException {
        return new AccountIdentity(r.getInt("id"), r.getString("username"), r.getBoolean("game_master"), r.getLong("auth_revision"), r.getBytes("salt"), r.getBytes("verifier"));
    }

    static int requireId(Connection c, String username) throws IOException, SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT id FROM accounts WHERE name_key=?")) {
            s.setString(1,username.toLowerCase(Locale.ROOT)); try (ResultSet r = s.executeQuery()) { if (!r.next()) throw new IOException("No such account"); return r.getInt(1); }
        }
    }

    static void event(Connection c,int id,String action) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO account_events(account_id,action,created_at) VALUES(?,?,?)")) {
            s.setInt(1,id); s.setString(2,action); s.setLong(3,System.currentTimeMillis()); s.executeUpdate();
        }
    }
}
