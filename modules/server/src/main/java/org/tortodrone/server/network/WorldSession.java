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
    private List<SessionCodec.FriendView> publishedFriends=List.of();
    private volatile boolean friendOnline;
    private volatile FriendPresence friendLocation=new FriendPresence("~",0,0);
    record FriendPresence(String location,int zone,int instance) { }
    /** Per-knight-session join secret published through PlayerObject.ML(); never persisted. */
    private static final java.security.SecureRandom SECRETS=new java.security.SecureRandom();
    private final long externalSecret=mintSecret();
    private volatile boolean lookingForGroup;
    private volatile int lookingForGroupDifficulty=-1;
    private volatile boolean lookingForGroupAnyLanguage;
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
    private boolean welcomed;
    @FunctionalInterface interface RoleSource { boolean gameMaster()throws IOException; }
    private final RoleSource roles;
    private boolean gameMaster,inspectionHidden;
    private SceneRegistry.Instance scene;
    private SceneSimulation.Membership membership;
    private SceneSnapshot lastSnapshot;
    private final SessionPublications publications=new SessionPublications();
    private StarterCatalog.Model appearance;
    private Knight knight;
    private WorldProfile profile;
    private Set<String> memories=Set.of();
    private Map<String,OpeningCompletion> completedOpening=Map.of();
    private int acknowledge, created, reference, lastStamp;
    private volatile int sceneGeneration;
    private short ping;
    private WorldStatePoller.Registration persistence;
    private boolean subscribed, entered, closed, inputThrottleSent;
    private Short forcedReceiver;
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
    private void deliverParty(PartyView.Event event)throws IOException{parties.deliver(event);}
    private void refreshParty()throws IOException{parties.refresh();}
    private void refreshParty(boolean traveling)throws IOException{parties.refresh(traveling);}
    /** The native friend list's Join affordance is driven by this presence. */
    private FriendPresence presence(PartyView.View view)throws IOException {
        if(scene.map().town())return new FriendPresence(scene.map().name(),scene.map().zone(),scene.oid());
        // Only Training rooms are party-shared; Laboratory and Hall of Heroes stay per-world.
        boolean shared=scene.map().kind().equals("training")&&scenes.parties().owner(partyMember,profile.worldId())!=0;
        boolean joinable=shared&&(view.automatic()||!view.inviteOnly()||view.allowFriends());
        return new FriendPresence(scene.map().name(),scene.map().zone(),joinable?scene.oid():0);
    }
    void partyPresence(PartyView.View view)throws IOException{friendLocation=presence(view);}
    void forcePartyExit()throws IOException {
        if(runtime.readyRoom())return;
        if(scene.map().town()&&runtime.portalMove()==null)return;
        if(runtime.portalMove()!=null&&runtime.portalMove().destination().map().town())return;
        if(runtime.portalMove()!=null&&runtime.portalMove().destination()!=scene)scenes.release(runtime.portalMove().destination(),profile.worldId());
        var run=scene.run()!=null?scene.run():runtime.portalMove()==null?null:runtime.portalMove().destination().run();
        if(run!=null)profile=loadProfile(profile);
        int safeTown=run==null?scenes.catalog().entry().id()
            :profile.progress().openingStage()>=2||!org.tortodrone.core.character.OpeningMission.ANCIENT_GENERATOR.id().equals(run.missionId())
                ?run.destination().id():217;
        var next=destination(safeTown);
        runtime.beginPortal(next,new ScenePortals.Entrance(1,next.map().spawnX(),next.map().spawnY(),next.map().spawnRotation()));
        moves.clearDeferred();runtime.departedSceneOid(scene.oid());leaveChat();subscribed=entered=false;sceneGeneration++;membership.pause();
        if(forcedReceiver!=null){short receiver=forcedReceiver;output.send(()->codec.forceScene(oid,receiver,next));}
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
    private final Map<Long,WorldProfile.Item> temporaryItems=new LinkedHashMap<>();
    private long nextTemporaryItem=-1;
    private final long[] quickbar=new long[4];
    void publishQuickbar()throws IOException {
        if(membership!=null)membership.inventory(visibleProfile());
        for(int slot=0;slot<quickbar.length;slot++)if(!temporaryItems.containsKey(quickbar[slot]))quickbar[slot]=0;
        var slots=quickbar.clone();output.send(()->codec.quickbar(oid,slots));
    }
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
    private WorldProfile visibleProfile(){
        return visibleProfile(membership==null?Set.of():membership.status().cursedItems());
    }
    WorldProfile visibleProfile(Set<Long> cursed){
        if(temporaryItems.isEmpty()&&cursed.isEmpty())return profile;
        var items=new ArrayList<>(profile.items());items.addAll(temporaryItems.values());
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
        if(before.energy()>after.energy()&&membership!=null)membership.effect(new StarterCatalog.Model("Energy Spent",Map.of()));
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
            if(closed||!selected()||!entered||!subscribed||!exchangeHandler.subscribed()||!scene.map().town()&&!scene.map().kind().equals("training"))throw new IOException("Open the Energy Exchange in town first");
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
        closeConversation();closeTownDialog();runtime.beginPortal(next,start);runtime.departedSceneOid(scene.oid());
        partyState();leaveChat();subscribed=entered=false;sceneGeneration++;if(membership!=null)membership.pause();
        short receiver=forcedReceiver;
        output.send(()->codec.invocationConfirmed(oid,requestId));output.send(()->codec.forceScene(oid,receiver,next));
    }
    void clearArrivalComlink(){arrivalComlink=null;}
    void leaveChatForTown(){leaveChat();}
    void requestRenameDisconnect(){renameDisconnect=true;}
    void recipeLearnedEffect(){if(membership!=null)membership.effect(new StarterCatalog.Model("Player/Learn Recipe",Map.of()));}
    /** Remove one consumable/session pickup from the visible inventory without a store write. */
    boolean trashTemporary(long id)throws IOException {
        if(!temporaryItems.containsKey(id))return false;
        var item=temporaryItems.get(id);
        if(item.permanent()||item.equippedSlot()!=null)throw new IOException("m.internal_error");
        var before=visibleProfile();temporaryItems.remove(id);var after=visibleProfile();
        output.send(()->codec.itemChanges(oid,knight,before,after));publishQuickbar();
        return true;
    }
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
    void setLookingForGroup(boolean enabled,org.tortodrone.core.wire.EnumValue difficulty)throws IOException {
        synchronized(this){
            if(closed||!selected())throw new IOException("Select a knight first");
            try{characters.worldProfile(knight.name(),authRevision,profile);}catch(CharacterStore.Refused refused){throw new IOException("Knight changed; reconnect",refused);}
            if((profile.progress().progressFlags()&4)==0)throw new IOException("m.internal_error");
            lookingForGroupDifficulty=enabled&&difficulty!=null?difficulty.ordinal():-1;
            // The current marshaller no longer sends the old party_with_any preference.
            lookingForGroupAnyLanguage=false;
            lookingForGroup=enabled;
            if(log.debugEnabled())log.debug("Party finder "+(enabled?"enabled":"disabled")+"; difficulty="+lookingForGroupDifficulty+"; anyLanguage="+lookingForGroupAnyLanguage);
            output.send(()->codec.lookingForGroup(oid,enabled));
        }
    }
    long externalSecret(){return externalSecret;}
    boolean lookingForGroup(){return lookingForGroup;}
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
        if(closed||!selected()||registration!=null&&persistence!=registration)return;
        if(snapshot.authentication().getOrDefault(profile.worldId(),-1L)!=authRevision)throw new IOException("Knight or account changed; reconnect");
        if(snapshot.revisions().getOrDefault(profile.worldId(),-1L)<profile.revision())return;
        var next=snapshot.lists().getOrDefault(profile.worldId(),List.of()).stream().map(p->new SessionCodec.FriendView(p.world(),p.name(),p.color(),online.containsKey(p.world())?online.get(p.world()).location():null,p.lastSession(),online.containsKey(p.world())?online.get(p.world()).zone():0,online.containsKey(p.world())?online.get(p.world()).instance():0)).toList();
        if(!next.equals(publishedFriends)){var previous=publishedFriends;output.send(()->codec.friends(oid,previous,next));publishedFriends=next;}
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
            output.send(()->codec.conversationDialog(oid,scene.oid(),null,null,null,0));return true;
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
        this.runtime = new SessionRuntime();
        authRevision = revision;
        this.log = log;
        this.welcomeMessage = welcomeMessage == null ? "" : welcomeMessage;
        diagnostics=new ClientDiagnostics(log,oid,characters.accountId(),characters.username());
        this.scenes = scenes;
        this.weapons=scenes.weapons();
        this.roles=roles;
        responder=new SessionResponder(output,codec,oid);
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
    boolean equipmentAllowed(){return membership!=null&&membership.equipmentAllowed();}
    String selectedKnightName(){return knight==null?null:knight.name();}
    boolean inspectionHiddenValue(){return inspectionHidden;}
    int quickbarLength(){return quickbar.length;}
    int departedSceneOid(){return runtime.departedSceneOid();}
    void ping(short value){ping=value;}
    void acknowledge(int value){acknowledge=value;}
    boolean carriesConsumable(long id){return temporaryItems.containsKey(id)&&temporaryItems.get(id).equippedSlot()==null;}
    void clearQuickbarItem(long item){for(int index=0;index<quickbar.length;index++)if(quickbar[index]==item)quickbar[index]=0;}
    void quickbarSlot(int slot,long item){quickbar[slot]=item;}
    InspectionHandler.Inspection inspectionNow(SceneRegistry.Instance place)throws IOException{return inspection.snapshot(place);}
    Short forcedReceiverValue(){return forcedReceiver;}
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
        runtime.beginPortal(next,entrance);runtime.departedSceneOid(scene.oid());
        partyState();leaveChat();subscribed=entered=false;sceneGeneration++;membership.pause();
    }
    SessionResponder responder(){return responder;}
    SessionOutput output(){return output;}
    SessionCodec codec(){return codec;}
    Socket socket(){return socket;}
    boolean sceneEntered(){return entered;}
    boolean sceneSubscribed(){return subscribed;}
    boolean inSubscribedScene(){return !closed&&selected()&&entered&&subscribed;}
    boolean sceneTravelPending(){return runtime.portalMove()!=null;}
    boolean deferredMovePending(){return moves.deferredPending();}
    boolean returnRequested(){return runtime.returnRequested();}
    /** The stock Ready Room return request; the client answers with its zone move. */
    void requestReturnToLast()throws IOException {
        runtime.returnRequested(true);short receiver=forcedReceiver;
        output.send(()->codec.forceScene(oid,receiver,scene));
    }
    void setInspectionHidden(boolean hidden)throws IOException {
        characters.inspectionHidden(authRevision,hidden);inspectionHidden=hidden;
        gameMaster=roles.gameMaster();if(membership!=null)membership.appearance(actorAppearance());
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
    SceneSnapshot lastSnapshotValue(){return lastSnapshot;}
    void recordPublished(int stamp,SceneSnapshot snapshot){lastStamp=reference=stamp;lastSnapshot=snapshot;}
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
    long nextTemporaryItemId(){return nextTemporaryItem--;}
    Map<Long,WorldProfile.Item> temporaryItems(){return temporaryItems;}
    boolean quickbarContains(long id){for(long value:quickbar)if(value==id)return true;return false;}
    void addToQuickbar(long id){for(int slot=0;slot<quickbar.length;slot++)if(quickbar[slot]==0){quickbar[slot]=id;break;}}
    void forcePortal(ScenePortals.Trigger trigger)throws IOException{publisher.beginPortal(trigger);}
    void forcePublish(SessionPublications.Publication publication)throws IOException{publisher.publish(publication);}
    boolean tutorialFloorDue(int stamp)throws IOException{return floors.tutorial(stamp);}
    boolean openingFloorDue(int stamp)throws IOException{return floors.opening(stamp);}
    void publishVisited(Set<Integer> visited)throws IOException{parties.publishVisited(visited);}
    boolean stateRefreshActive(WorldStatePoller.Registration registration){return !closed&&entered&&persistence==registration;}
    void setGameMaster(boolean value){gameMaster=value;}
    void applyActorAppearance(){membership.appearance(actorAppearance());}
    void publishOutbids(WorldProfile before,WorldProfile after)throws IOException{auction.publishOutbids(before,after);}
    void refreshOutbids(WorldProfile before,WorldProfile after)throws IOException{auction.publishOutbids(before,after);}


    SessionDecoder.Invocation queuedTravel(){return destinations.queued();}
    boolean auctionAfterArrivalPending(){return destinations.auctionAfterArrivalPending();}
    boolean canOpenBox(){return membership!=null&&membership.canOpenBox();}
    void openBox(java.util.List<StarterCatalog.Model> references){membership.openBox(references);}
    void confetti(String config){membership.confetti(config);}
    void animatedAction(String config){membership.animatedAction(config);}
    SceneSimulation.Membership.ItemUse<org.tortodrone.storage.TownStore.Result> useOwnedItem(long id,
            org.tortodrone.core.world.SceneContent.Spec spec,SceneSimulation.RevivePayment<org.tortodrone.storage.TownStore.Result> payment)throws IOException{
        return membership.useOwnedItem(id,spec,payment);
    }
    boolean useTemporaryItem(long id,org.tortodrone.core.world.SceneContent.Spec spec){return membership.useItem(id,spec);}
    org.tortodrone.core.world.SceneContent.Spec persistentItemSpec(String config){return scene==null?null:scene.map().content().items().get(config);}
    org.tortodrone.core.world.SceneContent.Spec temporaryItemSpec(long id){var item=temporaryItems.get(id);return item==null?null:persistentItemSpec(item.config());}
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
    synchronized SceneRegistry.Instance currentScene(){return scene;}
    synchronized boolean inReadyRoom(){return runtime.readyRoom();}
    WorldProfile visibleProfileNow()throws IOException{return visibleProfile();}
    SessionLog sessionLog(){return log;}
    synchronized void adminFeedback(String reason)throws IOException{chatFeedback(scene.oid(),sceneGeneration,reason);}
    ActorState selfActor(){return scene.simulation().snapshot().players().stream().filter(a->a.id()==profile.worldId()).findFirst().orElse(null);}
    SceneSimulation.Membership membership(){return membership;}
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
            membership.equipment(visibleProfile());missionDialogCreated=dialog;
            var entry=scenes.missions().mission(terminalId);
            boolean immediate=entry==null||!entry.requirement().type().equals("gate");
            output.send(()->codec.missionSettlement(oid,before,profile,settled.completion(),scenes.missions().grants(terminalId),scenes.missions().prestige(terminalId),immediate,dialog));
            output.send(()->codec.itemChanges(oid,knight,before,profile));
            if(prepared.firstEver())output.send(()->codec.controllerAction(oid,"new_mission",own));partyState();
        }
    }
    SceneChat.RateLimit chatRate(){return chats.rate();}
    int sceneGenerationValue(){return sceneGeneration;}
    PartyRegistry.Member partyMember(){return partyMember;}
    boolean hasInteriors(){return scenes.catalog().hasNonTown();}
    synchronized boolean ownsScene(int oid) { return selected() && !runtime.readyRoom() && scene.oid()==oid; }
    boolean partyInvocation(SessionDecoder.Invocation request)throws IOException{return parties.invocation(request);}
    synchronized boolean ownsInteriorParty(int target){return parties.ownsInterior(target);}
    /** Cross-session friend follow only reads the peer's stable party seat under its own monitor. */
    synchronized PartyRegistry.Member partyMemberSnapshot(){return partyMember;}
    void subscribeInteriorParty(short message)throws IOException{parties.subscribeInterior(message);}
    synchronized void unsubscribeInteriorParty(int party){parties.unsubscribeInterior(party);}
    synchronized boolean interiorPartyInvocation(SessionDecoder.Invocation request)throws IOException{return parties.interiorInvocation(request);}
    org.tortodrone.core.world.SceneInstanceView sceneNowForParty(){return runtime.portalMove()!=null?runtime.portalMove().destination():scene;}
    void completeDeferredPartyMove()throws IOException {
        var move=moves.takeDeferred(scene.oid());
        if(move!=null)completeMove(move.requestId(),move.zone());
    }
    void publishedHealthStatus(SceneHealth.State health,SceneStatus.State status){publishedHealth=health;publishedStatus=status;}
    /** Publish the current equip policy on the ordered stream before an arsenal window opens. */
    void publishArsenalPolicy()throws IOException {
        boolean inZone=membership!=null&&membership.arsenalZone();
        output.send(()->codec.equipPolicy(oid,inZone));publishedArsenal=inZone;
    }
    FloorReport floorReport(){return floors.report();}
    boolean finishTutorialFloor(int stamp)throws IOException{return floors.finishTutorial(stamp);}
    boolean finishOpeningFloor(int stamp)throws IOException{return floors.finishOpening(stamp);}
    boolean sceneTutorial(){return scene.tutorial()!=null;}
    MissionRun sceneRun(){return scene.run();}
    synchronized boolean departingScene(int oid) {
        // Returning from the client-local Ready Room can reuse the reserved town OID.
        // Once that place is subscribed again its enteredPlace is current, not stale.
        return !closed&&runtime.departedSceneOid()!=0&&runtime.departedSceneOid()==oid
            &&(runtime.readyRoom()||runtime.portalMove()!=null||!subscribed||scene.oid()!=oid);
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
        select(name,receivers,false);
    }

    boolean openingAvailable(){return scenes.tutorial()!=null;}
    /** Incomplete Crash Site knights resume a private floor; completed knights stage locally. */
    synchronized void select(String name, Map<String, Short> receivers, boolean stageInReadyRoom)
            throws IOException, CharacterStore.Refused {
        long selectionStarted=System.nanoTime();
        if(closed||socket.isClosed())throw new IOException("Session is closed");
        if (selected()) throw new IOException("A character is already selected; reconnect to change characters");
        if(scenes.catalog().isEmpty())throw new IOException("Maps are unavailable; install default maps and restart the server");
        gameMaster=roles.gameMaster();
        inspectionHidden=characters.inspectionHidden(authRevision);
        // Picker edits/deletes use this same account lock around their online check and save.
        synchronized (characters) {
            var loaded = characters.worldProfile(name, authRevision);
            for(var item:loaded.items())if(!item.starter()) {
                var entry=characters.catalog().require(item.config());
                if(entry.slot()!=item.slot())throw new IOException("Saved item category differs from the deployed catalogue");
            }
            var choice = characters.roster().stream().filter(k -> k.id() == loaded.knightId())
                .findFirst().orElseThrow(() -> new IOException("No such knight"));
            int saved=characters.lastTownScene(choice.id(),authRevision);
            int destination=scenes.catalog().towns().stream().filter(map->map.id()==saved)
                .mapToInt(org.tortodrone.core.world.MapScene::id).findFirst().orElse(scenes.catalog().entry().id());
            if(loaded.progress().crashSiteIncomplete()){
                if(scenes.tutorial()==null)throw new IOException("The opening adventure is unavailable; please try again later");
                destination=scenes.tutorial().source().id();stageInReadyRoom=false;
            }else if(loaded.progress().openingStage()==1||loaded.progress().openingStage()==2){
                destination=loaded.progress().openingStage()==1?217:218;
                if(!scenes.catalog().scene(destination).town())throw new IOException("The opening camp is unavailable; please try again later");
            }
            synchronized (ACTIVE) {
                if (ACTIVE.containsKey(loaded.worldId())) throw new IOException("This knight is already online");
                profile = loaded;
                knight = choice;
                scene = scenes.destination(destination,loaded.worldId());
                ACTIVE.put(loaded.worldId(), this);
            }
        }
        created = SceneClock.now();
        if(characters.hasWorldStorage()){
            var house=characters.services().auction(scenes.town());
            boolean delivered=house.deliverCampaigns(profile.worldId());
            if(house.deliverWelcome(profile.worldId(),knight.createdAt()))delivered=true;
            if(delivered)profile=characters.worldProfile(knight.name(),authRevision,profile);
        }
        if(characters.hasWorldStorage()){characters.services().friends().session(characters.accountId(),knight.id(),authRevision);profile=characters.worldProfile(knight.name(),authRevision,profile);}
        guilds.reload();
        friendOnline=true;
        runtime.readyRoom(stageInReadyRoom);
        if(runtime.readyRoom())friendLocation=new FriendPresence("~",ReadyRoom.ZONE,0);
        else attach();
        partyMember=scenes.parties().join(oid,knight,profile,this::offerParty);
        if(profile.progress().crashSiteIncomplete())scenes.parties().enterTutorial(partyMember);
        partyState();
        if(characters.hasWorldStorage())memories=characters.knightMemories(knight.id(),authRevision);
        if(characters.hasWorldStorage())objectives=characters.knightObjectives(knight.id(),authRevision);
        if(characters.hasWorldStorage())completedOpening=characters.services().openingMissions(scenes.town(),scenes.missions()).load(characters.accountId(),knight.id(),authRevision);
        output.send(() -> codec.selected(oid, knight, profile, guilds.guildInfo()));
        output.send(() -> codec.knightMemories(oid,memories));
        publishObjectives();
        output.send(()->codec.completedMissions(oid,completedOpening.values()));
        output.send(() -> codec.townState(oid,profile));
        styles.publish();
        output.send(() -> codec.playerFlags(oid,gameMaster,inspectionHidden,profile.progress().tutorialComplete()));
        if(characters.hasWorldStorage())refreshFriends(null,characters.services().friends().snapshot(),friendPresence(characters.worldStorageFile()));
        refreshParty();
        receiverChanged(receivers);
        log.selected(knight.id(),profile.worldId(),knight.name());
        log.info("Character selected; knight=" + knight.name() + " (#" + profile.worldId() + "); rosterId="+knight.id());
        if(!runtime.readyRoom())monitorScene();
        // Party events belong to the selected session, not its scene subscription.
        // Ready Room is client-local and never calls entered(); delaying this worker
        // until then strands invitations and can overflow the queue on world return.
        publications.start("SceneDelivery-"+oid,publisher::publish,this::deliverParty,failure->{
            log.warn("Scene delivery closed; "+deliveryFailure(failure));close();
            try{socket.close();}catch(IOException ignored){}
        });
        log.debug("Knight/scene initialization completed; duration-ms="+((System.nanoTime()-selectionStarted)/1000000));
    }

    /** Call outside the CharacterStore publication lock to preserve session/account lock order. */
    synchronized void refresh() throws IOException {
        if (selected()) {
            var visible=visibleProfile();output.send(() -> codec.selected(oid, knight, visible, guilds.guildInfo()));
            publishQuickbar();
            output.send(() -> codec.playerFlags(oid,gameMaster,inspectionHidden,profile.progress().tutorialComplete()));
            if (subscribed) output.send(() -> codec.sceneLocation(oid,scene,scenes.channelOid(scene.map().zone(),false),scenes.channelOid(scene.map().zone(),true)));
        }
    }

    synchronized void receiverChanged(Map<String, Short> receivers) throws IOException {
        Short receiver = receivers.get(SceneRegistry.ZONE_RECEIVER);
        if (receiver == null) forcedReceiver = null;
        if (selected() && receiver != null && !receiver.equals(forcedReceiver)) {
            var destination=runtime.portalMove()==null?scene:runtime.portalMove().destination();
            if(runtime.readyRoom()&&!runtime.returnRequested())output.send(()->codec.forceDestination(oid,receiver,ReadyRoom.ZONE,ReadyRoom.SCENE));
            else output.send(() -> codec.forceScene(oid, receiver,destination));
            forcedReceiver = receiver;
            flushPendingTravel();
        }
    }

    /** Replay a town destination command that arrived before the scene was ready. */
    private synchronized void flushPendingTravel()throws IOException { destinations.flush(); }

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
    synchronized void enterReadyRoom(short requestId)throws IOException {
        if(profile.progress().crashSiteIncomplete())throw new IOException("Finish the tutorial through its exit first");
        if(runtime.portalMove()!=null||runtime.returnRequested())throw new IOException("Finish the current journey first");
        if(!runtime.readyRoom()){
            var returnScene=scene;
            if(!scene.map().town()){
                int saved=characters.lastTownScene(knight.id(),authRevision);
                int permanent=scenes.catalog().towns().stream().filter(map->map.id()==saved)
                    .mapToInt(org.tortodrone.core.world.MapScene::id).findFirst().orElse(scenes.catalog().entry().id());
                returnScene=scenes.destination(permanent,profile.worldId());
            }
            var before=visibleProfile();
            closeConversation();closeTownDialog();leaveChat();subscribed=entered=false;sceneGeneration++;
            runtime.departedSceneOid(scene.oid());membership.close();membership=null;
            scenes.release(scene,profile.worldId());
            // Private instances are not durable return destinations.
            scene=returnScene;
            floors.clearReport();
            temporaryItems.clear();lastSnapshot=null;reference=acknowledge=0;runtime.arrival(null);
            runtime.readyRoom(true);refreshParty(true);
            var after=visibleProfile();if(!before.equals(after))output.send(()->codec.itemChanges(oid,knight,before,after));
        }
        friendLocation=new FriendPresence("~",ReadyRoom.ZONE,0);
        output.send(()->codec.readyRoomLocation(oid));
        output.send(()->codec.readyRoomMove(oid,requestId));
    }

    void completeMove(short requestId,boolean zone)throws IOException {
        output.send(() -> codec.sceneMove(oid,requestId,zone,scene,scenes.catalog()));
        output.send(() -> codec.sceneLocation(oid,scene,scenes.channelOid(scene.map().zone(),false),scenes.channelOid(scene.map().zone(),true)));
        // Deliver the pre-arranged arrival comlink behind the moveSucceeded response: the
        // client processes it in the window where its scene controllers are gone (see the
        // arrival openComlink), so the dialog waits for the destination view like native.
        // A failed delivery falls back to the enteredPlace/publish path.
        if(arrivalComlink!=null){
            var pending=arrivalComlink;arrivalComlink=null;
            try{conversations.openArrivalComlink(pending);deliveredComlink=pending;}catch(IOException unavailable){arrivalComlink=pending;}
        }
    }

    /** Install the destination scene before the client acknowledges its move. The Ready Room
     *  uses the same path so a native `/training`, `/alc` or `/haven` command can leave it
     *  directly instead of first entering the saved town. */
    void enterScene(SceneRegistry.Instance next,ScenePortals.Entrance entrance)throws IOException {
        closeConversation();closeTownDialog();
        if(!temporaryItems.isEmpty()){var before=visibleProfile();temporaryItems.clear();output.send(()->codec.itemChanges(oid,knight,before,profile));publishQuickbar();}
        leaveChat();
        subscribed=entered=false;sceneGeneration++;occupantClients=Map.of();occupantRoles=Map.of();lastSnapshot=null;
        reference=acknowledge=0;destinations.clearAlchemyTarget();runtime.departedSceneOid(scene.oid());if(membership!=null)membership.close();if(scene!=next)scenes.release(scene,profile.worldId());scene=next;
        runtime.readyRoom(false);runtime.returnRequested(false);
        runtime.arrival(entrance);runtime.clearPortal();
        created=SceneClock.now();attach();
        monitorScene();
        // Arriving comlinks are delivered behind the move response (see completeMove),
        // before the client can answer enteredPlace from inside its loading screen.
        arrivalComlink=null;
        for(var candidate:List.of(OpeningMission.FIRST_CONTACT,OpeningMission.WELCOME_HAVEN))if(scene.map().id()==OpeningComlinks.scene(candidate)&&candidate!=deliveredComlink&&scenes.missions().eligible(candidate.id(),profile.progress(),completedOpening.keySet()))arrivalComlink=candidate;
    }

    synchronized void requireAdminReady()throws IOException { requireAdminReady(false); }
    synchronized void requireAdminReady(boolean roomTravel)throws IOException {
        if(closed||socket.isClosed()||!selected()||(!(roomTravel&&runtime.readyRoom())&&(!subscribed||!entered))||forcedReceiver==null||runtime.portalMove()!=null||deferredMovePending())throw new IOException("Finish entering this scene first");
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
        runtime.departedSceneOid(runtime.readyRoom()?0:scene.oid());leaveChat();subscribed=entered=false;sceneGeneration++;
        if(membership!=null)membership.pause();short receiver=forcedReceiver;
        output.send(()->codec.forceScene(oid,receiver,next));
    }
    AdminHandler adminHandler(){return adminTools;}

    /** Cross-session tools deliberately acquire only one session monitor at a time. */
    boolean adminPeople(SessionDecoder.Invocation request)throws IOException { return adminTools.people(request); }

    /** Stock admin service, resolved by the client from the bootstrap; every call is authorized here. */
    synchronized void admin(SessionDecoder.Invocation request) throws IOException { adminTools.dispatch(request); }

    synchronized void subscribe(short message) throws IOException {
        if (!selected()) throw new IOException("Select a knight before subscribing to a scene");
        if(runtime.readyRoom())throw new IOException("The Ready Room has no server place object");
        if(closed)throw new IOException("Session closed");
        if(runtime.portalMove()!=null)throw new IOException("Scene is departing through a portal");
        var snapshot=scene.simulation().snapshot();
        if(snapshot.players().stream().filter(actor->actor.clientOid()==oid&&actor.id()==profile.worldId()).count()!=1)
            throw new IOException("Scene has no unique owned pawn");
        if(!inputThrottleSent){
            // Town movement and service calls share the native TCP throttle queue.
            output.send(codec::inputThrottle);inputThrottleSent=true;
        }
        output.send(()->codec.scene(message,scene,snapshot.players()));
        leaveChat();lastSnapshot=null;reference=acknowledge=0;
        occupantClients=snapshot.players().stream().collect(java.util.stream.Collectors.toMap(ActorState::id,ActorState::clientOid));
        occupantRoles=snapshot.roles();subscribed=true;entered=false;sceneGeneration++;
        membership.pause();
    }

    /** The stock controller sends enteredPlace after attaching its scene-delta listener. */
    synchronized void entered() throws IOException {
        if(runtime.portalMove()!=null)return; // A source readiness RPC may already be queued before forceScene.
        if (!subscribed || closed) throw new IOException("Subscribe to town before entering it");
        if (entered) return;
        if(scene.map().town()){
            characters.lastTownScene(knight.id(),authRevision,scene.map().id());
            if(profile.progress().openingStage()>=0)output.send(()->codec.homeTown(oid,profile));
        }
        entered = true;
        friendLocation=presence(scenes.parties().view(partyMember));
        runtime.departedSceneOid(0);
        int generation=sceneGeneration,place=scene.oid();
        chats.enter(place,generation);
        if(!welcomed&&!welcomeMessage.isEmpty()) {
            welcomed=true;
            output.send(()->codec.systemNotice(place,welcomeMessage));
        }
        trade.join(place);
        lastStamp = SceneClock.now();
        var placeInstance=scene;
        membership.enter(snapshot->offer(placeInstance,generation,snapshot));
        if(scene.run()!=null&&(!scenes.authorized(scene,profile.worldId(),partyMember)||!membership.authorizeElevator(scene.run().exitKey(),Set.of(profile.worldId()))))throw new IOException("Opening mission exit is unavailable");
        arrivalComlink=null;
        for(var candidate:List.of(OpeningMission.FIRST_CONTACT,OpeningMission.WELCOME_HAVEN))if(scene.map().id()==OpeningComlinks.scene(candidate)&&candidate!=deliveredComlink&&scenes.missions().eligible(candidate.id(),profile.progress(),completedOpening.keySet()))arrivalComlink=candidate;
        if(destinations.takeAuctionAfterArrival()){try{openAuction();}catch(IOException refused){unavailable(refused.getMessage());}}
        persistence=WorldStatePoller.join(this,characters,profile,authRevision,knight.name());
        syncMissionObjective();
        flushPendingTravel();
    }
    /** The mission bar follows the owned run: the imported mission's {@code o.<id>} objective
     *  while it is active, and nothing outside a mission. */
    private void syncMissionObjective()throws IOException {
        var run=scene.run();String next=null;
        if(run!=null){var mission=scenes.missions().mission(run.missionId());if(mission!=null&&mission.requirement().hasObjective())next=run.missionId();}
        if(!java.util.Objects.equals(next,missionObjective)){missionObjective=next;publishObjectives();}
    }

    ActorState auctionSource(SceneRegistry.Instance destination)throws IOException{return auction.source(destination);}
    void openAuction()throws IOException{auction.open();}

    synchronized void unsubscribe() { leaveChat();subscribed = entered = false; sceneGeneration++;if(membership!=null)membership.pause(); }

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
    private void leaveChat(){
        friendLocation=new FriendPresence("~",0,0);
        town.reset();
        trade.leave();
        chats.leave();
        if(persistence!=null){persistence.close();persistence=null;}
    }

    synchronized void input(SessionDecoder.Input input)throws IOException { sceneInput.input(input); }

    /** Coalesce ordinary frames while retaining native data-dependent activity boundaries. */
    private void offer(SceneRegistry.Instance place,int generation,SceneSnapshot snapshot) {
        publications.offer(sceneGeneration,place,generation,snapshot);
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
    /** A scene tick failed; return this knight to character selection rather than a frozen world. */
    private void sceneFailed() {
        if(closed)return;
        log.warn("Scene tick failed; closing session for a safe return; scene="+(scene==null?0:scene.oid()));
        close();
        try{socket.close();}catch(IOException ignored){}
    }
    private void attach() {
        floors.reset();
        if(!scene.map().kind().equals("training")&&partyMember!=null)try{scenes.parties().leaveTraining(partyMember);}catch(IOException invalid){throw new IllegalStateException(invalid);}
        publishedArsenal=null;
        membership=null; // The old scene membership is closed before rebuilding its replacement.
        rebuildAppearance();var place=scene;
        membership=place.simulation().join(actorAppearance(),snapshot->{},this::sceneFailed);
        membership.equipment(visibleProfile());
        if(!place.map().town())membership.combat(weapons,scenes.equipment(),visibleProfile());
        try{partyState();}catch(IOException invalid){throw new IllegalStateException(invalid);}
    }
    private void rebuildAppearance() {
        var model=scene.map().town()?StarterCatalog.town(knight,visibleProfile(),characters.catalog()):StarterCatalog.interior(knight,visibleProfile(),characters.catalog());
        var modelArgs=new TreeMap<String,Object>(model.arguments());var visible=visibleProfile();long[] slots=visible.equipment();
        for(var item:visible.items())if(item.equippedSlot()!=null){int slot=item.equippedSlot();if(slot==0&&slots[3]!=0||slot==1&&slots[4]!=0)continue;
            for(String accessory:item.additionalAccessories()){var nativeModel=scenes.town().accessoryModel(accessory);String parameter=scenes.town().accessoryParameter(accessory);if(nativeModel!=null&&parameter!=null)modelArgs.put(parameter,nativeModel);}}
        model=new StarterCatalog.Model(model.name(),modelArgs);
        appearance=new StarterCatalog.Model(scene.map().town()?"Character/PC/Default":"Character/PC/Level",
            Map.of("Model",model,"Height",model.arguments().get("Height")));
    }
    void updateAppearance(){rebuildAppearance();if(membership!=null){membership.equipment(visibleProfile());membership.appearance(actorAppearance());}}
    private ActorState actorAppearance() {
        var checkpoint=runtime.arrival()==null?scene.simulation().defaultEntrance():null;
        return new ActorState(profile.worldId(),oid,knight.name(),256|knight.color(),created,
            runtime.arrival()==null?checkpoint==null?scene.map().spawnX():checkpoint.x():runtime.arrival().x(),runtime.arrival()==null?checkpoint==null?scene.map().spawnY():checkpoint.y():runtime.arrival().y(),
            runtime.arrival()==null?checkpoint==null?scene.map().spawnRotation():checkpoint.rotation():runtime.arrival().rotation(),0,0,0,created,appearance,gameMaster);
    }

    static long monotonicTime() { return System.nanoTime() / 1_000_000L; }
    private void monitorScene() {
        log.scene(scene.map().id(),scene.oid(),scene.map().name());
        log.info("Scene entered; scene="+scene.map().name()+" (#"+scene.map().id()+"); instanceId="+scene.oid());
    }
    /** Set once a successful rename answered its confirm; honored by the connection loop. */
    private boolean renameDisconnect;
    synchronized boolean takeRenameDisconnect(){boolean requested=renameDisconnect;renameDisconnect=false;return requested;}

    @Override public synchronized void close() {
        friendOnline=false;
        chats.leave();
        leaveChat();
        closed = true;
        floors.clearReport();
        subscribed = false;
        if(membership!=null)membership.close();
        if(partyMember!=null){partyMember.close();partyMember=null;}
        if(profile!=null){scenes.release(scene,profile.worldId());if(runtime.portalMove()!=null)scenes.release(runtime.portalMove().destination(),profile.worldId());}
        publications.stop();
        if (profile != null) synchronized (ACTIVE) { ACTIVE.remove(profile.worldId(), this); }
    }
}
