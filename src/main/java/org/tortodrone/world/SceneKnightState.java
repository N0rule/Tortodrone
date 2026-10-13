package org.tortodrone.world;

import org.tortodrone.character.StarterCatalog;

/** Scene-owned item-use animation data and the native carried actor identity. */
public record SceneKnightState(StarterCatalog.Model activityData,int lift,SceneStatus.State status) {
    public SceneKnightState(StarterCatalog.Model activityData,int lift){this(activityData,lift,SceneStatus.State.NORMAL);}
}
