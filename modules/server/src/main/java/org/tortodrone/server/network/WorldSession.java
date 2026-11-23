package org.tortodrone.server.network;
import org.tortodrone.character.*;
import org.tortodrone.world.*;
import org.tortodrone.core.world.SceneClock;

import java.io.IOException;
import java.net.Socket;
import java.util.*;
import org.tortodrone.protocol.*;
import org.tortodrone.storage.Database;
import org.tortodrone.core.WireTypes;
import org.tortodrone.core.character.Knight;
import org.tortodrone.core.character.WorldProfile;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.character.SlotUpgrades;
import org.tortodrone.core.character.KnightObjective;
import org.tortodrone.core.character.OpeningCompletion;
import org.tortodrone.core.character.OpeningMission;
import org.tortodrone.core.character.KnightMemory;
import org.tortodrone.core.character.AnimatedItems;
import org.tortodrone.core.character.Confetti;
import org.tortodrone.core.mission.MissionRun;
import org.tortodrone.core.world.SceneConversation;
import org.tortodrone.core.world.SceneActions;
import org.tortodrone.core.town.TownState;
import org.tortodrone.core.town.ExchangeState;
import org.tortodrone.core.town.DepotGate;
import org.tortodrone.core.town.TownCatalog;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.core.world.PlayerEmotes;
import org.tortodrone.core.world.SceneChat;
import org.tortodrone.core.world.SceneHealth;
import org.tortodrone.core.world.SceneStatus;
import org.tortodrone.core.world.ReadyRoom;
import org.tortodrone.core.world.SceneElevator;
import org.tortodrone.core.world.ScenePortals;
import org.tortodrone.core.world.WeaponCatalog;
import org.tortodrone.core.mission.OpeningGate;
import org.tortodrone.core.party.PartyView;

