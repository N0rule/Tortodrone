package org.tortodrone.server.network;

import java.io.IOException;
import java.util.List;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.core.world.ReadyRoom;
import org.tortodrone.core.world.ScenePortals;
import org.tortodrone.world.SceneRegistry;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.protocol.SessionDecoder;

/** Native scene and zone moves for one connection.
 *
 * <p>A move installs the destination before the client acknowledges it. Interior moves wait for
 * the party subscription so the stock dungeon HUD sees its first snapshot before the move
 * response; late input for the departed scene is dropped by the input handler.
 */
final class MoveHandler {
    private final WorldSession session;
    private final SessionResponder responder;
    private DeferredMove deferred;
    record DeferredMove(short requestId,boolean zone,int sceneOid){ }

    MoveHandler(WorldSession session) {
        this.session = session;
        this.responder = session.responder();
    }

    boolean deferredPending(){return deferred!=null;}
    void clearDeferred(){deferred=null;}
    DeferredMove takeDeferred(int sceneOid){
        if(deferred!=null&&deferred.sceneOid()==sceneOid){var move=deferred;deferred=null;return move;}
        return null;
    }

    /** SceneMarshaller/ZoomMarshaller method 1; true when the request was answered. */
    boolean invocation(List<Object> args,boolean zone)throws IOException { synchronized (session) {
        long transitionStarted=System.nanoTime();
        int count=zone?4:3;
        if(args.size()!=count||!(args.get(count-1) instanceof SessionDecoder.Listener listener)
                ||!listener.type().endsWith(zone?"$ZoneMoveMarshaller":"$SceneMoveMarshaller"))
            throw new SessionDecoder.Rejected("Invalid scene move listener");
        String failure=null;
        if(!session.selected())failure="Select a knight first";
        else if(deferred!=null)failure="A scene move is already pending";
        else if(!(args.get(0) instanceof Integer)||!(args.get(1) instanceof Integer))failure="Invalid scene move request";
        if(failure!=null){
            String reason=failure;
            responder.send(()->responder.codec().invocationFailure(responder.oid(),listener.requestId(),reason));
            return true;
        }
        try{
            try{session.charactersStore().worldProfile(session.selectedKnight().name(),session.authRevision(),session.currentProfile());}
            catch(CharacterStore.Refused refused){throw new IOException("Knight changed; reconnect",refused);}
            int target=zone?(Integer)args.get(1):(Integer)args.get(0);
            if(zone&&args.get(0).equals(ReadyRoom.ZONE)){
                if(target!=ReadyRoom.SCENE||!(args.get(2) instanceof Integer))throw new IOException("%chat:m.no_such_scene");
                session.enterReadyRoom(listener.requestId());
                return true;
            }
            var place=session.currentScene();
            if(session.inReadyRoom()&&!session.sceneTravelPending()
                    &&(!session.returnRequested()||target!=place.map().id()))
                throw new IOException("Return to game before entering a scene");
            var progress=session.currentProfile().progress();
            if(progress.openingStage()>=0&&progress.openingStage()<3&&!session.sceneTravelPending()
                    &&target!=place.map().id())
                throw new IOException("Continue the opening adventure through its mission or exit");
            if(place.tutorial()!=null&&target!=place.map().id()&&!session.sceneTravelPending())
                throw new IOException("Finish the tutorial through its exit first");
            var targetMap=session.sceneRegistry().catalog().scene(target);
            if(zone&&(!(args.get(2) instanceof Integer)||!args.get(0).equals(targetMap.zone())))
                throw new IOException("Scene does not belong to that zone");
            if(session.sceneTravelPending()&&target!=session.pendingPortalMap())
                throw new IOException("Finish the current portal journey first");
            var next=session.sceneTravelPending()?session.pendingPortalDestination():session.destination(target);
            if(session.sceneTravelPending()&&next!=session.pendingPortalDestination())
                throw new IOException("Finish the current portal journey first");
            if(!session.sceneRegistry().authorized(next,session.currentProfile().worldId(),session.partyMember()))
                throw new IOException("Party membership changed");
            if(next!=place||session.sceneTravelPending()||session.inReadyRoom())
                session.enterScene(next,session.pendingPortalEntrance());
            int party=session.partyOid();
            // The stock PartyDirector resubscribes on every partyOid event, even when its value
            // is unchanged. Existing HUD listeners retain the previous party object if a second
            // snapshot replaces it.
            session.refreshPartyForTravel();
            // The stock dungeon HUD creates its quickbar once, while the scene model is attached.
            // Let its party subscription complete first.
            // enterScene has installed the destination; a town destination completes immediately.
            if(session.currentScene().map().town()||session.subscribedPartyOid()==party)
                session.completeMove(listener.requestId(),zone);
            // The deferred completion is matched against the scene installed by enterScene.
            else deferred=new DeferredMove(listener.requestId(),zone,session.currentScene().oid());
            session.sessionLog().debug("Scene transition initialized; duration-ms="+((System.nanoTime()-transitionStarted)/1000000));
        }catch(IOException error){
            responder.send(()->responder.codec().invocationFailure(responder.oid(),listener.requestId(),error.getMessage()));
        }
        return true;
    }}
}
