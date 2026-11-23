package org.tortodrone.world;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.core.world.SceneCombatEvent;
import org.tortodrone.core.world.SceneHealth;
import org.tortodrone.core.world.ScenePortals;

/** Assembles the immutable per-tick snapshot from the scene's live member, entity and
 *  weapon sources. Gathering stays free of encoding; the resulting snapshot owns all
 *  delta math and the shared encoding cache. */
final class SnapshotBuilder {
    private SnapshotBuilder() {}

    static SceneSnapshot build(
            int stamp,
            Collection<SceneMembership> members,
            SceneEntities entities,
            SceneWeapons weapons,
            Collection<SceneCombatEvent> combatEvents,
            SceneSnapshot baseline) {
        var portals = new HashMap<Integer, ScenePortals.Trigger>();
        var actors = new ArrayList<ActorState>();
        var health = new HashMap<Integer, SceneHealth.State>();
        for (var member : members) {
            var actor = member.snapshotActor();
            if (member.portalTrigger() != null) portals.put(actor.id(), member.portalTrigger());
            actors.add(actor);
            health.put(actor.id(), member.healthState());
        }
        // Pets follow their owners so the client always sees the owner record first.
        for (var member : members) {
            var pet = member.petSnapshot();
            if (pet != null) actors.add(pet);
        }
        actors.addAll(entities.actors());
        actors.addAll(weapons.actors());
        return new SceneSnapshot(
                stamp, actors, weapons.effects(), List.copyOf(combatEvents), portals, health, baseline);
    }
}
