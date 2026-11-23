package org.tortodrone.storage;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import org.tortodrone.core.Json;
import org.tortodrone.core.guild.LogEntry;
import org.tortodrone.core.guild.Page;

/** Guild record inserts and cursor paging, including the stock category groups. */
final class GuildLogStore {
    private GuildLogStore() {}

    static void log(Connection c, long guildId, int type, String knightName, long when, List<String> args)
            throws SQLException, IOException {
        try (var s =
                c.prepareStatement("INSERT INTO guild_log(guild_id,type,knight_name,when_ms,args) VALUES(?,?,?,?,?)")) {
            s.setLong(1, guildId);
            s.setInt(2, type);
            s.setString(3, knightName);
            s.setLong(4, when);
            s.setString(5, Json.stringify(args == null ? List.of() : args));
            s.executeUpdate();
        }
    }

    static Page page(
            Connection c, long guildId, long cursor, int offset, int count, Integer category, String knightName)
            throws SQLException, IOException {
        var where = new StringBuilder(" FROM guild_log WHERE guild_id=?");
        var args = new ArrayList<Object>();
        args.add(guildId);
        if (cursor != Long.MAX_VALUE) {
            where.append(" AND id<=?");
            args.add(cursor);
        }
        if (category != null) {
            where.append(" AND type IN (" + typesForCategory(category) + ")");
        }
        if (knightName != null && !knightName.isBlank()) {
            where.append(" AND knight_name=? COLLATE NOCASE");
            args.add(knightName);
        }
        int total;
        try (var s = c.prepareStatement("SELECT count(*)" + where)) {
            bind(s, args);
            try (var r = s.executeQuery()) {
                r.next();
                total = r.getInt(1);
            }
        }
        var entries = new ArrayList<LogEntry>();
        try (var s = c.prepareStatement("SELECT *" + where + " ORDER BY id DESC LIMIT ? OFFSET ?")) {
            bind(s, args);
            s.setInt(args.size() + 1, count);
            s.setInt(args.size() + 2, offset);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    var raw = (List<?>) Json.parse(r.getString("args"));
                    entries.add(new LogEntry(
                            r.getLong("id"),
                            r.getInt("type"),
                            r.getString("knight_name"),
                            r.getLong("when_ms"),
                            raw.stream().map(String::valueOf).toList()));
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
}
