package org.tortodrone.server.network;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.core.character.OpeningMission;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.core.world.SceneClock;
import org.tortodrone.core.world.ReadyRoom;
import org.tortodrone.core.world.ScenePortals;
import org.tortodrone.world.OpeningComlinks;
import org.tortodrone.world.SceneMembership;
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
    private SceneMembership membership;
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

    SceneMembership membership(){return membership;}
    void membership(SceneMembership value){membership=value;}

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

    void select(String name,Map<String,Short> receivers)throws IOException,CharacterStore.Refused {
        select(name,receivers,false);
    }

    /** Incomplete Crash Site knights resume a private floor; completed knights stage locally. */
    void select(String name,Map<String,Short> receivers,boolean stageInReadyRoom)throws IOException,CharacterStore.Refused {synchronized(session){
        long selectionStarted=System.nanoTime();
        if(session.isClosed()||session.socket().isClosed())throw new IOException("Session is closed");
        if(session.selected())throw new IOException("A character is already selected; reconnect to change characters");
        var scenes=session.sceneRegistry();
        if(scenes.catalog().isEmpty())throw new IOException("Maps are unavailable; install default maps and restart the server");
        var characters=session.charactersStore();
        session.applySelectionFlags(session.gameMasterRole(),characters.inspectionHidden(session.authRevision()));
        // Picker edits/deletes use this same account lock around their online check and save.
        synchronized(characters){
            var loaded=characters.worldProfile(name,session.authRevision());
            for(var item:loaded.items())if(!item.starter()){
                var entry=characters.catalog().require(item.config());
                if(entry.slot()!=item.slot())throw new IOException("Saved item category differs from the deployed catalogue");
            }
            var choice=characters.roster().stream().filter(k->k.id()==loaded.knightId())
                .findFirst().orElseThrow(()->new IOException("No such knight"));
            int saved=characters.lastTownScene(choice.id(),session.authRevision());
            int target=scenes.catalog().towns().stream().filter(map->map.id()==saved)
                .mapToInt(org.tortodrone.core.world.MapScene::id).findFirst().orElse(scenes.catalog().entry().id());
            if(loaded.progress().crashSiteIncomplete()){
                if(scenes.tutorial()==null)throw new IOException("The opening adventure is unavailable; please try again later");
                target=scenes.tutorial().source().id();stageInReadyRoom=false;
            }else if(loaded.progress().openingStage()==1||loaded.progress().openingStage()==2){
                target=loaded.progress().openingStage()==1?217:218;
                if(!scenes.catalog().scene(target).town())throw new IOException("The opening camp is unavailable; please try again later");
            }
            synchronized(WorldSession.ACTIVE){
                if(WorldSession.ACTIVE.containsKey(loaded.worldId()))throw new IOException("This knight is already online");
                session.applyProfile(loaded);
                session.setSelectedKnight(choice);
                scene=scenes.destination(target,loaded.worldId());
                WorldSession.ACTIVE.put(loaded.worldId(),session);
            }
        }
        session.markSceneCreated();
        if(characters.hasWorldStorage()){
            var house=characters.services().auction(scenes.town());
            boolean delivered=house.deliverCampaigns(session.currentProfile().worldId());
            if(house.deliverWelcome(session.currentProfile().worldId(),session.selectedKnight().createdAt()))delivered=true;
            if(delivered)session.applyProfile(characters.worldProfile(session.selectedKnight().name(),session.authRevision(),session.currentProfile()));
        }
        if(characters.hasWorldStorage()){characters.services().friends().session(characters.accountId(),session.selectedKnight().id(),session.authRevision());session.applyProfile(characters.worldProfile(session.selectedKnight().name(),session.authRevision(),session.currentProfile()));}
        session.guilds.reload();
        session.setFriendOnline(true);
        readyRoom=stageInReadyRoom;
        if(readyRoom)session.friendLocation(new WorldSession.FriendPresence("~",ReadyRoom.ZONE,0));
        else attach();
        session.setPartyMember(scenes.parties().join(session.connectionOid(),session.selectedKnight(),session.currentProfile(),session::offerParty));
        if(session.currentProfile().progress().crashSiteIncomplete())scenes.parties().enterTutorial(session.partyMember());
        session.publishPartyState();
        if(characters.hasWorldStorage())session.setMemories(characters.knightMemories(session.selectedKnight().id(),session.authRevision()));
        if(characters.hasWorldStorage())session.setObjectives(characters.knightObjectives(session.selectedKnight().id(),session.authRevision()));
        if(characters.hasWorldStorage())session.setCompletedOpening(characters.services().openingMissions(scenes.town(),scenes.missions()).load(characters.accountId(),session.selectedKnight().id(),session.authRevision()));
        session.output().send(()->session.codec().selected(session.connectionOid(),session.selectedKnight(),session.currentProfile(),session.guilds.guildInfo()));
        session.output().send(()->session.codec().knightMemories(session.connectionOid(),session.memoriesValue()));
        session.publishObjectives();
        session.output().send(()->session.codec().completedMissions(session.connectionOid(),session.completedOpeningValues()));
        session.output().send(()->session.codec().townState(session.connectionOid(),session.currentProfile()));
        session.publishStyles();
        session.output().send(()->session.codec().playerFlags(session.connectionOid(),session.gameMasterFlag(),session.inspectionHiddenValue(),session.currentProfile().progress().tutorialComplete()));
        if(characters.hasWorldStorage())session.refreshFriends(null,characters.services().friends().snapshot(),WorldSession.friendPresence(characters.worldStorageFile()));
        session.refreshParty();
        session.receiverChanged(receivers);
        session.sessionLog().selected(session.selectedKnight().id(),session.currentProfile().worldId(),session.selectedKnight().name());
        session.sessionLog().info("Character selected; knight="+session.selectedKnight().name()+" (#"+session.currentProfile().worldId()+"); rosterId="+session.selectedKnight().id());
        if(!readyRoom)monitorScene();
        // Party events belong to the selected session, not its scene subscription.
        // Ready Room is client-local and never calls entered(); delaying this worker
        // until then strands invitations and can overflow the queue on world return.
        session.startDelivery();
        session.sessionLog().debug("Knight/scene initialization completed; duration-ms="+((System.nanoTime()-selectionStarted)/1000000));
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
        session.markSceneCreated();attach();
        monitorScene();
        // Arriving comlinks are delivered behind the move response (see completeMove),
        // before the client can answer enteredPlace from inside its loading screen.
        session.clearArrivalComlink();
        for(var candidate:List.of(OpeningMission.FIRST_CONTACT,OpeningMission.WELCOME_HAVEN))
            if(scene.map().id()==OpeningComlinks.scene(candidate)&&candidate!=session.deliveredComlink()
                    &&session.sceneRegistry().missions().eligible(candidate.id(),session.currentProfile().progress(),session.completedOpeningKeys()))
                session.setArrivalComlink(candidate);
    }

    /** A scene tick failed; return this knight to character selection rather than a frozen world. */
    void sceneFailed(){
        if(session.isClosed())return;
        session.sessionLog().warn("Scene tick failed; closing session for a safe return; scene="+(scene==null?0:scene.oid()));
        closeSession();
        try{session.socket().close();}catch(IOException ignored){}
    }

    /** Join the installed instance's simulation and publish the knight actor. */
    void attach(){
        var scenes=session.sceneRegistry();
        session.floorsHandler().reset();
        if(!scene.map().kind().equals("training")&&session.partyMember()!=null)
            try{scenes.parties().leaveTraining(session.partyMember());}catch(IOException invalid){throw new IllegalStateException(invalid);}
        session.setPublishedArsenal(null);
        membership=null; // The replacement is built after the previous membership is released.
        session.appearance.rebuild();
        var place=scene;
        membership=place.simulation().join(session.appearance.actor(),snapshot->{},this::sceneFailed);
        membership.equipment(session.visibleProfile());
        if(!place.map().town())membership.combat(scenes.weapons(),scenes.equipment(),session.visibleProfile());
        try{session.publishPartyState();}catch(IOException invalid){throw new IllegalStateException(invalid);}
    }

    void monitorScene(){
        session.sessionLog().scene(scene.map().id(),scene.oid(),scene.map().name());
        session.sessionLog().info("Scene entered; scene="+scene.map().name()+" (#"+scene.map().id()+"); instanceId="+scene.oid());
    }

    /** Release every party, scene and delivery resource this connection owns. */
    void closeSession(){ synchronized(session){
        session.setFriendOnline(false);
        session.chatHandler().leave();
        session.leaveChat();
        session.markClosed();
        session.floorsHandler().clearReport();
        subscribed=false;
        if(membership!=null)membership.close();
        var member=session.partyMember();
        if(member!=null){member.close();session.setPartyMember(null);}
        var profile=session.currentProfile();
        if(profile!=null){
            session.sceneRegistry().release(scene,profile.worldId());
            if(portalMove!=null)session.sceneRegistry().release(portalMove.destination(),profile.worldId());
        }
        session.stopPublications();
        if(profile!=null)synchronized(WorldSession.ACTIVE){WorldSession.ACTIVE.remove(profile.worldId(),session);}
    }}

    /** Detach the client subscription while keeping the installed instance and membership. */
    void unsubscribe(){ synchronized(session){
        session.leaveChat();subscribed=false;entered=false;sceneGeneration++;
        if(membership!=null)membership.pause();
    }}
}
