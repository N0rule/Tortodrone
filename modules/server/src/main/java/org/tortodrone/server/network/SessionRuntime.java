package org.tortodrone.server.network;

import org.tortodrone.core.world.ScenePortals;
import org.tortodrone.world.SceneRegistry;

/** Scene identity and travel state for one connection: the current instance, Ready Room
 *  staging, the one in-flight portal journey, the arrival entrance and the departure
 *  generation. The session owns behaviour; this holds only the state those transitions
 *  replace atomically under the session monitor. */
final class SessionRuntime {
    record PortalMove(SceneRegistry.Instance destination,ScenePortals.Entrance entrance) { }

    private SceneRegistry.Instance scene;
    private boolean readyRoom,returnRequested;
    private PortalMove portalMove;
    private ScenePortals.Entrance arrival;
    private int departedSceneOid;

    SceneRegistry.Instance scene(){return scene;}
    void scene(SceneRegistry.Instance value){scene=value;}

    boolean readyRoom(){return readyRoom;}
    void readyRoom(boolean value){readyRoom=value;}

    boolean returnRequested(){return returnRequested;}
    void returnRequested(boolean value){returnRequested=value;}

    PortalMove portalMove(){return portalMove;}
    void beginPortal(SceneRegistry.Instance destination,ScenePortals.Entrance entrance){portalMove=new PortalMove(destination,entrance);}
    void clearPortal(){portalMove=null;}

    ScenePortals.Entrance arrival(){return arrival;}
    void arrival(ScenePortals.Entrance value){arrival=value;}

    int departedSceneOid(){return departedSceneOid;}
    void departedSceneOid(int value){departedSceneOid=value;}
}