/** Selected character protocol/controller; authoritative entities live in their scene instance. */
final class WorldSession implements AutoCloseable {
    private final ClientDiagnostics diagnostics;
    void hardware(List<Object> args)throws IOException { diagnostics.hardware(args); }
    final SessionPresence presenceState=new SessionPresence();
    /** Per-knight-session join secret published through PlayerObject.ML(); never persisted. */
    private static final java.security.SecureRandom SECRETS=new java.security.SecureRandom();
    private final long externalSecret=mintSecret();
    private static long mintSecret(){long value;do{value=SECRETS.nextLong();}while(value==0);return value;}
    final TradeHandler trade;
    private final Socket socket;
    private final CharacterStore characters;
    private final int oid;
    private final SessionRuntime runtime;
    private final long authRevision;
    private final SessionLog log;
    private final SceneRegistry scenes;
    private final WeaponCatalog weapons;
    private final String welcomeMessage;
    @FunctionalInterface interface RoleSource { boolean gameMaster()throws IOException; }
    private final RoleSource roles;
    private final SessionPublications publications=new SessionPublications();
    final SessionAppearance appearance;
    private Knight knight;
    private WorldProfile profile;
    private boolean closed;
    private final SessionRefresh refreshState;
    private final SessionProfilePublisher profilePublisher;
    /** Stock town commands may arrive from the loading screen or Ready Room; replay the
     *  native destination request once the scene has subscribed and accepted enteredPlace. */
    private PartyRegistry.Member partyMember;
    final SessionResponder responder;
    final GuildHandler guilds;
    private final SteamHandler steam;
    private final BillingHandler billing;
    private final FriendHandler friends;
    private final AuctionHandler auction;
    private final StyleHandler styles;
    private final BoxHandler boxes;
    private final ItemUseHandler itemUse;
    private final AdminHandler adminTools;
    private final DepotHandler depot;
    private final ConversationHandler conversations;
    private final OpeningHandler opening;
    private final TownHandler town;
    private final ChatHandler chats;
    private final PartyHandler parties;
    private final LoadoutHandler loadouts;
    private final InspectionHandler inspection;
    private final NewsHandler news;
    private final EquipHandler equipment;
    private final SceneInputHandler sceneInput;
    private final FloorHandler floors;
    private final DestinationHandler destinations;
    private final MoveHandler moves;
    private final KnightCommandHandler knightCommands;
    private final ScenePublisher publisher;
    private final WorldStateHandler worldState;
    int partyOid()throws IOException{return parties.oid();}
    SceneRegistry.Instance destination(int template)throws IOException{return scenes.destination(template,profile.worldId(),partyMember);}
    void publishPartyState()throws IOException{parties.state();}
    int announcedPartyOid(){return parties.announcedOid();}
    PartyView.View publishedParty(){return parties.published();}
    Thread deliveryThread(){return publications.worker();}
    private void partyState()throws IOException{parties.state();}
    void offerParty(PartyView.Event event){parties.offer(event);}
    void offerPartyEvent(PartyView.Event event){publications.offerParty(event);}
    void deliverParty(PartyView.Event event)throws IOException{parties.deliver(event);}
    void refreshParty()throws IOException{parties.refresh();}
    void refreshParty(boolean traveling)throws IOException{parties.refresh(traveling);}
    /** The native friend list's Join affordance is driven by this presence. */
    SessionPresence.FriendPresence presence(PartyView.View view)throws IOException {
        if(runtime.scene().map().town())return new SessionPresence.FriendPresence(runtime.scene().map().name(),runtime.scene().map().zone(),runtime.scene().oid());
        // Only Training rooms are party-shared; Laboratory and Hall of Heroes stay per-world.
        boolean shared=runtime.scene().map().kind().equals("training")&&scenes.parties().owner(partyMember,profile.worldId())!=0;
        boolean joinable=shared&&(view.automatic()||!view.inviteOnly()||view.allowFriends());
        return new SessionPresence.FriendPresence(runtime.scene().map().name(),runtime.scene().map().zone(),joinable?runtime.scene().oid():0);
    }
    void partyPresence(PartyView.View view)throws IOException{presenceState.location(presence(view));}
    void forcePartyExit()throws IOException { parties.forceExit(); }
    /** Verified stock SteamMarshaller: 2[int] and 5[boolean] are unacknowledged client reports;
     * purchase, profile and friend-invite requests carry listeners and receive native failure. */
    boolean steamInvocation(SessionDecoder.Invocation request)throws IOException{return steam.invocation(request);}
    /** The active mission whose {@code o.<missionId>} objective is on the player's bar, or null. */
    final SessionCarriedItems carried;
    final SessionSceneState sceneState;
    final SessionStory story;
    final SessionFlags flags;
    void publishQuickbar()throws IOException { carried.publish(); }
    void publishObjectives()throws IOException { story.publishObjectives(); }
    WorldProfile visibleProfile(){
        return carried.visibleProfile(profile,runtime.membership()==null?Set.of():runtime.membership().status().cursedItems());
    }
    WorldProfile visibleProfile(Set<Long> cursed){return carried.visibleProfile(profile,cursed);}
    void closeTownDialog()throws IOException { town.close(); }
    long townDialogCreated(){return town.created();}
    boolean townDialogOpen(){return town.dialogOpen();}
    void openTownDialog(org.tortodrone.core.town.TownCatalog.Station station,ActorState source)throws IOException { town.open(station,source); }
    void townChanged(org.tortodrone.storage.TownStore.Result result)throws IOException {
        profilePublisher.changed(result,true);
    }
    void townChanged(org.tortodrone.storage.TownStore.Result result,boolean publishTown)throws IOException {
        profilePublisher.changed(result,publishTown);
    }
    boolean ownsGuild(int object)throws IOException{return guilds.ownsGuild(object);}
    void subscribeGuild(short message)throws IOException{guilds.subscribeGuild(message);}
    void unsubscribeGuild(int object){guilds.unsubscribeGuild(object);}
    boolean guildInvocation(SessionDecoder.Invocation request)throws IOException{return guilds.guildInvocation(request);}

    final ExchangeHandler exchangeHandler;
    boolean wantsExchange(){return exchangeHandler.wants();}
    void subscribeExchange(short message)throws IOException{exchangeHandler.subscribe(message);}
    void unsubscribeExchange(){exchangeHandler.unsubscribe();}
    void refreshExchange(org.tortodrone.core.town.ExchangeState.Book book)throws IOException{exchangeHandler.refresh(book);}
    boolean exchangeInvocation(SessionDecoder.Invocation request)throws IOException{return exchangeHandler.invocation(request);}
    private boolean depotInvocation(SessionDecoder.Invocation request)throws IOException{
        if(request.oid()!=SessionCodec.INVOCATION_OID||request.service()!=SessionCodec.MISSION_SERVICE)return false;
        if(!Set.of(4,5,6,10).contains(request.method()))return opening.invocation(request);
        return depot.invocation(request);
    }
    void loadOpeningCompletions()throws IOException{story.load();}
    Set<String> completedOpeningKeys(){return story.completedKeys();}
    boolean completedOpeningEmpty(){return story.completedEmpty();}
    void clearArrivalComlink(){story.arrivalComlink(null);}
    void leaveChatForTown(){leaveChat();}
    void requestRenameDisconnect(){renameDisconnect=true;}
    void recipeLearnedEffect(){if(runtime.membership()!=null)runtime.membership().effect(new StarterCatalog.Model("Player/Learn Recipe",Map.of()));}
    /** Remove one consumable/session pickup from the visible inventory without a store write. */
    boolean trashTemporary(long id)throws IOException { return carried.trash(id); }
    SceneConversation comlink(String missionId){return story.comlink(missionId);}
    void reloadProfile()throws IOException{profile=loadProfile(profile);}
    boolean openingEligible(String missionId){return scenes.missions().eligible(missionId,profile.progress(),story.completedKeys());}
    boolean auctionInvocation(SessionDecoder.Invocation request)throws IOException{return auction.invocation(request);}

