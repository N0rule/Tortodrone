package org.tortodrone.storage;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import org.tortodrone.core.guild.Guild;
import org.tortodrone.core.guild.Member;
import org.tortodrone.core.guild.Page;
import org.tortodrone.core.guild.View;

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
    private final GuildSettingsStore settings;

    public GuildStore(Database database) {
        this.database = Objects.requireNonNull(database);
        this.hall = new GuildHallStore(this.database);
        this.members = new GuildMembershipStore(this.database);
        this.settings = new GuildSettingsStore(this.database);
    }

    /** Guild tables for the 0.42.0 baseline. */
    public static void initialize(Connection c) throws SQLException {
        GuildSchema.initialize(c);
    }

    public Guild guildOf(int worldId) throws IOException {
        return database.read(c -> guildOf(c, worldId));
    }

    public View view(long guildId) throws IOException {
        return database.read(c -> view(c, guildId));
    }

    public Page log(
            int account,
            int knight,
            long auth,
            long guildId,
            long cursor,
            int offset,
            int count,
            Integer category,
            String knightName)
            throws IOException {
        if (count < 1 || count > 100 || offset < 0) throw new IOException("Invalid log page");
        return database.read(c -> {
            var profile = WorldProfiles.load(c, account, knight, auth);
            if (membership(c, guildId, profile.worldId()) == null) throw new IOException("m.access_denied");
            return GuildLogStore.page(c, guildId, cursor, offset, count, category, knightName);
        });
    }

    /** {@code GuildMarshaller} id 4, the same gates {@code formGuild} applies to a name. */
    public void checkName(String name) throws IOException {
        settings.checkName(name);
    }

    /** {@code GuildMarshaller} id 8: form a guild and make the founder its master. */
    public TownStore.Result form(int account, int knight, long auth, String name) throws IOException {
        return members.form(account, knight, auth, name);
    }

    /** {@code GuildMarshaller} id 10: deliver an invitation letter to a knight by name. */
    public TownStore.Result invite(int account, int knight, long auth, String target, String subject, String message)
            throws IOException {
        return members.invite(account, knight, auth, target, subject, message);
    }

    /** Accept the invitation carried by a guild-letter mail. Called inside the mailbox write. */
    public static void acceptInvite(Connection c, int worldId, long guildId, String guildName, String knightName)
            throws SQLException, IOException {
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
        settings.canRename(account, knight, auth);
    }

    /** {@code GuildMarshaller} id 15: consumes the rename ticket with the new name. */
    public TownStore.Result rename(int account, int knight, long auth, String name) throws IOException {
        return settings.rename(account, knight, auth, name);
    }

    /** {@code GuildMarshaller} id 18. Only an officer or master may write the message of the day. */
    public TownStore.Result setMotd(int account, int knight, long auth, long guildId, String motd) throws IOException {
        return settings.setMotd(account, knight, auth, guildId, motd);
    }

    /** {@code GuildMarshaller} id 17. Opening the hall is refused until hall scenes are authored. */
    public TownStore.Result setHallOpen(int account, int knight, long auth, long guildId, boolean open)
            throws IOException {
        return hall.setHallOpen(account, knight, auth, guildId, open);
    }

    /** {@code GuildMarshaller} id 5: deposit crowns into the guild's energy well. */
    public TownStore.Result depositEnergy(int account, int knight, long auth, long guildId, int amount)
            throws IOException {
        return hall.depositEnergy(account, knight, auth, guildId, amount);
    }

    /** {@code GuildMarshaller} id 22: take {@code min(100, well)} back to the knight. */
    public TownStore.Result withdrawEnergy(int account, int knight, long auth, long guildId) throws IOException {
        return hall.withdrawEnergy(account, knight, auth, guildId);
    }

    /** {@code GuildMarshaller} id 1. Prices are the shipped expansion tickets. */
    public TownStore.Result buyExpansion(int account, int knight, long auth, long guildId, int expansion)
            throws IOException {
        return hall.buyExpansion(account, knight, auth, guildId, expansion);
    }

    /** {@code GuildMarshaller} id 14; removal refunds nothing, as the archive's own rule. */
    public TownStore.Result removeExpansion(int account, int knight, long auth, long guildId, int expansion)
            throws IOException {
        return hall.removeExpansion(account, knight, auth, guildId, expansion);
    }

    public TownStore.Result setHallGlobal(int account, int knight, long auth, long guildId, int type, String ticket)
            throws IOException {
        return hall.setHallGlobal(account, knight, auth, guildId, type, ticket);
    }

    /** Expansion ticket prices from {@code Guild/Expansion/*} in the shipped item catalogue. */
    public static int expansionPrice(int expansion) {
        return GuildHallStore.expansionPrice(expansion);
    }

    /** Per-expansion upkeep is not stated by a surviving table; zero keeps the well honest. */
    public static int expansionUpkeep(int expansion) {
        return GuildHallStore.expansionUpkeep(expansion);
    }

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
                return new Guild(
                        r.getLong("id"),
                        r.getString("name"),
                        r.getLong("formed"),
                        r.getString("motd"),
                        r.getInt("hall_open") != 0,
                        r.getInt("energy"),
                        r.getInt("upkeep_cost"),
                        r.getLong("upkeep_time"),
                        r.getInt("in_default") != 0,
                        r.getInt("member_limit"),
                        r.getString("theme"),
                        r.getString("environment"),
                        r.getLong("hall_open_at"),
                        r.getLong("renamed"));
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
                    members.add(new Member(
                            r.getInt("world_id"),
                            r.getString("name"),
                            (byte) r.getInt("rank"),
                            r.getInt("command_order"),
                            r.getLong("joined"),
                            r.getLong("last_session"),
                            r.getLong("last_withdrawal")));
                }
            }
        }
        var expansions = new ArrayList<Integer>();
        try (var s = c.prepareStatement("SELECT expansion FROM guild_expansions WHERE guild_id=? ORDER BY expansion")) {
            s.setLong(1, guildId);
            try (var r = s.executeQuery()) {
                while (r.next()) expansions.add(r.getInt(1));
            }
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
}
