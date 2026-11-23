package org.tortodrone.core.world;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Exact static collision compiled from a map's own scene.
 *
 * <p>Unlike the sampled walkability grid, this keeps the native 1-unit tile masks and the native
 * placeable shapes. A uniform grid indexes the shapes so a query touches only the shapes near the
 * moving body; this is the property that matters when a deployment serves hundreds of maps and
 * thousands of props.
 *
 * <p>The file is build output, never authored. Its layout-hash field ties it to the exact
 * {@code scene.dat} it was compiled from, so a body and its collision cannot silently drift.
 */
public final class MapCollision {
    private static final float GRID = 4f;

    public record Penetration(float x, float y) {
        public static final Penetration NONE = new Penetration(0, 0);

        public boolean present() {
            return x != 0 || y != 0;
        }

        public Penetration deepest(Penetration other) {
            return other.x * other.x + other.y * other.y > x * x + y * y ? other : this;
        }
    }

    sealed interface Geom permits Circle, Capsule, Polygon, Rail {}

    record Point(float x, float y) {}

    record Circle(Point center, float radius) implements Geom {}

    record Capsule(Point a, Point b, float radius) implements Geom {}

    record Polygon(List<Point> points) implements Geom {}

    record Rail(Point a, Point b, Bounds gate) implements Geom {}

    record Bounds(float minX, float minY, float maxX, float maxY) {
        boolean intersects(Bounds o) {
            return minX <= o.maxX && maxX >= o.minX && minY <= o.maxY && maxY >= o.minY;
        }

