package org.tortodrone.world;

import java.io.IOException;
import java.nio.file.Path;

/** Immutable collision view for one prepared scene template. */
public interface CollisionMap {
    boolean walkable(float x, float y);
    boolean walkableSegment(float fromX, float fromY, float toX, float toY);
    static CollisionMap read(Path file) throws IOException { return SampledCollision.read(file); }
    static CollisionMap exact(MapCollision collision, int mask, float radius) { return new ExactCollision(collision, mask, radius); }
}
