package org.tortodrone.server.network;

import java.util.Map;
import java.util.TreeMap;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.world.ActorState;

/** The connection's published knight model and the scene actor built from the installed
 *  instance, the pending arrival entrance and the selected knight. Appearance is rebuilt
 *  whenever equipment, sprite or scene changes and is read under the session monitor. */
final class SessionAppearance {
    private final WorldSession session;
    private StarterCatalog.Model model;

    SessionAppearance(WorldSession session) {
        this.session = session;
    }

    void rebuild() {
        var scene = session.currentScene();
        var visible = session.visibleProfile();
        var base = scene.map().town()
            ? StarterCatalog.town(session.selectedKnight(), visible, session.charactersStore().catalog())
            : StarterCatalog.interior(session.selectedKnight(), visible, session.charactersStore().catalog());
        var arguments = new TreeMap<String, Object>(base.arguments());
        long[] slots = visible.equipment();
        for (var item : visible.items()) if (item.equippedSlot() != null) {
            int slot = item.equippedSlot();
            if (slot == 0 && slots[3] != 0 || slot == 1 && slots[4] != 0) continue;
            for (String accessory : item.additionalAccessories()) {
                var nativeModel = session.sceneRegistry().town().accessoryModel(accessory);
                String parameter = session.sceneRegistry().town().accessoryParameter(accessory);
                if (nativeModel != null && parameter != null) arguments.put(parameter, nativeModel);
            }
        }
        base = new StarterCatalog.Model(base.name(), arguments);
        model = new StarterCatalog.Model(scene.map().town() ? "Character/PC/Default" : "Character/PC/Level",
            Map.of("Model", base, "Height", base.arguments().get("Height")));
    }

    ActorState actor() {
        var runtime = session.runtime();
        var scene = session.currentScene();
        var arrival = runtime.arrival();
        var checkpoint = arrival == null ? scene.simulation().defaultEntrance() : null;
        return new ActorState(session.currentProfile().worldId(), session.connectionOid(), session.selectedKnight().name(),
            256 | session.selectedKnight().color(), session.sceneCreated(),
            arrival == null ? checkpoint == null ? scene.map().spawnX() : checkpoint.x() : arrival.x(),
            arrival == null ? checkpoint == null ? scene.map().spawnY() : checkpoint.y() : arrival.y(),
            arrival == null ? checkpoint == null ? scene.map().spawnRotation() : checkpoint.rotation() : arrival.rotation(),
            0, 0, 0, session.sceneCreated(), model, session.gameMasterFlag());
    }

    void update() {
        rebuild();
        if (session.membership() != null) {
            session.membership().equipment(session.visibleProfile());
            session.membership().appearance(actor());
        }
    }
}
