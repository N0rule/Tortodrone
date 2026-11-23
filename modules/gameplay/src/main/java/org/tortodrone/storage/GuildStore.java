package org.tortodrone.storage;

import org.tortodrone.core.guild.Guild;
import org.tortodrone.core.guild.LogEntry;
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

    public GuildStore(Database database) { this.database = Objects.requireNonNull(database); }

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
            return page(c, guildId, cursor, offset, count, category, knightName);
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
        String error = nameError(name);
        if (error != null) throw new IOException(error);
        return database.write(c -> {
            var profile = WorldProfiles.load(c, account, knight, auth);
            if (guildOf(c, profile.worldId()) != null) throw new IOException("m.internal_error");
            if (nameTaken(c, name)) throw new IOException("m.name_in_use");
            if (profile.crowns() < FORMATION_CROWNS) throw new IOException("m.nsf_crowns");
            if (profile.energy() < FORMATION_ENERGY) throw new IOException("m.nsf_energy");
            TownLedger.pay(c, profile, FORMATION_CROWNS, FORMATION_ENERGY);
            long now = System.currentTimeMillis();
            long id;
            try (var s = c.prepareStatement("INSERT INTO guilds(name,name_key,formed,member_limit) VALUES(?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                s.setString(1, name);
                s.setString(2, name.toLowerCase(Locale.ROOT));
                s.setLong(3, now);
                s.setInt(4, MAX_MEMBERS);
                s.executeUpdate();
                try (var keys = s.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("Missing guild id");
                    id = keys.getLong(1);
                }
            }
            try (var s = c.prepareStatement("INSERT INTO guild_members(world_id,guild_id,rank,command_order,joined) VALUES(?,?,0,0,?)")) {
                s.setInt(1, profile.worldId());
                s.setLong(2, id);
                s.setLong(3, now);
                s.executeUpdate();
            }
            log(c, id, 0, null, now, null);
            TownLedger.touch(c, profile.worldId());
            return new TownStore.Result(WorldProfiles.load(c, account, knight, auth), view(c, id));
        });
    }

    /** {@code GuildMarshaller} id 10: deliver an invitation letter to a knight by name. */
    public TownStore.Result invite(int account, int knight, long auth, String target, String subject,
            String message) throws IOException {
        if (subject == null) subject = "";
        if (message == null) message = "";
        if (subject.length() > 50 || message.length() > 8096) throw new IOException("Invalid mail text");
        String finalSubject = subject, finalMessage = message;
        return database.write(c -> {
            var profile = WorldProfiles.load(c, account, knight, auth);
            var guild = requireMember(c, guildOf(c, profile.worldId()));
            if (profile.worldId() == worldId(c, target)) throw new IOException("m.invite_self");
            var member = membership(c, guild.id(), profile.worldId());
            if (member == null || member.rank() > 1) throw new IOException("m.access_denied");
            int targetWorld = worldId(c, target);
            if (guildOf(c, targetWorld) != null) throw new IOException("m.already_in_guild");
            try (var s = c.prepareStatement("INSERT INTO town_mail(world_id,sent,subject,body,sender,mail_type,guild_invite_id,guild_invite_name) VALUES(?,?,?,?,?,2,?,?)")) {
                s.setInt(1, targetWorld);
                s.setLong(2, System.currentTimeMillis());
                s.setString(3, finalSubject);
                s.setString(4, finalMessage);
                s.setInt(5, profile.worldId());
                s.setLong(6, guild.id());
                s.setString(7, guild.name());
                s.executeUpdate();
            }
            TownLedger.touch(c, targetWorld);
            return new TownStore.Result(WorldProfiles.load(c, account, knight, auth), view(c, guild.id()));
        });
    }

    /** Accept the invitation carried by a guild-letter mail. Called inside the mailbox write. */
    public static void acceptInvite(Connection c, int worldId, long guildId, String guildName,
            String knightName) throws SQLException, IOException {
        if (guildId <= 0) throw new IOException("m.internal_error");
        if (guildOf(c, worldId) != null) throw new IOException("m.already_in_guild");
        var guild = guildRow(c, guildId);
        if (guild == null || !guild.name().equals(guildName)) throw new IOException("e.guild_not_found");
        int members;
        try (var s = c.prepareStatement("SELECT count(*) FROM guild_members WHERE guild_id=?")) {
            s.setInt(1, (int) guildId);
            try (var r = s.executeQuery()) { r.next(); members = r.getInt(1); }
        }
        if (members >= guild.memberLimit()) throw new IOException("m.too_many_members");
        long now = System.currentTimeMillis();
        try (var s = c.prepareStatement("INSERT INTO guild_members(world_id,guild_id,rank,command_order,joined) VALUES(?,?,4,0,?)")) {
            s.setInt(1, worldId);
            s.setLong(2, guildId);
            s.setLong(3, now);
            s.executeUpdate();
        }
        log(c, guildId, 0, knightName, now, null);
        TownLedger.touch(c, worldId);
    }

    /** {@code GuildMarshaller} id 11. A master leaving hands the guild to the senior officer. */
    public TownStore.Result leave(int account, int knight, long auth) throws IOException {
        return database.write(c -> {
            var profile = WorldProfiles.load(c, account, knight, auth);
            var guild = requireMember(c, guildOf(c, profile.worldId()));
            var member = membership(c, guild.id(), profile.worldId());
            if (member == null) throw new IOException("m.internal_error");
            remove(c, guild.id(), profile.worldId(), 2, null);
            TownLedger.touch(c, profile.worldId());
            return new TownStore.Result(WorldProfiles.load(c, account, knight, auth), null);
        });
    }

    /** {@code GuildMarshaller} id 7. */
    public TownStore.Result expel(int account, int knight, long auth, String target) throws IOException {
        return database.write(c -> {
            var profile = WorldProfiles.load(c, account, knight, auth);
            var guild = requireMember(c, guildOf(c, profile.worldId()));
            var changer = membership(c, guild.id(), profile.worldId());
            var changee = byName(c, guild.id(), target);
            if (changer == null || changee == null || !canChangeStatus(changee, changer))
                throw new IOException("m.internal_error");
            remove(c, guild.id(), changee.worldId(), 1, changer.name());
            TownLedger.touch(c, changee.worldId());
            return new TownStore.Result(WorldProfiles.load(c, account, knight, auth), view(c, guild.id()));
        });
    }

    /** {@code GuildMarshaller} id 3. Ranks are a byte where a smaller value is higher. */
    public TownStore.Result changeRank(int account, int knight, long auth, String target, byte rank)
            throws IOException {
        if (rank < 0 || rank >= RANK_COUNT) throw new IOException("m.internal_error");
        return database.write(c -> {
            var profile = WorldProfiles.load(c, account, knight, auth);
            var guild = requireMember(c, guildOf(c, profile.worldId()));
            var changer = membership(c, guild.id(), profile.worldId());
            var changee = byName(c, guild.id(), target);
            if (changer == null || changee == null || !canChangeStatus(changee, changer)
                    || rank < changer.rank()) throw new IOException("m.internal_error");
            if (changee.rank() == rank) return new TownStore.Result(profile, view(c, guild.id()));
            int order = rank <= 1 ? highestOrder(c, guild.id(), rank) + 1 : -2147483647;
            try (var s = c.prepareStatement("UPDATE guild_members SET rank=?,command_order=? WHERE world_id=? AND guild_id=?")) {
                s.setInt(1, rank);
                s.setInt(2, order);
                s.setInt(3, changee.worldId());
                s.setLong(4, guild.id());
                s.executeUpdate();
            }
            log(c, guild.id(), 3, changer.name(), System.currentTimeMillis(),
                List.of(rank < changee.rank() ? "m.gl_promoted" : "m.gl_demoted", "~" + changee.name(), "m.rank." + rank));
            TownLedger.touch(c, changee.worldId());
            return new TownStore.Result(WorldProfiles.load(c, account, knight, auth), view(c, guild.id()));
        });
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
            log(c, guild.id(), 31, member.name(), System.currentTimeMillis(), List.of("~" + name));
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
            log(c, guild.id(), 28, member.name(), System.currentTimeMillis(), null);
            TownLedger.touch(c, profile.worldId());
            return new TownStore.Result(WorldProfiles.load(c, account, knight, auth), view(c, guild.id()));
        });
    }

    /** {@code GuildMarshaller} id 17. Opening the hall is refused until hall scenes are authored. */
    public TownStore.Result setHallOpen(int account, int knight, long auth, long guildId, boolean open)
            throws IOException {
        return database.write(c -> {
            var profile = WorldProfiles.load(c, account, knight, auth);
            var guild = requireMember(c, guildOf(c, profile.worldId()));
            if (guild.id() != guildId) throw new IOException("m.access_denied");
            var member = membership(c, guild.id(), profile.worldId());
            if (member == null || member.rank() > 1) throw new IOException("m.access_denied");
            try (var s = c.prepareStatement("UPDATE guilds SET hall_open=?,hall_open_at=? WHERE id=?")) {
                s.setInt(1, open ? 1 : 0);
                s.setLong(2, open ? System.currentTimeMillis() : 0);
                s.setLong(3, guild.id());
                s.executeUpdate();
            }
            log(c, guild.id(), open ? 32 : 33, member.name(), System.currentTimeMillis(), null);
            TownLedger.touch(c, profile.worldId());
            return new TownStore.Result(WorldProfiles.load(c, account, knight, auth), view(c, guild.id()));
        });
    }

    /** {@code GuildMarshaller} id 5: deposit crowns into the guild's energy well. */
    public TownStore.Result depositEnergy(int account, int knight, long auth, long guildId, int amount)
            throws IOException {
        if (amount < MIN_DEPOSIT || amount > 100_000) throw new IOException("m.internal_error");
        return database.write(c -> {
            var profile = WorldProfiles.load(c, account, knight, auth);
            var guild = requireMember(c, guildOf(c, profile.worldId()));
            if (guild.id() != guildId) throw new IOException("m.access_denied");
            if (profile.energy() < amount) throw new IOException("m.nsf_energy");
            int total = Math.addExact(guild.energy(), amount);
            try (var s = c.prepareStatement("UPDATE guilds SET energy=? WHERE id=?")) {
                s.setInt(1, total);
                s.setLong(2, guild.id());
                s.executeUpdate();
            }
            TownLedger.pay(c, profile, 0, amount);
            log(c, guild.id(), 9, null, System.currentTimeMillis(), List.of("~" + amount));
            TownLedger.touch(c, profile.worldId());
            return new TownStore.Result(WorldProfiles.load(c, account, knight, auth), view(c, guild.id()));
        });
    }

    /** {@code GuildMarshaller} id 22: take {@code min(100, well)} back to the knight. */
    public TownStore.Result withdrawEnergy(int account, int knight, long auth, long guildId)
            throws IOException {
        return database.write(c -> {
            var profile = WorldProfiles.load(c, account, knight, auth);
            var guild = requireMember(c, guildOf(c, profile.worldId()));
            var member = membership(c, guild.id(), profile.worldId());
            if (guild.id() != guildId || member == null) throw new IOException("m.access_denied");
            long now = System.currentTimeMillis();
            if (member.lastWithdrawal() > 0 && now - member.lastWithdrawal() < WITHDRAWAL_INTERVAL)
                throw new IOException("e.already_withdrew");
            int amount = Math.min(WITHDRAWAL_LIMIT, guild.energy());
            if (amount <= 0) throw new IOException("e.nsf_energy_in_well");
            try (var s = c.prepareStatement("UPDATE guilds SET energy=energy-? WHERE id=? AND energy>=?")) {
                s.setInt(1, amount);
                s.setLong(2, guild.id());
                s.setInt(3, amount);
                if (s.executeUpdate() != 1) throw new IOException("e.nsf_energy_in_well");
            }
            try (var s = c.prepareStatement("UPDATE guild_members SET last_withdrawal=? WHERE world_id=?")) {
                s.setLong(1, now);
                s.setInt(2, profile.worldId());
                s.executeUpdate();
            }
            TownLedger.credit(c, profile.worldId(), 0, amount);
            log(c, guild.id(), 10, member.name(), now, List.of("~" + amount));
            TownLedger.touch(c, profile.worldId());
            return new TownStore.Result(WorldProfiles.load(c, account, knight, auth), view(c, guild.id()));
        });
    }

    /** {@code GuildMarshaller} id 1. Prices are the shipped expansion tickets. */
    public TownStore.Result buyExpansion(int account, int knight, long auth, long guildId, int expansion)
            throws IOException {
        int price = expansionPrice(expansion);
        if (price < 0) throw new IOException("m.internal_error");
        return database.write(c -> {
            var profile = WorldProfiles.load(c, account, knight, auth);
            var guild = requireMember(c, guildOf(c, profile.worldId()));
            if (guild.id() != guildId) throw new IOException("m.access_denied");
            var member = membership(c, guild.id(), profile.worldId());
            if (member == null || member.rank() > 1) throw new IOException("m.access_denied");
            try (var s = c.prepareStatement("SELECT 1 FROM guild_expansions WHERE guild_id=? AND expansion=?")) {
                s.setLong(1, guild.id());
                s.setInt(2, expansion);
                try (var r = s.executeQuery()) { if (r.next()) throw new IOException("m.internal_error"); }
            }
            if (profile.crowns() < price) throw new IOException("m.nsf_crowns");
            TownLedger.pay(c, profile, price, 0);
            try (var s = c.prepareStatement("INSERT INTO guild_expansions(guild_id,expansion) VALUES(?,?)")) {
                s.setLong(1, guild.id());
                s.setInt(2, expansion);
                s.executeUpdate();
            }
            try (var s = c.prepareStatement("UPDATE guilds SET upkeep_cost=upkeep_cost+? WHERE id=?")) {
                s.setInt(1, expansionUpkeep(expansion));
                s.setLong(2, guild.id());
                s.executeUpdate();
            }
            log(c, guild.id(), 21, member.name(), System.currentTimeMillis(), List.of("m.exp." + expansion));
            TownLedger.touch(c, profile.worldId());
            return new TownStore.Result(WorldProfiles.load(c, account, knight, auth), view(c, guild.id()));
        });
    }

    /** {@code GuildMarshaller} id 14; removal refunds nothing, as the archive's own rule. */
    public TownStore.Result removeExpansion(int account, int knight, long auth, long guildId, int expansion)
            throws IOException {
        return database.write(c -> {
            var profile = WorldProfiles.load(c, account, knight, auth);
            var guild = requireMember(c, guildOf(c, profile.worldId()));
            if (guild.id() != guildId) throw new IOException("m.access_denied");
            var member = membership(c, guild.id(), profile.worldId());
            if (member == null || member.rank() > 1) throw new IOException("m.access_denied");
            try (var s = c.prepareStatement("DELETE FROM guild_expansions WHERE guild_id=? AND expansion=?")) {
                s.setLong(1, guild.id());
                s.setInt(2, expansion);
                if (s.executeUpdate() != 1) throw new IOException("m.internal_error");
            }
            try (var s = c.prepareStatement("UPDATE guilds SET upkeep_cost=MAX(0,upkeep_cost-?) WHERE id=?")) {
                s.setInt(1, expansionUpkeep(expansion));
                s.setLong(2, guild.id());
                s.executeUpdate();
            }
            log(c, guild.id(), 22, member.name(), System.currentTimeMillis(), List.of("m.exp." + expansion));
            TownLedger.touch(c, profile.worldId());
            return new TownStore.Result(WorldProfiles.load(c, account, knight, auth), view(c, guild.id()));
        });
    }

    public TownStore.Result setHallGlobal(int account, int knight, long auth, long guildId, int type,
            String ticket) throws IOException {
        return database.write(c -> {
            var profile = WorldProfiles.load(c, account, knight, auth);
            var guild = requireMember(c, guildOf(c, profile.worldId()));
            if (guild.id() != guildId) throw new IOException("m.access_denied");
            var member = membership(c, guild.id(), profile.worldId());
            if (member == null || member.rank() > 1) throw new IOException("m.access_denied");
            if (type != 0 && type != 1) throw new IOException("m.internal_error");
            try (var s = c.prepareStatement("UPDATE guilds SET " + (type == 0 ? "theme" : "environment") + "=? WHERE id=?")) {
                s.setString(1, ticket);
                s.setLong(2, guild.id());
                s.executeUpdate();
            }
            log(c, guild.id(), type == 0 ? 23 : 24, member.name(), System.currentTimeMillis(),
                ticket == null ? null : List.of(ticket));
            TownLedger.touch(c, profile.worldId());
            return new TownStore.Result(WorldProfiles.load(c, account, knight, auth), view(c, guild.id()));
        });
    }

    /** Expansion ticket prices from {@code Guild/Expansion/*} in the shipped item catalogue. */
    public static int expansionPrice(int expansion) {
        return switch (expansion) {
            case 0, 1 -> 15000;            // WEST_1F, EAST_1F
            case 2, 3 -> 30000;            // WEST_2F, EAST_2F
            case 4 -> 45000;               // CENTER_2F
            case 5, 6 -> 60000;            // WEST_3F, EAST_3F
            case 7, 8 -> 75000;            // WEST_4F, EAST_4F
            case 9 -> 90000;               // CENTER_4F
            case 10, 11 -> 105000;         // WEST_5F, EAST_5F
            default -> -1;
        };
    }

    /** Per-expansion upkeep is not stated by a surviving table; zero keeps the well honest. */
    public static int expansionUpkeep(int expansion) { return 0; }

    // ---- read helpers

    static Guild guildOf(Connection c, int worldId) throws SQLException, IOException {
        try (var s = c.prepareStatement("SELECT guild_id FROM guild_members WHERE world_id=?")) {
            s.setInt(1, worldId);
            try (var r = s.executeQuery()) {
                return r.next() ? guildRow(c, r.getLong(1)) : null;
            }
        }
    }

    private static Guild guildRow(Connection c, long guildId) throws SQLException, IOException {
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

    private static View view(Connection c, long guildId) throws SQLException, IOException {
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

    private static Member membership(Connection c, long guildId, int worldId) throws SQLException, IOException {
        if (guildId <= 0) return null;
        for (var member : view(c, guildId).members()) if (member.worldId() == worldId) return member;
        return null;
    }

    private static Guild requireMember(Connection c, Guild guild) throws IOException {
        if (guild == null) throw new IOException("m.internal_error");
        return guild;
    }

    private static Member byName(Connection c, long guildId, String name) throws SQLException, IOException {
        if (name == null) return null;
        for (var member : view(c, guildId).members()) if (member.name().equalsIgnoreCase(name)) return member;
        return null;
    }

    private static boolean canChangeStatus(Member changee, Member changer) {
        return changee.worldId() != changer.worldId() && changer.rank() <= 1
            && (changer.rank() < changee.rank()
                || changer.rank() == changee.rank() && changer.commandOrder() < changee.commandOrder());
    }

    private static int highestOrder(Connection c, long guildId, int rank) throws SQLException {
        try (var s = c.prepareStatement("SELECT COALESCE(MAX(command_order),-2147483648) FROM guild_members WHERE guild_id=? AND rank=?")) {
            s.setLong(1, guildId);
            s.setInt(2, rank);
            try (var r = s.executeQuery()) { r.next(); return r.getInt(1); }
        }
    }

    private static void remove(Connection c, long guildId, int worldId, int type, String actor)
            throws SQLException, IOException {
        boolean master;
        try (var s = c.prepareStatement("SELECT rank FROM guild_members WHERE world_id=? AND guild_id=?")) {
            s.setInt(1, worldId);
            s.setLong(2, guildId);
            try (var r = s.executeQuery()) {
                if (!r.next()) throw new IOException("m.internal_error");
                master = r.getInt(1) == 0;
            }
        }
        try (var s = c.prepareStatement("DELETE FROM guild_members WHERE world_id=? AND guild_id=?")) {
            s.setInt(1, worldId);
            s.setLong(2, guildId);
            if (s.executeUpdate() != 1) throw new IOException("m.internal_error");
        }
        String name;
        try (var s = c.prepareStatement("SELECT k.name FROM world_characters w JOIN knights k ON k.account_id=w.account_id AND k.id=w.knight_id WHERE w.world_id=?")) {
            s.setInt(1, worldId);
            try (var r = s.executeQuery()) { name = r.next() ? r.getString(1) : null; }
        }
        log(c, guildId, type, actor, System.currentTimeMillis(), name == null ? null : List.of("~" + name));
        if (master) promoteSuccessor(c, guildId);
    }

    /** A guild cannot be left masterless: the senior remaining member becomes master. */
    private static void promoteSuccessor(Connection c, long guildId) throws SQLException, IOException {
        int next;
        try (var s = c.prepareStatement("SELECT world_id FROM guild_members WHERE guild_id=? ORDER BY rank,command_order,joined,world_id LIMIT 1")) {
            s.setLong(1, guildId);
            try (var r = s.executeQuery()) {
                if (!r.next()) {
                    // Last member gone: the guild row (and its log) is retired with it.
                    try (var d = c.prepareStatement("DELETE FROM guilds WHERE id=?")) {
                        d.setLong(1, guildId);
                        d.executeUpdate();
                    }
                    return;
                }
                next = r.getInt(1);
            }
        }
        try (var s = c.prepareStatement("UPDATE guild_members SET rank=0,command_order=0 WHERE world_id=? AND guild_id=?")) {
            s.setInt(1, next);
            s.setLong(2, guildId);
            s.executeUpdate();
        }
        log(c, guildId, 4, null, System.currentTimeMillis(), List.of("m.rank.0"));
    }

    static void log(Connection c, long guildId, int type, String knightName, long when,
            List<String> args) throws SQLException, IOException {
        try (var s = c.prepareStatement("INSERT INTO guild_log(guild_id,type,knight_name,when_ms,args) VALUES(?,?,?,?,?)")) {
            s.setLong(1, guildId);
            s.setInt(2, type);
            s.setString(3, knightName);
            s.setLong(4, when);
            s.setString(5, org.tortodrone.core.Json.stringify(args == null ? List.of() : args));
            s.executeUpdate();
        }
    }

    private static Page page(Connection c, long guildId, long cursor, int offset, int count,
            Integer category, String knightName) throws SQLException, IOException {
        var where = new StringBuilder(" FROM guild_log WHERE guild_id=?");
        var args = new ArrayList<Object>();
        args.add(guildId);
        if (cursor != Long.MAX_VALUE) { where.append(" AND id<=?"); args.add(cursor); }
        if (category != null) { where.append(" AND type IN (" + typesForCategory(category) + ")"); }
        if (knightName != null && !knightName.isBlank()) {
            where.append(" AND knight_name=? COLLATE NOCASE");
            args.add(knightName);
        }
        int total;
        try (var s = c.prepareStatement("SELECT count(*)" + where)) {
            bind(s, args);
            try (var r = s.executeQuery()) { r.next(); total = r.getInt(1); }
        }
        var entries = new ArrayList<LogEntry>();
        try (var s = c.prepareStatement("SELECT *" + where + " ORDER BY id DESC LIMIT ? OFFSET ?")) {
            bind(s, args);
            s.setInt(args.size() + 1, count);
            s.setInt(args.size() + 2, offset);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    var raw = (List<?>) org.tortodrone.core.Json.parse(r.getString("args"));
                    entries.add(new LogEntry(r.getLong("id"), r.getInt("type"), r.getString("knight_name"),
                        r.getLong("when_ms"), raw.stream().map(String::valueOf).toList()));
                }
            }
        }
        return new Page(total, entries);
    }

    private static String typesForCategory(int category) {
        return switch (category) {
            case 0 -> "0,1,2,3,4";
            case 1 -> "5,6,7,8";
            case 2 -> "13,14,15,16,17,18";
            case 3 -> "9,10";
            case 4 -> "11,12";
            case 5 -> "19,20,21,22,23,24,25,26,27,28,29,30,31,32,33";
            default -> "-1";
        };
    }

    private static void bind(PreparedStatement s, List<Object> args) throws SQLException {
        for (int i = 0; i < args.size(); i++) {
            Object value = args.get(i);
            if (value instanceof Integer number) s.setInt(i + 1, number);
            else if (value instanceof Long number) s.setLong(i + 1, number);
            else s.setString(i + 1, (String) value);
        }
    }

    private static boolean nameTaken(Connection c, String name) throws SQLException {
        try (var s = c.prepareStatement("SELECT 1 FROM guilds WHERE name_key=?")) {
            s.setString(1, name.trim().toLowerCase(Locale.ROOT));
            try (var r = s.executeQuery()) { return r.next(); }
        }
    }

    private static int worldId(Connection c, String name) throws SQLException, IOException {
        if (name == null) throw new IOException("m.unknown_knight");
        try (var s = c.prepareStatement("SELECT w.world_id FROM world_characters w "
                + "JOIN knights k ON k.account_id=w.account_id AND k.id=w.knight_id WHERE k.name_key=?")) {
            s.setString(1, name.trim().toLowerCase(Locale.ROOT));
            try (var r = s.executeQuery()) {
                if (!r.next()) throw new IOException("m.unknown_knight");
                return r.getInt(1);
            }
        }
    }

    /** Guild names are longer than knight names; the live roster carries names of 16+ letters. */
    private static String nameError(String name) {
        if (name == null) return "m.internal_error";
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 32
                || !trimmed.matches("[A-Za-z0-9][A-Za-z0-9 '\\-]*")) return "m.internal_error";
        return null;
    }
}
