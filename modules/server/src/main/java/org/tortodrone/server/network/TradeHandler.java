package org.tortodrone.server.network;

import java.io.IOException;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.core.character.Knight;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.protocol.SessionDecoder;
import org.tortodrone.storage.Database;

/** Player-trade dialog ownership and routing for one connection. Cross-player routing stays in
 *  {@link PlayerTrades}; this handler holds only this session's seat and writes through the
 *  responder. */
final class TradeHandler {
    private final WorldSession session;
    private final SessionResponder responder;
    private final CharacterStore store;
    private PlayerTrades trades;
    private PlayerTrades.Member member;

    TradeHandler(WorldSession session) {
        this.session = session;
        this.responder = session.responder();
        this.store = session.charactersStore();
    }

    boolean active() { return trades != null && trades.active(member); }
    boolean owns(int objectId) { return trades != null && trades.owns(member, objectId); }
    long dialogGeneration() { return member == null ? 0 : member.dialogGeneration; }
    boolean dialogChanged(long townTradeGeneration) { return member != null && townTradeGeneration != member.dialogGeneration; }

    void join(int place) throws IOException {
        synchronized (session) {
            PlayerTrades broker = PlayerTrades.forStore(store);
            Knight knight = session.selectedKnight();
            trades = broker;
            member = broker.join(store, knight, session.currentProfile(), session.authRevision(), place,
                responder.oid(), session.output(), responder.codec(), session.socket());
        }
    }

    void leave() {
        synchronized (session) {
            if (member != null) { trades.leave(member); member = null; }
        }
    }

    void changed(org.tortodrone.character.WorldSummary state) throws IOException {
        synchronized (session) {
            if (member != null) trades.changed(member, state);
        }
    }

    void subscribe(int objectId, short message) throws IOException {
        PlayerTrades broker; PlayerTrades.Member seat;
        synchronized (session) { broker = trades; seat = member; }
        if (broker == null) throw new IOException("No such trade");
        broker.subscribe(seat, objectId, message);
    }

    void unsubscribe(int objectId) {
        synchronized (session) {
            if (trades != null) trades.unsubscribe(member, objectId);
        }
    }

    /** Route across players without holding either WorldSession's monitor. */
    boolean invocation(SessionDecoder.Invocation request) throws IOException {
        boolean invite = request.service() == 6 && request.method() == 4 && session.ownsScene(request.oid());
        boolean close = request.service() == 6 && request.method() == 1 && session.ownsScene(request.oid());
        boolean response = request.oid() == SessionCodec.INVOCATION_OID && request.service() == SessionCodec.SOCIAL_SERVICE && request.method() == 5;
        boolean operation = request.service() == 1 && request.oid() >= 1001 && (request.oid() & 1) == 1;
        if (!invite && !close && !response && !operation) return false;
        var args = request.arguments();
        PlayerTrades broker; PlayerTrades.Member seat;
        synchronized (session) { broker = trades; seat = member; }
        if (close) {
            if (args.size() != 1 || !(args.getFirst() instanceof Long)) throw new SessionDecoder.Rejected("Invalid dialog close");
            if (broker != null) broker.close(seat, (Long) args.getFirst());
            return true;
        }
        if (args.isEmpty() || !(args.getLast() instanceof SessionDecoder.Listener listener)
                || !listener.type().equals(org.tortodrone.core.WireTypes.CONFIRM_LISTENER))
            throw new SessionDecoder.Rejected("Invalid trade confirmation listener");
        boolean valid = invite ? args.size() == 2 && args.getFirst() instanceof String :
            response ? args.size() == 3 && args.get(0) instanceof String && args.get(1) instanceof Integer :
            request.method() >= 1 && request.method() <= 5 && (request.method() == 2 || request.method() == 3 ?
                args.size() == 3 && args.get(0) instanceof Long && args.get(1) instanceof Integer :
                args.size() == 2 && args.get(0) instanceof Integer);
        if (!valid) throw new SessionDecoder.Rejected("Invalid trade arguments");
        try {
            if (seat == null || broker == null) throw new IOException("Enter a scene before trading");
            if (invite) {
                if (!session.chatRate().allow(System.nanoTime())) throw new IOException("Please slow down before inviting another trade");
                broker.invite(seat, (String) args.get(0));
            } else if (response) broker.respond(seat, (String) args.get(0), (Integer) args.get(1));
            else broker.operate(seat, request.oid(), request.method(), args);
            responder.confirmed(listener.requestId());
        } catch (IOException refused) {
            responder.failure(listener.requestId(), refused.getMessage());
        }
        return true;
    }
}
