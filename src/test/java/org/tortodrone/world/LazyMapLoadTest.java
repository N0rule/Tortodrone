package org.tortodrone.world;

import java.nio.file.*;
import org.tortodrone.resources.ContentManager;
import static org.tortodrone.support.TestWire.check;

/** A catalog keeps map bodies on disk until a scene is addressed. */
public final class LazyMapLoadTest {
    public static void main(String[] args) throws Exception {
        var content = ContentManager.load(Path.of(""));
        var catalog = content.scenes();
        check(catalog.sceneCount() == 17, "all seventeen maps are indexed");
        check(catalog.loadedCount() == 1, "only the entry scene is loaded at startup");
        catalog.scene(318);
        check(catalog.loadedCount() == 2, "addressing a scene loads exactly that body");
        check(catalog.scene(318) == catalog.scene(318), "a loaded scene is cached");
        System.out.println("LazyMapLoadTest PASS");
    }
}
