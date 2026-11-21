package org.tortodrone.world;

import java.util.List;
import java.util.Objects;

/**
 * One authored wave of catalogue-backed monsters.
 *
 * <p>Waves are scene runtime state, never catalogue data: the type id names the definition, the
 * anchors say where it appears, and the caps stay server-side policy. Every group carries the
 * scene generation it was created for, so a generation change drops it instead of leaking spawns
 * into a later instance.
 */
public record MonsterGroup(
        String id,
        String typeId,
        List<ScenePathing.Point> anchors,
        int total,
        int maxAlive,
        int intervalMs,
        int respawnDelayMs,
        String onCleared,
        long generation) {
    public MonsterGroup {
        if (id == null || !id.matches("[a-z0-9][a-z0-9_]{0,63}"))
            throw new IllegalArgumentException("Invalid monster group id");
        if (typeId == null || !typeId.matches("[a-z0-9][a-z0-9_]{0,95}"))
            throw new IllegalArgumentException("Invalid monster group type id");
        anchors = List.copyOf(Objects.requireNonNull(anchors, "anchors"));
        if (anchors.isEmpty() || anchors.size() > 16)
            throw new IllegalArgumentException("A monster group needs 1 to 16 anchors");
        for (var anchor : anchors)
            if (anchor == null || !Float.isFinite(anchor.x()) || !Float.isFinite(anchor.y()))
                throw new IllegalArgumentException("Invalid monster group anchor");
        if (total < 1 || total > 4096)
            throw new IllegalArgumentException("A monster group needs a positive bounded total");
        if (maxAlive < 1 || maxAlive > 64 || maxAlive > total)
            throw new IllegalArgumentException("A monster group needs 1 to 64 concurrently alive spawns");
        if (intervalMs < 0 || intervalMs > 3_600_000)
            throw new IllegalArgumentException("Invalid monster group spawn interval");
        if (respawnDelayMs < 0 || respawnDelayMs > 3_600_000)
            throw new IllegalArgumentException("Invalid monster group respawn delay");
        onCleared = onCleared == null ? "" : onCleared;
        if (!onCleared.isEmpty() && (onCleared.length() > 128 || onCleared.isBlank()))
            throw new IllegalArgumentException("Invalid monster group cleared signal");
        if (generation < 1)
            throw new IllegalArgumentException("Invalid monster group generation");
    }

    public MonsterGroup(String id, String typeId, List<ScenePathing.Point> anchors,
            int total, int maxAlive, int intervalMs, int respawnDelayMs, long generation) {
        this(id, typeId, anchors, total, maxAlive, intervalMs, respawnDelayMs, "", generation);
    }

    public MonsterGroup(String id, String typeId, ScenePathing.Point anchor,
            int total, int maxAlive, int intervalMs, int respawnDelayMs, long generation) {
        this(id, typeId, List.of(anchor), total, maxAlive, intervalMs, respawnDelayMs, "", generation);
    }

    public MonsterGroup(String id, String typeId, ScenePathing.Point anchor,
            int total, int maxAlive, int intervalMs, int respawnDelayMs, String onCleared, long generation) {
        this(id, typeId, List.of(anchor), total, maxAlive, intervalMs, respawnDelayMs, onCleared, generation);
    }
}
