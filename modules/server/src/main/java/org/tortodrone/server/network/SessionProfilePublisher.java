package org.tortodrone.server.network;

import java.io.IOException;
import java.util.Map;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.storage.TownStore;

/** Publishes one committed town/profile result in the native order: item, balance,
 *  achievement, town, outbid and loadout changes. The durable write has already committed,
 *  so a failed buyer connection never suppresses its shared scene effect for other
 *  observers. Runs under the session monitor. */
final class SessionProfilePublisher {
    private final WorldSession session;
    private final SessionResponder responder;

    SessionProfilePublisher(WorldSession session) {
        this.session = session;
        this.responder = session.responder();
    }

    void changed(TownStore.Result result, boolean publishTown) throws IOException {
        var before = session.visibleProfile();
        session.applyProfile(result.profile());
        session.updateAppearance();
        var after = session.visibleProfile();
        if (before.energy() > after.energy() && session.membership() != null)
            session.membership().effect(new StarterCatalog.Model("Energy Spent", Map.of()));
        responder.send(() -> responder.codec().itemChanges(responder.oid(), session.selectedKnight(), before, after));
        responder.send(() -> responder.codec().balances(responder.oid(), after));
        var achievementChanges = responder.codec().achievementChanges(responder.oid(), before, after);
        if (achievementChanges != null) responder.send(() -> achievementChanges);
        if (publishTown) responder.send(() -> responder.codec().townState(responder.oid(), after));
        session.publishOutbids(before, after);
        if (!before.town().loadouts().equals(after.town().loadouts())
                || before.town().equippedSprite() != after.town().equippedSprite()
                || !before.town().sprites().equals(after.town().sprites()))
            responder.send(() -> responder.codec().loadoutChanges(responder.oid(), before, after));
    }
}
