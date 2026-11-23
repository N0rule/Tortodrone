package org.tortodrone.storage;

import org.tortodrone.core.guild.Guild;
import org.tortodrone.core.guild.Member;
import org.tortodrone.core.guild.Page;
import org.tortodrone.core.guild.View;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import org.tortodrone.core.character.WorldProfile;
import org.tortodrone.core.Json;

/**
 * Guild membership, ranks, record, treasury well and hall state.
 *
 * <p>Guild identity is deliberately separate from knight identity: a knight belongs to at most one
 * guild (the unique {@code guild_members.world_id} row), while a guild keeps its own name, roster
 * and durable record. Every mutation runs inside one authenticated write transaction, and the
 * caller is rechecked against the stored auth revision before any row changes.
 *
 * <p>Only guild state that can be read from the stock client or a capture is stored. A hall scene
 * is not created here; the columns exist so {@code GuildObject} can publish the same fields a live
 * server publishes, and {@code visitGuildhall} is refused until a hall map is authored.
 */
public final class GuildStore {
    /** Stock formation prices, read from the live ServerObject capture (2000 crowns / 100 energy). */
    public static final int FORMATION_CROWNS = 2000, FORMATION_ENERGY = 100;
    /** {@code GuildCodes.MAX_MEMBERS}; the member-limit ticket tiers are not implemented yet. */
    public static final int MAX_MEMBERS = 100;
    /** Shipped caps: 255 for the message of the day, 500 minimum deposit, 100 per withdrawal. */
    public static final int MOTD_LENGTH = 255, MIN_DEPOSIT = 500, WITHDRAWAL_LIMIT = 100;
    public static final long RENAME_INTERVAL = 30L * 24 * 60 * 60 * 1000;
    public static final long WITHDRAWAL_INTERVAL = 24L * 60 * 60 * 1000;
    public static final String RENAME_ITEM = "Upgrade/Rename Guild";
    /** Highest valid rank: 0 master, 1 officer, 2 member, 3 veteran, 4 recruit. */
    public static final int RANK_COUNT = 5;


    private final Database database;
    private final GuildHallStore hall;
    private final GuildMembershipStore members;

    public GuildStore(Database database) {
        this.database = Objects.requireNonNull(database);
        this.hall = new GuildHallStore(this.database);
        this.members = new GuildMembershipStore(this.database);
    }