    boolean friendInvocation(SessionDecoder.Invocation request)throws IOException{return friends.invocation(request);}
    boolean feedInvocation(SessionDecoder.Invocation request)throws IOException{return friends.feedInvocation(request);}
    boolean eventLogInvocation(SessionDecoder.Invocation request)throws IOException{return friends.eventLogInvocation(request);}
    /** Native method 4: publish the party-finder flag; matchmaking itself is not implemented. */
    void setLookingForGroup(boolean enabled,org.tortodrone.core.wire.EnumValue difficulty)throws IOException { friends.setLookingForGroup(enabled,difficulty); }
    long externalSecret(){return externalSecret;}
    boolean lookingForGroup(){return friends.lookingForGroup();}
    boolean friendOnlineValue(){return presenceState.online();}
    SessionPresence.FriendPresence friendPresenceValue(){return presenceState.location();}
    boolean hasWorldStorage(){return characters.hasWorldStorage();}
    boolean sameWorld(WorldSession peer){return peer!=null&&peer.scenes==scenes&&peer.characters.worldStorageFile().equals(characters.worldStorageFile());}
    /** Snapshot taken under the session monitor, never under ACTIVE (close locks in that order). */
    int identityWorld(String name){synchronized(this){return knight!=null&&profile!=null&&knight.name().equalsIgnoreCase(name)?profile.worldId():0;}}
    synchronized void refreshFriends(WorldStatePoller.Registration registration,org.tortodrone.storage.FriendStore.Snapshot snapshot,Map<Integer,SessionPresence.FriendPresence> online)throws IOException {
        friends.refresh(registration,snapshot,online);
    }

    /** Compatibility only: the stock Depot constructs its purchase panel using this service.
     * No payment operation, account lookup or entitlement mutation is supported.
     * Current stock IDs: 6=[int,String,Confirm], 7=[int,String,Result],
     * 8=[long,boolean,Confirm]. Older archives incorrectly place method 8 at 7. */
    boolean billingInvocation(SessionDecoder.Invocation request)throws IOException{return billing.invocation(request);}

    synchronized boolean townInvocation(SessionDecoder.Invocation request)throws IOException {
        if(styles.invocation(request))return true;
        if(boxes.invocation(request))return true;
        if(depotInvocation(request))return true;
        if(billingInvocation(request))return true;
        if(conversations.dismissStaleClose(request))return true;
        return town.invocation(request);
    }

    synchronized boolean conversationInvocation(SessionDecoder.Invocation request)throws IOException {
        return conversations.invocation(request);
    }
    synchronized String conversationNode(){return conversations.node();}
    void closeConversation()throws IOException { conversations.close(); }
    ConversationHandler conversations(){return conversations;}

    WorldSession(Socket socket, CharacterStore characters, SessionOutput output,
            SessionCodec codec, int oid, long revision, SessionLog log) throws IOException {
        this(socket,characters,output,codec,oid,revision,log,SceneRegistry.system());
    }

    WorldSession(Socket socket, CharacterStore characters, SessionOutput output,
            SessionCodec codec, int oid, long revision, SessionLog log, SceneRegistry scenes) {
        this(socket,characters,output,codec,oid,revision,log,scenes,()->false);
    }

    WorldSession(Socket socket, CharacterStore characters, SessionOutput output,
            SessionCodec codec, int oid, long revision, SessionLog log, SceneRegistry scenes,RoleSource roles) {
        this(socket,characters,output,codec,oid,revision,log,scenes,roles,"");
    }

