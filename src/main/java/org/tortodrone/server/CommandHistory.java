package org.tortodrone.server;

import java.time.Instant;
import org.jline.reader.impl.history.DefaultHistory;

/** Bounded, memory-only history of all command input; sensitive prompts use another reader. */
final class CommandHistory extends DefaultHistory {
    static final int LIMIT = 100;

    @Override public void add(Instant time, String line) {
        if (allowed(line)) super.add(time, line.trim());
    }

    static boolean allowed(String line) {
        return line != null && line.length() <= 4096 && !line.isBlank();
    }
}
