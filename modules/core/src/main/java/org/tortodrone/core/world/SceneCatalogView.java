package org.tortodrone.core.world;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/** Read-only scene template catalogue used by the wire codec and route owners. */
public interface SceneCatalogView {
    MapScene scene(int id) throws IOException;

    MapScene entry();

    List<MapScene> towns();

    List<MapScene> zoneScenes(int zone);

    Set<Integer> zoneIds();

    boolean contains(int id);

    boolean isEmpty();
}
