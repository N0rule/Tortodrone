package org.tortodrone.server.network;

import java.io.IOException;
import java.util.List;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.protocol.SessionDecoder;

/** Ordered invocation dispatch for one connection. Every probe claims a service before the next
 *  one runs; the generic handler and the character/placeholder fallbacks stay last. The router
 *  never writes to the socket directly; responses go through the session's responder path. */
final class SessionInvocationRouter {
    /** Character roster operations need the caller's receiver registry and subscription state. */
    @FunctionalInterface interface CharacterRequests {
        void handle(SessionDecoder.Invocation invocation) throws IOException;
    }

    private final WorldSession world;
    private final SessionResponder responder;
    private final SessionLog log;

    SessionInvocationRouter(WorldSession world, SessionResponder responder, SessionLog log) {
        this.world = world;
        this.responder = responder;
        this.log = log;
    }

    void route(SessionDecoder.Invocation invocation, CharacterRequests characterRequests) throws IOException {
        // The steam partner service must be claimed before the party dispatcher: stock party
        // operations share its service number on party objects and reject listener-less shapes.
        if (world.steamInvocation(invocation)) return;
        if (world.channelSpeak(invocation)) return;
        if (world.partyInvocation(invocation)) return;
        if (world.interiorPartyInvocation(invocation)) return;
        if (world.exchangeInvocation(invocation)) return;
        if (world.friendInvocation(invocation)) return;
        if (world.feedInvocation(invocation)) return;
        if (world.eventLogInvocation(invocation)) return;
        // Auction, uplink mail and the item forge leave through the early dispatcher below,
        // which sits in front of the generic request logging, so they get their own line here.
        if ((invocation.service() == SessionCodec.AUCTION_SERVICE
                || invocation.service() == SessionCodec.UPLINK_SERVICE && invocation.method() != 7
                || invocation.service() == SessionCodec.ITEM_SERVICE && invocation.method() == 8)) {
            logRequest(invocation);
        }
        if (world.guildInvocation(invocation)) return;
        if (world.auctionInvocation(invocation)) return;
        if (world.townInvocation(invocation)) {
            if (world.takeRenameDisconnect()) { world.close(); responder.disconnect(); }
            return;
        }
        if (world.conversationInvocation(invocation)) return;
        if (world.tradeInvocation(invocation)) return;
        if (world.departingScene(invocation.oid()) && invocation.service() == 10
                && invocation.method() == 1 && invocation.arguments().isEmpty()) return;
        if ((invocation.service() == org.tortodrone.core.world.SceneChat.SERVICE
                || invocation.service() == org.tortodrone.core.world.SceneChat.PARTY_SERVICE)
                && invocation.oid() != SessionCodec.INVOCATION_OID) {
            world.speak(invocation);
            return;
        }
        if (world.ownsScene(invocation.oid()) && invocation.service() >= 6 && invocation.service() <= 11) {
            if (invocation.service() == 6 && invocation.method() == 2) { world.inspect(invocation); return; }
            if (invocation.service() == 6 && invocation.method() == 3) { world.emote(invocation); return; }
            if (invocation.service() == 10 && invocation.method() == 1 && invocation.arguments().isEmpty()) {
                world.entered();
                return;
            }
            for (Object arg : invocation.arguments())
                if (arg instanceof SessionDecoder.Listener listener)
                    responder.send(() -> responder.codec().invocationFailure(responder.oid(), listener.requestId(), "Town operation is not available yet"));
            return;
        }
        if (invocation.oid() != SessionCodec.INVOCATION_OID || invocation.service() > SessionCodec.SERVICES.size())
            throw new SessionDecoder.Rejected("Invalid invocation target");
        if (invocation.service() == SessionCodec.CHAT_SERVICE && invocation.method() == 1) { world.away(invocation); return; }
        if (invocation.service() == SessionCodec.CHAT_SERVICE && invocation.method() == 2) { world.broadcast(invocation); return; }
        if (invocation.service() == SessionCodec.CHAT_SERVICE && invocation.method() == 3) { world.tell(invocation); return; }
        if (invocation.service() == SessionCodec.ADMIN_SERVICE) {
            if (invocation.oid() != SessionCodec.INVOCATION_OID)
                throw new SessionDecoder.Rejected("Invalid admin invocation target");
            if (!world.adminPeople(invocation)) world.admin(invocation);
            return;
        }
        logRequest(invocation);
        if (world.invocation(invocation)) return;
        if (invocation.service() == SessionCodec.KNIGHT_SERVICE && invocation.method() == 10) {
            world.hardware(invocation.arguments());
            return;
        }
        if (invocation.service() == SessionCodec.KNIGHT_SERVICE
                && (invocation.method() == 3 || invocation.method() == 4 || invocation.method() == 5 || invocation.method() == 6)) {
            characterRequests.handle(invocation);
            return;
        }
        boolean answered = false;
        for (Object argument : invocation.arguments()) {
            if (argument instanceof SessionDecoder.Listener listener) {
                answered = true;
                responder.failure(listener.requestId(), "Placeholder: this server operation is not implemented yet");
            }
        }
        if (!answered) world.unavailable("Placeholder: this server operation is not implemented yet");
    }

    private void logRequest(SessionDecoder.Invocation invocation) {
        String service = SessionCodec.SERVICES.get(invocation.service() - 1);
        log.debug("InvocationRequestEvent received; service=" + service.substring(service.lastIndexOf('.') + 1)
            + "; method=" + invocation.method() + "; arguments=" + invocation.arguments().size());
    }
}
