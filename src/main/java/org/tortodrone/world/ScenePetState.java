package org.tortodrone.world;

import java.util.List;
import org.tortodrone.town.TownState;

/** Native dungeon companion state, owned by one scene and one authenticated knight. */
public record ScenePetState(int knight,TownState.Sprite sprite,TownState.Harness harness,boolean follow,
        List<Integer> nextSkill,int shieldHealth,int shieldMaximum,String shieldPower) {
    public ScenePetState { nextSkill=List.copyOf(nextSkill); }
}
