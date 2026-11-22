package org.tortodrone.server.network;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.tortodrone.core.character.OpeningMission;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.core.world.SceneClock;
import org.tortodrone.core.world.ScenePortals;
import org.tortodrone.world.OpeningComlinks;
import org.tortodrone.world.SceneRegistry;
import org.tortodrone.world.SceneSimulation;
import org.tortodrone.world.SceneSnapshot;

/** Scene identity, subscription and travel state for one connection: the current instance,
 *  Ready Room staging, the one in-flight portal journey, the arrival entrance, the departure
 *  generation and the subscribe/enter flags. The session keeps character-facing behaviour and
 *  delegates the scene-entry transitions here; every transition runs under the session lock. */
final class SessionRuntime {
    record PortalMove(SceneRegistry.Instance destination,ScenePortals.Entrance entrance) { }

    private final WorldSession session;
    private SceneRegistry.Instance scene;
    private SceneSimulation.Membership membership;
    private SceneSnapshot lastSnapshot;
    private WorldStatePoller.Registration persistence;
    private boolean readyRoom,returnRequested,subscribed,entered,welcomed,inputThrottleSent;
    private int sceneGeneration;
    private PortalMove portalMove;
    private ScenePortals.Entrance arrival;
    private int departedSceneOid;

    SessionRuntime(WorldSession session) {
        this.session = session;
    }

    SceneRegistry.Instance scene(){return scene;}
    void scene(SceneRegistry.Instance value){scene=value;}

    SceneSimulation.Membership membership(){return membership;}
    void membership(SceneSimulation.Membership value){membership=value;}

    boolean subscribed(){return subscribed;}
    void subscribed(boolean value){subscribed=value;}

    boolean entered(){return entered;}
    void entered(boolean value){entered=value;}

    int sceneGeneration(){return sceneGeneration;}
    void bumpSceneGeneration(){sceneGeneration++;}

    SceneSnapshot lastSnapshot(){return lastSnapshot;}
    void lastSnapshot(SceneSnapshot value){lastSnapshot=value;}

    WorldStatePoller.Registration persistence(){return persistence;}
    void persistence(WorldStatePoller.Registration value){persistence=value;}

    boolean welcomed(){return welcomed;}
    void welcomed(boolean value){welcomed=value;}

    boolean inputThrottleSent(){return inputThrottleSent;}
    void inputThrottleSent(boolean value){inputThrottleSent=value;}

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

    /** Bind the connection's subscription to the installed instance and reset the scene clock. */
    void subscribe(short message)throws IOException { synchronized(session){
        if(!session.selected())throw new IOException("Select a knight before subscribing to a scene");
        if(readyRoom)throw new IOException("The Ready Room has no server place object");
        if(session.isClosed())throw new IOException("Session closed");
        if(portalMove!=null)throw new IOException("Scene is departing through a portal");
        var snapshot=scene.simulation().snapshot();
        if(snapshot.players().stream().filter(actor->actor.clientOid()==session.connectionOid()&&actor.id()==session.currentProfile().worldId()).count()!=1)
            throw new IOException("Scene has no unique owned pawn");
        if(!inputThrottleSent){
            // Town movement and service calls share the native TCP throttle queue.
            session.output().send(session.codec()::inputThrottle);inputThrottleSent=true;
        }
        session.output().send(()->session.codec().scene(message,scene,snapshot.players()));
        session.leaveChat();lastSnapshot=null;session.resetReferences();
        session.setOccupantClients(snapshot.players().stream().collect(java.util.stream.Collectors.toMap(ActorState::id,ActorState::clientOid)));
        session.setOccupantRoles(snapshot.roles());subscribed=true;entered=false;sceneGeneration++;
        membership.pause();
    }}

    /** The stock controller sends enteredPlace after attaching its scene-delta listener. */
    void enterPlace()throws IOException { synchronized(session){
        if(portalMove!=null)return; // A source readiness RPC may already be queued before forceScene.
        if(!subscribed||session.isClosed())throw new IOException("Subscribe to town before entering it");
        if(entered)return;
        if(scene.map().town()){
            session.charactersStore().lastTownScene(session.selectedKnight().id(),session.authRevision(),scene.map().id());
            if(session.currentProfile().progress().openingStage()>=0)session.output().send(()->session.codec().homeTown(session.connectionOid(),session.currentProfile()));
        }
        entered=true;
        session.friendLocation(session.presence(session.sceneRegistry().parties().view(session.partyMember())));
        departedSceneOid=0;
        int generation=sceneGeneration,place=scene.oid();
        session.chatHandler().enter(place,generation);
        if(!welcomed&&!session.welcomeMessage().isEmpty()){
            welcomed=true;
            session.output().send(()->session.codec().systemNotice(place,session.welcomeMessage()));
        }
        session.tradeHandler().join(place);
        session.markSceneEntry();
        var placeInstance=scene;
        membership.enter(snapshot->session.offer(placeInstance,generation,snapshot));
        if(scene.run()!=null&&(!session.sceneRegistry().authorized(scene,session.currentProfile().worldId(),session.partyMember())
                ||!membership.authorizeElevator(scene.run().exitKey(),Set.of(session.currentProfile().worldId()))))
            throw new IOException("Opening mission exit is unavailable");
        session.clearArrivalComlink();
        for(var candidate:List.of(OpeningMission.FIRST_CONTACT,OpeningMission.WELCOME_HAVEN))
            if(scene.map().id()==OpeningComlinks.scene(candidate)&&candidate!=session.deliveredComlink()
                    &&session.sceneRegistry().missions().eligible(candidate.id(),session.currentProfile().progress(),session.completedOpeningKeys()))
                session.setArrivalComlink(candidate);
        if(session.destinationsHandler().takeAuctionAfterArrival()){try{session.openAuction();}catch(IOException refused){session.unavailable(refused.getMessage());}}
        persistence=WorldStatePoller.join(session,session.charactersStore(),session.currentProfile(),session.authRevision(),session.selectedKnight().name());
        session.syncMissionObjective();
        session.flushPendingTravel();
    }}

    /** Detach the client subscription while keeping the installed instance and membership. */
    void unsubscribe(){ synchronized(session){
        session.leaveChat();subscribed=false;entered=false;sceneGeneration++;
        if(membership!=null)membership.pause();
    }}
}
