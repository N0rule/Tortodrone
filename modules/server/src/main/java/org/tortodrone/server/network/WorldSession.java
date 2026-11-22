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
    static final Map<Integer, WorldSession> ACTIVE = new HashMap<>();
    private volatile boolean friendOnline;
    private volatile FriendPresence friendLocation=new FriendPresence("~",0,0);
    record FriendPresence(String location,int zone,int instance) { }
    /** Per-knight-session join secret published through PlayerObject.ML(); never persisted. */
    private static final java.security.SecureRandom SECRETS=new java.security.SecureRandom();
    private final long externalSecret=mintSecret();
    private static long mintSecret(){long value;do{value=SECRETS.nextLong();}while(value==0);return value;}
    final TradeHandler trade;
    private final Socket socket;
    private final CharacterStore characters;
    private final SessionOutput output;
    private final SessionCodec codec;
    private final int oid;
    private final SessionRuntime runtime;
    private final long authRevision;
    private final SessionLog log;
    private final SceneRegistry scenes;
    private final WeaponCatalog weapons;
    private final String welcomeMessage;
    @FunctionalInterface interface RoleSource { boolean gameMaster()throws IOException; }
    private final RoleSource roles;
    private boolean gameMaster,inspectionHidden;
    private final SessionPublications publications=new SessionPublications();
    final SessionAppearance appearance;
    private Knight knight;
    private WorldProfile profile;
    private Set<String> memories=Set.of();
    private Map<String,OpeningCompletion> completedOpening=Map.of();
    private int acknowledge, created, reference, lastStamp;
    private short ping;
    private boolean closed;
    private final SessionRefresh refreshState;
    /** Stock town commands may arrive from the loading screen or Ready Room; replay the
     *  native destination request once the scene has subscribed and accepted enteredPlace. */
    private SceneHealth.State publishedHealth;
    private SceneStatus.State publishedStatus;
    private Boolean publishedArsenal;
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
    FriendPresence presence(PartyView.View view)throws IOException {
        if(runtime.scene().map().town())return new FriendPresence(runtime.scene().map().name(),runtime.scene().map().zone(),runtime.scene().oid());
        // Only Training rooms are party-shared; Laboratory and Hall of Heroes stay per-world.
        boolean shared=runtime.scene().map().kind().equals("training")&&scenes.parties().owner(partyMember,profile.worldId())!=0;
        boolean joinable=shared&&(view.automatic()||!view.inviteOnly()||view.allowFriends());
        return new FriendPresence(runtime.scene().map().name(),runtime.scene().map().zone(),joinable?runtime.scene().oid():0);
    }
    void partyPresence(PartyView.View view)throws IOException{friendLocation=presence(view);}
    void forcePartyExit()throws IOException {
        if(runtime.readyRoom())return;
        if(runtime.scene().map().town()&&runtime.portalMove()==null)return;
        if(runtime.portalMove()!=null&&runtime.portalMove().destination().map().town())return;
        if(runtime.portalMove()!=null&&runtime.portalMove().destination()!=runtime.scene())scenes.release(runtime.portalMove().destination(),profile.worldId());
        var run=runtime.scene().run()!=null?runtime.scene().run():runtime.portalMove()==null?null:runtime.portalMove().destination().run();
        if(run!=null)profile=loadProfile(profile);
        int safeTown=run==null?scenes.catalog().entry().id()
            :profile.progress().openingStage()>=2||!org.tortodrone.core.character.OpeningMission.ANCIENT_GENERATOR.id().equals(run.missionId())
                ?run.destination().id():217;
        var next=destination(safeTown);
        runtime.beginPortal(next,new ScenePortals.Entrance(1,next.map().spawnX(),next.map().spawnY(),next.map().spawnRotation()));
        moves.clearDeferred();runtime.departedSceneOid(runtime.scene().oid());leaveChat();runtime.subscribed(false);runtime.entered(false);runtime.bumpSceneGeneration();runtime.membership().pause();
        var receiver=refreshState.forcedReceiver();if(receiver!=null)output.send(()->codec.forceScene(oid,receiver,next));
    }
    /** Verified stock SteamMarshaller: 2[int] and 5[boolean] are unacknowledged client reports;
     * purchase, profile and friend-invite requests carry listeners and receive native failure. */
    boolean steamInvocation(SessionDecoder.Invocation request)throws IOException{return steam.invocation(request);}
    private Map<Integer,Integer> occupantClients=Map.of();
    private Map<Integer,Boolean> occupantRoles=Map.of();
    private Map<String,org.tortodrone.core.character.KnightObjective.State> objectives=Map.of();
    /** The active mission whose {@code o.<missionId>} objective is on the player's bar, or null. */
    private String missionObjective;
    private OpeningMission arrivalComlink;
    /** The one arrival comlink this session already delivered; native bodyEntered spends the
     *  held comlink once, so re-entering the scene must not reopen it. */
    private OpeningMission deliveredComlink;
    private long missionDialogCreated;
    final SessionCarriedItems carried;
    void publishQuickbar()throws IOException { carried.publish(); }
    /** The native {@code PlayerObject.objectives} set: knight-persistent entries plus the
     *  mission bar derived from the mission's own {@code o.<id>} text key. */
    private java.util.List<org.tortodrone.core.mission.ObjectiveEntry> wireObjectives(){
        int next=0;var result=new ArrayList<org.tortodrone.core.mission.ObjectiveEntry>(objectives.size()+1);
        for(var value:objectives.values()){result.add(new org.tortodrone.core.mission.ObjectiveEntry(value.index(),value.name(),value.text(),org.tortodrone.core.mission.ObjectiveEntry.KNIGHT));next=Math.max(next,value.index()+1);}
        // The HUD resolves the key through the default (projectx) bundle, so the mission
        // bullet names its own bundle explicitly, exactly like native mission strings.
        if(missionObjective!=null)result.add(new org.tortodrone.core.mission.ObjectiveEntry(next,missionObjective,"%mission:o."+missionObjective,org.tortodrone.core.mission.ObjectiveEntry.PARTY));
        return result;
    }
    void publishObjectives()throws IOException {
        var wire=wireObjectives();output.send(()->codec.objectives(oid,wire));
    }
    WorldProfile visibleProfile(){
        return visibleProfile(runtime.membership()==null?Set.of():runtime.membership().status().cursedItems());
    }
    WorldProfile visibleProfile(Set<Long> cursed){
        if(carried.items().isEmpty()&&cursed.isEmpty())return profile;
        var items=new ArrayList<>(profile.items());items.addAll(carried.items().values());
        items.replaceAll(item->item.curse(cursed.contains(item.id())));
        return new WorldProfile(profile.worldId(),profile.accountId(),profile.knightId(),profile.crowns(),profile.energy(),profile.revision(),items,profile.progress(),profile.town(),profile.achievements());
    }
    void closeTownDialog()throws IOException { town.close(); }
    long townDialogCreated(){return town.created();}
    boolean townDialogOpen(){return town.dialogOpen();}
    void openTownDialog(org.tortodrone.core.town.TownCatalog.Station station,ActorState source)throws IOException { town.open(station,source); }
    void townChanged(org.tortodrone.storage.TownStore.Result result)throws IOException {
        townChanged(result,true);
    }
    void townChanged(org.tortodrone.storage.TownStore.Result result,boolean publishTown)throws IOException {
        var before=visibleProfile();profile=result.profile();updateAppearance();var after=visibleProfile();
        // The durable operation already committed. A failed buyer connection must
        // not suppress its shared scene effect for remaining observers.
        if(before.energy()>after.energy()&&runtime.membership()!=null)runtime.membership().effect(new StarterCatalog.Model("Energy Spent",Map.of()));
        output.send(()->codec.itemChanges(oid,knight,before,after));output.send(()->codec.balances(oid,after));
        var achievementChanges=codec.achievementChanges(oid,before,after);
        if(achievementChanges!=null)output.send(()->achievementChanges);
        if(publishTown)output.send(()->codec.townState(oid,after));
        publishOutbids(before,after);
        if(!before.town().loadouts().equals(after.town().loadouts())||before.town().equippedSprite()!=after.town().equippedSprite()
                ||!before.town().sprites().equals(after.town().sprites()))output.send(()->codec.loadoutChanges(oid,before,after));
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
    synchronized boolean exchangeInvocation(SessionDecoder.Invocation request)throws IOException{
        if(request.oid()!=SessionCodec.EXCHANGE_OID||request.service()!=SessionCodec.EXCHANGE_SERVICE)return false;
        var a=request.arguments();int method=request.method();
        if(a.isEmpty()||!(a.getLast() instanceof SessionDecoder.Listener listener)||!listener.type().equals(org.tortodrone.core.WireTypes.LISTENER_PREFIX+(method==1?"ConfirmMarshaller":"ResultMarshaller")))throw new SessionDecoder.Rejected("Invalid Energy Exchange listener");
        boolean valid=switch(method){case 1->a.size()==2&&a.getFirst() instanceof Integer;case 2->a.size()==1;case 3->a.size()==5&&a.get(0) instanceof Integer&&a.get(1) instanceof Integer&&a.get(2) instanceof Boolean&&a.get(3) instanceof Boolean;default->false;};
        if(!valid)throw new SessionDecoder.Rejected("Invalid Energy Exchange request");
        try{
            if(closed||!selected()||!runtime.entered()||!runtime.subscribed()||!exchangeHandler.subscribed()||!runtime.scene().map().town()&&!runtime.scene().map().kind().equals("training"))throw new IOException("Open the Energy Exchange in town first");
            if(trade.active())throw new IOException("Finish the trade first");
            var exchange=characters.services().exchange();var result=switch(method){case 1->exchange.cancel(characters.accountId(),knight.id(),authRevision,(Integer)a.getFirst());case 2->exchange.offers(characters.accountId(),knight.id(),authRevision);default->exchange.trade(characters.accountId(),knight.id(),authRevision,(Integer)a.get(0),(Integer)a.get(1),(Boolean)a.get(2),(Boolean)a.get(3));};
            townChanged(result);refreshExchange(exchange.book());
            if(method==1)output.send(()->codec.invocationConfirmed(oid,listener.requestId()));else output.send(()->codec.townResult(oid,listener.requestId(),result.result(),profile));
        }catch(IOException failure){output.send(()->codec.invocationFailure(oid,listener.requestId(),failure.getMessage()));}return true;
    }
    private boolean depotInvocation(SessionDecoder.Invocation request)throws IOException{
        if(request.oid()!=SessionCodec.INVOCATION_OID||request.service()!=SessionCodec.MISSION_SERVICE)return false;
        if(!Set.of(4,5,6,10).contains(request.method()))return opening.invocation(request);
        return depot.invocation(request);
    }
    void loadOpeningCompletions()throws IOException{completedOpening=characters.services().openingMissions(scenes.town(),scenes.missions()).load(characters.accountId(),knight.id(),authRevision);}
    Set<String> completedOpeningKeys(){return completedOpening.keySet();}
    boolean completedOpeningEmpty(){return completedOpening.isEmpty();}
    /** Commit, acknowledge and force the mission move in that native order. */
    synchronized void beginForcedMissionTravel(SceneRegistry.Instance next,ScenePortals.Entrance start,short requestId)throws IOException {
        closeConversation();closeTownDialog();runtime.beginPortal(next,start);runtime.departedSceneOid(runtime.scene().oid());
        partyState();leaveChat();runtime.subscribed(false);runtime.entered(false);runtime.bumpSceneGeneration();if(runtime.membership()!=null)runtime.membership().pause();
        short receiver=refreshState.forcedReceiver();
        output.send(()->codec.invocationConfirmed(oid,requestId));output.send(()->codec.forceScene(oid,receiver,next));
    }
    void clearArrivalComlink(){arrivalComlink=null;}
    void leaveChatForTown(){leaveChat();}
    void requestRenameDisconnect(){renameDisconnect=true;}
    void recipeLearnedEffect(){if(runtime.membership()!=null)runtime.membership().effect(new StarterCatalog.Model("Player/Learn Recipe",Map.of()));}
    /** Remove one consumable/session pickup from the visible inventory without a store write. */
    boolean trashTemporary(long id)throws IOException { return carried.trash(id); }
    SceneConversation comlink(String missionId){
        var opening=OpeningMission.byId(missionId);
        if(opening!=null&&OpeningComlinks.get(opening)!=null)return OpeningComlinks.get(opening);
        return scenes.comlinks().get(missionId);
    }
    void reloadProfile()throws IOException{profile=loadProfile(profile);}
    boolean openingEligible(String missionId){return scenes.missions().eligible(missionId,profile.progress(),completedOpening.keySet());}
    boolean auctionInvocation(SessionDecoder.Invocation request)throws IOException{return auction.invocation(request);}

    boolean friendInvocation(SessionDecoder.Invocation request)throws IOException{return friends.invocation(request);}
    boolean feedInvocation(SessionDecoder.Invocation request)throws IOException{return friends.feedInvocation(request);}
    boolean eventLogInvocation(SessionDecoder.Invocation request)throws IOException{return friends.eventLogInvocation(request);}
    /** Native method 4: publish the party-finder flag; matchmaking itself is not implemented. */
    void setLookingForGroup(boolean enabled,org.tortodrone.core.wire.EnumValue difficulty)throws IOException { friends.setLookingForGroup(enabled,difficulty); }
    long externalSecret(){return externalSecret;}
    boolean lookingForGroup(){return friends.lookingForGroup();}
    boolean friendOnlineValue(){return friendOnline;}
    FriendPresence friendPresenceValue(){return friendLocation;}
    boolean hasWorldStorage(){return characters.hasWorldStorage();}
    boolean sameWorld(WorldSession peer){return peer!=null&&peer.scenes==scenes&&peer.characters.worldStorageFile().equals(characters.worldStorageFile());}
    /** Snapshot taken under the session monitor, never under ACTIVE (close locks in that order). */
    int identityWorld(String name){synchronized(this){return knight!=null&&profile!=null&&knight.name().equalsIgnoreCase(name)?profile.worldId():0;}}
    static WorldSession activeSession(int world){synchronized(ACTIVE){return ACTIVE.get(world);}}
    static List<WorldSession> activeSessions(){synchronized(ACTIVE){return List.copyOf(ACTIVE.values());}}
    /** Only volatile presence is read under ACTIVE; no peer session monitor is acquired. */
    static Map<Integer,FriendPresence> friendPresence(java.nio.file.Path storage) {
        var result=new HashMap<Integer,FriendPresence>();
        synchronized(ACTIVE){for(var entry:ACTIVE.entrySet()){var session=entry.getValue();if(session.friendOnline&&!session.socket.isClosed()&&session.characters.hasWorldStorage()&&session.characters.worldStorageFile().equals(storage))result.put(entry.getKey(),session.friendLocation);}}
        return Map.copyOf(result);
    }
    /** Read only volatile session presence and immutable auth revisions under ACTIVE.
     * The recipient's DB authentication is checked again before a direct refund commits. */
    static Map<Integer,Long> auctionPresence(java.nio.file.Path storage) {
        var result=new HashMap<Integer,Long>();
        synchronized(ACTIVE){for(var entry:ACTIVE.entrySet()){var session=entry.getValue();if(session.friendOnline&&!session.socket.isClosed()&&session.characters.hasWorldStorage()&&session.characters.worldStorageFile().equals(storage))result.put(entry.getKey(),session.authRevision);}}
        return Map.copyOf(result);
    }
    synchronized void refreshFriends(WorldStatePoller.Registration registration,org.tortodrone.storage.FriendStore.Snapshot snapshot,Map<Integer,FriendPresence> online)throws IOException {
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
        var a=request.arguments();
        if(!town.dialogOpen()&&!conversations.active()&&ownsScene(request.oid())&&request.service()==6&&request.method()==1
                &&a.size()==1&&a.getFirst() instanceof Long created){
            if(missionDialogCreated!=0&&created!=missionDialogCreated)return true;
            missionDialogCreated=0;
            // A stale close from a client still holding a replaced dialog: acknowledge idempotently.
            output.send(()->codec.conversationDialog(oid,runtime.scene().oid(),null,null,null,0));return true;
        }
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
        this.output = output;
        this.codec = codec;
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
        carried=new SessionCarriedItems(this);
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
    boolean inspectionHiddenValue(){return inspectionHidden;}
    int quickbarLength(){return carried.quickbarLength();}
    int departedSceneOid(){return runtime.departedSceneOid();}
    void ping(short value){ping=value;}
    void acknowledge(int value){acknowledge=value;}
    boolean carriesConsumable(long id){return carried.carriesConsumable(id);}
    void clearQuickbarItem(long item){carried.clearQuickbarItem(item);}
    void quickbarSlot(int slot,long item){carried.quickbarSlot(slot,item);}
    InspectionHandler.Inspection inspectionNow(SceneRegistry.Instance place)throws IOException{return inspection.snapshot(place);}
    Short forcedReceiverValue(){return refreshState.forcedReceiver();}
    int subscribedPartyOid()throws IOException{return parties.subscribedOid();}
    boolean gameMasterFlag(){return gameMaster;}
    boolean firstOpeningCompletion(){return completedOpening.isEmpty();}
    void applySettlementStory(Set<String> freshMemories,Map<String,org.tortodrone.core.character.KnightObjective.State> freshObjectives)throws IOException {
        if(!memories.equals(freshMemories)){var old=memories;memories=freshMemories;output.send(()->codec.knightMemoryChanges(oid,old,memories));}
        if(!objectives.equals(freshObjectives)){objectives=freshObjectives;publishObjectives();}
    }
    void recordOpeningCompletion(String mission,OpeningCompletion completion){
        if(!completion.equals(completedOpening.get(mission))){
            var completed=new LinkedHashMap<>(completedOpening);completed.put(mission,completion);completedOpening=Map.copyOf(completed);
        }
    }
    long noteMissionDialog(){missionDialogCreated=conversations.touch();return missionDialogCreated;}
    /** Install a forced destination before the client acknowledges its move. */
    void beginForcedTravel(SceneRegistry.Instance next,ScenePortals.Entrance entrance)throws IOException {
        runtime.beginPortal(next,entrance);runtime.departedSceneOid(runtime.scene().oid());
        partyState();leaveChat();runtime.subscribed(false);runtime.entered(false);runtime.bumpSceneGeneration();runtime.membership().pause();
    }
    void resetReferences(){reference=acknowledge=0;}
    void applySelectionFlags(boolean gm,boolean hidden){gameMaster=gm;inspectionHidden=hidden;}
    SessionRuntime runtime(){return runtime;}
    void markClosed(){closed=true;}
    void stopPublications(){publications.stop();}
    int sceneCreated(){return created;}
    void setSelectedKnight(Knight value){knight=value;}
    void setPartyMember(PartyRegistry.Member value){partyMember=value;}
    void setCompletedOpening(Map<String,OpeningCompletion> value){completedOpening=value;}
    java.util.Collection<OpeningCompletion> completedOpeningValues(){return completedOpening.values();}
    void setFriendOnline(boolean value){friendOnline=value;}
    void publishStyles()throws IOException{styles.publish();}
    void startDelivery(){
        publications.start("SceneDelivery-"+oid,publisher::publish,this::deliverParty,failure->{
            log.warn("Scene delivery closed; "+deliveryFailure(failure));close();
            try{socket.close();}catch(IOException ignored){}
        });
    }

    void markSceneCreated(){created=SceneClock.now();}
    FloorHandler floorsHandler(){return floors;}
    OpeningMission arrivalComlink(){return arrivalComlink;}

    void markSceneEntry(){lastStamp=SceneClock.now();}
    ChatHandler chatHandler(){return chats;}
    DestinationHandler destinationsHandler(){return destinations;}
    String welcomeMessage(){return welcomeMessage;}
    OpeningMission deliveredComlink(){return deliveredComlink;}
    void setArrivalComlink(OpeningMission value){arrivalComlink=value;}
    void friendLocation(FriendPresence value){friendLocation=value;}
    SessionResponder responder(){return responder;}
    SessionOutput output(){return output;}
    SessionCodec codec(){return codec;}
    Socket socket(){return socket;}
    boolean sceneEntered(){return runtime.entered();}
    boolean sceneSubscribed(){return runtime.subscribed();}
    boolean inSubscribedScene(){return !closed&&selected()&&runtime.entered()&&runtime.subscribed();}
    boolean sceneTravelPending(){return runtime.portalMove()!=null;}
    boolean deferredMovePending(){return moves.deferredPending();}
    boolean returnRequested(){return runtime.returnRequested();}
    /** The stock Ready Room return request; the client answers with its zone move. */
    void requestReturnToLast()throws IOException {
        runtime.returnRequested(true);short receiver=refreshState.forcedReceiver();
        output.send(()->codec.forceScene(oid,receiver,runtime.scene()));
    }
    void setInspectionHidden(boolean hidden)throws IOException {
        characters.inspectionHidden(authRevision,hidden);inspectionHidden=hidden;
        gameMaster=roles.gameMaster();if(runtime.membership()!=null)runtime.membership().appearance(appearance.actor());
        output.send(()->codec.playerFlags(oid,gameMaster,inspectionHidden,profile.progress().tutorialComplete()));
    }
    int pendingPortalMap(){var move=runtime.portalMove();return move==null?-1:move.destination().map().id();}
    SceneRegistry.Instance pendingPortalDestination(){var move=runtime.portalMove();return move==null?null:move.destination();}
    ScenePortals.Entrance pendingPortalEntrance(){var move=runtime.portalMove();return move==null?null:move.entrance();}
    void refreshPartyForTravel()throws IOException{refreshParty(true);}
    int connectionOid(){return oid;}
    int acknowledgeValue(){return acknowledge;}
    short pingValue(){return ping;}
    int referenceValue(){return reference;}
    int lastStampValue(){return lastStamp;}
    SceneSnapshot lastSnapshotValue(){return runtime.lastSnapshot();}
    void recordPublished(int stamp,SceneSnapshot snapshot){lastStamp=reference=stamp;runtime.lastSnapshot(snapshot);}
    Map<Integer,Integer> occupantClientsValue(){return occupantClients;}
    void setOccupantClients(Map<Integer,Integer> value){occupantClients=value;}
    Map<Integer,Boolean> occupantRolesValue(){return occupantRoles;}
    void setOccupantRoles(Map<Integer,Boolean> value){occupantRoles=value;}
    SceneHealth.State publishedHealthValue(){return publishedHealth;}
    SceneStatus.State publishedStatusValue(){return publishedStatus;}
    Boolean publishedArsenalValue(){return publishedArsenal;}
    void setPublishedArsenal(Boolean value){publishedArsenal=value;}
    Set<String> memoriesValue(){return memories;}
    void setMemories(Set<String> value){memories=value;}
    Map<String,KnightObjective.State> objectivesValue(){return objectives;}
    void setObjectives(Map<String,KnightObjective.State> value){objectives=value;}
    TownHandler townHandler(){return town;}
    OpeningMission arrivalComlinkValue(){return arrivalComlink;}
    void setDeliveredComlink(OpeningMission value){deliveredComlink=value;}
    long missionDialogCreatedValue(){return missionDialogCreated;}
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
    void setGameMaster(boolean value){gameMaster=value;}
    void applyActorAppearance(){runtime.membership().appearance(appearance.actor());}
    void publishOutbids(WorldProfile before,WorldProfile after)throws IOException{auction.publishOutbids(before,after);}
    void refreshOutbids(WorldProfile before,WorldProfile after)throws IOException{auction.publishOutbids(before,after);}


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
    /** The saved half of one conversation terminal: the pre-write profile and what committed. */
    record PreparedSettlement(WorldProfile before,org.tortodrone.storage.OpeningMissionStore.Settlement settled,boolean firstEver) { }
    PreparedSettlement prepareConversationSettlement(String terminalId)throws IOException {
        profile=loadProfile(profile);var before=profile;boolean firstEver=completedOpening.isEmpty();
        var settled=characters.services().openingMissions(scenes.town(),scenes.missions()).complete(characters.accountId(),knight.id(),authRevision,profile,terminalId,null);
        return new PreparedSettlement(before,settled,firstEver);
    }
    void applyConversationSettlement(PreparedSettlement prepared,String terminalId,ActorState own,long dialog)throws IOException {
        var settled=prepared.settled();var before=prepared.before();profile=settled.profile();
        if(!memories.equals(settled.memories())){var old=memories;memories=settled.memories();output.send(()->codec.knightMemoryChanges(oid,old,memories));}
        if(!objectives.equals(settled.objectives())){objectives=settled.objectives();publishObjectives();}
        if(!settled.completion().equals(completedOpening.get(terminalId))){var completed=new LinkedHashMap<>(completedOpening);completed.put(terminalId,settled.completion());completedOpening=Map.copyOf(completed);}
        if(settled.first()){
            runtime.membership().equipment(visibleProfile());missionDialogCreated=dialog;
            var entry=scenes.missions().mission(terminalId);
            boolean immediate=entry==null||!entry.requirement().type().equals("gate");
            output.send(()->codec.missionSettlement(oid,before,profile,settled.completion(),scenes.missions().grants(terminalId),scenes.missions().prestige(terminalId),immediate,dialog));
            output.send(()->codec.itemChanges(oid,knight,before,profile));
            if(prepared.firstEver())output.send(()->codec.controllerAction(oid,"new_mission",own));partyState();
        }
    }
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
    void publishedHealthStatus(SceneHealth.State health,SceneStatus.State status){publishedHealth=health;publishedStatus=status;}
    /** Publish the current equip policy on the ordered stream before an arsenal window opens. */
    void publishArsenalPolicy()throws IOException {
        boolean inZone=runtime.membership()!=null&&runtime.membership().arsenalZone();
        output.send(()->codec.equipPolicy(oid,inZone));publishedArsenal=inZone;
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

    static boolean active(int accountId, int knightId) {
        synchronized (ACTIVE) {
            return ACTIVE.values().stream().anyMatch(session ->
                session.characters.accountId() == accountId && session.profile.knightId() == knightId);
        }
    }

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

    synchronized void requireAdminReady()throws IOException { requireAdminReady(false); }
    synchronized void requireAdminReady(boolean roomTravel)throws IOException {
        if(closed||socket.isClosed()||!selected()||(!(roomTravel&&runtime.readyRoom())&&(!runtime.subscribed()||!runtime.entered()))||refreshState.forcedReceiver()==null||runtime.portalMove()!=null||deferredMovePending())throw new IOException("Finish entering this scene first");
        try{characters.worldProfile(knight.name(),authRevision);}catch(CharacterStore.Refused refused){throw new IOException("Knight changed; reconnect",refused);}
        if(!roles.gameMaster())throw new IOException("Game Master permission required");
    }
    WorldSession adminTarget(String name)throws IOException {
        if(name.length()<3||name.length()>16||!name.matches("[A-Za-z]+(?:-[A-Za-z]+)*"))throw new IOException("Enter a valid knight name");
        synchronized(ACTIVE){return ACTIVE.values().stream().filter(w->w.knight.name().equalsIgnoreCase(name)
            &&w.scenes==scenes&&w.characters.worldStorageFile().equals(characters.worldStorageFile())).findFirst().orElseThrow(()->new IOException("That knight is not online"));}
    }
    /** The game-master travel tail; location validation lives in AdminHandler. */
    synchronized void beginAdminTravel(SceneRegistry.Instance next,AdminHandler.AdminLocation location)throws IOException {
        runtime.beginPortal(next,new ScenePortals.Entrance(1,location.x(),location.y(),location.rotation()));
        runtime.departedSceneOid(runtime.readyRoom()?0:runtime.scene().oid());leaveChat();runtime.subscribed(false);runtime.entered(false);runtime.bumpSceneGeneration();
        if(runtime.membership()!=null)runtime.membership().pause();short receiver=refreshState.forcedReceiver();
        output.send(()->codec.forceScene(oid,receiver,next));
    }
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
    void syncMissionObjective()throws IOException {
        var run=runtime.scene().run();String next=null;
        if(run!=null){var mission=scenes.missions().mission(run.missionId());if(mission!=null&&mission.requirement().hasObjective())next=run.missionId();}
        if(!java.util.Objects.equals(next,missionObjective)){missionObjective=next;publishObjectives();}
    }

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
        friendLocation=new FriendPresence("~",0,0);
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
    void refreshState(WorldStatePoller.Registration registration,org.tortodrone.character.WorldSummary state)throws IOException {
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

    @Override public synchronized void close() { runtime.closeSession(); }
}
