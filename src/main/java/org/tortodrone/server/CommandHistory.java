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
        if (command.equals("knight")) {
            return words.length >= 3 && words[2].matches("[A-Za-z][A-Za-z0-9_-]{2,31}")
                && (words.length == 3 && words[1].equals("list")
                    || words.length == 4 && words[1].equals("info") && words[3].matches("[A-Za-z]+(?:-[A-Za-z]+)*") && words[3].length() <= 16);
        }
        if(command.equals("currency")) {
            if(words.length!=6||!(words[1].equals("give")||words[1].equals("withdraw"))||!words[2].matches("[A-Za-z][A-Za-z0-9_-]{2,31}")||!words[3].matches("[A-Za-z]+(?:-[A-Za-z]+)*")||words[3].length()>16||!(words[4].equals("crowns")||words[4].equals("energy")))return false;
            try {long n=Long.parseLong(words[5]);return n>0&&n<=Integer.MAX_VALUE;}catch(NumberFormatException invalid){return false;}
        }
        if (!command.equals("account")) return false;
        if (words.length == 2) return words[1].equals("list");
        if (!words[2].matches("[A-Za-z][A-Za-z0-9_-]{2,31}")) return false;
        return switch (words[1]) {
            case "create" -> words.length == 3 || words.length == 4 && (words[3].equals("gm") || words[3].equals("admin"));
            case "password", "check", "delete" -> words.length == 3;
            case "gm", "admin" -> words.length == 4 && (words[3].equals("true") || words[3].equals("false"));
            default -> false;
        };
    }
}
