package org.tortodrone.core.guild;

import java.util.List;

/** Paged guild record. */
public record Page(int entryCount, List<LogEntry> entries) {
    public Page {
        entries = List.copyOf(entries);
    }
}
