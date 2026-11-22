package org.tortodrone.server.network;

import java.io.IOException;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.core.character.Knight;
import org.tortodrone.core.town.ExchangeState;

/** Energy Exchange subscription and revision tracking for one connection. */
final class ExchangeHandler {
    private final WorldSession session;
    private final SessionResponder responder;
    private final CharacterStore store;
    private boolean subscribed;
    private long revision = -1;

    ExchangeHandler(WorldSession session) {
        this.session = session;
        this.responder = session.responder();
        this.store = session.charactersStore();
    }

    boolean subscribed() { return subscribed; }

    boolean wants() {
        synchronized (session) { return !session.isClosed() && subscribed; }
    }

    void subscribe(short message) throws IOException {
        synchronized (session) {
            if (session.isClosed() || !session.selected()) throw new IOException("Select a knight first");
            Knight knight = session.selectedKnight();
            try {
                store.worldProfile(knight.name(), session.authRevision(), session.currentProfile());
            } catch (CharacterStore.Refused refused) {
                throw new IOException("Knight changed; reconnect", refused);
            }
            var book = store.services().exchange().book();
            subscribed = true;
            revision = book.revision();
            responder.send(() -> responder.codec().exchange(message, book));
        }
    }

    void unsubscribe() {
        synchronized (session) { subscribed = false; revision = -1; }
    }

    void refresh(ExchangeState.Book book) throws IOException {
        synchronized (session) {
            if (!session.isClosed() && subscribed && book.revision() > revision) {
                revision = book.revision();
                responder.send(() -> responder.codec().exchangeChanged(book));
            }
        }
    }
}
