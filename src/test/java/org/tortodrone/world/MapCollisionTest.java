package org.tortodrone.world;

import static org.tortodrone.support.TestWire.check;

import java.nio.file.*;
import java.util.*;
import org.tortodrone.core.world.MapCollision;

/** Exact collision: properties import, binary round trip, broadphase queries and wall penetration. */
public final class MapCollisionTest {
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("tortodrone-collision-");
        try {
            Path properties = home.resolve("static-collision.properties");
            var p = new Properties();
            p.setProperty("format", "1");
            p.setProperty("layout-sha256", "a".repeat(64));
            int floors = 0;
            for (int x = -5; x <= 5; x++)
                for (int y = -5; y <= 5; y++) {
                    p.setProperty("floor." + floors + ".x", Integer.toString(x));
                    p.setProperty("floor." + floors + ".y", Integer.toString(y));
                    floors++;
                }
            p.setProperty("floor-count", Integer.toString(floors));
            p.setProperty("tile-count", "1");
            p.setProperty("tile.0.x", "2");
            p.setProperty("tile.0.y", "0");
            p.setProperty("tile.0.mask", "1");
            p.setProperty("shape-count", "1");
            p.setProperty("shape.0.mask", "1");
            p.setProperty("shape.0.source", "123");
            p.setProperty("shape.0.geometry", "P;0.5;-1.5;1.5;-1.5;1.5;-0.5;0.5;-0.5");
            try (var out = Files.newOutputStream(properties)) {
                p.store(out, "test");
            }

            var collision = MapCollision.fromProperties(properties);
            check(collision.tileCount() == 1 && collision.shapeCount() == 1, "properties import counts");
            check(collision.floorSupported(.5f, .5f), "floor tile is supported");
            check(!collision.floorSupported(20f, 20f), "absent floor is not invented");
            check(collision.clear(43, .5f, .5f, .33f), "open cell is clear");
            check(!collision.clear(43, 2.5f, .5f, .33f), "wall tile blocks");
            check(!collision.clear(43, 1.0f, -1.0f, .33f), "polygon obstacle blocks");
            check(
                    collision.clear(43, 1.0f, -1.0f, .33f, Set.of(123)),
                    "a source-marked door/block shape can be disabled when it opens");
            check(!collision.clearSegment(43, -1f, .5f, 4f, .5f, .33f), "segment crossing wall is blocked");
            check(collision.clearSegment(43, -1f, .5f, 1f, .5f, .33f), "short clear segment passes");
            var pen = collision.penetration(43, 2.5f, .5f, .33f);
            check(pen.present(), "penetration reports a push-out");

            Path bin = home.resolve("collision.bin");
            collision.write(bin, "a".repeat(64));
            var restored = MapCollision.read(bin);
            check(restored.tileCount() == 1 && restored.shapeCount() == 1, "binary round trip");
            check(restored.clear(43, .5f, .5f, .33f), "restored clearance");
            check(!restored.clear(43, 2.5f, .5f, .33f), "restored wall");

            // Broadphase: 8000 shapes spread over a 400x20 strip. A query near the origin must
            // not walk the whole list; this is the property 500-map deployments depend on.
            var many = new Properties();
            many.setProperty("format", "1");
            many.setProperty("layout-sha256", "b".repeat(64));
            many.setProperty("floor-count", "1");
            many.setProperty("floor.0.x", "0");
            many.setProperty("floor.0.y", "0");
            many.setProperty("tile-count", "0");
            many.setProperty("shape-count", "8000");
            for (int i = 0; i < 8000; i++) {
                float x = (i % 200) * 2f, y = (i / 200) * .5f;
                many.setProperty("shape." + i + ".mask", "1");
                many.setProperty("shape." + i + ".geometry", "C;" + x + ";" + y + ";0.2");
            }
            try (var out = Files.newOutputStream(home.resolve("many.properties"))) {
                many.store(out, "many");
            }
            var wide = MapCollision.fromProperties(home.resolve("many.properties"));
            long start = System.nanoTime();
            for (int i = 0; i < 20_000; i++) wide.clear(1, .5f, .5f, .33f);
            long millis = (System.nanoTime() - start) / 1_000_000;
            check(millis < 2_000, "8000-shape broadphase stays fast (" + millis + " ms for 20000 queries)");

            // One compiled template is shared by scene instances that tick on parallel workers;
            // broadphase dedupe state must not be instance state.
            try (var workers = java.util.concurrent.Executors.newFixedThreadPool(8)) {
                var go = new java.util.concurrent.CountDownLatch(1);
                var tasks = new ArrayList<java.util.concurrent.Callable<Void>>();
                for (int t = 0; t < 8; t++)
                    tasks.add(() -> {
                        go.await();
                        for (int i = 0; i < 20_000; i++) {
                            int shape = i & 511;
                            float x = (shape % 200) * 2f, y = (shape / 200) * .5f;
                            check(!wide.clear(1, x, y, .33f), "parallel broadphase finds every blocking shape");
                        }
                        return null;
                    });
                var running = new ArrayList<java.util.concurrent.Future<Void>>();
                for (var task : tasks) running.add(workers.submit(task));
                go.countDown();
                for (var future : running) future.get();
            }
            System.out.println("MapCollisionTest PASS (" + millis + " ms for 20000 queries over 8000 shapes)");
        } finally {
            try (var paths = Files.walk(home)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
