package org.tortodrone.core.world;

/** An instance-owned combat feedback; player amounts use the knight HUD encoding. The hit
 *  position is recorded at land time: the client's damage effect attaches numbers to the
 *  target's sprite when it is on screen and falls back to this translation when it is not. */
public record SceneCombatEvent(
        long sequence,
        int timestamp,
        int actor,
        int amount,
        int source,
        float x,
        float y,
        float rotation,
        String performance) {}