    WorldSession(Socket socket, CharacterStore characters, SessionOutput output,
            SessionCodec codec, int oid, long revision, SessionLog log, SceneRegistry scenes,RoleSource roles,String welcomeMessage) {
        this.socket = socket;
        this.characters = characters;
        this.oid = oid;
        this.runtime = new SessionRuntime(this);
        appearance = new SessionAppearance(this);
        authRevision = revision;
        this.log = log;
        this.welcomeMessage = welcomeMessage == null ? "" : welcomeMessage;
        diagnostics=new ClientDiagnostics(log,oid,characters.accountId(),characters.username());
        this.scenes = scenes;
        this.weapons=scenes.weapons();
        this.roles=roles;
        responder=new SessionResponder(output,codec,oid);
        refreshState=new SessionRefresh(this);
        profilePublisher=new SessionProfilePublisher(this);
        carried=new SessionCarriedItems(this);
        sceneState=new SessionSceneState();
        story=new SessionStory(this);
        flags=new SessionFlags();
        guilds=new GuildHandler(this);
        trade=new TradeHandler(this);
        exchangeHandler=new ExchangeHandler(this);
        steam=new SteamHandler(this);
        billing=new BillingHandler(this);
        auction=new AuctionHandler(this);
        friends=new FriendHandler(this);
        styles=new StyleHandler(this);
        boxes=new BoxHandler(this);
        itemUse=new ItemUseHandler(this);
        adminTools=new AdminHandler(this);
        depot=new DepotHandler(this);
        conversations=new ConversationHandler(this);
        opening=new OpeningHandler(this);
        town=new TownHandler(this);
        chats=new ChatHandler(this);
        parties=new PartyHandler(this);
        loadouts=new LoadoutHandler(this);
        inspection=new InspectionHandler(this);
        news=new NewsHandler(this);
        equipment=new EquipHandler(this);
        sceneInput=new SceneInputHandler(this);
        floors=new FloorHandler(this);
        destinations=new DestinationHandler(this);
        moves=new MoveHandler(this);
        knightCommands=new KnightCommandHandler(this);
        publisher=new ScenePublisher(this);
        worldState=new WorldStateHandler(this);
    }

    static final String RHENDON_CAMP="Character/NPC/Rescue Camp/Rhendon, Rescue Camp";
    synchronized boolean selected() { return knight != null; }
    boolean selectedKnightSelected(){return selected();}
    boolean equipmentAllowed(){return runtime.membership()!=null&&runtime.membership().equipmentAllowed();}
    String selectedKnightName(){return knight==null?null:knight.name();}
    boolean inspectionHiddenValue(){return flags.inspectionHidden();}
    int quickbarLength(){return carried.quickbarLength();}
    int departedSceneOid(){return runtime.departedSceneOid();}
    void ping(short value){flags.ping(value);}
    void acknowledge(int value){flags.acknowledge(value);}
    boolean carriesConsumable(long id){return carried.carriesConsumable(id);}
    void clearQuickbarItem(long item){carried.clearQuickbarItem(item);}
    void quickbarSlot(int slot,long item){carried.quickbarSlot(slot,item);}
    InspectionHandler.Inspection inspectionNow(SceneRegistry.Instance place)throws IOException{return inspection.snapshot(place);}
    Short forcedReceiverValue(){return refreshState.forcedReceiver();}
    int subscribedPartyOid()throws IOException{return parties.subscribedOid();}
    boolean gameMasterFlag(){return flags.gameMaster();}
    boolean firstOpeningCompletion(){return story.completedEmpty();}
    void applySettlementStory(Set<String> freshMemories,Map<String,org.tortodrone.core.character.KnightObjective.State> freshObjectives)throws IOException {
        story.applySettlementStory(freshMemories,freshObjectives);
    }
    void recordOpeningCompletion(String mission,OpeningCompletion completion){story.recordCompletion(mission,completion);}
    void missionDialogCreated(long value){story.missionDialogCreated(value);}
    long noteMissionDialog(){return story.noteMissionDialog();}
    /** Install a forced destination before the client acknowledges its move. */
    void beginForcedTravel(SceneRegistry.Instance next,ScenePortals.Entrance entrance)throws IOException {
        runtime.beginPortal(next,entrance);partyState();runtime.retireScene(runtime.scene().oid());
    }
    void resetReferences(){flags.resetReferences();}
    void applySelectionFlags(boolean gm,boolean hidden){flags.apply(gm,hidden);}
    SessionRuntime runtime(){return runtime;}
    void markClosed(){closed=true;}
    void stopPublications(){publications.stop();}
    void awaitPublications(){publications.awaitStop();}
    int sceneCreated(){return flags.created();}
    void setSelectedKnight(Knight value){knight=value;}
    void setPartyMember(PartyRegistry.Member value){partyMember=value;}
    void setCompletedOpening(Map<String,OpeningCompletion> value){story.completed(value);}
    java.util.Collection<OpeningCompletion> completedOpeningValues(){return story.completedValues();}
    void setFriendOnline(boolean value){presenceState.online(value);}
    void publishStyles()throws IOException{styles.publish();}
    void startDelivery(){
        publications.start("SceneDelivery-"+oid,publisher::publish,this::deliverParty,failure->{
            log.warn("Scene delivery closed; "+deliveryFailure(failure));close();
            try{socket.close();}catch(IOException ignored){}
        });
    }

