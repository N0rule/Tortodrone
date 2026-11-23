package org.tortodrone.core.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Prepared static-collision ingestion for {@link SceneCollision}.
 *
 * <p>The runtime value keeps the immutable tile/floor/shape tables and the query kernel. This
 * class owns the exported properties format, the layout-hash check, the bounded row counts and
 * the geometry string grammar; it never mutates runtime state.
 */
public final class SceneCollisionCodec {
    private static final int MAX_BYTES = 4 * 1024 * 1024,
            MAX_TILES = 1_000_000,
            MAX_FLOORS = 1_000_000,
            MAX_SHAPES = 100_000;

    private SceneCollisionCodec() {}

    /** Loads an optional prepared collision file and verifies it belongs to this scene layout. */
    public static SceneCollision load(Path folder, String layoutHash) throws IOException {
        Path file = folder.resolve("static-collision.properties");
        if (!Files.exists(file)) return SceneCollision.empty();
        long size = Files.size(file);
        if (size <= 0 || size > MAX_BYTES) throw new IOException("Static collision file is too large or empty");
        Properties p = new Properties();
        try (var in = Files.newInputStream(file)) {
            p.load(in);
        }
        if (!"1".equals(p.getProperty("format"))) throw new IOException("Unsupported static collision format");
        if (!Objects.equals(layoutHash, p.getProperty("layout-sha256")))
            throw new IOException("Static collision layout hash mismatch");
        int tileCount = count(p, "tile-count", MAX_TILES),
                floorCount = count(p, "floor-count", MAX_FLOORS),
                shapeCount = count(p, "shape-count", MAX_SHAPES);
        var floorCells = new HashSet<Long>(Math.max(16, floorCount * 4 / 3));
        for (int i = 0; i < floorCount; i++) {
            String prefix = "floor." + i + ".";
            int x = integer(p, prefix + "x"), y = integer(p, prefix + "y");
            if (Math.abs((long) x) > 1_000_000
                    || Math.abs((long) y) > 1_000_000
                    || !floorCells.add(SceneCollision.key(x, y)))
                throw new IOException("Invalid static collision floor cell");
        }
        var tiles = new HashMap<Long, Integer>(Math.max(16, tileCount * 4 / 3));
        for (int i = 0; i < tileCount; i++) {
            String prefix = "tile." + i + ".";
            int x = integer(p, prefix + "x"), y = integer(p, prefix + "y"), mask = integer(p, prefix + "mask");
            if (mask == 0
                    || Math.abs((long) x) > 1_000_000
                    || Math.abs((long) y) > 1_000_000
                    || tiles.put(SceneCollision.key(x, y), mask) != null)
                throw new IOException("Invalid static collision tile");
        }
        var shapes = new ArrayList<SceneCollision.Obstacle>(shapeCount);
        for (int i = 0; i < shapeCount; i++) {
            String prefix = "shape." + i + ".";
            int mask = integer(p, prefix + "mask");
            if (mask == 0) throw new IOException("Invalid static collision shape mask");
            int source = p.containsKey(prefix + "source") ? integer(p, prefix + "source") : 0;
            var geometry = parseGeometry(required(p, prefix + "geometry"));
            shapes.add(new SceneCollision.Obstacle(mask, source, geometry, SceneCollision.bounds(geometry)));
        }
        int floorShapeCount = p.containsKey("floor-shape-count") ? count(p, "floor-shape-count", MAX_SHAPES) : 0;
        var surfaces = new ArrayList<SceneCollision.Obstacle>(floorShapeCount);
        for (int i = 0; i < floorShapeCount; i++)
            surfaces.add(new SceneCollision.Obstacle(0, parseGeometry(required(p, "floor-shape." + i + ".geometry"))));
        return new SceneCollision(tiles, floorCells, surfaces, shapes, true);
    }

    private static SceneCollision.Geom parseGeometry(String text) throws IOException {
        try {
            String[] f = text.split(";", -1);
            String kind = f[0];
            if (kind.equals("D") && f.length == 9)
                return new SceneCollision.Rail(
                        new SceneCollision.Point(num(f[1]), num(f[2])),
                        new SceneCollision.Point(num(f[3]), num(f[4])),
                        new SceneCollision.Bounds(num(f[5]), num(f[6]), num(f[7]), num(f[8])));
            if (kind.equals("C") && f.length == 4)
                return new SceneCollision.Circle(
                        new SceneCollision.Point(num(f[1]), num(f[2])), SceneCollision.positive(num(f[3])));
            if (kind.equals("K") && f.length == 6)
                return new SceneCollision.Capsule(
                        new SceneCollision.Point(num(f[1]), num(f[2])),
                        new SceneCollision.Point(num(f[3]), num(f[4])),
                        SceneCollision.positive(num(f[5])));
            if (kind.equals("P") && f.length >= 7 && f.length % 2 == 1) {
                var points = new ArrayList<SceneCollision.Point>();
                for (int i = 1; i < f.length; i += 2) points.add(new SceneCollision.Point(num(f[i]), num(f[i + 1])));
                return new SceneCollision.Polygon(points);
            }
            throw new IllegalArgumentException();
        } catch (RuntimeException e) {
            throw new IOException("Invalid static collision geometry", e);
        }
    }

    private static float num(String s) {
        float f = Float.parseFloat(s);
        if (!Float.isFinite(f)) throw new IllegalArgumentException();
        return f;
    }

    private static int count(Properties p, String key, int max) throws IOException {
        int n = integer(p, key);
        if (n < 0 || n > max) throw new IOException("Invalid " + key);
        return n;
    }

    private static int integer(Properties p, String key) throws IOException {
        try {
            return Integer.parseInt(required(p, key));
        } catch (NumberFormatException e) {
            throw new IOException("Invalid " + key, e);
        }
    }

    private static String required(Properties p, String key) throws IOException {
        String v = p.getProperty(key);
        if (v == null) throw new IOException("Missing " + key);
        return v;
    }
}
