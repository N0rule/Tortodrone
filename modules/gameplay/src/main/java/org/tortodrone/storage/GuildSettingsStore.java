package org.tortodrone.storage;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import org.tortodrone.core.Json;

/** Guild name policy, the rename window and the message of the day. Formation and
 *  roster commands live in {@link GuildMembershipStore}; these are the guild-profile
 *  settings every command shares. */
final class GuildSettingsStore {
    private final Database database;
    GuildSettingsStore(Database database){this.database=database;}

    /** {@code GuildMarshaller} id 4, the same gates {@code formGuild} applies to a name. */
    void checkName(String name) throws IOException {
        String error = nameError(name);
        if (error != null) throw new IOException(error);
        database.read(c -> {
            if (nameTaken(c, name)) throw new IOException("m.name_in_use");
            return null;
        });
    }

    /** {@code GuildMarshaller} id 2: the rename window needs a ticket and a 30-day interval. */
    void canRename(int account, int knight, long auth) throws IOException {
        database.read(c -> {
            var profile = WorldProfiles.load(c, account, knight, auth);
            var guild = GuildStore.requireMember(c, GuildStore.guildOf(c, profile.worldId()));
            var member = GuildStore.membership(c, guild.id(), profile.worldId());
            if (member == null || member.rank() != 0) throw new IOException("m.access_denied");
            if (guild.renamed() > 0 && System.currentTimeMillis() - guild.renamed() < GuildStore.RENAME_INTERVAL)
                throw new IOException("m.rename_too_soon");
            if (profile.items().stream().noneMatch(i -> i.config().equals(GuildStore.RENAME_ITEM)))
                throw new IOException("m.no_rename_item");
            return null;
        });
    }

    /** {@code GuildMarshaller} id 15: consumes the rename ticket with the new name. */
    TownStore.Result rename(int account, int knight, long auth, String name) throws IOException {
        String error = nameError(name);
        if (error != null) throw new IOException(error);
        return database.write(c -> {
            var profile = WorldProfiles.load(c, account, knight, auth);
            var guild = GuildStore.requireMember(c, GuildStore.guildOf(c, profile.worldId()));
            var member = GuildStore.membership(c, guild.id(), profile.worldId());
            if (member == null || member.rank() != 0) throw new IOException("m.access_denied");
            if (nameTaken(c, name) && !guild.name().equalsIgnoreCase(name)) throw new IOException("m.name_in_use");
            if (guild.renamed() > 0 && System.currentTimeMillis() - guild.renamed() < GuildStore.RENAME_INTERVAL)
                throw new IOException("m.rename_too_soon");
            var ticket = profile.items().stream().filter(i -> i.config().equals(GuildStore.RENAME_ITEM)).findFirst()
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
            return new TownStore.Result(WorldProfiles.load(c, account, knight, auth), GuildStore.view(c, guild.id()));
        });
    }

    /** {@code GuildMarshaller} id 18. Only an officer or master may write the message of the day. */
    TownStore.Result setMotd(int account, int knight, long auth, long guildId, String motd)
            throws IOException {
        if (motd == null) motd = "";
        if (motd.length() > GuildStore.MOTD_LENGTH) throw new IOException("Invalid message of the day");
        String value = motd;
        return database.write(c -> {
            var profile = WorldProfiles.load(c, account, knight, auth);
            var guild = GuildStore.requireMember(c, GuildStore.guildOf(c, profile.worldId()));
            if (guild.id() != guildId) throw new IOException("m.access_denied");
            var member = GuildStore.membership(c, guild.id(), profile.worldId());
            if (member == null || member.rank() > 1) throw new IOException("m.access_denied");
            try (var s = c.prepareStatement("UPDATE guilds SET motd=? WHERE id=?")) {
                s.setString(1, value);
                s.setLong(2, guild.id());
                s.executeUpdate();
            }
            GuildLogStore.log(c, guild.id(), 28, member.name(), System.currentTimeMillis(), null);
            TownLedger.touch(c, profile.worldId());
            return new TownStore.Result(WorldProfiles.load(c, account, knight, auth), GuildStore.view(c, guild.id()));
        });
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