    void markSceneCreated(){flags.markCreated();}
    FloorHandler floorsHandler(){return floors;}
    OpeningMission arrivalComlink(){return story.arrivalComlink();}

    void markSceneEntry(){flags.markEntry();}
    ChatHandler chatHandler(){return chats;}
    DestinationHandler destinationsHandler(){return destinations;}
    String welcomeMessage(){return welcomeMessage;}
    OpeningMission deliveredComlink(){return story.deliveredComlink();}
    void setArrivalComlink(OpeningMission value){story.arrivalComlink(value);}
    void friendLocation(SessionPresence.FriendPresence value){presenceState.location(value);}
    SessionResponder responder(){return responder;}
    boolean sceneEntered(){return runtime.entered();}
    boolean sceneSubscribed(){return runtime.subscribed();}
    boolean inSubscribedScene(){return !closed&&selected()&&runtime.entered()&&runtime.subscribed();}
    boolean sceneTravelPending(){return runtime.portalMove()!=null;}
    boolean deferredMovePending(){return moves.deferredPending();}
    boolean returnRequested(){return runtime.returnRequested();}
    /** The stock Ready Room return request; the client answers with its zone move. */
    void requestReturnToLast()throws IOException {
        runtime.returnRequested(true);short receiver=refreshState.forcedReceiver();
        responder.send(()->responder.codec().forceScene(responder.oid(),receiver,runtime.scene()));
    }
    void setInspectionHidden(boolean hidden)throws IOException {
        characters.inspectionHidden(authRevision,hidden);flags.inspectionHidden(hidden);
        flags.gameMaster(roles.gameMaster());if(runtime.membership()!=null)runtime.membership().appearance(appearance.actor());
        responder.send(()->responder.codec().playerFlags(responder.oid(),flags.gameMaster(),flags.inspectionHidden(),profile.progress().tutorialComplete()));
    }
    int pendingPortalMap(){var move=runtime.portalMove();return move==null?-1:move.destination().map().id();}
    SceneRegistry.Instance pendingPortalDestination(){var move=runtime.portalMove();return move==null?null:move.destination();}
    ScenePortals.Entrance pendingPortalEntrance(){var move=runtime.portalMove();return move==null?null:move.entrance();}
    void refreshPartyForTravel()throws IOException{refreshParty(true);}
    int connectionOid(){return oid;}
    int acknowledgeValue(){return flags.acknowledge();}
    short pingValue(){return flags.ping();}
    int referenceValue(){return flags.reference();}
    int lastStampValue(){return flags.lastStamp();}
    SceneSnapshot lastSnapshotValue(){return runtime.lastSnapshot();}
    void recordPublished(int stamp,SceneSnapshot snapshot){flags.recordPublished(stamp);runtime.lastSnapshot(snapshot);}
    Map<Integer,Integer> occupantClientsValue(){return sceneState.occupantClients();}
    void setOccupantClients(Map<Integer,Integer> value){sceneState.occupantClients(value);}
    Map<Integer,Boolean> occupantRolesValue(){return sceneState.occupantRoles();}
    void setOccupantRoles(Map<Integer,Boolean> value){sceneState.occupantRoles(value);}
    SceneHealth.State publishedHealthValue(){return sceneState.health();}
    SceneStatus.State publishedStatusValue(){return sceneState.status();}
    Boolean publishedArsenalValue(){return sceneState.arsenal();}
    void setPublishedArsenal(Boolean value){sceneState.arsenal(value);}
    Set<String> memoriesValue(){return story.memories();}
    void setMemories(Set<String> value){story.memories(value);}
    Map<String,KnightObjective.State> objectivesValue(){return story.objectives();}
    void setObjectives(Map<String,KnightObjective.State> value){story.objectives(value);}
    TownHandler townHandler(){return town;}
    OpeningMission arrivalComlinkValue(){return story.arrivalComlink();}
    void setDeliveredComlink(OpeningMission value){story.deliveredComlink(value);}
    long missionDialogCreatedValue(){return story.missionDialogCreated();}
    long nextTemporaryItemId(){return carried.nextId();}
    Map<Long,WorldProfile.Item> temporaryItems(){return carried.items();}
    boolean quickbarContains(long id){return carried.quickbarContains(id);}
    void addToQuickbar(long id){carried.addToQuickbar(id);}
    long[] quickbarSnapshot(){return carried.snapshot();}
    /** Collection seam used by the scene test fixtures; the publisher owns the reward path. */
    void collectPickups()throws IOException{publisher.collectPickups();}
    void forcePortal(ScenePortals.Trigger trigger)throws IOException{publisher.beginPortal(trigger);}
    void forcePublish(SessionPublications.Publication publication)throws IOException{publisher.publish(publication);}
    boolean tutorialFloorDue(int stamp)throws IOException{return floors.tutorial(stamp);}
    boolean openingFloorDue(int stamp)throws IOException{return floors.opening(stamp);}
    void publishVisited(Set<Integer> visited)throws IOException{parties.publishVisited(visited);}
    boolean stateRefreshActive(WorldStatePoller.Registration registration){return !closed&&runtime.entered()&&runtime.persistence()==registration;}
    void setGameMaster(boolean value){flags.gameMaster(value);}
    void applyActorAppearance(){runtime.membership().appearance(appearance.actor());}
    void publishOutbids(WorldProfile before,WorldProfile after)throws IOException{auction.publishOutbids(before,after);}
    void refreshOutbids(WorldProfile before,WorldProfile after)throws IOException{auction.publishOutbids(before,after);}


