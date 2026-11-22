package org.tortodrone.server.network;

import java.io.IOException;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.protocol.SessionDecoder;

/** Saved and PvP loadout requests for one connection.
 *
 * <p>Stock loadout pills are party-gated rather than scene-gated, so the Ready Room may
 * save and apply them with no scene. Both the town store write and the confirm run under
 * the session monitor like every other feature handler.
 */
final class LoadoutHandler {
    private final WorldSession session;
    private final SessionResponder responder;
    private final CharacterStore store;

    LoadoutHandler(WorldSession session) {
        this.session = session;
        this.responder = session.responder();
        this.store = session.charactersStore();
    }

    boolean invocation(SessionDecoder.Invocation request)throws IOException { synchronized (session) {
        int service=request.service(),method=request.method();
        var args=request.arguments();
        if(service==SessionCodec.ITEM_SERVICE&&(method==4||method==10))return apply(args,method==10);
        if(service==SessionCodec.ITEM_SERVICE&&method==2)return remove(request,args);
        if(service==SessionCodec.KNIGHT_SERVICE&&method==15)return pvp(request,args);
        return false;
    }}

    private boolean apply(java.util.List<Object> args,boolean save)throws IOException {
        if(args.size()!=2||!(args.getFirst() instanceof String name)||!(args.getLast() instanceof SessionDecoder.Listener listener)
                ||!listener.type().equals(org.tortodrone.core.WireTypes.CONFIRM_LISTENER))
            throw new SessionDecoder.Rejected("Invalid loadout request");
        try {
            // Stock loadout pills are party-gated, not scene-gated, and the Ready Room
            // stages with no party, so staging can apply and save its loadouts there.
            if(!session.selectedKnightSelected()||!session.inReadyRoom()&&(!session.sceneSubscribed()||!session.sceneEntered()||session.sceneTravelPending()))
                throw new IOException("Enter a scene first");
            if(session.tradeHandler().active())throw new IOException("Finish the trade first");
            if(!save&&!session.inReadyRoom()&&!session.equipmentAllowed())throw new IOException("Equipment is locked outside an Arsenal Station in this level");
            var result=store.services().town(session.sceneRegistry().town()).loadout(store.accountId(),session.selectedKnight().id(),session.authRevision(),name,save);
            session.townChanged(result);
            responder.confirmed(listener.requestId());
        }catch(IOException refused){responder.failure(listener.requestId(),refused.getMessage());}
        return true;
    }

    private boolean remove(SessionDecoder.Invocation request,java.util.List<Object> args)throws IOException {
        if(request.oid()!=SessionCodec.INVOCATION_OID||args.size()!=2||!(args.getFirst() instanceof String name)
                ||!(args.getLast() instanceof SessionDecoder.Listener listener)||!listener.type().endsWith("$ConfirmMarshaller"))
            throw new SessionDecoder.Rejected("Invalid loadout removal");
        try{
            if(!session.selectedKnightSelected()||!session.inReadyRoom()&&(!session.sceneEntered()||!session.sceneSubscribed()||session.sceneTravelPending()))
                throw new IOException("Enter a scene first");
            session.townChanged(store.services().town(session.sceneRegistry().town()).removeLoadout(store.accountId(),session.selectedKnight().id(),session.authRevision(),name));
            responder.confirmed(listener.requestId());
        }catch(IOException refused){responder.failure(listener.requestId(),refused.getMessage());}
        return true;
    }

    private boolean pvp(SessionDecoder.Invocation request,java.util.List<Object> args)throws IOException {
        if(request.oid()!=SessionCodec.INVOCATION_OID||args.size()!=3||!(args.get(0) instanceof String name)||!(args.get(1) instanceof Boolean enabled)
                ||!(args.get(2) instanceof SessionDecoder.Listener listener)||!listener.type().equals(org.tortodrone.core.WireTypes.CONFIRM_LISTENER))
            throw new SessionDecoder.Rejected("Invalid PvP loadout request");
        try{
            // The Ready Room has no party view, like every other stock loadout context.
            if(!session.selectedKnightSelected()||!session.inReadyRoom()&&(!session.sceneSubscribed()||!session.sceneEntered()||session.sceneTravelPending()))
                throw new IOException("Enter a scene first");
            session.townChanged(store.services().town(session.sceneRegistry().town()).loadoutPvp(store.accountId(),session.selectedKnight().id(),session.authRevision(),name,enabled));
            responder.confirmed(listener.requestId());
        }catch(IOException refused){responder.failure(listener.requestId(),refused.getMessage());}
        return true;
    }
}
