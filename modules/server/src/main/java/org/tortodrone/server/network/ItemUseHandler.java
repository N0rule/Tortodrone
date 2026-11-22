package org.tortodrone.server.network;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.core.character.AnimatedItems;
import org.tortodrone.core.character.Confetti;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.protocol.SessionDecoder;
import org.tortodrone.storage.TownStore;

/** Native scene item use (ItemMarshaller 14) for one connection.
 *
 * <p>Confetti and animated items, persistent owned items and temporary scene pickups each
 * charge through the town store and apply on the scene, then publish. Receipts make a
 * retried listener id idempotent without a second write.
 */
final class ItemUseHandler {
    private final WorldSession session;
    private final SessionResponder responder;
    private final CharacterStore store;
    private final RequestReceipts<Reply> receipts=new RequestReceipts<>();

    private record Reply(long item,String failure) { }

    ItemUseHandler(WorldSession session) {
        this.session = session;
        this.responder = session.responder();
        this.store = session.charactersStore();
    }

    boolean invocation(SessionDecoder.Invocation request,List<Object> args)throws IOException { synchronized (session) {
        if(request.oid()!=SessionCodec.INVOCATION_OID||request.service()!=SessionCodec.ITEM_SERVICE||request.method()!=14)return false;
        if(args.size()!=3||!(args.getFirst() instanceof Long id)||!(args.getLast() instanceof SessionDecoder.Listener listener)
                ||!listener.type().equals(org.tortodrone.core.WireTypes.CONFIRM_LISTENER)||args.get(1)!=null)
            throw new SessionDecoder.Rejected("Invalid item use request");
        var profile=session.currentProfile();
        if(!session.inSubscribedScene()||session.sceneTravelPending()){responder.failure(listener.requestId(),"Enter a scene first");return true;}
        int world=profile.worldId();short receiptId=listener.requestId();
        var prior=receipts.get(world,receiptId);
        if(prior!=null){
            try{session.loadOwnProfile();}
            catch(CharacterStore.Refused failure){responder.failure(listener.requestId(),failure.getMessage());return true;}
            if(prior.item()!=id||prior.failure()!=null)responder.failure(listener.requestId(),prior.failure()==null?"m.internal_error":prior.failure());
            else responder.confirmed(listener.requestId());
            return true;
        }
        var held=profile.items().stream().filter(i->i.id()==id).findFirst().orElse(null);
        if(held!=null&&(Confetti.supports(held.config())||AnimatedItems.supports(held.config()))){
            try{
                var current=session.loadOwnProfile();
                if(session.tradeHandler().active())throw new IOException("Finish the trade first");
                if(!session.canOpenBox())throw new IOException("e.not_use_busy");
                var town=store.services().town(session.sceneRegistry().town());
                int knight=session.selectedKnight().id();
                var result=Confetti.supports(held.config())?town.confetti(store.accountId(),knight,session.authRevision(),current.revision(),id)
                    :town.animatedItem(store.accountId(),knight,session.authRevision(),current.revision(),id);
                receipts.put(world,receiptId,new Reply(id,null));
                String config=(String)result.result();
                if(Confetti.supports(config))session.confetti(config);else session.animatedAction(config);
                session.townChanged(result,false);
                responder.confirmed(listener.requestId());
            }catch(CharacterStore.Refused|IOException failure){
                receipts.putIfAbsent(world,receiptId,new Reply(id,failure.getMessage()));
                responder.failure(listener.requestId(),failure.getMessage());
            }
            return true;
        }
        var persistent=held==null?null:session.persistentItemSpec(held.config());
        if(persistent!=null&&Set.of("heal","lethal","spawn-actor","lift").contains(persistent.text("kind",""))){
            try{
                var current=session.loadOwnProfile();
                if(session.tradeHandler().active())throw new IOException("Finish the trade first");
                var town=store.services().town(session.sceneRegistry().town());
                int knight=session.selectedKnight().id();
                var outcome=session.useOwnedItem(id,persistent,
                    ()->town.useItem(store.accountId(),knight,session.authRevision(),current.revision(),id));
                receipts.put(world,receiptId,new Reply(id,null));
                if(outcome.consumed())session.townChanged(outcome.payment(),false);
                responder.confirmed(listener.requestId());
            }catch(CharacterStore.Refused|IOException failure){
                receipts.putIfAbsent(world,receiptId,new Reply(id,failure.getMessage()));
                responder.failure(listener.requestId(),failure.getMessage());
            }
            return true;
        }
        var spec=session.temporaryItemSpec(id);
        if(spec!=null&&session.useTemporaryItem(id,spec))responder.confirmed(listener.requestId());
        else responder.failure(listener.requestId(),"That item cannot be used now");
        return true;
    }}
}
