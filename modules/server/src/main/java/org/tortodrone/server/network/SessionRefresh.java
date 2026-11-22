package org.tortodrone.server.network;

import java.io.IOException;
import java.util.Map;
import org.tortodrone.core.world.ReadyRoom;
import org.tortodrone.world.SceneRegistry;

/** Owns the forced scene receiver and the post-selection / receiver-change publications. The
 *  receiver is the zone channel the client currently listens to; every publication happens
 *  under the session monitor so a concurrent travel decision cannot interleave. */
final class SessionRefresh {
    private final WorldSession session;
    private Short forcedReceiver;

    SessionRefresh(WorldSession session) {
        this.session = session;
    }

    Short forcedReceiver() {
        return forcedReceiver;
    }

    /** Call outside the CharacterStore publication lock to preserve session/account lock order. */
    void refresh() throws IOException {
        synchronized (session) {
            if (!session.selected()) return;
            var visible = session.visibleProfile();
            session.output().send(() -> session.codec().selected(session.connectionOid(), session.selectedKnight(),
                visible, session.guilds.guildInfo()));
            session.publishQuickbar();
            session.output().send(() -> session.codec().playerFlags(session.connectionOid(), session.gameMasterFlag(),
                session.inspectionHiddenValue(), session.currentProfile().progress().tutorialComplete()));
            if (session.runtime().subscribed()) session.output().send(() -> session.codec().sceneLocation(
                session.connectionOid(), session.currentScene(),
                session.sceneRegistry().channelOid(session.currentScene().map().zone(), false),
                session.sceneRegistry().channelOid(session.currentScene().map().zone(), true)));
        }
    }

    void receiverChanged(Map<String, Short> receivers) throws IOException {
        synchronized (session) {
            Short receiver = receivers.get(SceneRegistry.ZONE_RECEIVER);
            if (receiver == null) forcedReceiver = null;
            if (!session.selected() || receiver == null || receiver.equals(forcedReceiver)) return;
            var destination = session.runtime().portalMove() == null
                ? session.currentScene() : session.runtime().portalMove().destination();
            if (session.runtime().readyRoom() && !session.runtime().returnRequested())
                session.output().send(() -> session.codec().forceDestination(session.connectionOid(), receiver,
                    ReadyRoom.ZONE, ReadyRoom.SCENE));
            else session.output().send(() -> session.codec().forceScene(session.connectionOid(), receiver, destination));
            forcedReceiver = receiver;
            session.flushPendingTravel();
        }
    }
}