    void clearDeferredMoves(){moves.clearDeferred();}
    SessionDecoder.Invocation queuedTravel(){return destinations.queued();}
    boolean auctionAfterArrivalPending(){return destinations.auctionAfterArrivalPending();}
    boolean canOpenBox(){return runtime.membership()!=null&&runtime.membership().canOpenBox();}
    void openBox(java.util.List<StarterCatalog.Model> references){runtime.membership().openBox(references);}
    void confetti(String config){runtime.membership().confetti(config);}
    void animatedAction(String config){runtime.membership().animatedAction(config);}
    SceneMembership.ItemUse<org.tortodrone.storage.TownStore.Result> useOwnedItem(long id,
            org.tortodrone.core.world.SceneContent.Spec spec,SceneSimulation.RevivePayment<org.tortodrone.storage.TownStore.Result> payment)throws IOException{
        return runtime.membership().useOwnedItem(id,spec,payment);
    }
    boolean useTemporaryItem(long id,org.tortodrone.core.world.SceneContent.Spec spec){return runtime.membership().useItem(id,spec);}
    org.tortodrone.core.world.SceneContent.Spec persistentItemSpec(String config){return runtime.scene()==null?null:runtime.scene().map().content().items().get(config);}
    org.tortodrone.core.world.SceneContent.Spec temporaryItemSpec(long id){var item=carried.items().get(id);return item==null?null:persistentItemSpec(item.config());}
    WorldProfile loadOwnProfile()throws IOException,CharacterStore.Refused{return characters.worldProfile(knight.name(),authRevision,profile);}
    void refreshKnight(){knight=characters.roster().stream().filter(k->k.id()==knight.id()).findFirst().orElseThrow();}
    SceneRegistry sceneRegistry(){return scenes;}
    boolean gameMasterRole()throws IOException{return roles.gameMaster();}
    TradeHandler tradeHandler(){return trade;}
    CharacterStore charactersStore(){return characters;}
    Knight selectedKnight(){return knight;}
    WorldProfile currentProfile(){return profile;}
    void applyProfile(WorldProfile value){profile=value;}
    long authRevision(){return authRevision;}
    boolean isClosed(){return closed;}
    synchronized SceneRegistry.Instance currentScene(){return runtime.scene();}
    synchronized boolean inReadyRoom(){return runtime.readyRoom();}
    WorldProfile visibleProfileNow()throws IOException{return visibleProfile();}
    SessionLog sessionLog(){return log;}
    synchronized void adminFeedback(String reason)throws IOException{chatFeedback(runtime.scene().oid(),runtime.sceneGeneration(),reason);}
    ActorState selfActor(){return runtime.scene().simulation().snapshot().players().stream().filter(a->a.id()==profile.worldId()).findFirst().orElse(null);}
    SceneMembership membership(){return runtime.membership();}
    SceneChat.RateLimit chatRate(){return chats.rate();}
    int sceneGenerationValue(){return runtime.sceneGeneration();}
    PartyRegistry.Member partyMember(){return partyMember;}
    boolean hasInteriors(){return scenes.catalog().hasNonTown();}
    synchronized boolean ownsScene(int oid) { return selected() && !runtime.readyRoom() && runtime.scene().oid()==oid; }
    boolean partyInvocation(SessionDecoder.Invocation request)throws IOException{return parties.invocation(request);}
    synchronized boolean ownsInteriorParty(int target){return parties.ownsInterior(target);}
    /** Cross-session friend follow only reads the peer's stable party seat under its own monitor. */
    synchronized PartyRegistry.Member partyMemberSnapshot(){return partyMember;}
    void subscribeInteriorParty(short message)throws IOException{parties.subscribeInterior(message);}
    synchronized void unsubscribeInteriorParty(int party){parties.unsubscribeInterior(party);}
    synchronized boolean interiorPartyInvocation(SessionDecoder.Invocation request)throws IOException{return parties.interiorInvocation(request);}
    org.tortodrone.core.world.SceneInstanceView sceneNowForParty(){return runtime.portalMove()!=null?runtime.portalMove().destination():runtime.scene();}
    void completeDeferredPartyMove()throws IOException {
        var move=moves.takeDeferred(runtime.scene().oid());
        if(move!=null)completeMove(move.requestId(),move.zone());
    }
    void publishedHealthStatus(SceneHealth.State health,SceneStatus.State status){sceneState.publishedHealthStatus(health,status);}
    /** Publish the current equip policy on the ordered stream before an arsenal window opens. */
    void publishArsenalPolicy()throws IOException {
        boolean inZone=runtime.membership()!=null&&runtime.membership().arsenalZone();
        responder.send(()->responder.codec().equipPolicy(responder.oid(),inZone));sceneState.arsenal(inZone);
    }
    FloorReport floorReport(){return floors.report();}
    boolean finishTutorialFloor(int stamp)throws IOException{return floors.finishTutorial(stamp);}
    boolean finishOpeningFloor(int stamp)throws IOException{return floors.finishOpening(stamp);}
    boolean sceneTutorial(){return runtime.scene().tutorial()!=null;}
    MissionRun sceneRun(){return runtime.scene().run();}
    synchronized boolean departingScene(int oid) {
        // Returning from the client-local Ready Room can reuse the reserved town OID.
        // Once that place is subscribed again its enteredPlace is current, not stale.
        return !closed&&runtime.departedSceneOid()!=0&&runtime.departedSceneOid()==oid
            &&(runtime.readyRoom()||runtime.portalMove()!=null||!runtime.subscribed()||runtime.scene().oid()!=oid);
    }
    boolean ownsTrade(int objectId){return trade.owns(objectId);}
    void subscribeTrade(int objectId,short message)throws IOException{trade.subscribe(objectId,message);}
    void unsubscribeTrade(int objectId){trade.unsubscribe(objectId);}
    boolean tradeInvocation(SessionDecoder.Invocation request)throws IOException{return trade.invocation(request);}

