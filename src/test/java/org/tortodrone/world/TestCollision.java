package org.tortodrone.world;

import java.nio.file.*;
import java.util.Properties;

/** Synthetic exact collision for movement tests; no sampled grid is involved. */
final class TestCollision {
    private TestCollision() { }

    /** An open rectangle of floor cells with no blocking shapes. */
    static CollisionMap floor(int minX, int minY, int width, int height) throws Exception { return floor(minX,minY,width,height,Integer.MIN_VALUE,Integer.MIN_VALUE,0); }

    /** A closed rectangular room: floor inside, blocking tiles on the border. */
    static CollisionMap corridor(int minX, int minY, int width, int height) throws Exception {
        var p = new Properties();
        p.setProperty("format", "1");
        p.setProperty("layout-sha256", "a".repeat(64));
        int floors = 0;
        for (int y = minY + 1; y < minY + height - 1; y++) for (int x = minX + 1; x < minX + width - 1; x++) {
            p.setProperty("floor." + floors + ".x", Integer.toString(x));
            p.setProperty("floor." + floors + ".y", Integer.toString(y));
            floors++;
        }
        p.setProperty("floor-count", Integer.toString(floors));
        var walls = new java.util.ArrayList<int[]>();
        for (int x = minX; x < minX + width; x++) { walls.add(new int[]{x, minY}); walls.add(new int[]{x, minY + height - 1}); }
        for (int y = minY + 1; y < minY + height - 1; y++) { walls.add(new int[]{minX, y}); walls.add(new int[]{minX + width - 1, y}); }
        p.setProperty("tile-count", Integer.toString(walls.size()));
        for (int i = 0; i < walls.size(); i++) {
            p.setProperty("tile." + i + ".x", Integer.toString(walls.get(i)[0]));
            p.setProperty("tile." + i + ".y", Integer.toString(walls.get(i)[1]));
            p.setProperty("tile." + i + ".mask", "1");
        }
        p.setProperty("shape-count", "0");
        Path file = Files.createTempFile("tortodrone-exact-corridor-", ".properties");
        try {
            try (var out = Files.newOutputStream(file)) { p.store(out, "test"); }
            var collision = MapCollision.fromProperties(file);
            return CollisionMap.exact(collision, Math.round(SceneContent.DEFAULT_PLAYER_COLLISION_MASK), ScenePortals.PAWN_RADIUS);
        } finally { Files.deleteIfExists(file); }
    }

    /** As {@link #floor}, with one blocking tile at (wallX,wallY). */
    static CollisionMap floorWithWall(int minX, int minY, int width, int height, int wallX, int wallY) throws Exception {
        return floor(minX, minY, width, height, wallX, wallY, 1);
    }
    private static CollisionMap floor(int minX, int minY, int width, int height, int wallX, int wallY, int wallMask) throws Exception {
        var p = new Properties();
        p.setProperty("format", "1");
        p.setProperty("layout-sha256", "a".repeat(64));
        p.setProperty("tile-count", wallMask == 0 ? "0" : "1");
        if (wallMask != 0) {
            p.setProperty("tile.0.x", Integer.toString(wallX));
            p.setProperty("tile.0.y", Integer.toString(wallY));
            p.setProperty("tile.0.mask", Integer.toString(wallMask));
        }
        p.setProperty("shape-count", "0");
        int floors = 0;
        for (int y = minY; y < minY + height; y++) for (int x = minX; x < minX + width; x++) {
            p.setProperty("floor." + floors + ".x", Integer.toString(x));
            p.setProperty("floor." + floors + ".y", Integer.toString(y));
            floors++;
        }
        p.setProperty("floor-count", Integer.toString(floors));
        Path file = Files.createTempFile("tortodrone-exact-floor-", ".properties");
        try {
            try (var out = Files.newOutputStream(file)) { p.store(out, "test"); }
            var collision = MapCollision.fromProperties(file);
            return CollisionMap.exact(collision, Math.round(SceneContent.DEFAULT_PLAYER_COLLISION_MASK), ScenePortals.PAWN_RADIUS);
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
