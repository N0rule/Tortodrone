package org.tortodrone.storage;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import org.tortodrone.core.guild.Member;

/** Guild formation, invitations and roster commands, each in one authenticated write. */
final class GuildMembershipStore {
    private final Database database;

    GuildMembershipStore(Database database) {
        this.database = Objects.requireNonNull(database);
    }

    /** {@code GuildMarshaller} id 8: form a guild and make the founder its master. */
    TownStore.Result form(int account, int knight, long auth, String name) throws IOException {
        String error = GuildSettingsStore.nameError(name);
        if (error != null) throw new IOException(error);
        // The name is validated trimmed and probed by its trimmed, case-folded key, so it is stored
        // trimmed as well: a padded " Foo" would otherwise occupy a key the "Foo" duplicate probe
        // never looks at, and the padding would be published to every client.
        String guildName = name.trim();
        return database.write(c -> {
            var profile = WorldProfiles.load(c, account, knight, auth);
            if (GuildStore.guildOf(c, profile.worldId()) != null) throw new IOException("m.already_in_guild");
            if (GuildSettingsStore.nameTaken(c, guildName)) throw new IOException("m.name_in_use");
            if (profile.crowns() < GuildStore.FORMATION_CROWNS) throw new IOException("m.nsf_crowns");
            if (profile.energy() < GuildStore.FORMATION_ENERGY) throw new IOException("m.nsf_energy");
            TownLedger.pay(c, profile, GuildStore.FORMATION_CROWNS, GuildStore.FORMATION_ENERGY);
            long now = System.currentTimeMillis();
            long id;
            try (var s = c.prepareStatement(
                    "INSERT INTO guilds(name,name_key,formed,member_limit) VALUES(?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                s.setString(1, guildName);
                s.setString(2, guildName.toLowerCase(Locale.ROOT));
                s.setLong(3, now);
                s.setInt(4, GuildStore.MAX_MEMBERS);
                s.executeUpdate();
                try (var keys = s.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("Missing guild id");
                    id = keys.getLong(1);
                }
            }
            try (var s = c.prepareStatement(
                    "INSERT INTO guild_members(world_id,guild_id,rank,command_order,joined) VALUES(?,?,0,0,?)")) {
                s.setInt(1, profile.worldId());
                s.setLong(2, id);
                s.setLong(3, now);
                s.executeUpdate();
            }
            GuildLogStore.log(c, id, 0, null, now, null);
            TownLedger.touch(c, profile.worldId());
            return new TownStore.Result(WorldProfiles.load(c, account, knight, auth), GuildStore.view(c, id));
        });
    }

    /** {@code GuildMarshaller} id 10: deliver an invitation letter to a knight by name. */
    TownStore.Result invite(int account, int knight, long auth, String target, String subject, String message)
            throws IOException {
        if (subject == null) subject = "";
        if (message == null) message = "";
        if (subject.length() > 50 || message.length() > 8096) throw new IOException("Invalid mail text");
        String finalSubject = subject, finalMessage = message;
        return database.write(c -> {
            var profile = WorldProfiles.load(c, account, knight, auth);
            var guild = GuildStore.requireMember(c, GuildStore.guildOf(c, profile.worldId()));
            // Authorise before the target name is resolved: otherwise any member could probe which
            // knight names exist from the difference between m.unknown_knight and m.access_denied.
            var member = GuildStore.membership(c, guild.id(), profile.worldId());
            if (member == null || member.rank() > 1) throw new IOException("m.access_denied");
            int targetWorld = worldId(c, target);
            if (profile.worldId() == targetWorld) throw new IOException("m.invite_self");
            if (GuildStore.guildOf(c, targetWorld) != null) throw new IOException("m.already_in_guild");
            try (var s = c.prepareStatement(
                    "INSERT INTO town_mail(world_id,sent,subject,body,sender,mail_type,guild_invite_id,guild_invite_name) VALUES(?,?,?,?,?,2,?,?)")) {
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
            return new TownStore.Result(WorldProfiles.load(c, account, knight, auth), GuildStore.view(c, guild.id()));
        });
    }

    /** Accept the invitation carried by a guild-letter mail. Called inside the mailbox write. */
    static void acceptInvite(Connection c, int worldId, long guildId, String guildName, String knightName)
            throws SQLException, IOException {
        if (guildId <= 0) throw new IOException("m.internal_error");
        if (GuildStore.guildOf(c, worldId) != null) throw new IOException("m.already_in_guild");
        var guild = GuildStore.guildRow(c, guildId);
        if (guild == null || !guild.name().equals(guildName)) throw new IOException("e.guild_not_found");
        int members;
        try (var s = c.prepareStatement("SELECT count(*) FROM guild_members WHERE guild_id=?")) {
            s.setInt(1, (int) guildId);
            try (var r = s.executeQuery()) {
                r.next();
                members = r.getInt(1);
            }
        }
        if (members >= guild.memberLimit()) throw new IOException("m.too_many_members");
        long now = System.currentTimeMillis();
        try (var s = c.prepareStatement(
                "INSERT INTO guild_members(world_id,guild_id,rank,command_order,joined) VALUES(?,?,4,0,?)")) {
            s.setInt(1, worldId);
            s.setLong(2, guildId);
            s.setLong(3, now);
            s.executeUpdate();
        }
        GuildLogStore.log(c, guildId, 0, knightName, now, null);
        TownLedger.touch(c, worldId);
    }

    /** {@code GuildMarshaller} id 11. A master leaving hands the guild to the senior officer. */
    TownStore.Result leave(int account, int knight, long auth) throws IOException {
        return database.write(c -> {
            var profile = WorldProfiles.load(c, account, knight, auth);
            var guild = GuildStore.requireMember(c, GuildStore.guildOf(c, profile.worldId()));
            var member = GuildStore.membership(c, guild.id(), profile.worldId());
            if (member == null) throw new IOException("m.internal_error");
            remove(c, guild.id(), profile.worldId(), 2, null);
            TownLedger.touch(c, profile.worldId());
            return new TownStore.Result(WorldProfiles.load(c, account, knight, auth), null);
        });
    }

    /** {@code GuildMarshaller} id 7. */
    TownStore.Result expel(int account, int knight, long auth, String target) throws IOException {
        return database.write(c -> {
            var profile = WorldProfiles.load(c, account, knight, auth);
            var guild = GuildStore.requireMember(c, GuildStore.guildOf(c, profile.worldId()));
            var changer = GuildStore.membership(c, guild.id(), profile.worldId());
            if (changer == null) throw new IOException("m.access_denied");
            var changee = byName(c, guild.id(), target);
            if (changee == null) throw new IOException("m.unknown_knight");
            if (!canChangeStatus(changee, changer)) throw new IOException("m.access_denied");
            remove(c, guild.id(), changee.worldId(), 1, changer.name());
            TownLedger.touch(c, changee.worldId());
            return new TownStore.Result(WorldProfiles.load(c, account, knight, auth), GuildStore.view(c, guild.id()));
        });
    }

    /** {@code GuildMarshaller} id 3. Ranks are a byte where a smaller value is higher. */
    TownStore.Result changeRank(int account, int knight, long auth, String target, byte rank) throws IOException {
        if (rank < 0 || rank >= GuildStore.RANK_COUNT) throw new IOException("m.internal_error");
        return database.write(c -> {
            var profile = WorldProfiles.load(c, account, knight, auth);
            var guild = GuildStore.requireMember(c, GuildStore.guildOf(c, profile.worldId()));
            var changer = GuildStore.membership(c, guild.id(), profile.worldId());
            if (changer == null || changer.rank() > 1) throw new IOException("m.access_denied");
            var changee = byName(c, guild.id(), target);
            if (changee == null) throw new IOException("m.unknown_knight");
            if (!canChangeStatus(changee, changer) || rank < changer.rank()) throw new IOException("m.access_denied");
            if (changee.rank() == rank) return new TownStore.Result(profile, GuildStore.view(c, guild.id()));
            long now = System.currentTimeMillis();
            // A guild has one master. Promoting a member to master hands the guild over, so the
            // promoter steps down to senior officer first and the single-master index holds for
            // every statement of this transaction.
            if (rank == 0)
                try (var s = c.prepareStatement(
                        "UPDATE guild_members SET rank=1,command_order=? WHERE world_id=? AND guild_id=?")) {
                    s.setInt(1, highestOrder(c, guild.id(), 1) + 1);
                    s.setInt(2, changer.worldId());
                    s.setLong(3, guild.id());
                    s.executeUpdate();
                }
            int order = rank == 0 ? 0 : rank == 1 ? highestOrder(c, guild.id(), rank) + 1 : -2147483647;
            try (var s = c.prepareStatement(
                    "UPDATE guild_members SET rank=?,command_order=? WHERE world_id=? AND guild_id=?")) {
                s.setInt(1, rank);
                s.setInt(2, order);
                s.setInt(3, changee.worldId());
                s.setLong(4, guild.id());
                s.executeUpdate();
            }
            if (rank == 0)
                GuildLogStore.log(
                        c,
                        guild.id(),
                        3,
                        changer.name(),
                        now,
                        List.of("m.gl_demoted", "~" + changer.name(), "m.rank.1"));
            GuildLogStore.log(
                    c,
                    guild.id(),
                    3,
                    changer.name(),
                    now,
                    List.of(
                            rank < changee.rank() ? "m.gl_promoted" : "m.gl_demoted",
                            "~" + changee.name(),
                            "m.rank." + rank));
            TownLedger.touch(c, changee.worldId());
            TownLedger.touch(c, changer.worldId());
            return new TownStore.Result(WorldProfiles.load(c, account, knight, auth), GuildStore.view(c, guild.id()));
        });
    }

    private static Member byName(Connection c, long guildId, String name) throws SQLException, IOException {
        if (name == null) return null;
        for (var member : GuildStore.view(c, guildId).members())
            if (member.name().equalsIgnoreCase(name)) return member;
        return null;
    }

    private static boolean canChangeStatus(Member changee, Member changer) {
        return changee.worldId() != changer.worldId()
                && changer.rank() <= 1
                && (changer.rank() < changee.rank()
                        || changer.rank() == changee.rank() && changer.commandOrder() < changee.commandOrder());
    }

    private static int highestOrder(Connection c, long guildId, int rank) throws SQLException {
        try (var s = c.prepareStatement(
                "SELECT COALESCE(MAX(command_order),-2147483648) FROM guild_members WHERE guild_id=? AND rank=?")) {
            s.setLong(1, guildId);
            s.setInt(2, rank);
            try (var r = s.executeQuery()) {
                r.next();
                return r.getInt(1);
            }
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
        try (var s = c.prepareStatement(
                "SELECT k.name FROM world_characters w JOIN knights k ON k.account_id=w.account_id AND k.id=w.knight_id WHERE w.world_id=?")) {
            s.setInt(1, worldId);
            try (var r = s.executeQuery()) {
                name = r.next() ? r.getString(1) : null;
            }
        }
        GuildLogStore.log(
                c, guildId, type, actor, System.currentTimeMillis(), name == null ? null : List.of("~" + name));
        if (master) promoteSuccessor(c, guildId);
    }

    /** A guild cannot be left masterless: the senior remaining member becomes master. */
    private static void promoteSuccessor(Connection c, long guildId) throws SQLException, IOException {
        int next;
        try (var s = c.prepareStatement(
                "SELECT world_id FROM guild_members WHERE guild_id=? ORDER BY rank,command_order,joined,world_id LIMIT 1")) {
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
        try (var s =
                c.prepareStatement("UPDATE guild_members SET rank=0,command_order=0 WHERE world_id=? AND guild_id=?")) {
            s.setInt(1, next);
            s.setLong(2, guildId);
            s.executeUpdate();
        }
        GuildLogStore.log(c, guildId, 4, null, System.currentTimeMillis(), List.of("m.rank.0"));
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
}