    /** Guild tables for the 0.42.0 baseline. */
    public static void initialize(Connection c) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS guilds("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "name TEXT NOT NULL,name_key TEXT NOT NULL UNIQUE,formed INTEGER NOT NULL,"
                + "motd TEXT NOT NULL DEFAULT '',hall_open INTEGER NOT NULL DEFAULT 0,"
                + "energy INTEGER NOT NULL DEFAULT 0 CHECK(energy>=0),"
                + "upkeep_cost INTEGER NOT NULL DEFAULT 0,upkeep_time INTEGER NOT NULL DEFAULT 0,"
                + "in_default INTEGER NOT NULL DEFAULT 0 CHECK(in_default IN(0,1)),"
                + "member_limit INTEGER NOT NULL DEFAULT " + MAX_MEMBERS + ","
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

    /** The guild a world belongs to, or null. Read-only and safe outside a write transaction. */
    public Guild guildOf(int worldId) throws IOException {
        return database.read(c -> guildOf(c, worldId));
    }

    public View view(long guildId) throws IOException {
        return database.read(c -> view(c, guildId));
    }

    public Page log(int account, int knight, long auth, long guildId, long cursor, int offset,
            int count, Integer category, String knightName) throws IOException {
        if (count < 1 || count > 100 || offset < 0) throw new IOException("Invalid log page");
        return database.read(c -> {
            var profile = WorldProfiles.load(c, account, knight, auth);
            if (membership(c, guildId, profile.worldId()) == null) throw new IOException("m.access_denied");
            return GuildLogStore.page(c, guildId, cursor, offset, count, category, knightName);
        });
    }

    /** {@code GuildMarshaller} id 4, the same gates {@code formGuild} applies to a name. */
    public void checkName(String name) throws IOException {
        String error = nameError(name);
        if (error != null) throw new IOException(error);
        database.read(c -> {
            if (nameTaken(c, name)) throw new IOException("m.name_in_use");
            return null;
        });
    }

    /** {@code GuildMarshaller} id 8: form a guild and make the founder its master. */
    public TownStore.Result form(int account, int knight, long auth, String name) throws IOException {
        return members.form(account, knight, auth, name);
    }

    /** {@code GuildMarshaller} id 10: deliver an invitation letter to a knight by name. */
    public TownStore.Result invite(int account, int knight, long auth, String target, String subject,
            String message) throws IOException {
        return members.invite(account, knight, auth, target, subject, message);
    }

    /** Accept the invitation carried by a guild-letter mail. Called inside the mailbox write. */
    public static void acceptInvite(Connection c, int worldId, long guildId, String guildName,
            String knightName) throws SQLException, IOException {
        GuildMembershipStore.acceptInvite(c, worldId, guildId, guildName, knightName);
    }

    /** {@code GuildMarshaller} id 11. A master leaving hands the guild to the senior officer. */
    public TownStore.Result leave(int account, int knight, long auth) throws IOException {
        return members.leave(account, knight, auth);
    }

    /** {@code GuildMarshaller} id 7. */
    public TownStore.Result expel(int account, int knight, long auth, String target) throws IOException {
        return members.expel(account, knight, auth, target);
    }

    /** {@code GuildMarshaller} id 3. Ranks are a byte where a smaller value is higher. */
    public TownStore.Result changeRank(int account, int knight, long auth, String target, byte rank)
            throws IOException {
        return members.changeRank(account, knight, auth, target, rank);
    }

    /** {@code GuildMarshaller} id 2: the rename window needs a ticket and a 30-day interval. */
    public void canRename(int account, int knight, long auth) throws IOException {
        database.read(c -> {
            var profile = WorldProfiles.load(c, account, knight, auth);
            var guild = requireMember(c, guildOf(c, profile.worldId()));
            var member = membership(c, guild.id(), profile.worldId());
            if (member == null || member.rank() != 0) throw new IOException("m.access_denied");
            if (guild.renamed() > 0 && System.currentTimeMillis() - guild.renamed() < RENAME_INTERVAL)
                throw new IOException("m.rename_too_soon");
            if (profile.items().stream().noneMatch(i -> i.config().equals(RENAME_ITEM)))
                throw new IOException("m.no_rename_item");
            return null;
        });
    }

    /** {@code GuildMarshaller} id 15: consumes the rename ticket with the new name. */
    public TownStore.Result rename(int account, int knight, long auth, String name) throws IOException {
        String error = nameError(name);
        if (error != null) throw new IOException(error);
        return database.write(c -> {
            var profile = WorldProfiles.load(c, account, knight, auth);
            var guild = requireMember(c, guildOf(c, profile.worldId()));
            var member = membership(c, guild.id(), profile.worldId());
            if (member == null || member.rank() != 0) throw new IOException("m.access_denied");
            if (nameTaken(c, name) && !guild.name().equalsIgnoreCase(name)) throw new IOException("m.name_in_use");
            if (guild.renamed() > 0 && System.currentTimeMillis() - guild.renamed() < RENAME_INTERVAL)
                throw new IOException("m.rename_too_soon");
            var ticket = profile.items().stream().filter(i -> i.config().equals(RENAME_ITEM)).findFirst()
                .orElseThrow(() -> new IOException("m.no_rename_item"));
            try (var s = c.prepareStatement("UPDATE guilds SET name=?,name_key=?,renamed=? WHERE id=?")) {
                s.setString(1, name);
                s.setString(2, name.toLowerCase(Locale.ROOT));
                s.setLong(3, System.currentTimeMillis());
                s.setLong(4, guild.id());
                s.executeUpdate();
            }
            TownLedger.consume(c, profile, ticket.id(), 1);
            GuildLogStore.log(c, guild.id(), 31, member.name(), System.currentTimeMillis(), List.of("~" + name));
            TownLedger.touch(c, profile.worldId());
            return new TownStore.Result(WorldProfiles.load(c, account, knight, auth), view(c, guild.id()));
        });
    }

    /** {@code GuildMarshaller} id 18. Only an officer or master may write the message of the day. */
    public TownStore.Result setMotd(int account, int knight, long auth, long guildId, String motd)
            throws IOException {
        if (motd == null) motd = "";
        if (motd.length() > MOTD_LENGTH) throw new IOException("Invalid message of the day");
        String value = motd;
        return database.write(c -> {
            var profile = WorldProfiles.load(c, account, knight, auth);
            var guild = requireMember(c, guildOf(c, profile.worldId()));
            if (guild.id() != guildId) throw new IOException("m.access_denied");
            var member = membership(c, guild.id(), profile.worldId());
            if (member == null || member.rank() > 1) throw new IOException("m.access_denied");
            try (var s = c.prepareStatement("UPDATE guilds SET motd=? WHERE id=?")) {
                s.setString(1, value);
                s.setLong(2, guild.id());
                s.executeUpdate();
            }
            GuildLogStore.log(c, guild.id(), 28, member.name(), System.currentTimeMillis(), null);
            TownLedger.touch(c, profile.worldId());
            return new TownStore.Result(WorldProfiles.load(c, account, knight, auth), view(c, guild.id()));
        });
    }

    /** {@code GuildMarshaller} id 17. Opening the hall is refused until hall scenes are authored. */
    public TownStore.Result setHallOpen(int account,int knight,long auth,long guildId,boolean open)
            throws IOException { return hall.setHallOpen(account,knight,auth,guildId,open); }

    /** {@code GuildMarshaller} id 5: deposit crowns into the guild's energy well. */
    public TownStore.Result depositEnergy(int account,int knight,long auth,long guildId,int amount)
            throws IOException { return hall.depositEnergy(account,knight,auth,guildId,amount); }

    /** {@code GuildMarshaller} id 22: take {@code min(100, well)} back to the knight. */
    public TownStore.Result withdrawEnergy(int account,int knight,long auth,long guildId)
            throws IOException { return hall.withdrawEnergy(account,knight,auth,guildId); }

    /** {@code GuildMarshaller} id 1. Prices are the shipped expansion tickets. */
    public TownStore.Result buyExpansion(int account,int knight,long auth,long guildId,int expansion)
            throws IOException { return hall.buyExpansion(account,knight,auth,guildId,expansion); }

    /** {@code GuildMarshaller} id 14; removal refunds nothing, as the archive's own rule. */
    public TownStore.Result removeExpansion(int account,int knight,long auth,long guildId,int expansion)
            throws IOException { return hall.removeExpansion(account,knight,auth,guildId,expansion); }

    public TownStore.Result setHallGlobal(int account,int knight,long auth,long guildId,int type,String ticket)
            throws IOException { return hall.setHallGlobal(account,knight,auth,guildId,type,ticket); }

    /** Expansion ticket prices from {@code Guild/Expansion/*} in the shipped item catalogue. */
    public static int expansionPrice(int expansion){ return GuildHallStore.expansionPrice(expansion); }

    /** Per-expansion upkeep is not stated by a surviving table; zero keeps the well honest. */
    public static int expansionUpkeep(int expansion){ return GuildHallStore.expansionUpkeep(expansion); }

    // ---- read helpers

    static Guild guildOf(Connection c, int worldId) throws SQLException, IOException {
        try (var s = c.prepareStatement("SELECT guild_id FROM guild_members WHERE world_id=?")) {
            s.setInt(1, worldId);
            try (var r = s.executeQuery()) {
                return r.next() ? guildRow(c, r.getLong(1)) : null;
            }
        }
    }

    static Guild guildRow(Connection c, long guildId) throws SQLException, IOException {
        try (var s = c.prepareStatement("SELECT * FROM guilds WHERE id=?")) {
            s.setLong(1, guildId);
            try (var r = s.executeQuery()) {
                if (!r.next()) return null;
                return new Guild(r.getLong("id"), r.getString("name"), r.getLong("formed"),
                    r.getString("motd"), r.getInt("hall_open") != 0, r.getInt("energy"),
                    r.getInt("upkeep_cost"), r.getLong("upkeep_time"), r.getInt("in_default") != 0,
                    r.getInt("member_limit"), r.getString("theme"), r.getString("environment"),
                    r.getLong("hall_open_at"), r.getLong("renamed"));
            }
        }
    }

    static View view(Connection c, long guildId) throws SQLException, IOException {
        var guild = guildRow(c, guildId);
        if (guild == null) throw new IOException("m.internal_error");
        var members = new ArrayList<Member>();
        try (var s = c.prepareStatement("SELECT m.world_id,m.rank,m.command_order,m.joined,k.name,"
                + "0 last_session,m.last_withdrawal FROM guild_members m "
                + "JOIN world_characters w ON w.world_id=m.world_id "
                + "JOIN knights k ON k.account_id=w.account_id AND k.id=w.knight_id "
                + "WHERE m.guild_id=? "
                + "ORDER BY m.rank,m.command_order,m.joined,m.world_id")) {
            s.setLong(1, guildId);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    members.add(new Member(r.getInt("world_id"), r.getString("name"),
                        (byte) r.getInt("rank"), r.getInt("command_order"), r.getLong("joined"),
                        r.getLong("last_session"), r.getLong("last_withdrawal")));
                }
            }
        }
        var expansions = new ArrayList<Integer>();
        try (var s = c.prepareStatement("SELECT expansion FROM guild_expansions WHERE guild_id=? ORDER BY expansion")) {
            s.setLong(1, guildId);
            try (var r = s.executeQuery()) { while (r.next()) expansions.add(r.getInt(1)); }
        }
        return new View(guild, members, expansions);
    }

    static Member membership(Connection c, long guildId, int worldId) throws SQLException, IOException {
        if (guildId <= 0) return null;
        for (var member : view(c, guildId).members()) if (member.worldId() == worldId) return member;
        return null;
    }

    static Guild requireMember(Connection c, Guild guild) throws IOException {
        if (guild == null) throw new IOException("m.internal_error");
        return guild;
    }

    static boolean nameTaken(Connection c, String name) throws SQLException {
        try (var s = c.prepareStatement("SELECT 1 FROM guilds WHERE name_key=?")) {
            s.setString(1, name.trim().toLowerCase(Locale.ROOT));
            try (var r = s.executeQuery()) { return r.next(); }
        }
    }

    /** Guild names are longer than knight names; the live roster carries names of 16+ letters. */
    static String nameError(String name) {
        if (name == null) return "m.internal_error";
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 32
                || !trimmed.matches("[A-Za-z0-9][A-Za-z0-9 '\\-]*")) return "m.internal_error";
        return null;
    }
}
