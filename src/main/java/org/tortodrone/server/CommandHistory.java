package org.tortodrone.server;

import java.time.Instant;
import java.util.Locale;
import org.jline.reader.impl.history.DefaultHistory;

/** Only recognized command syntax enters history; rejected input may contain a misplaced password. */
final class CommandHistory extends DefaultHistory {
    static final int LIMIT = 100;

    @Override public void add(Instant time, String line) {
        if (allowed(line)) super.add(time, line.trim());
    }

    static boolean allowed(String line) {
        if (line == null || line.length() > 4096) return false;
        line = line.trim(); if (line.startsWith("/")) line = line.substring(1);
        String[] words = line.split("\\s+");
        String command = words[0].toLowerCase(Locale.ROOT);
        if (words.length == 1) return command.equals("help") || command.equals("status") || command.equals("stop");
        if (!command.equals("account")) return false;
        if (words.length == 2) return words[1].equals("list");
        if (!words[2].matches("[A-Za-z][A-Za-z0-9_-]{2,31}")) return false;
        return switch (words[1]) {
            case "create" -> words.length == 3 || words.length == 4 && words[3].equals("admin");
            case "password", "check", "delete" -> words.length == 3;
            case "admin" -> words.length == 4 && (words[3].equals("true") || words[3].equals("false"));
            default -> false;
        };
    }
}
