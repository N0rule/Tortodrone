package org.tortodrone.core.world;

import org.tortodrone.core.world.MapCollision;
import org.tortodrone.core.world.CollisionMap;

/** Exact {@link CollisionMap} view over a compiled {@link MapCollision}. */
public final class ExactCollision implements CollisionMap {
    private final MapCollision collision;
    private final int mask;
    private final float radius;
    public ExactCollision(MapCollision collision, int mask, float radius) {
        this.collision = java.util.Objects.requireNonNull(collision);
        this.mask = mask;
        this.radius = radius;
    }
    @Override public boolean walkable(float x, float y) { return collision.clear(mask, x, y, radius); }
    @Override public boolean walkableSegment(float fromX, float fromY, float toX, float toY) {
        return collision.clearSegment(mask, fromX, fromY, toX, toY, radius);
    }
}
