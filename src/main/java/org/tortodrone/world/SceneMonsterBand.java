package org.tortodrone.world;

/**
 * Scheduling band for one scene monster.
 *
 * <p>Dormant monsters keep their entity and publication state but perform only a cheap
 * distance check. Alert monsters acquire targets at a quarter-second cadence. Engaged
 * monsters run the full per-tick pursuit and attack path. Intervals are staggered by the
 * entity id so a wave does not wake on the same tick, and are derived only from the id so
 * a replay of the same tick sequence always makes the same decisions.
 */
enum SceneMonsterBand {
    DORMANT,
    ALERT,
    ENGAGED;

    /** Milliseconds until this band's next decision tick. */
    int thinkInterval(int id) {
        return switch (this) {
            case DORMANT -> 1000 + (int) Math.floorMod((long) id * 37L, 1000L);
            case ALERT -> 250;
            case ENGAGED -> 50;
        };
    }
}
