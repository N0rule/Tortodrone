package org.tortodrone.server.network;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.character.WorldProfile;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.protocol.SessionDecoder;

/** Native box opening: spawn-table lookup, opener results and spawn-all for one connection.
 *
 * <p>Receipts are retained before publication so a queued-output failure can never cause a
 * second write; retries repeat the recorded outcome under the same listener id.
 */
final class BoxHandler {
    private final WorldSession session;
    private final SessionResponder responder;
    private final CharacterStore store;
    private final RequestReceipts<Reply> receipts=new RequestReceipts<>();

    private record Reply(long box,int option,Object rewards,String failure) { }

    BoxHandler(WorldSession session) {
        this.session = session;
        this.responder = session.responder();
        this.store = session.charactersStore();
    }

    boolean invocation(SessionDecoder.Invocation request)throws IOException { synchronized (session) {
        if(request.oid()!=SessionCodec.INVOCATION_OID||request.service()!=SessionCodec.ITEM_SERVICE||!Set.of(6,11,12).contains(request.method()))return false;
        var args=request.arguments();
        boolean normal=request.method()==12;
        if(args.size()!=(normal?3:2)||!(args.get(0) instanceof Long id)||normal&&!(args.get(1) instanceof Integer)
                ||!(args.getLast() instanceof SessionDecoder.Listener listener)
                ||!listener.type().equals(org.tortodrone.core.WireTypes.RESULT_LISTENER))
            throw new SessionDecoder.Rejected("Invalid box opening request");
        int option=normal?(Integer)args.get(1):-request.method();
        int world=-1;short receipt=0;boolean tracked=false;
        try {
            if(!session.inSubscribedScene())throw new IOException("m.internal_error");
            var knight=session.selectedKnight();
            var current=session.loadOwnProfile();
            if(session.tradeHandler().active())throw new IOException("Finish the trade first");
            if(request.method()==6){
                var table=store.services().boxes(session.sceneRegistry().boxes()).spawnTable(store.accountId(),knight.id(),session.authRevision(),id);
                var tableProfile=current;
                responder.send(()->responder.codec().townResult(responder.oid(),listener.requestId(),table,tableProfile));
                return true;
            }
            world=current.worldId();receipt=listener.requestId();tracked=true;
            var prior=receipts.get(world,receipt);
            if(prior!=null) {
                if(prior.box()!=id||prior.option()!=option||prior.failure()!=null)throw new IOException(prior.failure()==null?"m.internal_error":prior.failure());
                Object rewards=prior.rewards();var replayProfile=session.currentProfile();
                responder.send(()->responder.codec().townResult(responder.oid(),listener.requestId(),rewards,replayProfile));
                return true;
            }
            if(!session.canOpenBox())throw new IOException("Finish your current action before opening a box");
            var boxes=store.services().boxes(session.sceneRegistry().boxes());
            var result=normal?boxes.open(store.accountId(),knight.id(),session.authRevision(),current.revision(),id,option)
                :boxes.spawnAll(store.accountId(),knight.id(),session.authRevision(),current.revision(),id);
            // Retain the receipt before publication: a queued-output failure must never cause a second write.
            receipts.put(world,receipt,new Reply(id,option,normal?result.result():null,null));
            if(!normal){
                session.townChanged(result,false);
                responder.result(listener.requestId(),null,result.profile());
                return true;
            }
            var references=new ArrayList<StarterCatalog.Model>();
            references.add(new StarterCatalog.Model(current.items().stream().filter(i->i.id()==id).findFirst().orElseThrow().config(),Map.of()));
            var rewards=((org.tortodrone.core.wire.Multiset)result.result()).counts();
            result.profile().items().stream().filter(i->rewards.containsKey(i.id())).map(WorldProfile.Item::config).distinct()
                .forEach(config->references.add(new StarterCatalog.Model(config,Map.of())));
            session.openBox(List.copyOf(references));
            // Capture: suppress ordinary acquisition popups while consuming/granting, then
            // clear the delay before the native reward-window callback. Publish only after commit.
            responder.send(()->responder.codec().itemAcquireDelay(responder.oid(),7500));
            session.townChanged(result,false);
            responder.send(()->responder.codec().itemAcquireDelay(responder.oid(),0));
            responder.result(listener.requestId(),result.result(),result.profile());
        }catch(CharacterStore.Refused|IOException failure) {
            if(tracked&&!receipts.contains(world,receipt))receipts.put(world,receipt,new Reply(id,option,null,failure.getMessage()));
            responder.failure(listener.requestId(),failure.getMessage());
        }
        return true;
    }}
}
