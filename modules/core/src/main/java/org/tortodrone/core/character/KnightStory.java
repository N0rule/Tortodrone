package org.tortodrone.core.character;

import java.util.Map;
import java.util.Set;
import org.tortodrone.core.character.KnightObjective;
import org.tortodrone.core.character.WorldProfile;

/** One committed story transaction: refreshed profile, KNIGHT memory set and objectives. */
public record KnightStory(WorldProfile profile,Set<String> memories,Map<String,KnightObjective.State> objectives){
    public KnightStory{memories=Set.copyOf(memories);objectives=Map.copyOf(objectives);}
}
