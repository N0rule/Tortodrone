package org.tortodrone.world;

import org.tortodrone.core.world.MonsterArchetype;

/**
 * Scheduling band for one scene monster.
 *
 * <p>Dormant monsters keep their entity and publication state but perform only a cheap
 * distance check. Alert monsters acquire targets at a quarter-second cadence. Engaged
 * monsters run the full per-tick pursuit and attack path. Intervals are staggered by the
 * entity id so a wave does not wake on the same tick, and are derived only from the id and
 * the monster's archetype so a replay of the same tick sequence always makes the same
 * decisions.
 */
enum SceneMonsterBand {
    DORMANT,
    ALERT,
    ENGAGED;

    /** Milliseconds until this band's next decision tick for the default policy. */
    int thinkInterval(int id) {
        return thinkInterval(id, MonsterArchetype.fallback());
    }

    /** Milliseconds until this band's next decision tick under an archetype policy. */
    int thinkInterval(int id, MonsterArchetype archetype) {
        return switch (this) {
            case DORMANT -> archetype.dormantThinkInterval(id);
            case ALERT -> archetype.alertThinkMillis();
            case ENGAGED -> archetype.engagedThinkMillis();
        };
    }
}
