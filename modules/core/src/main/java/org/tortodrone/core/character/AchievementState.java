package org.tortodrone.core.character;

import java.util.Map;
import java.util.TreeMap;

/** Account-scoped native achievement receipts and lifetime stat counters.
 *
 * <p>Unlocked ordinals are the installed client's {@code Achievement.Type} enum
 * ordinals; counters are {@code StatType} enum ordinals. Nothing here invents a
 * type: the tables live in {@link NativeAchievements} and {@link NativeStats} and
 * are checked against the client's own {@code qualifies(PlayerObject)} code.
 */
public record AchievementState(Map<Integer, Long> unlocked, Map<Integer, Integer> counters) {
    public static final AchievementState EMPTY = new AchievementState(Map.of(), Map.of());

    public AchievementState {
        var receipts = new TreeMap<Integer, Long>();
        for (var entry : unlocked.entrySet()) {
            int type = entry.getKey();
            long at = entry.getValue();
            if (type < 0 || type >= NativeAchievements.count())
                throw new IllegalArgumentException("Unknown achievement type " + type);
            if (at < 1) throw new IllegalArgumentException("Achievement unlock time must be positive");
            receipts.put(type, at);
        }
        var values = new TreeMap<Integer, Integer>();
        for (var entry : counters.entrySet()) {
            int stat = entry.getKey();
            int value = entry.getValue();
            if (stat < 0 || stat >= NativeStats.count())
                throw new IllegalArgumentException("Unknown lifetime stat " + stat);
            if (value < 0) throw new IllegalArgumentException("Lifetime stat cannot be negative");
            if (value != 0) values.put(stat, value);
        }
        unlocked = Map.copyOf(receipts);
        counters = Map.copyOf(values);
    }

    public AchievementState withUnlocked(Map<Integer, Long> additions) {
        if (additions.isEmpty()) return this;
        var merged = new TreeMap<>(unlocked);
        for (var entry : additions.entrySet()) {
            if (entry.getValue() < 1) throw new IllegalArgumentException("Achievement unlock time must be positive");
            merged.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return new AchievementState(merged, counters);
    }

    public AchievementState withCounters(Map<Integer, Integer> values) {
        return new AchievementState(unlocked, values);
    }
}
