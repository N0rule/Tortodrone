package org.tortodrone.server.network;

import java.io.IOException;
import org.tortodrone.core.character.WorldSummary;
import org.tortodrone.core.character.WorldProfile;

/** Applies one batched world-state read to a session: identity validation, permission changes
 *  and revision-guarded profile publication. Reads happen outside the session monitor; the
 *  handler revalidates the subscription before every publication and never rolls back a newer
 *  committed equip. */
final class WorldStateHandler {
    private final WorldSession session;
    private final SessionResponder responder;

    WorldStateHandler(WorldSession session) {
        this.session = session;
        this.responder = session.responder();
    }

    void refresh(WorldStatePoller.Registration registration, WorldSummary state) throws IOException {
        synchronized (session) {
            if (!session.stateRefreshActive(registration)) return;
            if (state == null || state.accountId() != registration.accountId || state.knightId() != registration.knightId
                    || state.authRevision() != registration.authRevision || !state.name().equalsIgnoreCase(registration.name))
                throw new IOException("Knight or account changed; reconnect");
            session.tradeHandler().changed(state);
        }
        WorldProfile before = session.currentProfile();
        WorldProfile fresh = state.revision() > before.revision() ? session.loadProfile(before) : before;
        synchronized (session) {
            if (!session.stateRefreshActive(registration)) return;
            if (state.gameMaster() != session.gameMasterFlag()) {
                session.setGameMaster(state.gameMaster());
                session.applyActorAppearance();
                responder.send(() -> responder.codec().playerFlags(responder.oid(),
                    session.gameMasterFlag(), session.inspectionHiddenValue(), session.currentProfile().progress().tutorialComplete()));
            }
            // An equip may commit while the read is in flight: never roll back its publication.
            if (fresh.revision() > session.currentProfile().revision()) {
                var visibleBefore = session.visibleProfileNow();
                var previous = session.currentProfile();
                session.applyProfile(fresh);
                if (fresh.crowns() != previous.crowns() || fresh.energy() != previous.energy())
                    responder.send(() -> responder.codec().balances(responder.oid(), session.currentProfile()));
                if (!fresh.progress().equals(previous.progress())) {
                    session.membership().equipment(session.visibleProfileNow());
                    responder.send(() -> responder.codec().knightProgress(responder.oid(), session.currentProfile()));
                }
                if (!fresh.town().equals(previous.town())) {
                    session.membership().equipment(session.visibleProfileNow());
                    responder.send(() -> responder.codec().townState(responder.oid(), session.currentProfile()));
                    session.refreshOutbids(previous, session.currentProfile());
                }
                if (!fresh.town().loadouts().equals(previous.town().loadouts()))
                    responder.send(() -> responder.codec().loadoutChanges(responder.oid(), previous, session.currentProfile()));
                if (!fresh.items().equals(previous.items())) {
                    session.updateAppearance();
                    var visibleAfter = session.visibleProfileNow();
                    responder.send(() -> responder.codec().itemChanges(responder.oid(),
                        session.selectedKnight(), visibleBefore, visibleAfter));
                }
            }
        }
    }

    void failed(WorldStatePoller.Registration registration, Exception failure) {
        synchronized (session) {
            if (!session.stateRefreshActive(registration)) return;
            session.sessionLog().warn("World state refresh closed; " + WorldSession.deliveryFailure(failure));
            session.close();
            responder.disconnect();
        }
    }
}
