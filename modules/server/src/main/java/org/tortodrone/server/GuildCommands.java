package org.tortodrone.server;

import java.io.IOException;
import java.time.Instant;
import org.tortodrone.core.guild.Guild;
import org.tortodrone.core.guild.Member;
import org.tortodrone.storage.GuildStore;

/**
 * Local guild inspection.
 *
 * <p>Read-only on purpose: guild mutations are authorised inside the game service against the
 * caller's stored auth revision, and the console has no knight to authenticate as. Listing and
 * reporting is what an operator needs, and it cannot corrupt a guild.
 */
public final class GuildCommands {
    private GuildCommands() {}

    public static String execute(String line, GuildStore guilds) throws IOException {
        var head = line.strip().split("\\s+", 3);
        if (head.length >= 2 && head[1].equalsIgnoreCase("list")) {
            if (head.length != 2) throw new IOException("Usage: guild list");
            var all = guilds.list();
            if (all.isEmpty()) return "No guilds are formed.";
            var text = new StringBuilder("Guild | Id | Members | Well | Upkeep | Formed");
            for (Guild guild : all) {
                text.append('\n')
                        .append(guild.name())
                        .append(" | ")
                        .append(guild.id())
                        .append(" | ")
                        .append(guilds.view(guild.id()).members().size())
                        .append('/')
                        .append(guild.memberLimit())
                        .append(" | ")
                        .append(guild.energy())
                        .append(" | ")
                        .append(guild.upkeepCost())
                        .append(" | ")
                        .append(stamp(guild.formed()));
            }
            return text.toString();
        }
        if (head.length >= 2 && head[1].equalsIgnoreCase("info")) {
            if (head.length != 3) throw new IOException("Usage: guild info <guild-name>");
            var guild = guilds.named(head[2]);
            if (guild == null) throw new IOException("No such guild: " + head[2]);
            var view = guilds.view(guild.id());
            var text = new StringBuilder("Guild " + guild.name() + " | id=" + guild.id() + " | formed="
                    + stamp(guild.formed()) + " | members=" + view.members().size() + "/" + guild.memberLimit()
                    + " | well=" + guild.energy() + " | upkeep=" + guild.upkeepCost() + " | hallOpen="
                    + guild.hallOpen() + " | theme=" + dash(guild.theme()) + " | environment="
                    + dash(guild.environment()) + " | motd=" + dash(guild.motd()) + " | expansions="
                    + (view.expansions().isEmpty()
                            ? "-"
                            : String.join(
                                    ",",
                                    view.expansions().stream()
                                            .map(String::valueOf)
                                            .toList())));
            for (Member member : view.members())
                text.append('\n')
                        .append("  ")
                        .append(rank(member.rank()))
                        .append(" | ")
                        .append(member.name())
                        .append(" | joined=")
                        .append(stamp(member.joined()))
                        .append(" | lastSession=")
                        .append(member.lastSession() == 0 ? "never" : stamp(member.lastSession()));
            return text.toString();
        }
        if (head.length >= 2 && head[1].equalsIgnoreCase("log")) {
            if (head.length != 3) throw new IOException("Usage: guild log <guild-name>");
            var guild = guilds.named(head[2]);
            if (guild == null) throw new IOException("No such guild: " + head[2]);
            var page = guilds.operatorLog(guild.id(), Long.MAX_VALUE, 0, LOG_PAGE, null, null);
            var text = new StringBuilder("Guild " + guild.name() + " | record entries=" + page.entryCount()
                    + " | newest first, showing " + page.entries().size());
            for (var entry : page.entries())
                text.append('\n')
                        .append("  ")
                        .append(stamp(entry.when()))
                        .append(" | type=")
                        .append(entry.type())
                        .append(" | ")
                        .append(dash(entry.knightName()))
                        .append(" | ")
                        .append(
                                entry.args() == null || entry.args().isEmpty()
                                        ? "-"
                                        : String.join(" · ", entry.args()));
            // The client localizes entry types; printing the stored byte keeps this report honest.
            text.append('\n').append("Entry types are the stored client bytes; the game client localizes them.");
            return text.toString();
        }
        throw new IOException("Usage: guild list | guild info <guild-name> | guild log <guild-name>");
    }

    /** One console page of the record; the WebUI pages the same store reads. */
    private static final int LOG_PAGE = 20;

    /** Stock rank bytes: 0 master, 1 officer, 2 member, 3 veteran, 4 recruit. */
    public static String rank(byte rank) {
        return switch (rank) {
            case 0 -> "Master";
            case 1 -> "Officer";
            case 2 -> "Member";
            case 3 -> "Veteran";
            case 4 -> "Recruit";
            default -> "Rank-" + rank;
        };
    }

    private static String stamp(long millis) {
        return millis <= 0 ? "-" : Instant.ofEpochMilli(millis).toString();
    }

    private static String dash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
