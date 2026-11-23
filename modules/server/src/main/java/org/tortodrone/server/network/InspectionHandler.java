package org.tortodrone.server.network;

import java.io.IOException;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.core.character.Knight;
import org.tortodrone.core.character.WorldProfile;
import org.tortodrone.protocol.SessionDecoder;
import org.tortodrone.world.SceneRegistry;

/** Cross-session knight inspection for one connection.
 *
 * <p>The sender monitor is released while the peer snapshot is taken, then reacquired to
 * answer, so two sessions can inspect each other without holding both monitors. The
 * responder is the only write path.
 */
final class InspectionHandler {
    private final WorldSession session;
    private final SessionResponder responder;
    private final CharacterStore store;

    InspectionHandler(WorldSession session) {
        this.session = session;
        this.responder = session.responder();
        this.store = session.charactersStore();
    }

    record Inspection(Knight knight,WorldProfile profile,boolean hidden) { }

    /** Capture one target at a time, with no sender monitor held while inspecting a peer. */
    void inspect(SessionDecoder.Invocation request)throws IOException {
        var args=request.arguments();
        if(request.service()!=6||request.method()!=2||args.size()!=2||!(args.get(0) instanceof String target)
                ||!(args.get(1) instanceof SessionDecoder.Listener listener)
                ||!listener.type().equals(org.tortodrone.core.WireTypes.RESULT_LISTENER))
            throw new SessionDecoder.Rejected("Invalid inspection request");
        SceneRegistry.Instance place;
        synchronized(session) {
            if(session.isClosed()||!session.responder().open()||!session.sceneSubscribed()||!session.sceneEntered()||!session.ownsScene(request.oid()))
                throw new SessionDecoder.Rejected("Unauthorized inspection");
            try{store.worldProfile(session.selectedKnight().name(),session.authRevision());}catch(CharacterStore.Refused refused){throw new IOException("Knight changed; reconnect",refused);}
            place=session.currentScene();
        }
        WorldSession peer;
        synchronized(SessionDirectory.ACTIVE) {peer=SessionDirectory.ACTIVE.values().stream().filter(w->w.selectedKnightName().equalsIgnoreCase(target)
            &&w.charactersStore().worldStorageFile().equals(store.worldStorageFile())).findFirst().orElse(null);}
        Inspection found=peer==null?null:peer.inspectionNow(place);
        synchronized(session) {
            if(session.isClosed()||session.currentScene()!=place||!session.sceneSubscribed()||!session.sceneEntered())return;
            if(found==null)responder.failure(listener.requestId(),"That knight is not in this scene");
            else responder.send(()->responder.codec().inspected(responder.oid(),listener.requestId(),found.knight(),found.profile(),found.hidden()));
        }
    }

    Inspection snapshot(SceneRegistry.Instance place)throws IOException {
        synchronized(session) {
            if(session.isClosed()||!session.responder().open()||!session.sceneSubscribed()||!session.sceneEntered()||session.currentScene()!=place)return null;
            try {var current=store.worldProfile(session.selectedKnight().name(),session.authRevision());return new Inspection(session.selectedKnight(),current,session.inspectionHiddenValue());}
            catch(CharacterStore.Refused refused){return null;}
        }
    }
}
