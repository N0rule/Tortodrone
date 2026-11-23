package org.tortodrone.world;

import java.util.*;
import org.tortodrone.core.world.SceneCollision;
import org.tortodrone.core.world.ScenePortals;
import org.tortodrone.core.world.SceneShape;

/** Collision and contact queries over the shared live entity model. All calls run under the
 *  scene monitor; this system only reads entity positions, flags and shapes and never mutates
 *  the scene. */
final class SceneCollisions {
    private final SceneEntities scene;
    /** Uniform broadphase cell for entity collision/perception queries. */
    private static final float SPATIAL_CELL = 2;

    private final SpatialIndex spatial = new SpatialIndex();

    SceneCollisions(SceneEntities scene) {
        this.scene = scene;
    }

    void add(SceneEntity entity) {
        spatial.add(entity);
    }

    void remove(SceneEntity entity) {
        spatial.remove(entity);
    }

    void moved(SceneEntity entity) {
        spatial.move(entity);
    }

    float reach() {
        return spatial.reach();
    }

    List<SceneEntity> near(float minX, float minY, float maxX, float maxY) {
        return spatial.near(minX, minY, maxX, maxY);
    }
    /** Bounded broadphase lookup. A non-finite window or the linear fallback scans every entity. */
    List<SceneEntity> candidates(float minX, float minY, float maxX, float maxY) {
        if (!scene.spatialQuery()
                || !Float.isFinite(minX)
                || !Float.isFinite(minY)
                || !Float.isFinite(maxX)
                || !Float.isFinite(maxY)
                || Math.abs(minX) > 1e8
                || Math.abs(minY) > 1e8
                || Math.abs(maxX) > 1e8
                || Math.abs(maxY) > 1e8) return List.copyOf(scene.entityValues());
        return spatial.near(minX, minY, maxX, maxY);
    }

    private static final class SpatialIndex {
        private final Map<Long, List<SceneEntity>> cells = new HashMap<>();
        /** Largest inserted extent; queries widen by it instead of rescanning the scene. */
        private float reach;

        private static long key(int x, int y) {
            return ((long) x << 32) ^ (y & 0xffffffffL);
        }

        private static int cell(float value) {
            return (int) Math.floor(value / SPATIAL_CELL);
        }

        void add(SceneEntity entity) {
            remove(entity);
            int minX = cell(entity.x - entity.spatialExtent), maxX = cell(entity.x + entity.spatialExtent);
            int minY = cell(entity.y - entity.spatialExtent), maxY = cell(entity.y + entity.spatialExtent);
            var occupied = new long[(maxX - minX + 1) * (maxY - minY + 1)];
            int index = 0;
            for (int x = minX; x <= maxX; x++)
                for (int y = minY; y <= maxY; y++) {
                    long key = key(x, y);
                    occupied[index++] = key;
                    cells.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entity);
                }
            entity.spatialCells = occupied;
            entity.spatialMinX = minX;
            entity.spatialMaxX = maxX;
            entity.spatialMinY = minY;
            entity.spatialMaxY = maxY;
            reach = Math.max(reach, entity.spatialExtent);
        }
        /** Re-bucket only when the occupied cell block changes; ordinary movement stays put. */
        void move(SceneEntity entity) {
            int minX = cell(entity.x - entity.spatialExtent), maxX = cell(entity.x + entity.spatialExtent);
            int minY = cell(entity.y - entity.spatialExtent), maxY = cell(entity.y + entity.spatialExtent);
            if (entity.spatialCells != null
                    && minX == entity.spatialMinX
                    && maxX == entity.spatialMaxX
                    && minY == entity.spatialMinY
                    && maxY == entity.spatialMaxY) return;
            add(entity);
        }

        float reach() {
            return reach;
        }

        void remove(SceneEntity entity) {
            if (entity.spatialCells == null) return;
            for (long key : entity.spatialCells) {
                var bucket = cells.get(key);
                if (bucket == null) continue;
                bucket.remove(entity);
                if (bucket.isEmpty()) cells.remove(key);
            }
            entity.spatialCells = null;
        }

        List<SceneEntity> near(float minX, float minY, float maxX, float maxY) {
            var found = new TreeMap<Long, SceneEntity>();
            int left = cell(minX), right = cell(maxX), bottom = cell(minY), top = cell(maxY);
            for (int x = left; x <= right; x++)
                for (int y = bottom; y <= top; y++) {
                    var bucket = cells.get(key(x, y));
                    if (bucket == null) continue;
                    for (var entity : bucket) found.put(entity.order, entity);
                }
            return new ArrayList<>(found.values());
        }

