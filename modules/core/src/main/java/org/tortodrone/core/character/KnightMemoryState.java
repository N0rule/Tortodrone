package org.tortodrone.core.character;

import java.util.Set;

/** One committed KNIGHT memory transaction: refreshed profile and the resulting memory set. */
public record KnightMemoryState(WorldProfile profile, Set<String> memories) {
    public KnightMemoryState {
        memories = Set.copyOf(memories);
    }
}
