package org.tortodrone.world;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Scheduling policy for one family of scene monsters.
 *
 * <p>An archetype never contains attacks, damage or timings: the exported native action graph
 * remains the only source of behaviour. It only decides when the engine looks at a monster
 * (activation, think cadence, pursuit re-planning) and how far it may stray from home. The
 * policies are hand-reviewed constants; a generated catalogue may only name one of these ids.
 */
public record MonsterArchetype(
        String id,
        float activationRadius,
        float leashRadius,
        int dormantThinkMinMillis,
        int dormantThinkMaxMillis,
        int alertThinkMillis,
        int engagedThinkMillis,
        int repathMillis) {
    /** Rollback policy: every unknown or un-migrated monster keeps this shape. */
    public static final String DEFAULT_ID = "melee-chaser";
    /** The escape hatch is deliberately capped by the roadmap; six encounters are enough. */
    public static final int MAX_SCRIPTED_BOSSES = 6;
    public static final String BOSS_SCRIPTED_ID = "boss-scripted";
    private static final Map<String, MonsterArchetype> ALL;

    static {
        var all = new LinkedHashMap<String, MonsterArchetype>();
        register(all, new MonsterArchetype(DEFAULT_ID, 32, 0, 1000, 1999, 250, 50, 0));
        // Ranged families wake from further away and re-check targets a little faster.
        register(all, new MonsterArchetype("ranged-kiter", 40, 0, 1200, 2199, 200, 50, 0));
        // Bombers hold a short leash so area denial stays near the authored encounter.
        register(all, new MonsterArchetype("bomber", 20, 24, 1200, 2199, 250, 50, 250));
        // Summoners stay put and let their native spawn actions do the work.
        register(all, new MonsterArchetype("summoner", 48, 0, 1400, 2399, 300, 50, 500));
        // Sentries wake only at close range and reacquire slowly.
        register(all, new MonsterArchetype("stationary", 20, 0, 1000, 1999, 500, 50, 0));
        // Explicitly listed bosses react faster; attacks still come from the native graph.
        register(all, new MonsterArchetype(BOSS_SCRIPTED_ID, 64, 0, 1000, 1999, 100, 25, 0));
        ALL = Map.copyOf(all);
    }

    public MonsterArchetype {
        if (id == null || !id.matches("[a-z][a-z0-9-]{0,31}"))
            throw new IllegalArgumentException("Invalid monster archetype id");
        finite(activationRadius, "activation radius");
        if (activationRadius < 1 || activationRadius > 512)
            throw new IllegalArgumentException("Monster archetype activation radius must be 1..512");
        finite(leashRadius, "leash radius");
        if (leashRadius < 0 || leashRadius > 1024)
            throw new IllegalArgumentException("Monster archetype leash radius must be 0..1024");
        if (leashRadius > 0 && leashRadius < activationRadius)
            throw new IllegalArgumentException("Monster archetype leash radius must not be smaller than its activation radius");
        if (dormantThinkMinMillis < 100 || dormantThinkMaxMillis > 10_000
                || dormantThinkMinMillis > dormantThinkMaxMillis)
            throw new IllegalArgumentException("Invalid monster archetype dormant think interval");
        if (alertThinkMillis < 10 || alertThinkMillis > 10_000
                || engagedThinkMillis < 10 || engagedThinkMillis > 10_000)
            throw new IllegalArgumentException("Invalid monster archetype think interval");
        if (repathMillis < 0 || repathMillis > 10_000)
            throw new IllegalArgumentException("Invalid monster archetype repath interval");
    }

    /** Resolve a catalogue/overlay policy id; unknown ids are a data error, never a default. */
    public static MonsterArchetype of(String id) {
        MonsterArchetype archetype = id == null ? null : ALL.get(id);
        if (archetype == null) throw new IllegalArgumentException("Unknown monster archetype: " + id);
        return archetype;
    }

    public static boolean known(String id) {
        return id != null && ALL.containsKey(id);
    }

    public static MonsterArchetype fallback() { return of(DEFAULT_ID); }

    public static List<MonsterArchetype> all() { return List.copyOf(ALL.values()); }

    /** Stable millisecond cadence for one entity, derived only from its id. */
    public int dormantThinkInterval(int entityId) {
        return dormantThinkMinMillis
            + (int) Math.floorMod((long) entityId * 37L, (long) dormantThinkMaxMillis - dormantThinkMinMillis + 1);
    }

    private static void register(Map<String, MonsterArchetype> all, MonsterArchetype archetype) {
        Objects.requireNonNull(archetype);
        if (all.putIfAbsent(archetype.id(), archetype) != null)
            throw new IllegalStateException("Duplicate monster archetype: " + archetype.id());
    }

    private static void finite(float value, String name) {
        if (!Float.isFinite(value))
            throw new IllegalArgumentException("Monster archetype " + name + " must be finite");
    }
}
