package org.tortodrone.server.network;

import java.io.IOException;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.core.character.Knight;
import org.tortodrone.core.character.WorldProfile;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.protocol.SessionDecoder;
import org.tortodrone.world.SceneRegistry;

/** Auction house and uplink mail operations for one connection. */
final class AuctionHandler {
    private final WorldSession session;
    private final SessionResponder responder;
    private final CharacterStore store;

    AuctionHandler(WorldSession session) {
        this.session = session;
        this.responder = session.responder();
        this.store = session.charactersStore();
    }

    /** The scene's auction station, surfaced as the native controller source. */
    ActorState source(SceneRegistry.Instance place)throws IOException {
        var source=place.simulation().station("OpenAuction");
        if(source==null)throw new IOException("No Auction House in this scene");
        return source;
    }

    void open()throws IOException { synchronized (session) {
        var source=source(session.currentScene());
        responder.send(()->responder.codec().controllerAction(responder.oid(),"auction",source));
    }}

    /** Publish each newly durable outbid once the write has committed. */
    void publishOutbids(WorldProfile before,WorldProfile after)throws IOException { synchronized (session) {
        for(var notice:after.town().outbids())if(!before.town().outbids().contains(notice)){
            var item=store.catalog().require(notice.item());
            responder.send(()->responder.codec().auctionOutbid(session.currentScene().oid(),"%item:"+item.nameKey(),notice.crowns()));
        }
    }}

    boolean invocation(SessionDecoder.Invocation request) throws IOException {
        synchronized (session) {
            if (request.oid() != SessionCodec.INVOCATION_OID) return false;
            var a = request.arguments();
            int method = request.method();
            boolean auction = request.service() == SessionCodec.AUCTION_SERVICE;
            boolean mail = request.service() == SessionCodec.UPLINK_SERVICE
                && (method == 1 || method == 2 || method == 4 || method == 5 || method == 6 || method == 11);
            if (!auction && !mail) return false;
            boolean resultListener = auction || method == 6;
            if (a.isEmpty() || !(a.getLast() instanceof SessionDecoder.Listener listener)
                    || !listener.type().equals(org.tortodrone.core.WireTypes.LISTENER_PREFIX
                        + (resultListener ? "ResultMarshaller" : "ConfirmMarshaller")))
                throw new SessionDecoder.Rejected("Invalid auction/mail listener");
            boolean valid = auction ? switch (method) {
                case 1 -> a.size() == 3 && a.get(0) instanceof Long && a.get(1) instanceof Integer;
                case 2, 7 -> a.size() == 2 && a.get(0) instanceof Long;
                case 3, 4 -> a.size() == 1;
                case 5 -> a.size() == 3 && a.get(0) instanceof org.tortodrone.core.wire.AuctionSearch && a.get(1) instanceof Integer;
                case 6 -> a.size() == 7 && a.get(0) instanceof Long && a.get(1) instanceof Integer
                    && a.get(2) instanceof Integer && a.get(3) instanceof Integer
                    && a.get(4) instanceof org.tortodrone.core.wire.EnumValue e
                        && e.type().equals("com.threerings.projectx.auction.data.Duration")
                    && a.get(5) instanceof Boolean;
                default -> false;
            } : a.size() == 2 && (method == 5 ? a.get(0) instanceof org.tortodrone.core.wire.SentMail
                : method == 6 ? a.get(0) == null || a.get(0) instanceof long[]
                : method == 11 ? a.get(0) instanceof long[]
                : a.get(0) instanceof Long);
            if (!valid) throw new SessionDecoder.Rejected("Invalid auction/mail arguments");
            // The stock uplink window serves mail wherever a knight is selected; the auction
            // station is a town place, so its operations keep the scene requirement.
            try {
                if (session.isClosed() || !session.selected()) throw new IOException("m.internal_error");
                if (auction && (!session.sceneEntered() || !session.sceneSubscribed()))
                    throw new IOException("Enter a scene first");
                if (session.tradeHandler().active()
                        && (auction && (method == 1 || method == 2 || method == 6)
                            || mail && (method == 1 || method == 2 || method == 5)))
                    throw new IOException("Finish the trade first");
                Knight knight = session.selectedKnight();
                var house = store.services().auction(session.sceneRegistry().town());
                var result = auction
                    ? house.operate(store.accountId(), knight.id(), session.authRevision(), method, a,
                        session.gameMasterRole(), WorldSession.auctionPresence(store.worldStorageFile()))
                    : house.mail(store.accountId(), knight.id(), session.authRevision(), method, a);
                session.townChanged(result);
                if (resultListener) responder.result(listener.requestId(), result.result(), session.currentProfile());
                else responder.confirmed(listener.requestId());
            } catch (IOException refused) {
                responder.failure(listener.requestId(), refused.getMessage());
            }
            return true;
        }
    }
}