        void clear() {
            cells.clear();
            reach = 0;
        }
    }

    boolean walkable(float x, float y, int ignore) {
        for (var e : scene.entityValues()) {
            if (e.id == ignore || e.destroyed != Integer.MAX_VALUE) continue;
            if (e.carrier != 0) continue;
            if (e.kind.equals("Door") && (e.flags & 6) == 2) continue;
            if ((e.collisionFlags & scene.pawnCollisionMask()) != 0 && e.containsPawn(x, y)) return false;
        }
        return true;
    }
    /** Same mask and live actor exclusions as pawn collision, retaining the separating direction.
     *  Closed Lift Barriers additionally block a pawn that is carrying a lift. The served evidence
     *  never states that rule on the raw mask test (the pawn mask is 43, the barrier's lift bit
     *  8192), yet the gym's Lift Barrier doors are the served
     *  {@code DungeonActorSpriteConfig$SubjectiveDoor}: the client judges each door per viewer,
     *  open exactly when the viewer's target actor cannot collide with it, and a knight carrying
     *  a statue reads every closed Lift Barrier as closed ("when you pick up a statue, they go
     *  purple, and you are not supposed to go through them, only if you put down the statue").
     *  With the old raw-mask pass the server kept moving the pawn through a gate the client
     *  closed, which was the reported gradual penetration; matching the client server-side ends
     *  it. A lift at rest releases the pawn again, and an open barrier (open flag set) blocks
     *  nobody. */
    /** Source ids whose static collision must be ignored because the entity is open or destroyed. */
    java.util.Set<Integer> disabledSources() {
        var result = new java.util.HashSet<Integer>();
        for (var e : scene.entityValues()) {
            if (e.collisionFlags == 0) continue;
            boolean disabled = e.destroyed != Integer.MAX_VALUE || e.kind.equals("Door") && (e.flags & 6) == 2;
            if (!disabled) continue;
            try {
                result.add(Integer.parseInt(e.binding.key()));
            } catch (NumberFormatException ignored) {
            }
        }
        return java.util.Set.copyOf(result);
    }

    SceneCollision.Penetration penetration(float x, float y, int ignore) {
        boolean carrying = ignore != 0
                && scene.entityValues().stream()
                        .anyMatch(e -> SceneEntities.liftKind(e.kind)
                                && e.carrier == ignore
                                && e.destroyed == Integer.MAX_VALUE);
        var result = SceneCollision.Penetration.NONE;
        for (var e : scene.entityValues()) {
            if (e.id == ignore
                    || e.destroyed != Integer.MAX_VALUE
                    || e.carrier != 0
                    || e.kind.equals("Door") && (e.flags & 6) == 2) continue;
            if ((e.collisionFlags & scene.pawnCollisionMask()) == 0
                    && !(carrying && e.kind.equals("Door") && (e.collisionFlags & SceneEntities.LIFT_COLLISION) != 0))
                continue;
            if (!e.containsPawn(x, y)) continue;
            result = result.deepest(e.penetration(x, y));
        }
        return result;
    }

    boolean contact(float x, float y, int owner) {
        return contact(x, y, 0, owner, null, -1);
    }
    /** A live monster can carry an attached weapon/orbital tag. */
    boolean attachable(int id) {
        var e = scene.entity(id);
        return e != null && e.kind.equals("Monster") && e.health > 0 && e.destroyed == Integer.MAX_VALUE;
    }

    int contactActor(float x, float y, float rotation, int owner, Object shape, int mask) {
        for (var e : scene.entityValues())
            if (e.id != owner
                    && e.carrier == 0
                    && e.destroyed == Integer.MAX_VALUE
                    && (e.collisionFlags & mask) != 0
                    && !(e.kind.equals("Door") && (e.flags & 2) != 0)
                    && (shape == null || e.shape == null
                            ? e.spec.contains(e.x, e.y, e.rotation, x, y, 0)
                            : SceneCollision.overlaps(shape, x, y, rotation, e.shape, e.x, e.y, e.rotation, 0)))
                return e.id;
        return 0;
    }

    boolean contact(float x, float y, float rotation, int owner, Object shape, int mask) {
        var source = scene.entity(owner);
        if (source != null
                && (source.kind.equals("Monster") || source.kind.equals("Trap"))
                && (mask & 4) != 0
                && scene.occupants().stream()
                        .anyMatch(p -> shape == null
                                ? Math.hypot(p.x() - x, p.y() - y) <= ScenePortals.PAWN_RADIUS
                                : SceneShape.intersects(
                                        shape,
                                        (p.x() - x) * (float) Math.cos(rotation)
                                                + (p.y() - y) * (float) Math.sin(rotation),
                                        -(p.x() - x) * (float) Math.sin(rotation)
                                                + (p.y() - y) * (float) Math.cos(rotation),
                                        ScenePortals.PAWN_RADIUS))) return true;
        return contactActor(x, y, rotation, owner, shape, mask) != 0;
    }
}
