package org.tortodrone.core.guild;

import java.util.List;

/** One guild-record entry. */
public record LogEntry(long id, int type, String knightName, long when, List<String> args) {
    public LogEntry {
        args = args == null ? null : List.copyOf(args);
    }
}
