package org.tortodrone.server.network;

import java.io.IOException;
import java.util.List;
import org.tortodrone.core.character.WorldProfile;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.protocol.SessionDecoder;

/** Equipment and quickbar moves for one connection.
 *
 * <p>Method 9 is the native moveItem and method 3 is the Arsenal equip action; both end in
 * the same confirm/failure responder. Quickbar slot changes touch only the scene's carried
 * inventory and republish it.
 */
final class EquipHandler {
    private final WorldSession session;
    private final SessionResponder responder;

    EquipHandler(WorldSession session) {
        this.session = session;
        this.responder = session.responder();
    }

    boolean invocation(SessionDecoder.Invocation request,List<Object> args)throws IOException { synchronized (session) {
        if(request.service()!=SessionCodec.ITEM_SERVICE)return false;
        if(request.method()==9)return equip(args);
        if(request.method()==3){
            if(args.size()!=3||!(args.get(0) instanceof Long)
                    ||!(args.get(1) instanceof Integer)||!(args.get(2) instanceof SessionDecoder.Listener))
                throw new SessionDecoder.Rejected("Invalid equip item request");
            return equip(List.of(args.get(0),new org.tortodrone.core.wire.EnumValue(
                org.tortodrone.core.WireTypes.ITEM_LOCATION,1),args.get(1),args.get(2)));
        }
        return false;
    }}

    private boolean equip(List<Object> args)throws IOException {
        if (args.size() != 4 || !(args.get(0) instanceof Long item)
                || !(args.get(1) instanceof org.tortodrone.core.wire.EnumValue location)
                || !location.type().equals(org.tortodrone.core.WireTypes.ITEM_LOCATION)
                || !(args.get(2) instanceof Integer slot)
                || !(args.get(3) instanceof SessionDecoder.Listener listener)
                || !listener.type().endsWith("$ConfirmMarshaller")) {
            throw new SessionDecoder.Rejected("Invalid equipment request");
        }
        try {
            if (!session.selectedKnightSelected()) throw new IOException("Select a knight first");
            if(location.ordinal()==2){
                if(slot<0||slot>=session.quickbarLength()||item!=0&&!session.carriesConsumable(item))
                    throw new IOException("That item or quickbar slot is not available");
                session.clearQuickbarItem(item);
                session.quickbarSlot(slot,item);session.publishQuickbar();responder.confirmed(listener.requestId());return true;
            }
            if (location.ordinal() != 0 && location.ordinal() != 1) {
                throw new IOException("Only inventory and equipment moves are available");
            }
            // The stock ItemManager gates equipping on the party alone (ensureCanEquip passes
            // whenever the knight has no party), and the client-local Ready Room stages with no
            // party, so gear may be changed while staging; live scenes keep their station rules.
            if(!session.inReadyRoom()&&(!session.sceneSubscribed()||!session.sceneEntered()||!session.equipmentAllowed()))
                throw new IOException("Equipment is locked outside an Arsenal Station in this level");
            var before=session.visibleProfileNow();
            var knight=session.selectedKnight();
            session.applyProfile(session.charactersStore().equip(knight.id(),session.authRevision(),item,location.ordinal()==0?null:slot));
            session.updateAppearance();
            var after=session.visibleProfileNow();responder.send(()->responder.codec().itemChanges(responder.oid(),knight,before,after));
            responder.confirmed(listener.requestId());
        } catch (IOException failure) {
            responder.failure(listener.requestId(),failure.getMessage());
        }
        return true;
    }
}
