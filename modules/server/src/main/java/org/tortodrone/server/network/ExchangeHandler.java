package org.tortodrone.server.network;

import java.io.IOException;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.core.character.Knight;
import org.tortodrone.core.town.ExchangeState;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.protocol.SessionDecoder;

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

    boolean subscribed() {
        return subscribed;
    }

    boolean wants() {
        synchronized (session) {
            return !session.isClosed() && subscribed;
        }
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
        synchronized (session) {
            subscribed = false;
            revision = -1;
        }
    }

    /** Native cancel, offers and trade requests; the listener shape is validated before any work. */
    boolean invocation(SessionDecoder.Invocation request) throws IOException {
        synchronized (session) {
            if (request.oid() != SessionCodec.EXCHANGE_OID || request.service() != SessionCodec.EXCHANGE_SERVICE)
                return false;
            var arguments = request.arguments();
            int method = request.method();
            if (arguments.isEmpty()
                    || !(arguments.getLast() instanceof SessionDecoder.Listener listener)
                    || !listener.type()
                            .equals(org.tortodrone.core.WireTypes.LISTENER_PREFIX
                                    + (method == 1 ? "ConfirmMarshaller" : "ResultMarshaller")))
                throw new SessionDecoder.Rejected("Invalid Energy Exchange listener");
            boolean valid = switch (method) {
                case 1 -> arguments.size() == 2 && arguments.getFirst() instanceof Integer;
                case 2 -> arguments.size() == 1;
                case 3 ->
                    arguments.size() == 5
                            && arguments.get(0) instanceof Integer
                            && arguments.get(1) instanceof Integer
                            && arguments.get(2) instanceof Boolean
                            && arguments.get(3) instanceof Boolean;
                default -> false;
            };
            if (!valid) throw new SessionDecoder.Rejected("Invalid Energy Exchange request");
            try {
                var scene = session.currentScene().map();
                if (session.isClosed()
                        || !session.selected()
                        || !session.sceneEntered()
                        || !session.sceneSubscribed()
                        || !subscribed
                        || !scene.town() && !scene.kind().equals("training"))
                    throw new IOException("Open the Energy Exchange in town first");
                if (session.tradeHandler().active()) throw new IOException("Finish the trade first");
                var exchange = store.services().exchange();
                var knight = session.selectedKnight();
                int account = store.accountId();
                long revision = session.authRevision();
                var result = switch (method) {
                    case 1 -> exchange.cancel(account, knight.id(), revision, (Integer) arguments.getFirst());
                    case 2 -> exchange.offers(account, knight.id(), revision);
                    default ->
                        exchange.trade(
                                account,
                                knight.id(),
                                revision,
                                (Integer) arguments.get(0),
                                (Integer) arguments.get(1),
                                (Boolean) arguments.get(2),
                                (Boolean) arguments.get(3));
                };
                session.townChanged(result);
                refresh(exchange.book());
                if (method == 1) responder.confirmed(listener.requestId());
                else responder.result(listener.requestId(), result.result(), session.currentProfile());
            } catch (IOException failure) {
                responder.failure(listener.requestId(), failure.getMessage());
            }
            return true;
        }
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