    synchronized void select(String name, Map<String, Short> receivers)
            throws IOException, CharacterStore.Refused {
        runtime.select(name,receivers);
    }

    boolean openingAvailable(){return scenes.tutorial()!=null;}
    /** Incomplete Crash Site knights resume a private floor; completed knights stage locally. */
    synchronized void select(String name, Map<String, Short> receivers, boolean stageInReadyRoom)
            throws IOException, CharacterStore.Refused {
        runtime.select(name,receivers,stageInReadyRoom);
    }

    /** Call outside the CharacterStore publication lock to preserve session/account lock order. */
    synchronized void refresh() throws IOException { refreshState.refresh(); }

    synchronized void receiverChanged(Map<String, Short> receivers) throws IOException { refreshState.receiverChanged(receivers); }

    /** Replay a town destination command that arrived before the scene was ready. */
    synchronized void flushPendingTravel()throws IOException { destinations.flush(); }

    synchronized boolean invocation(SessionDecoder.Invocation request) throws IOException {
        if(closed||socket.isClosed())throw new IOException("Session is closed");
        int service = request.service();
        var args = request.arguments();
        if(knightCommands.invocation(request))return true;
        if(service==SessionCodec.KNIGHT_SERVICE&&request.method()==7)return destinations.invocation(request);
        if ((service == SessionCodec.SCENE_SERVICE || service == SessionCodec.ZONE_SERVICE) && request.method() == 1) {
            if(moves.invocation(args, service == SessionCodec.ZONE_SERVICE))return true;
        }
        if(news.invocation(request))return true;
        if(loadouts.invocation(request))return true;
        if(equipment.invocation(request,args))return true;
        // ItemMarshaller 1 (Trash) is dispatched in townInvocation; scene item use is 14 only.
        if(itemUse.invocation(request,args))return true;
        return false;
    }

    void inspect(SessionDecoder.Invocation request)throws IOException { inspection.inspect(request); }

    /** The native Ready Room entry: the scene becomes client-local and the saved town is retained
     *  only as a durable return destination. */
    synchronized void enterReadyRoom(short requestId)throws IOException { runtime.enterReadyRoom(requestId); }

    void completeMove(short requestId,boolean zone)throws IOException { runtime.completeMove(requestId,zone); }

    /** Install the destination scene before the client acknowledges its move. The Ready Room
     *  uses the same path so a native `/training`, `/alc` or `/haven` command can leave it
     *  directly instead of first entering the saved town. */
    void enterScene(SceneRegistry.Instance next,ScenePortals.Entrance entrance)throws IOException { runtime.enterScene(next,entrance); }

