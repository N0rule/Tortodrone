package org.tortodrone.server.network;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.tortodrone.core.character.OpeningMission;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.core.world.SceneClock;
import org.tortodrone.core.world.ReadyRoom;
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

    /** The native Ready Room entry: the scene becomes client-local and the saved town is
     *  retained only as a durable return destination. */
    void enterReadyRoom(short requestId)throws IOException { synchronized(session){
        if(session.currentProfile().progress().crashSiteIncomplete())throw new IOException("Finish the tutorial through its exit first");
        if(portalMove!=null||returnRequested)throw new IOException("Finish the current journey first");
        if(!readyRoom){
            var returnScene=scene;
            if(!scene.map().town()){
                int saved=session.charactersStore().lastTownScene(session.selectedKnight().id(),session.authRevision());
                int permanent=session.sceneRegistry().catalog().towns().stream().filter(map->map.id()==saved)
                    .mapToInt(org.tortodrone.core.world.MapScene::id).findFirst().orElse(session.sceneRegistry().catalog().entry().id());
                returnScene=session.sceneRegistry().destination(permanent,session.currentProfile().worldId());
            }
            var before=session.visibleProfile();
            session.closeConversation();session.closeTownDialog();session.leaveChat();subscribed=false;entered=false;sceneGeneration++;
            departedSceneOid=scene.oid();membership.close();membership=null;
            session.sceneRegistry().release(scene,session.currentProfile().worldId());
            // Private instances are not durable return destinations.
            scene=returnScene;
            session.floorsHandler().clearReport();
            session.temporaryItems().clear();lastSnapshot=null;session.resetReferences();arrival=null;
            readyRoom=true;session.refreshParty(true);
            var after=session.visibleProfile();
            if(!before.equals(after))session.output().send(()->session.codec().itemChanges(session.connectionOid(),session.selectedKnight(),before,after));
        }
        session.friendLocation(new WorldSession.FriendPresence("~",ReadyRoom.ZONE,0));
        session.output().send(()->session.codec().readyRoomLocation(session.connectionOid()));
        session.output().send(()->session.codec().readyRoomMove(session.connectionOid(),requestId));
    }}

    void completeMove(short requestId,boolean zone)throws IOException {
        session.output().send(()->session.codec().sceneMove(session.connectionOid(),requestId,zone,scene,session.sceneRegistry().catalog()));
        session.output().send(()->session.codec().sceneLocation(session.connectionOid(),scene,
            session.sceneRegistry().channelOid(scene.map().zone(),false),session.sceneRegistry().channelOid(scene.map().zone(),true)));
        // Deliver the pre-arranged arrival comlink behind the moveSucceeded response: the
        // client processes it in the window where its scene controllers are gone (see the
        // arrival openComlink), so the dialog waits for the destination view like native.
        // A failed delivery falls back to the enteredPlace/publish path.
        if(session.arrivalComlink()!=null){
            var pending=session.arrivalComlink();session.setArrivalComlink(null);
            try{session.conversations().openArrivalComlink(pending);session.setDeliveredComlink(pending);}
            catch(IOException unavailable){session.setArrivalComlink(pending);}
        }
    }

    /** Install the destination scene before the client acknowledges its move. The Ready Room
     *  uses the same path so a native {@code /training}, {@code /alc} or {@code /haven} command
     *  can leave it directly instead of first entering the saved town. */
    void enterScene(SceneRegistry.Instance next,ScenePortals.Entrance entrance)throws IOException {
        session.closeConversation();session.closeTownDialog();
        if(!session.temporaryItems().isEmpty()){
            var before=session.visibleProfile();session.temporaryItems().clear();
            session.output().send(()->session.codec().itemChanges(session.connectionOid(),session.selectedKnight(),before,session.currentProfile()));
            session.publishQuickbar();
        }
        session.leaveChat();
        subscribed=false;entered=false;sceneGeneration++;session.setOccupantClients(Map.of());session.setOccupantRoles(Map.of());lastSnapshot=null;
        session.resetReferences();session.destinationsHandler().clearAlchemyTarget();departedSceneOid=scene.oid();
        if(membership!=null)membership.close();
        if(scene!=next)session.sceneRegistry().release(scene,session.currentProfile().worldId());
        scene=next;
        readyRoom=false;returnRequested=false;
        arrival=entrance;portalMove=null;
        session.markSceneCreated();session.attach();
        session.monitorScene();
        // Arriving comlinks are delivered behind the move response (see completeMove),
        // before the client can answer enteredPlace from inside its loading screen.
        session.clearArrivalComlink();
        for(var candidate:List.of(OpeningMission.FIRST_CONTACT,OpeningMission.WELCOME_HAVEN))
            if(scene.map().id()==OpeningComlinks.scene(candidate)&&candidate!=session.deliveredComlink()
                    &&session.sceneRegistry().missions().eligible(candidate.id(),session.currentProfile().progress(),session.completedOpeningKeys()))
                session.setArrivalComlink(candidate);
    }

    /** Detach the client subscription while keeping the installed instance and membership. */
    void unsubscribe(){ synchronized(session){
        session.leaveChat();subscribed=false;entered=false;sceneGeneration++;
        if(membership!=null)membership.pause();
    }}
}
