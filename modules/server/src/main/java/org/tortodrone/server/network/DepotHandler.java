package org.tortodrone.server.network;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.core.town.DepotGate;
import org.tortodrone.core.town.TownCatalog;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.protocol.SessionDecoder;
import org.tortodrone.storage.DepotStore;

/** Native Supply Depot listing and purchase for one connection.
 *
 * <p>Quotes are held on this connection so a purchase can only settle a listing that this
 * client received; the store revalidates ownership, revision and price in one transaction.
 */
final class DepotHandler {
    private final WorldSession session;
    private final SessionResponder responder;
    private final CharacterStore store;
    private final Map<Integer,DepotStore.Quote> offers=new LinkedHashMap<>();

    DepotHandler(WorldSession session) {
        this.session = session;
        this.responder = session.responder();
        this.store = session.charactersStore();
    }

    boolean invocation(SessionDecoder.Invocation request)throws IOException { synchronized (session) {
        if(request.oid()!=SessionCodec.INVOCATION_OID||request.service()!=SessionCodec.MISSION_SERVICE
                ||!Set.of(4,5,6,10).contains(request.method()))return false;
        var a=request.arguments();int method=request.method();boolean purchase=method==10;
        if(a.isEmpty()||!(a.getLast() instanceof SessionDecoder.Listener listener)
                ||!listener.type().equals(org.tortodrone.core.WireTypes.LISTENER_PREFIX+(purchase?"ConfirmMarshaller":"ResultMarshaller")))
            throw new SessionDecoder.Rejected("Invalid depot listener");
        try{
            var map=session.currentScene().map();
            if(session.isClosed()||!session.selected()||!session.sceneEntered()||!session.sceneSubscribed()
                    ||!map.town()&&!map.kind().equals("training"))throw new IOException("Enter a town first");
            if(purchase){
                if(a.size()!=3||!(a.get(0) instanceof Integer id)||!(a.get(1) instanceof Integer price)||!offers.containsKey(id))
                    throw new IOException("e.good_unavailable");
                if(session.tradeHandler().active())throw new IOException("Finish the trade first");
                var knight=session.selectedKnight();
                session.townChanged(store.town(session.sceneRegistry().town())
                    .depot(store.accountId(),knight.id(),session.authRevision(),id,price,offers.get(id)));
                responder.confirmed(listener.requestId());
            }else{
                if(!((method==4||method==5)&&a.size()==1||method==6&&a.size()==2&&a.getFirst() instanceof List<?> names
                        &&names.size()<=128&&names.stream().allMatch(v->v instanceof String)))
                    throw new IOException("Unsupported Town depot operation");
                var listed=new LinkedHashMap<Integer,TownCatalog.DepotGood>();
                var rules=session.sceneRegistry().town();
                var snapshot=store.depot(rules).catalog();
                for(var offer:snapshot.offers()){
                    var good=offer.good();var def=store.catalog().find(good.item());
                    if(!offer.enabled()||def==null||!def.grantable()||!DepotGate.unlocked(session.currentProfile().progress(),def)
                            ||method==5&&!offer.featured()&&!offer.sale()&&offer.promotionId()==0
                            ||method==6&&!((List<?>)a.getFirst()).contains(good.item()))continue;
                    int price=good.price()<0?rules.price(good.item(),def.rarity(),true):good.price();
                    listed.put(offer.id(),TownCatalog.DepotGood.timed(
                        new TownCatalog.Good(good.item(),good.count(),price,good.energy(),good.limit(),good.token(),good.tokenCount()),
                        offer.featured(),offer.originalPrice(),offer.sale(),offer.promotionEnds(),System.currentTimeMillis()));
                    offers.put(offer.id(),new DepotStore.Quote(snapshot.revision(),offer));
                }
                responder.send(()->responder.codec().townResult(responder.oid(),listener.requestId(),
                    new TownCatalog.DepotOffers(listed),session.currentProfile()));
            }
        }catch(IOException failure){responder.failure(listener.requestId(),failure.getMessage());}
        return true;
    }}
}
