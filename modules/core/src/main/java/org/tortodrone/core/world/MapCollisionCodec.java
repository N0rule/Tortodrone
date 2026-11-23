package org.tortodrone.core.world;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Compiled-collision artifact for {@link MapCollision}.
 *
 * <p>This is build output, never authored: it validates and parses the legacy properties form
 * emitted by the collision exporter and reads/writes the versioned gzip binary form. The runtime
 * query state and geometry kernel stay in {@link MapCollision}; keeping the untrusted file and
 * byte-format policy here means a runtime collision value cannot grow format knowledge.
 *
 * <p>The binary writes the derived bounds and the exact shape order produced by ingestion. Tile
 * keys must remain sorted (queries binary-search them) and shape ids are positions in that list,
 * so the writer never re-sorts or rewrites them.
 */
public final class MapCollisionCodec {
    private static final int MAGIC = 0x54444d43; // "TDMC"
    private static final int VERSION = 2;
    private static final int MAX_TILES = 1_000_000, MAX_FLOORS = 1_000_000, MAX_SHAPES = 100_000;
    private static final int MAX_BYTES = 16 * 1024 * 1024;

    private MapCollisionCodec() { }

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
            tileKeys[i] = MapCollision.key(integer(p, prefix + "x"), integer(p, prefix + "y"));
            tileMasks[i] = integer(p, prefix + "mask");
        }
        var floorKeys = new long[floors];
        for (int i = 0; i < floors; i++) {
            String prefix = "floor." + i + ".";
            floorKeys[i] = MapCollision.key(integer(p, prefix + "x"), integer(p, prefix + "y"));
        }
        sort(tileKeys, tileMasks);
        Arrays.sort(floorKeys);
        var shapes = new ArrayList<MapCollision.Shape>(shapeCount + 16);
        float[] extent = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (int i = 0; i < shapeCount; i++) {
            String prefix = "shape." + i + ".";
            var geometry = parseGeometry(required(p, prefix + "geometry"));
            int mask = integer(p, prefix + "mask");
            shapes.add(new MapCollision.Shape(i, mask, integer(p, prefix + "source", 0), geometry, bounds(geometry)));
            include(extent, bounds(geometry));
        }
        int surfaces = p.containsKey("floor-shape-count") ? integer(p, "floor-shape-count") : 0;
        for (int i = 0; i < surfaces; i++) {
            var geometry = parseGeometry(required(p, "floor-shape." + i + ".geometry"));
            shapes.add(new MapCollision.Shape(shapeCount + i, 0, 0, geometry, bounds(geometry)));
            include(extent, bounds(geometry));
        }
        for (int i = 0; i < tileKeys.length; i++) {
            int x = (int) (tileKeys[i] >> 32), y = (int) tileKeys[i];
            include(extent, new MapCollision.Bounds(x, y, x + 1, y + 1));
        }
        for (long key : floorKeys) {
            int x = (int) (key >> 32), y = (int) key;
            include(extent, new MapCollision.Bounds(x, y, x + 1, y + 1));
        }
        if (!Float.isFinite(extent[0])) throw new IOException("Static collision has no geometry");
        return new MapCollision(tileKeys, tileMasks, floorKeys, shapes,
            new MapCollision.Bounds(extent[0], extent[1], extent[2], extent[3]), hash);
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
    private static void include(float[] extent, MapCollision.Bounds b) {
        extent[0] = Math.min(extent[0], b.minX()); extent[1] = Math.min(extent[1], b.minY());
        extent[2] = Math.max(extent[2], b.maxX()); extent[3] = Math.max(extent[3], b.maxY());
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
    private static MapCollision.Geom parseGeometry(String text) throws IOException {
        String[] f = text.split(";", -1);
        return switch (f[0]) {
            case "C" -> new MapCollision.Circle(new MapCollision.Point(num(f[1]), num(f[2])), positive(num(f[3])));
            case "K" -> new MapCollision.Capsule(new MapCollision.Point(num(f[1]), num(f[2])), new MapCollision.Point(num(f[3]), num(f[4])), positive(num(f[5])));
            case "P" -> {
                if (f.length < 7 || f.length % 2 == 0) throw new IOException("Invalid static collision polygon");
                var points = new ArrayList<MapCollision.Point>();
                for (int i = 1; i < f.length; i += 2) points.add(new MapCollision.Point(num(f[i]), num(f[i + 1])));
                yield new MapCollision.Polygon(points);
            }
            case "D" -> new MapCollision.Rail(new MapCollision.Point(num(f[1]), num(f[2])), new MapCollision.Point(num(f[3]), num(f[4])),
                new MapCollision.Bounds(num(f[5]), num(f[6]), num(f[7]), num(f[8])));
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
            var bounds = new MapCollision.Bounds(minX, minY, maxX, maxY);
            int tiles = count(in, MAX_TILES);
            var tileKeys = new long[tiles]; var tileMasks = new int[tiles];
            for (int i = 0; i < tiles; i++) { tileKeys[i] = in.readLong(); tileMasks[i] = in.readInt(); }
            int floors = count(in, MAX_FLOORS);
            var floorKeys = new long[floors];
            for (int i = 0; i < floors; i++) floorKeys[i] = in.readLong();
            int shapeCount = count(in, MAX_SHAPES);
            var shapes = new ArrayList<MapCollision.Shape>(shapeCount);
            for (int i = 0; i < shapeCount; i++) {
                int id = in.readInt(), mask = in.readInt();
                int source = version >= 2 ? in.readInt() : 0;
                MapCollision.Geom geometry = readGeometry(in);
                shapes.add(new MapCollision.Shape(id, mask, source, geometry, bounds(geometry)));
            }
            return new MapCollision(tileKeys, tileMasks, floorKeys, shapes, bounds, hash);
        }
    }

    public static void write(MapCollision collision, Path file, String layoutHash) throws IOException {
        if (layoutHash == null || !layoutHash.matches("[0-9a-f]{64}")) throw new IOException("Invalid layout hash");
        var parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        var bounds = collision.bounds();
        var tileKeys = collision.tileKeys();
        var tileMasks = collision.tileMasks();
        var floorKeys = collision.floorKeys();
        List<MapCollision.Shape> shapes = collision.shapes();
        try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) {
            out.writeInt(MAGIC); out.writeInt(VERSION); writeHash(out, layoutHash);
            out.writeFloat(bounds.minX()); out.writeFloat(bounds.minY());
            out.writeFloat(bounds.maxX()); out.writeFloat(bounds.maxY());
            out.writeInt(tileKeys.length);
            for (int i = 0; i < tileKeys.length; i++) { out.writeLong(tileKeys[i]); out.writeInt(tileMasks[i]); }
            out.writeInt(floorKeys.length);
            for (long key : floorKeys) out.writeLong(key);
            out.writeInt(shapes.size());
            for (MapCollision.Shape shape : shapes) {
                out.writeInt(shape.id()); out.writeInt(shape.mask()); out.writeInt(shape.source()); writeGeometry(out, shape.geometry());
            }
        }
    }

    // ---- geometry

    private static final int CIRCLE = 1, CAPSULE = 2, POLYGON = 3, RAIL = 4;

    private static void writeGeometry(DataOutputStream out, MapCollision.Geom g) throws IOException {
        if (g instanceof MapCollision.Circle c) { out.writeByte(CIRCLE); out.writeFloat(c.center().x()); out.writeFloat(c.center().y()); out.writeFloat(c.radius()); }
        else if (g instanceof MapCollision.Capsule c) { out.writeByte(CAPSULE); out.writeFloat(c.a().x()); out.writeFloat(c.a().y()); out.writeFloat(c.b().x()); out.writeFloat(c.b().y()); out.writeFloat(c.radius()); }
        else if (g instanceof MapCollision.Polygon p) {
            out.writeByte(POLYGON); out.writeInt(p.points().size());
            for (var point : p.points()) { out.writeFloat(point.x()); out.writeFloat(point.y()); }
        } else if (g instanceof MapCollision.Rail r) {
            out.writeByte(RAIL); out.writeFloat(r.a().x()); out.writeFloat(r.a().y()); out.writeFloat(r.b().x()); out.writeFloat(r.b().y());
            out.writeFloat(r.gate().minX()); out.writeFloat(r.gate().minY()); out.writeFloat(r.gate().maxX()); out.writeFloat(r.gate().maxY());
        } else throw new IOException("Unsupported collision geometry");
    }

    private static MapCollision.Geom readGeometry(DataInputStream in) throws IOException {
        return switch (in.readByte()) {
            case CIRCLE -> new MapCollision.Circle(new MapCollision.Point(in.readFloat(), in.readFloat()), positive(in.readFloat()));
            case CAPSULE -> new MapCollision.Capsule(new MapCollision.Point(in.readFloat(), in.readFloat()),
                new MapCollision.Point(in.readFloat(), in.readFloat()), positive(in.readFloat()));
            case POLYGON -> {
                int n = count(in, 4096);
                if (n < 3) throw new IOException("Invalid polygon");
                var points = new ArrayList<MapCollision.Point>(n);
                for (int i = 0; i < n; i++) points.add(new MapCollision.Point(in.readFloat(), in.readFloat()));
                yield new MapCollision.Polygon(points);
            }
            case RAIL -> new MapCollision.Rail(new MapCollision.Point(in.readFloat(), in.readFloat()), new MapCollision.Point(in.readFloat(), in.readFloat()),
                new MapCollision.Bounds(in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat()));
            default -> throw new IOException("Unsupported collision geometry");
        };
    }

    private static float positive(float v) throws IOException {
        if (!Float.isFinite(v) || v < 0) throw new IOException("Invalid collision dimension");
        return v;
    }

    private static MapCollision.Bounds bounds(MapCollision.Geom g) {
        if (g instanceof MapCollision.Circle c) return new MapCollision.Bounds(c.center().x() - c.radius(), c.center().y() - c.radius(), c.center().x() + c.radius(), c.center().y() + c.radius());
        if (g instanceof MapCollision.Capsule c) {
            float r = c.radius();
            return new MapCollision.Bounds(Math.min(c.a().x(), c.b().x()) - r, Math.min(c.a().y(), c.b().y()) - r,
                Math.max(c.a().x(), c.b().x()) + r, Math.max(c.a().y(), c.b().y()) + r);
        }
        if (g instanceof MapCollision.Polygon p) {
            float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
            for (var point : p.points()) { minX = Math.min(minX, point.x()); minY = Math.min(minY, point.y()); maxX = Math.max(maxX, point.x()); maxY = Math.max(maxY, point.y()); }
            return new MapCollision.Bounds(minX, minY, maxX, maxY);
        }
        var r = (MapCollision.Rail) g;
        return new MapCollision.Bounds(Math.min(r.a().x(), r.b().x()), Math.min(r.a().y(), r.b().y()), Math.max(r.a().x(), r.b().x()), Math.max(r.a().y(), r.b().y()));
    }

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
}
