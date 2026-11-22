package org.tortodrone.server.network;

import java.io.IOException;
import org.tortodrone.core.world.SceneClock;
import org.tortodrone.protocol.SessionDecoder;

/** Movement input for one connection's owned scene.
 *
 * <p>Client input is intent: frames are validated against the current subscription and handed
 * to the scene membership, which owns simulation state. Late frames for a scene that is
 * already departing are dropped until the destination is ready.
 */
final class SceneInputHandler {
    private final WorldSession session;

    SceneInputHandler(WorldSession session) {
        this.session = session;
    }

    void input(SessionDecoder.Input input)throws IOException { synchronized (session) {
        // Old frames can already be in flight when the server forces a portal move.
        // Ignore only the known departing scene until the destination becomes ready.
        if(!session.isClosed()&&!session.socket().isClosed()&&session.departedSceneOid()!=0&&input.oid()==session.departedSceneOid())return;
        if(session.isClosed()||session.socket().isClosed()||!session.sceneSubscribed()||!session.sceneEntered()
                ||input.oid()!=session.currentScene().oid())
            throw new SessionDecoder.Rejected("Unauthorized movement input");
        // TCP delivers every queued snapshot in order, even while scene loading delays input.
        // Client acknowledgements are for unreliable transport; they do not choose our baseline.
        long now=WorldSession.monotonicTime();
        int sceneTime=SceneClock.now();
        if(!session.sceneRegistry().authorized(session.currentScene(),session.currentProfile().worldId(),session.partyMember()))
            return; // Revoked memberships await their queued safe return.
        session.ping((short)Math.max(0,Math.min(Short.MAX_VALUE,(long)sceneTime-input.smoothedTime())));
        for(var frame:input.frames())
            if(session.membership().input(frame.timestamp(),frame.rotation(),frame.direction(),
                    frame.flags(),frame.actionChanged(),sceneTime,now))session.acknowledge(frame.timestamp());
    }}
}