    /** The admin handler owns readiness and travel; this lookup stays for its peer tools. */
    AdminHandler adminHandler(){return adminTools;}

    /** Cross-session tools deliberately acquire only one session monitor at a time. */
    boolean adminPeople(SessionDecoder.Invocation request)throws IOException { return adminTools.people(request); }

    /** Stock admin service, resolved by the client from the bootstrap; every call is authorized here. */
    synchronized void admin(SessionDecoder.Invocation request) throws IOException { adminTools.dispatch(request); }

    synchronized void subscribe(short message) throws IOException { runtime.subscribe(message); }

    /** The stock controller sends enteredPlace after attaching its scene-delta listener. */
    synchronized void entered() throws IOException { runtime.enterPlace(); }
    /** The mission bar follows the owned run: the imported mission's {@code o.<id>} objective
     *  while it is active, and nothing outside a mission. */
    void syncMissionObjective()throws IOException { story.syncMissionObjective(); }

    ActorState auctionSource(SceneRegistry.Instance destination)throws IOException{return auction.source(destination);}
    void openAuction()throws IOException{auction.open();}

    synchronized void unsubscribe() { runtime.unsubscribe(); }

    void emote(SessionDecoder.Invocation request)throws IOException{chats.emote(request);}
    synchronized boolean ownsChannel(int channel){return chats.ownsChannel(channel);}
    void subscribeChannel(short message,int channel)throws IOException{chats.subscribeChannel(message,channel);}
    void unsubscribeChannel(int channel){chats.unsubscribeChannel(channel);}
    boolean channelSpeak(SessionDecoder.Invocation request)throws IOException{return chats.channelSpeak(request);}
    void speak(SessionDecoder.Invocation request)throws IOException{chats.speak(request);}
    void broadcast(SessionDecoder.Invocation request)throws IOException{chats.broadcast(request);}
    void away(SessionDecoder.Invocation request)throws IOException{chats.away(request);}
    void tell(SessionDecoder.Invocation request)throws IOException{chats.tell(request);}
    synchronized void unavailable(String reason)throws IOException { chats.unavailable(reason); }
    /** The native {@code chatFeedback} for a scene that is still current. */
    synchronized void chatFeedback(int place,int generation,String reason)throws IOException { chats.feedback(place,generation,reason); }
    void leaveChat(){
        presenceState.reset();
        town.reset();
        trade.leave();
        chats.leave();
        if(runtime.persistence()!=null){runtime.persistence().close();runtime.persistence(null);}
    }

    synchronized void input(SessionDecoder.Input input)throws IOException { sceneInput.input(input); }

    /** Coalesce ordinary frames while retaining native data-dependent activity boundaries. */
    void offer(SceneRegistry.Instance place,int generation,SceneSnapshot snapshot) {
        publications.offer(runtime.sceneGeneration(),place,generation,snapshot);
    }
    /** Keep the encoder/writer cause when a downstream stream has become unusable. */
    static String deliveryFailure(Throwable failure) {
        var message=new StringBuilder();var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        for(int depth=0;failure!=null&&depth<8&&seen.add(failure);depth++,failure=failure.getCause()){
            if(depth>0)message.append(" caused by ");message.append(failure.getClass().getSimpleName());
            if(failure.getMessage()!=null){String detail=failure.getMessage().replaceAll("[\\r\\n\\t]"," ");message.append(": ").append(detail,0,Math.min(256,detail.length()));}
        }
        return message.toString();
    }
    WorldProfile loadProfile(WorldProfile cached)throws IOException {
        try{return characters.worldProfile(knight.name(),authRevision,cached);}
        catch(CharacterStore.Refused refused){throw new IOException("Knight changed; reconnect",refused);}
    }
    void refreshState(WorldStatePoller.Registration registration,org.tortodrone.core.character.WorldSummary state)throws IOException {
        worldState.refresh(registration,state);
    }
    synchronized void refreshFailed(WorldStatePoller.Registration registration,Exception failure) {
        worldState.failed(registration,failure);
    }
    void attach() { runtime.attach(); }
    void updateAppearance(){appearance.update();}

    static long monotonicTime() { return System.nanoTime() / 1_000_000L; }
    void monitorScene() { runtime.monitorScene(); }
    /** Set once a successful rename answered its confirm; honored by the connection loop. */
    private boolean renameDisconnect;
    synchronized boolean takeRenameDisconnect(){boolean requested=renameDisconnect;renameDisconnect=false;return requested;}

    @Override public void close() { runtime.closeSession(); }
}
