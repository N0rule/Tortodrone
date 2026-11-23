package org.tortodrone.world;

import java.util.HashMap;
import java.util.Map;
import org.tortodrone.core.world.ScenePortals;

/** Portal travel state for one scene instance. A membership is "in a portal" from the
 *  tick its movement reports a trigger until the session pauses/leaves or the scene
 *  tick reports none again; every scene action that is illegal during travel asks here
 *  instead of duplicating a nullable field per membership. */
final class PortalSystem {
    private final Map<Integer, ScenePortals.Trigger> active = new HashMap<>();

    ScenePortals.Trigger trigger(int actor) {
        return active.get(actor);
    }
    /** True while the member is not committed to a portal. */
    boolean open(int actor) {
        return !active.containsKey(actor);
    }

    void update(int actor, ScenePortals.Trigger trigger) {
        if (trigger == null) active.remove(actor);
        else active.put(actor, trigger);
    }

    void clear(int actor) {
        active.remove(actor);
    }

    void clearAll() {
        active.clear();
    }
}
