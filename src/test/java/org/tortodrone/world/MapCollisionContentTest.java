package org.tortodrone.world;

import static org.tortodrone.support.TestWire.check;

import java.nio.file.*;
import org.tortodrone.core.world.SceneContent;
import org.tortodrone.core.world.ScenePortals;
import org.tortodrone.resources.ContentManager;

/** The shipped maps with native static collision load their compiled exact collision. */
public final class MapCollisionContentTest {
    public static void main(String[] args) throws Exception {
        var content = ContentManager.load(Path.of(""));
        for (int id : new int[] {1, 2, 3, 217, 294, 318, 398, 445, 30893}) {
            var scene = content.scenes().scene(id);
            check(scene.exactCollision() != null, "exact collision is loaded for scene " + id);
            check(scene.exactCollision().shapeCount() > 0, "scene " + id + " keeps native shapes");
            check(
                    scene.exactCollision().layoutHash().matches("[0-9a-f]{64}"),
                    "scene " + id + " collision is pinned to a layout hash");
            int mask = Math.round(scene.content()
                    .stats()
                    .getOrDefault("player.collision-mask", SceneContent.DEFAULT_PLAYER_COLLISION_MASK));
            check(
                    scene.exactCollision().clear(mask, scene.spawnX(), scene.spawnY(), ScenePortals.PAWN_RADIUS),
                    "scene " + id + " spawn is clear in exact collision");
        }
        System.out.println("MapCollisionContentTest PASS");
    }
}
