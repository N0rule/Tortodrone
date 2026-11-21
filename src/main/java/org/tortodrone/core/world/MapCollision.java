package org.tortodrone.core.world;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

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
    private static final int MAGIC = 0x54444d43; // "TDMC"
    private static final int VERSION = 2;
    private static final int MAX_TILES = 1_000_000, MAX_FLOORS = 1_000_000, MAX_SHAPES = 100_000;
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final float GRID = 4f;

    public record Penetration(float x, float y) {
        public static final Penetration NONE = new Penetration(0, 0);
        public boolean present() { return x != 0 || y != 0; }
        public Penetration deepest(Penetration other) {
            return other.x * other.x + other.y * other.y > x * x + y * y ? other : this;
        }
    }

    sealed interface Geom permits Circle, Capsule, Polygon, Rail { }
    record Point(float x, float y) { }
    record Circle(Point center, float radius) implements Geom { }
    record Capsule(Point a, Point b, float radius) implements Geom { }
    record Polygon(List<Point> points) implements Geom { }
    record Rail(Point a, Point b, Bounds gate) implements Geom { }
    record Bounds(float minX, float minY, float maxX, float maxY) {
        boolean intersects(Bounds o) { return minX <= o.maxX && maxX >= o.minX && minY <= o.maxY && maxY >= o.minY; }
        boolean contains(float x, float y) { return x >= minX && x <= maxX && y >= minY && y <= maxY; }
    }
    record Shape(int id, int mask, int source, Geom geometry, Bounds bounds) { }

    private final long[] tileKeys;
    private final int[] tileMasks;
    private final long[] floorKeys;
    private final List<Shape> shapes;
    private final Bounds bounds;
    private final Grid grid;
    private final String layoutHash;

    private MapCollision(long[] tileKeys, int[] tileMasks, long[] floorKeys, List<Shape> shapes,
            Bounds bounds, String layoutHash) {
        this.tileKeys = tileKeys;
        this.tileMasks = tileMasks;
        this.floorKeys = floorKeys;
        this.shapes = List.copyOf(shapes);
        this.bounds = bounds;
        this.layoutHash = layoutHash;
        this.grid = new Grid(bounds, shapes);
    }

    public String layoutHash() { return layoutHash; }
    long[] tileKeys(){return tileKeys.clone();}
    int[] tileMasks(){return tileMasks.clone();}
    long[] floorKeys(){return floorKeys.clone();}
    List<Shape> shapes(){return shapes;}
    public int shapeCount() { return shapes.size(); }
    public int tileCount() { return tileKeys.length; }
    Bounds bounds() { return bounds; }

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
    public boolean clear(int mask, float x, float y, float radius) { return clear(mask, x, y, radius, Set.of()); }
    public boolean clear(int mask, float x, float y, float radius, Set<Integer> disabled) {
        if (!valid(x, y) || !Float.isFinite(radius) || radius < 0) return false;
        if (!floorSupported(x, y)) return false;
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
    public boolean clearSegment(int mask, float fromX, float fromY, float toX, float toY, float radius, Set<Integer> disabled) {
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
            if (!floorSupported(x, y) || tileHit(mask, x, y, radius)) return false;
            for (Shape shape : candidates) {
                if (shape.source != 0 && disabled.contains(shape.source)) continue;
                if ((shape.mask & mask) != 0 && circleIntersects(shape.geometry, x, y, radius)) return false;
            }
        }
        return true;
    }

    /** Minimum translation that moves the circle out of walk geometry, or NONE. */
    public Penetration penetration(int mask, float x, float y, float radius) { return penetration(mask, x, y, radius, Set.of()); }
    public Penetration penetration(int mask, float x, float y, float radius, Set<Integer> disabled) {
        if (!valid(x, y) || !Float.isFinite(radius) || radius < 0) return new Penetration(0, 0);
        var result = Penetration.NONE;
        int minX = floorInt(x - radius), maxX = floorInt(x + radius), minY = floorInt(y - radius), maxY = floorInt(y + radius);
        for (int cy = minY; cy <= maxY; cy++) for (int cx = minX; cx <= maxX; cx++) {
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

    // ---- properties import (build-time format emitted by SceneCollisionExport)

    public static MapCollision fromProperties(Path file) throws IOException {
        var p = new Properties();
        try (var in = Files.newInputStream(file)) { p.load(in); }
        if (!"1".equals(p.getProperty("format"))) throw new IOException("Unsupported static collision format");
        String hash = p.getProperty("layout-sha256");
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IOException("Missing static collision layout hash");
        int tiles = integer(p, "tile-count"), floors = integer(p, "floor-count"), shapeCount = integer(p, "shape-count");
        if (tiles < 0 || tiles > MAX_TILES || floors < 0 || floors > MAX_FLOORS || shapeCount < 0 || shapeCount > MAX_SHAPES)
            throw new IOException("Invalid static collision counts");
        var tileKeys = new long[tiles]; var tileMasks = new int[tiles];
        for (int i = 0; i < tiles; i++) {
            String prefix = "tile." + i + ".";
            tileKeys[i] = key(integer(p, prefix + "x"), integer(p, prefix + "y"));
            tileMasks[i] = integer(p, prefix + "mask");
        }
        var floorKeys = new long[floors];
        for (int i = 0; i < floors; i++) {
            String prefix = "floor." + i + ".";
            floorKeys[i] = key(integer(p, prefix + "x"), integer(p, prefix + "y"));
        }
        sort(tileKeys, tileMasks);
        Arrays.sort(floorKeys);
        var shapes = new ArrayList<Shape>(shapeCount + 16);
        float[] extent = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (int i = 0; i < shapeCount; i++) {
            String prefix = "shape." + i + ".";
            var geometry = parseGeometry(required(p, prefix + "geometry"));
            int mask = integer(p, prefix + "mask");
            shapes.add(new Shape(i, mask, integer(p, prefix + "source", 0), geometry, bounds(geometry)));
            include(extent, bounds(geometry));
        }
        int surfaces = p.containsKey("floor-shape-count") ? integer(p, "floor-shape-count") : 0;
        for (int i = 0; i < surfaces; i++) {
            var geometry = parseGeometry(required(p, "floor-shape." + i + ".geometry"));
            shapes.add(new Shape(shapeCount + i, 0, 0, geometry, bounds(geometry)));
            include(extent, bounds(geometry));
        }
        for (int i = 0; i < tileKeys.length; i++) {
            int x = (int) (tileKeys[i] >> 32), y = (int) tileKeys[i];
            include(extent, new Bounds(x, y, x + 1, y + 1));
        }
        for (long key : floorKeys) {
            int x = (int) (key >> 32), y = (int) key;
            include(extent, new Bounds(x, y, x + 1, y + 1));
        }
        if (!Float.isFinite(extent[0])) throw new IOException("Static collision has no geometry");
        return new MapCollision(tileKeys, tileMasks, floorKeys, shapes,
            new Bounds(extent[0], extent[1], extent[2], extent[3]), hash);
    }

    private static void sort(long[] keys, int[] values) throws IOException {
        Integer[] order = new Integer[keys.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingLong(i -> keys[i]));
        long[] sortedKeys = new long[keys.length]; int[] sortedValues = new int[keys.length];
        for (int i = 0; i < order.length; i++) { sortedKeys[i] = keys[order[i]]; sortedValues[i] = values[order[i]]; }
        if (keys.length > 1) for (int i = 1; i < keys.length; i++)
            if (sortedKeys[i] == sortedKeys[i - 1]) throw new IOException("Duplicate static collision tile");
        System.arraycopy(sortedKeys, 0, keys, 0, keys.length);
        System.arraycopy(sortedValues, 0, values, 0, values.length);
    }
    private static void include(float[] extent, Bounds b) {
        extent[0] = Math.min(extent[0], b.minX); extent[1] = Math.min(extent[1], b.minY);
        extent[2] = Math.max(extent[2], b.maxX); extent[3] = Math.max(extent[3], b.maxY);
    }
    private static int integer(Properties p, String key) throws IOException {
        try { return Integer.parseInt(required(p, key)); }
        catch (NumberFormatException e) { throw new IOException("Invalid static collision " + key, e); }
    }
    private static int integer(Properties p, String key, int fallback) throws IOException {
        String value = p.getProperty(key);
        if (value == null) return fallback;
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { throw new IOException("Invalid static collision " + key, e); }
    }
    private static String required(Properties p, String key) throws IOException {
        String value = p.getProperty(key);
        if (value == null) throw new IOException("Missing static collision " + key);
        return value;
    }
    private static Geom parseGeometry(String text) throws IOException {
        String[] f = text.split(";", -1);
        return switch (f[0]) {
            case "C" -> new Circle(new Point(num(f[1]), num(f[2])), positive(num(f[3])));
            case "K" -> new Capsule(new Point(num(f[1]), num(f[2])), new Point(num(f[3]), num(f[4])), positive(num(f[5])));
            case "P" -> {
                if (f.length < 7 || f.length % 2 == 0) throw new IOException("Invalid static collision polygon");
                var points = new ArrayList<Point>();
                for (int i = 1; i < f.length; i += 2) points.add(new Point(num(f[i]), num(f[i + 1])));
                yield new Polygon(points);
            }
            case "D" -> new Rail(new Point(num(f[1]), num(f[2])), new Point(num(f[3]), num(f[4])),
                new Bounds(num(f[5]), num(f[6]), num(f[7]), num(f[8])));
            default -> throw new IOException("Invalid static collision geometry");
        };
    }
    private static float num(String value) throws IOException {
        float f = Float.parseFloat(value);
        if (!Float.isFinite(f)) throw new IOException("Invalid static collision number");
        return f;
    }

    // ---- binary format

    public static MapCollision read(Path file) throws IOException {
        if (Files.size(file) > MAX_BYTES) throw new IOException("Collision file is too large");
        try (var in = new DataInputStream(new GZIPInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != MAGIC) throw new IOException("Unsupported collision format");
            int version = in.readInt();
            if (version < 1 || version > VERSION) throw new IOException("Unsupported collision format");
            String hash = readHash(in);
            float minX = in.readFloat(), minY = in.readFloat(), maxX = in.readFloat(), maxY = in.readFloat();
            var bounds = new Bounds(minX, minY, maxX, maxY);
            int tiles = count(in, MAX_TILES);
            var tileKeys = new long[tiles]; var tileMasks = new int[tiles];
            for (int i = 0; i < tiles; i++) { tileKeys[i] = in.readLong(); tileMasks[i] = in.readInt(); }
            int floors = count(in, MAX_FLOORS);
            var floorKeys = new long[floors];
            for (int i = 0; i < floors; i++) floorKeys[i] = in.readLong();
            int shapeCount = count(in, MAX_SHAPES);
            var shapes = new ArrayList<Shape>(shapeCount);
            for (int i = 0; i < shapeCount; i++) {
                int id = in.readInt(), mask = in.readInt();
                int source = version >= 2 ? in.readInt() : 0;
                Geom geometry = readGeometry(in);
                shapes.add(new Shape(id, mask, source, geometry, bounds(geometry)));
            }
            return new MapCollision(tileKeys, tileMasks, floorKeys, shapes, bounds, hash);
        }
    }

    public void write(Path file, String layoutHash) throws IOException {
        if (layoutHash == null || !layoutHash.matches("[0-9a-f]{64}")) throw new IOException("Invalid layout hash");
        var parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) {
            out.writeInt(MAGIC); out.writeInt(VERSION); writeHash(out, layoutHash);
            out.writeFloat(bounds.minX); out.writeFloat(bounds.minY);
            out.writeFloat(bounds.maxX); out.writeFloat(bounds.maxY);
            out.writeInt(tileKeys.length);
            for (int i = 0; i < tileKeys.length; i++) { out.writeLong(tileKeys[i]); out.writeInt(tileMasks[i]); }
            out.writeInt(floorKeys.length);
            for (long key : floorKeys) out.writeLong(key);
            out.writeInt(shapes.size());
            for (Shape shape : shapes) {
                out.writeInt(shape.id); out.writeInt(shape.mask); out.writeInt(shape.source); writeGeometry(out, shape.geometry);
            }
        }
    }

    // ---- geometry

    private static final int CIRCLE = 1, CAPSULE = 2, POLYGON = 3, RAIL = 4;

    private static void writeGeometry(DataOutputStream out, Geom g) throws IOException {
        if (g instanceof Circle c) { out.writeByte(CIRCLE); out.writeFloat(c.center.x); out.writeFloat(c.center.y); out.writeFloat(c.radius); }
        else if (g instanceof Capsule c) { out.writeByte(CAPSULE); out.writeFloat(c.a.x); out.writeFloat(c.a.y); out.writeFloat(c.b.x); out.writeFloat(c.b.y); out.writeFloat(c.radius); }
        else if (g instanceof Polygon p) {
            out.writeByte(POLYGON); out.writeInt(p.points.size());
            for (var point : p.points) { out.writeFloat(point.x); out.writeFloat(point.y); }
        } else if (g instanceof Rail r) {
            out.writeByte(RAIL); out.writeFloat(r.a.x); out.writeFloat(r.a.y); out.writeFloat(r.b.x); out.writeFloat(r.b.y);
            out.writeFloat(r.gate.minX); out.writeFloat(r.gate.minY); out.writeFloat(r.gate.maxX); out.writeFloat(r.gate.maxY);
        } else throw new IOException("Unsupported collision geometry");
    }

    private static Geom readGeometry(DataInputStream in) throws IOException {
        return switch (in.readByte()) {
            case CIRCLE -> new Circle(new Point(in.readFloat(), in.readFloat()), positive(in.readFloat()));
            case CAPSULE -> new Capsule(new Point(in.readFloat(), in.readFloat()),
                new Point(in.readFloat(), in.readFloat()), positive(in.readFloat()));
            case POLYGON -> {
                int n = count(in, 4096);
                if (n < 3) throw new IOException("Invalid polygon");
                var points = new ArrayList<Point>(n);
                for (int i = 0; i < n; i++) points.add(new Point(in.readFloat(), in.readFloat()));
                yield new Polygon(points);
            }
            case RAIL -> new Rail(new Point(in.readFloat(), in.readFloat()), new Point(in.readFloat(), in.readFloat()),
                new Bounds(in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat()));
            default -> throw new IOException("Unsupported collision geometry");
        };
    }

    private static float positive(float v) throws IOException {
        if (!Float.isFinite(v) || v < 0) throw new IOException("Invalid collision dimension");
        return v;
    }

    private static Bounds bounds(Geom g) {
        if (g instanceof Circle c) return new Bounds(c.center.x - c.radius, c.center.y - c.radius, c.center.x + c.radius, c.center.y + c.radius);
        if (g instanceof Capsule c) {
            float r = c.radius;
            return new Bounds(Math.min(c.a.x, c.b.x) - r, Math.min(c.a.y, c.b.y) - r,
                Math.max(c.a.x, c.b.x) + r, Math.max(c.a.y, c.b.y) + r);
        }
        if (g instanceof Polygon p) {
            float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
            for (var point : p.points) { minX = Math.min(minX, point.x); minY = Math.min(minY, point.y); maxX = Math.max(maxX, point.x); maxY = Math.max(maxY, point.y); }
            return new Bounds(minX, minY, maxX, maxY);
        }
        var r = (Rail) g;
        return new Bounds(Math.min(r.a.x, r.b.x), Math.min(r.a.y, r.b.y), Math.max(r.a.x, r.b.x), Math.max(r.a.y, r.b.y));
    }

    private boolean tileHit(int mask, float x, float y, float radius) {
        int minX = floorInt(x - radius), maxX = floorInt(x + radius), minY = floorInt(y - radius), maxY = floorInt(y + radius);
        for (int cy = minY; cy <= maxY; cy++) for (int cx = minX; cx <= maxX; cx++) {
            int index = Arrays.binarySearch(tileKeys, key(cx, cy));
            if (index >= 0 && (tileMasks[index] & mask) != 0
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
                float best = Float.POSITIVE_INFINITY; Point at = null;
                for (int i = 0; i < p.points.size(); i++) {
                    var a = p.points.get(i); var b = p.points.get((i + 1) % p.points.size());
                    var q = nearest(x, y, a, b); float d = distance2(x, y, q.x, q.y);
                    if (d < best) { best = d; at = q; }
                }
                if (at == null) return Penetration.NONE;
                return pushOut(x, y, at.x, at.y, (float) Math.sqrt(distance2(x, y, at.x, at.y)) + r);
            }
            float best = Float.POSITIVE_INFINITY; Point at = null;
            for (int i = 0; i < p.points.size(); i++) {
                var a = p.points.get(i); var b = p.points.get((i + 1) % p.points.size());
                var q = nearest(x, y, a, b); float d = distance2(x, y, q.x, q.y);
                if (d < best) { best = d; at = q; }
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
            var a = p.points.get(i); var b = p.points.get(j);
            if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) in = !in;
        }
        return in;
    }

    private static boolean circlePolygon(Polygon p, float x, float y, float r) {
        if (inside(p, x, y)) return true;
        for (int i = 0; i < p.points.size(); i++) {
            var a = p.points.get(i); var b = p.points.get((i + 1) % p.points.size());
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
        float dx = ax - bx, dy = ay - by; return dx * dx + dy * dy;
    }
    private static float square(float v) { return v * v; }

    // ---- grid

    private static final class Grid {
        private final float originX, originY;
        private final int width, height;
        private final int[][] buckets;
        private final int[] stamps;
        private int stamp;

        Grid(Bounds bounds, List<Shape> shapes) {
            originX = bounds.minX - GRID; originY = bounds.minY - GRID;
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
                for (int y = minY; y <= maxY; y++) for (int x = minX; x <= maxX; x++) lists.get(y * width + x).add(index);
            }
            buckets = new int[lists.size()][];
            for (int i = 0; i < lists.size(); i++) buckets[i] = lists.get(i).stream().mapToInt(Integer::intValue).toArray();
            stamps = new int[shapes.size()];
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
            int mark = ++stamp;
            if (mark == Integer.MAX_VALUE) { Arrays.fill(stamps, 0); mark = stamp = 1; }
            var result = new ArrayList<Shape>();
            for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
                for (int id : buckets[y * width + x]) {
                    if (stamps[id] == mark) continue;
                    stamps[id] = mark;
                    var shape = shapes[id];
                    if (shape.bounds.intersects(new Bounds(minX, minY, maxX, maxY)))
                        result.add(shape);
                }
            }
            // Floor shapes (mask 0) are excluded above; floorSupported queries them separately.
            return result;
        }
    }

    // ---- helpers

    private static int count(DataInputStream in, int max) throws IOException {
        int value = in.readInt();
        if (value < 0 || value > max) throw new IOException("Invalid collision count");
        return value;
    }
    private static String readHash(DataInputStream in) throws IOException {
        byte[] bytes = in.readNBytes(32);
        if (bytes.length != 32) throw new IOException("Invalid collision hash");
        return HexFormat.of().formatHex(bytes);
    }
    private static void writeHash(DataOutputStream out, String hash) throws IOException {
        out.write(HexFormat.of().parseHex(hash));
    }
    private static boolean valid(float x, float y) {
        return Float.isFinite(x) && Float.isFinite(y) && Math.abs(x) < 1_000_000 && Math.abs(y) < 1_000_000;
    }
    private static int floorInt(float value) { return (int) Math.floor(value); }
    private static long key(int x, int y) { return ((long) x << 32) ^ (y & 0xffffffffL); }
}
