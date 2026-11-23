package org.tortodrone.core.world;

/** Immutable collision view for one prepared scene template. */
public interface CollisionMap {
    boolean walkable(float x, float y);

    boolean walkableSegment(float fromX, float fromY, float toX, float toY);

    static CollisionMap exact(MapCollision collision, int mask, float radius) {
        return new ExactCollision(collision, mask, radius);
    }
}
