package org.tortodrone.core.world;

import java.util.Set;
import java.util.function.BiPredicate;

/** Exact {@link CollisionMap} view over a compiled {@link MapCollision}. */
public final class ExactCollision implements CollisionMap {
    private final MapCollision collision;
    private final int mask;
    private final float radius;
    private final BiPredicate<Float, Float> extraFloor;

    public ExactCollision(MapCollision collision, int mask, float radius) {
        this(collision, mask, radius, (x, y) -> false);
    }
    /** {@code extraFloor} covers authored moving platforms whose surface bridges a floor shaft. */
    public ExactCollision(MapCollision collision, int mask, float radius, BiPredicate<Float, Float> extraFloor) {
        this.collision = java.util.Objects.requireNonNull(collision);
        this.mask = mask;
        this.radius = radius;
        this.extraFloor = java.util.Objects.requireNonNull(extraFloor);
    }

    @Override
    public boolean walkable(float x, float y) {
        return collision.clear(mask, x, y, radius, Set.of(), extraFloor);
    }

    @Override
    public boolean walkableSegment(float fromX, float fromY, float toX, float toY) {
        return collision.clearSegment(mask, fromX, fromY, toX, toY, radius, Set.of(), extraFloor);
    }
}
