package org.tortodrone.world;

import static org.tortodrone.support.TestWire.check;

import java.nio.file.*;
import java.util.*;
import org.tortodrone.core.world.FireCatalog;
import org.tortodrone.resources.ContentManager;

/**
 * Tile and floor lookups must stay bounded for cells whose {@code Long} key collides.
 * {@code Long.hashCode} maps every {@code x==y} cell to zero; the immutable map/set this
 * class used resolved that bucket by linear probing, so one lookup there cost tens of
 * microseconds. Monster and helper path searches probe thousands of cells, which stalled
 * the scene tick for seconds exactly while NPCs were chasing or following.
 */
public final class SceneCollisionLookupTest {
    public static void main(String[] args) throws Exception {
        var map = ContentManager.load(Path.of(".")).scenes().scene(398);
        var collision = map.staticCollision();
        var shape = new FireCatalog.DataValue(
                "com.threerings.tudey.shape.config.ShapeConfig$Circle", Map.of("radius", .333f));
        // Warm both query paths before timing anything wall-clock.
        for (int i = 0; i < 500; i++) {
            collision.floorSupported(-1000.5f + i, -1000.5f + i);
            collision.clear(43, shape, -1000.5f + i, -1000.5f + i, 0);
        }
        long began = System.nanoTime();
        for (int i = 0; i < 20_000; i++) collision.floorSupported(-1000.5f + i, -1000.5f + i);
        for (int i = 0; i < 20_000; i++) collision.clear(43, shape, -1000.5f + i, -1000.5f + i, 0);
        long lookupMillis = (System.nanoTime() - began) / 1_000_000L;
        check(
                lookupMillis < 200,
                "40,000 colliding-key tile/floor lookups stay under 200 ms (" + lookupMillis + " ms)");
        began = System.nanoTime();
        ScenePathing.route(
                -38,
                -78,
                30,
                30,
                (x, y, nx, ny) ->
                        collision.floorSupported(nx, ny) && collision.clearSegment(43, shape, x, y, nx, ny, 0));
        long routeMillis = (System.nanoTime() - began) / 1_000_000L;
        check(routeMillis < 500, "a worst-case blocked path search stays under 500 ms (" + routeMillis + " ms)");
        System.out.println("PASS: colliding-key tile/floor lookups (" + lookupMillis
                + " ms) and a blocked path search (" + routeMillis + " ms) stay bounded");
    }
}
