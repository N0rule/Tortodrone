package org.tortodrone.world;

import org.tortodrone.core.character.KnightProgress;
import org.tortodrone.core.town.TownState;

/** Scene-owned item-use/box-opening data and the native carried actor identity. */
public record SceneKnightState(Object activityData,int lift,SceneStatus.State status,org.tortodrone.core.character.KnightProgress progress,org.tortodrone.core.town.TownState.Sprite sprite,org.tortodrone.core.town.TownState.Harness harness,int spriteActor,int spriteAttack,int hitTime) {
    public SceneKnightState {if(activityData instanceof java.util.List<?> list)activityData=java.util.List.copyOf(list);}
    public SceneKnightState(Object activityData,int lift,SceneStatus.State status,org.tortodrone.core.character.KnightProgress progress,org.tortodrone.core.town.TownState.Sprite sprite,org.tortodrone.core.town.TownState.Harness harness,int spriteActor,int spriteAttack){this(activityData,lift,status,progress,sprite,harness,spriteActor,spriteAttack,0);}
    public SceneKnightState(Object activityData,int lift,SceneStatus.State status,org.tortodrone.core.character.KnightProgress progress,org.tortodrone.core.town.TownState.Sprite sprite,org.tortodrone.core.town.TownState.Harness harness){this(activityData,lift,status,progress,sprite,harness,0,0);}
    public SceneKnightState(Object activityData,int lift,SceneStatus.State status,org.tortodrone.core.character.KnightProgress progress){this(activityData,lift,status,progress,null,null);}
    public SceneKnightState(Object activityData,int lift,SceneStatus.State status){this(activityData,lift,status,org.tortodrone.core.character.KnightProgress.RECRUIT);}
    public SceneKnightState(Object activityData,int lift){this(activityData,lift,SceneStatus.State.NORMAL);}
}
