package org.tortodrone.core.character;

import java.util.Set;
import org.tortodrone.core.character.WorldProfile;

/** One committed KNIGHT memory transaction: refreshed profile and the resulting memory set. */
public record KnightMemoryState(WorldProfile profile,Set<String> memories){
    public KnightMemoryState{memories=Set.copyOf(memories);}
}