        boolean contains(float x, float y) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY;
        }
    }

    record Shape(int id, int mask, int source, Geom geometry, Bounds bounds) {}

    private final long[] tileKeys;
    private final int[] tileMasks;
    private final long[] floorKeys;
    private final List<Shape> shapes;
    private final Bounds bounds;
    private final Grid grid;
    private final String layoutHash;

    MapCollision(
            long[] tileKeys, int[] tileMasks, long[] floorKeys, List<Shape> shapes, Bounds bounds, String layoutHash) {
        this.tileKeys = tileKeys;
        this.tileMasks = tileMasks;
        this.floorKeys = floorKeys;
        this.shapes = List.copyOf(shapes);
        this.bounds = bounds;
        this.layoutHash = layoutHash;
        this.grid = new Grid(bounds, shapes);
    }

    public String layoutHash() {
        return layoutHash;
    }

    long[] tileKeys() {
        return tileKeys.clone();
    }

    int[] tileMasks() {
        return tileMasks.clone();
    }

    long[] floorKeys() {
        return floorKeys.clone();
    }

    List<Shape> shapes() {
        return shapes;
    }

    public int shapeCount() {
        return shapes.size();
    }

    public int tileCount() {
        return tileKeys.length;
    }

    Bounds bounds() {
        return bounds;
    }

    /** True when the point is on native floor: a floor tile or a floorTile placeable. */
    public boolean floorSupported(float x, float y) {
        if (!Float.isFinite(x) || !Float.isFinite(y)) return false;
        int cx = floorInt(x), cy = floorInt(y);
        if (Arrays.binarySearch(floorKeys, key(cx, cy)) >= 0) return true;
        for (Shape shape : grid.query(x, y, 0)) {
            if (shape.mask == 0 && containsPoint(shape.geometry, x, y)) return true;
        }
        return false;
    }

    /** True when a circle can stand at (x,y) without touching a walk-masked tile or shape. */
    public boolean clear(int mask, float x, float y, float radius) {
        return clear(mask, x, y, radius, Set.of());
    }

    public boolean clear(int mask, float x, float y, float radius, Set<Integer> disabled) {
        return clear(mask, x, y, radius, disabled, (extraX, extraY) -> false);
    }
    /** Extra floor support for authored moving platforms (native elevators) that bridge a floor shaft. */
    public boolean clear(
            int mask,
            float x,
            float y,
            float radius,
            Set<Integer> disabled,
            java.util.function.BiPredicate<Float, Float> extraFloor) {
        if (!valid(x, y) || !Float.isFinite(radius) || radius < 0) return false;
        if (!floorSupported(x, y) && !extraFloor.test(x, y)) return false;
        if (tileHit(mask, x, y, radius)) return false;
        for (Shape shape : grid.query(x, y, radius)) {
            if (shape.source != 0 && disabled.contains(shape.source)) continue;
            if ((shape.mask & mask) != 0 && circleIntersects(shape.geometry, x, y, radius)) return false;
        }
        return true;
    }

    /** Sweeps a circle from (fromX,fromY) to (toX,toY); broadphase candidates are collected once. */
    public boolean clearSegment(int mask, float fromX, float fromY, float toX, float toY, float radius) {
        return clearSegment(mask, fromX, fromY, toX, toY, radius, Set.of());
    }

    public boolean clearSegment(
            int mask, float fromX, float fromY, float toX, float toY, float radius, Set<Integer> disabled) {
        return clearSegment(mask, fromX, fromY, toX, toY, radius, disabled, (extraX, extraY) -> false);
    }
    /** Extra floor support for authored moving platforms (native elevators) that bridge a floor shaft. */
    public boolean clearSegment(
            int mask,
            float fromX,
            float fromY,
            float toX,
            float toY,
            float radius,
            Set<Integer> disabled,
            java.util.function.BiPredicate<Float, Float> extraFloor) {
        if (!valid(fromX, fromY) || !valid(toX, toY) || !Float.isFinite(radius) || radius < 0) return false;
        float distance = (float) Math.hypot(toX - fromX, toY - fromY);
        if (!Float.isFinite(distance) || distance > 10_000) return false;
        int steps = Math.max(1, (int) Math.ceil(distance / Math.max(.05f, radius / 4f)));
        // One query over the swept bounds, not one query per step.
        float minX = Math.min(fromX, toX) - radius, maxX = Math.max(fromX, toX) + radius;
        float minY = Math.min(fromY, toY) - radius, maxY = Math.max(fromY, toY) + radius;
        var candidates = grid.query(minX, minY, maxX, maxY);
        for (int i = 0; i <= steps; i++) {
            float t = (float) i / steps, x = fromX + (toX - fromX) * t, y = fromY + (toY - fromY) * t;
            if (!floorSupported(x, y) && !extraFloor.test(x, y) || tileHit(mask, x, y, radius)) return false;
            for (Shape shape : candidates) {
                if (shape.source != 0 && disabled.contains(shape.source)) continue;
                if ((shape.mask & mask) != 0 && circleIntersects(shape.geometry, x, y, radius)) return false;
            }
        }
        return true;
    }

    /** Minimum translation that moves the circle out of walk geometry, or NONE. */
    public Penetration penetration(int mask, float x, float y, float radius) {
        return penetration(mask, x, y, radius, Set.of());
    }

    public Penetration penetration(int mask, float x, float y, float radius, Set<Integer> disabled) {
        if (!valid(x, y) || !Float.isFinite(radius) || radius < 0) return new Penetration(0, 0);
        var result = Penetration.NONE;
        int minX = floorInt(x - radius),
                maxX = floorInt(x + radius),
                minY = floorInt(y - radius),
                maxY = floorInt(y + radius);
        for (int cy = minY; cy <= maxY; cy++)
            for (int cx = minX; cx <= maxX; cx++) {
                int index = Arrays.binarySearch(tileKeys, key(cx, cy));
                if (index < 0 || (tileMasks[index] & mask) == 0) continue;
                if (circleIntersects(new Bounds(cx, cy, cx + 1, cy + 1), x, y, radius))
                    result = result.deepest(aabbPenetration(cx, cy, x, y, radius));
            }
        for (Shape shape : grid.query(x, y, radius)) {
            if (shape.source != 0 && disabled.contains(shape.source)) continue;
            if ((shape.mask & mask) != 0) result = result.deepest(penetration(shape.geometry, x, y, radius));
        }
        return result;
    }

    // ---- artifact codec (build-time properties and versioned binary form)

    /** Parse the build-time properties form emitted by the collision exporter. */
    public static MapCollision fromProperties(Path file) throws IOException {
        return MapCollisionCodec.fromProperties(file);
    }

    /** Read the versioned gzip binary form; the artifact codec owns validation. */
    public static MapCollision read(Path file) throws IOException {
        return MapCollisionCodec.read(file);
    }

    /** Write this collision with its layout hash; shape order and derived bounds are preserved. */
    public void write(Path file, String layoutHash) throws IOException {
        MapCollisionCodec.write(this, file, layoutHash);
    }

    // ---- geometry

    private static final int CIRCLE = 1, CAPSULE = 2, POLYGON = 3, RAIL = 4;

    // ---- geometry query kernel

    private boolean tileHit(int mask, float x, float y, float radius) {
        int minX = floorInt(x - radius),
                maxX = floorInt(x + radius),
                minY = floorInt(y - radius),
                maxY = floorInt(y + radius);
        for (int cy = minY; cy <= maxY; cy++)
            for (int cx = minX; cx <= maxX; cx++) {
                int index = Arrays.binarySearch(tileKeys, key(cx, cy));
                if (index >= 0
                        && (tileMasks[index] & mask) != 0
                        && circleIntersects(new Bounds(cx, cy, cx + 1, cy + 1), x, y, radius)) return true;
            }
        return false;
    }

    private static boolean circleIntersects(Geom g, float x, float y, float r) {
        if (g instanceof Circle c) return distance2(x, y, c.center.x, c.center.y) < square(r + c.radius);
        if (g instanceof Capsule c) return distanceToSegment2(x, y, c.a, c.b) < square(r + c.radius);
        if (g instanceof Polygon p) return circlePolygon(p, x, y, r);
        var rail = (Rail) g;
        return distanceToSegment2(x, y, rail.a, rail.b) < square(r);
    }

    private static boolean circleIntersects(Bounds box, float x, float y, float r) {
        float nx = Math.clamp(x, box.minX, box.maxX), ny = Math.clamp(y, box.minY, box.maxY);
        return distance2(x, y, nx, ny) < square(r) && (x != nx || y != ny || r > 0);
    }

    private static Penetration penetration(Geom g, float x, float y, float r) {
        if (g instanceof Circle c) return radial(x, y, c.center.x, c.center.y, r + c.radius);
        if (g instanceof Capsule c) {
            var nearest = nearest(x, y, c.a, c.b);
            return radial(x, y, nearest.x, nearest.y, r + c.radius);
        }
        if (g instanceof Polygon p) {
            if (inside(p, x, y)) {
                float best = Float.POSITIVE_INFINITY;
                Point at = null;
                for (int i = 0; i < p.points.size(); i++) {
                    var a = p.points.get(i);
                    var b = p.points.get((i + 1) % p.points.size());
                    var q = nearest(x, y, a, b);
                    float d = distance2(x, y, q.x, q.y);
                    if (d < best) {
                        best = d;
                        at = q;
                    }
                }
                if (at == null) return Penetration.NONE;
                return pushOut(x, y, at.x, at.y, (float) Math.sqrt(distance2(x, y, at.x, at.y)) + r);
            }
            float best = Float.POSITIVE_INFINITY;
            Point at = null;
            for (int i = 0; i < p.points.size(); i++) {
                var a = p.points.get(i);
                var b = p.points.get((i + 1) % p.points.size());
                var q = nearest(x, y, a, b);
                float d = distance2(x, y, q.x, q.y);
                if (d < best) {
                    best = d;
                    at = q;
                }
            }
            return at == null ? Penetration.NONE : radial(x, y, at.x, at.y, r);
        }
        return Penetration.NONE;
    }

    private static Penetration aabbPenetration(float minX, float minY, float x, float y, float radius) {
        float left = x - minX, right = minX + 1 - x, bottom = y - minY, top = minY + 1 - y;
        float nearest = Math.min(Math.min(left, right), Math.min(bottom, top));
        if (nearest == left) return new Penetration(-(left + radius), 0);
        if (nearest == right) return new Penetration(right + radius, 0);
        if (nearest == bottom) return new Penetration(0, -(bottom + radius));
        return new Penetration(0, top + radius);
    }

    private static Penetration pushOut(float x, float y, float qx, float qy, float amount) {
        float dx = x - qx, dy = y - qy, distance = (float) Math.hypot(dx, dy);
        if (distance == 0 || !Float.isFinite(distance)) return new Penetration(amount, 0);
        return new Penetration(dx / distance * amount, dy / distance * amount);
    }

    private static Penetration radial(float x, float y, float cx, float cy, float radius) {
        float dx = x - cx, dy = y - cy, distance = (float) Math.hypot(dx, dy);
        if (distance == 0 || distance >= radius) return Penetration.NONE;
        float scale = radius / distance - 1;
        return new Penetration(dx * scale, dy * scale);
    }

    private static Point nearest(float x, float y, Point a, Point b) {
        float dx = b.x - a.x, dy = b.y - a.y, length = dx * dx + dy * dy;
        float t = length == 0 ? 0 : Math.clamp(((x - a.x) * dx + (y - a.y) * dy) / length, 0, 1);
        return new Point(a.x + t * dx, a.y + t * dy);
    }

    private static boolean inside(Polygon p, float x, float y) {
        boolean in = false;
        for (int i = 0, j = p.points.size() - 1; i < p.points.size(); j = i++) {
            var a = p.points.get(i);
            var b = p.points.get(j);
            if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) in = !in;
        }
        return in;
    }

    private static boolean circlePolygon(Polygon p, float x, float y, float r) {
        if (inside(p, x, y)) return true;
        for (int i = 0; i < p.points.size(); i++) {
            var a = p.points.get(i);
            var b = p.points.get((i + 1) % p.points.size());
            if (distanceToSegment2(x, y, a, b) < square(r)) return true;
        }
        return false;
    }

    private static boolean containsPoint(Geom g, float x, float y) {
        if (g instanceof Circle c) return distance2(x, y, c.center.x, c.center.y) <= square(c.radius);
        if (g instanceof Polygon p) return inside(p, x, y);
        if (g instanceof Capsule c) return distanceToSegment2(x, y, c.a, c.b) <= square(c.radius);
        return false;
    }

    private static float distanceToSegment2(float x, float y, Point a, Point b) {
        var q = nearest(x, y, a, b);
        return distance2(x, y, q.x, q.y);
    }

    private static float distance2(float ax, float ay, float bx, float by) {
        float dx = ax - bx, dy = ay - by;
        return dx * dx + dy * dy;
    }

    private static float square(float v) {
        return v * v;
    }

    // ---- grid

    /**
     * Broadphase dedupe marks are per-thread, not per-template. One compiled {@code MapCollision}
     * is shared by every scene instance loaded from the same map, and those instances may tick on
     * different workers; instance-level marks would race and could skip blocking shapes.
     */
    private static final ThreadLocal<QueryMarks> QUERY_MARKS = ThreadLocal.withInitial(QueryMarks::new);

    private static final class QueryMarks {
        private int[] stamps = new int[0];
        private int generation;

        int next(int size) {
            if (stamps.length < size) stamps = Arrays.copyOf(stamps, size);
            if (++generation <= 1) {
                Arrays.fill(stamps, 0);
                generation = 1;
            }
            return generation;
        }
    }

    private static final class Grid {
        private final float originX, originY;
        private final int width, height;
        private final int[][] buckets;

        Grid(Bounds bounds, List<Shape> shapes) {
            originX = bounds.minX - GRID;
            originY = bounds.minY - GRID;
            width = Math.max(1, (int) Math.ceil((bounds.maxX - bounds.minX) / GRID) + 2);
            height = Math.max(1, (int) Math.ceil((bounds.maxY - bounds.minY) / GRID) + 2);
            if ((long) width * height > 4_000_000) throw new IllegalArgumentException("Collision bounds are too large");
            var lists = new ArrayList<List<Integer>>(width * height);
            for (int i = 0; i < width * height; i++) lists.add(new ArrayList<>());
            for (int index = 0; index < shapes.size(); index++) {
                Shape shape = shapes.get(index);
                int minX = Math.max(0, (int) Math.floor((shape.bounds.minX - originX) / GRID));
                int maxX = Math.min(width - 1, (int) Math.floor((shape.bounds.maxX - originX) / GRID));
                int minY = Math.max(0, (int) Math.floor((shape.bounds.minY - originY) / GRID));
                int maxY = Math.min(height - 1, (int) Math.floor((shape.bounds.maxY - originY) / GRID));
                for (int y = minY; y <= maxY; y++)
                    for (int x = minX; x <= maxX; x++) lists.get(y * width + x).add(index);
            }
            buckets = new int[lists.size()][];
            for (int i = 0; i < lists.size(); i++)
                buckets[i] = lists.get(i).stream().mapToInt(Integer::intValue).toArray();
            // Shape ids are positions in the source list; keep the mapping stable.
            this.shapes = shapes.toArray(Shape[]::new);
        }

        private final Shape[] shapes;

        List<Shape> query(float x, float y, float radius) {
            return query(x - radius, y - radius, x + radius, y + radius);
        }

        List<Shape> query(float minX, float minY, float maxX, float maxY) {
            int x0 = Math.max(0, (int) Math.floor((minX - originX) / GRID));
            int x1 = Math.min(width - 1, (int) Math.floor((maxX - originX) / GRID));
            int y0 = Math.max(0, (int) Math.floor((minY - originY) / GRID));
            int y1 = Math.min(height - 1, (int) Math.floor((maxY - originY) / GRID));
            if (x1 < x0 || y1 < y0) return List.of();
            var marks = QUERY_MARKS.get();
            int mark = marks.next(shapes.length);
            var result = new ArrayList<Shape>();
            for (int y = y0; y <= y1; y++)
                for (int x = x0; x <= x1; x++) {
                    for (int id : buckets[y * width + x]) {
                        if (marks.stamps[id] == mark) continue;
                        marks.stamps[id] = mark;
                        var shape = shapes[id];
                        if (shape.bounds.intersects(new Bounds(minX, minY, maxX, maxY))) result.add(shape);
                    }
                }
            // Floor shapes (mask 0) are excluded above; floorSupported queries them separately.
            return result;
        }
    }

    // ---- helpers

    private static boolean valid(float x, float y) {
        return Float.isFinite(x) && Float.isFinite(y) && Math.abs(x) < 1_000_000 && Math.abs(y) < 1_000_000;
    }

    private static int floorInt(float value) {
        return (int) Math.floor(value);
    }

    static long key(int x, int y) {
        return ((long) x << 32) ^ (y & 0xffffffffL);
    }
}
