package org.tortodrone.server.network;

import java.io.IOException;
import java.util.List;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.protocol.SessionDecoder;
import org.tortodrone.storage.StyleStore;

/** Knight color/eye/height styling and style-kit use for one connection.
 *  Applies the stock apply/use request shapes, writes through the store and publishes on
 *  the responder thread, under the session monitor like every other feature handler. */
final class StyleHandler {
    private final WorldSession session;
    private final SessionResponder responder;
    private final CharacterStore store;

    StyleHandler(WorldSession session) {
        this.session = session;
        this.responder = session.responder();
        this.store = session.charactersStore();
    }

    boolean invocation(SessionDecoder.Invocation request)throws IOException { synchronized (session) {
        if(request.oid()!=SessionCodec.INVOCATION_OID)return false;
        boolean apply=request.service()==SessionCodec.KNIGHT_SERVICE&&request.method()==1;
        var args=request.arguments();
        var profile=session.currentProfile();
        boolean use=request.service()==SessionCodec.ITEM_SERVICE&&request.method()==14&&args.size()==3&&args.getFirst() instanceof Long id&&profile!=null
            &&profile.items().stream().anyMatch(item->item.id()==id&&StyleStore.kit(item.config())!=null);
        if(!apply&&!use)return false;
        if(args.size()!=(apply?2:3)||!(args.getLast() instanceof SessionDecoder.Listener listener)||!listener.type().equals(org.tortodrone.core.WireTypes.CONFIRM_LISTENER)||use&&args.get(1)!=null)
            throw new SessionDecoder.Rejected("Invalid style request");
        try{
            if(!session.inSubscribedScene()||session.sceneTravelPending())throw new IOException("Enter a scene first");
            if(session.tradeHandler().active())throw new IOException("Finish the trade first");
            if(!session.canOpenBox())throw new IOException("e.not_use_busy");
            StyleStore.Style style=null;
            if(apply){
                var value=args.getFirst();var kind=StyleStore.Kind.Color;int ordinal;
                if(value instanceof Integer color)ordinal=color;
                else if(value instanceof org.tortodrone.core.wire.EnumValue e&&e.type().equals(org.tortodrone.core.WireTypes.EYE_TYPE)){kind=StyleStore.Kind.Eye;ordinal=e.ordinal();}
                else if(value instanceof org.tortodrone.core.wire.EnumValue e&&e.type().equals(org.tortodrone.core.WireTypes.KNIGHT_HEIGHT)){kind=StyleStore.Kind.Height;ordinal=e.ordinal();}
                else throw new IOException("m.internal_error");
                try{style=new StyleStore.Style(kind,ordinal);}catch(IllegalArgumentException invalid){throw new IOException("m.internal_error");}
            }
            var current=session.loadOwnProfile();
            var result=store.styles().change(store.accountId(),session.selectedKnight().id(),session.authRevision(),current.revision(),use?(Long)args.getFirst():null,style);
            store.refresh();session.refreshKnight();
            session.townChanged(result);publish();session.publishPartyState();
            responder.confirmed(listener.requestId());
        }catch(CharacterStore.Refused|IOException refused){responder.failure(listener.requestId(),refused.getMessage());}
        return true;
    }}

    void publish()throws IOException { synchronized (session) {
        if(!store.hasWorldStorage())return;
        var saved=store.styles().saved(store.accountId(),session.selectedKnight().id(),session.authRevision());
        var knight=session.selectedKnight();
        responder.send(()->responder.codec().styles(responder.oid(),knight,saved));
    }}
}
