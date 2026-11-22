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
    private boolean readyRoom, returnRequested;
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
    private record PortalMove(SceneRegistry.Instance destination,ScenePortals.Entrance entrance) { }
    private PortalMove portalMove;
    /** Stock town commands may arrive from the loading screen or Ready Room; replay the
     *  native destination request once the scene has subscribed and accepted enteredPlace. */
    private SessionDecoder.Invocation pendingTravel;
    private FloorReport floorReport;
    private org.tortodrone.core.world.FloorAdvanceRequest openingAdvance;
    private boolean openingDropStarted,openingPayoutWarning;
    private int openingPayoutRetry;
    private MissionRun lastGateStart;
    private Short lastGateStartRequest;
    private ScenePortals.Entrance arrival;
    private boolean auctionAfterArrival;
    private String alchemyTarget;
    private int departedSceneOid;
    private record DeferredMove(short requestId,boolean zone,int sceneOid) { }
    private DeferredMove deferredMove;
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
        if(readyRoom)return;
        if(scene.map().town()&&portalMove==null)return;
        if(portalMove!=null&&portalMove.destination().map().town())return;
        if(portalMove!=null&&portalMove.destination()!=scene)scenes.release(portalMove.destination(),profile.worldId());
        var run=scene.run()!=null?scene.run():portalMove==null?null:portalMove.destination().run();
        if(run!=null)profile=loadProfile(profile);
        int safeTown=run==null?scenes.catalog().entry().id()
            :profile.progress().openingStage()>=2||!org.tortodrone.core.character.OpeningMission.ANCIENT_GENERATOR.id().equals(run.missionId())
                ?run.destination().id():217;
        var next=destination(safeTown);
        portalMove=new PortalMove(next,new ScenePortals.Entrance(1,next.map().spawnX(),next.map().spawnY(),next.map().spawnRotation()));
        deferredMove=null;departedSceneOid=scene.oid();leaveChat();subscribed=entered=false;sceneGeneration++;membership.pause();
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
    private void publishObjectives()throws IOException {
        var wire=wireObjectives();output.send(()->codec.objectives(oid,wire));
    }
    private WorldProfile visibleProfile(){
        return visibleProfile(membership==null?Set.of():membership.status().cursedItems());
    }
    private WorldProfile visibleProfile(Set<Long> cursed){
        if(temporaryItems.isEmpty()&&cursed.isEmpty())return profile;
        var items=new ArrayList<>(profile.items());items.addAll(temporaryItems.values());
        items.replaceAll(item->item.curse(cursed.contains(item.id())));
        return new WorldProfile(profile.worldId(),profile.accountId(),profile.knightId(),profile.crowns(),profile.energy(),profile.revision(),items,profile.progress(),profile.town(),profile.achievements());
    }
    private void collectPickups()throws IOException {
        for(var pickup:membership.takePickups()){
            boolean success=false;
            try{
                if(pickup.heat()>0){success=membership.collectHeat(pickup.entityId());continue;}
                var before=visibleProfile();
                if(pickup.temporary()){
                    if(pickup.equippedSlot()!=null){
                        var existing=before.items().stream().filter(item->pickup.equippedSlot().equals(item.equippedSlot())).findFirst().orElse(null);
                        var stats=scene.map().content().stats();
                        if(existing!=null&&stats.getOrDefault("gear."+existing.config()+".health-bonus",0f)>=stats.getOrDefault("gear."+pickup.item()+".health-bonus",0f))throw new IOException("A better vitapod is equipped");
                        if(existing!=null&&existing.id()>0)throw new IOException("That equipment slot is occupied");
                        if(existing!=null)temporaryItems.remove(existing.id());
                        long id=nextTemporaryItem--;temporaryItems.put(id,new WorldProfile.Item(id,WorldProfile.Slot.OTHER,pickup.item(),null,0,pickup.equippedSlot(),false,false,false,1,false));
                        updateAppearance();
                    }else {
                    var existing=temporaryItems.values().stream().filter(item->item.config().equals(pickup.item())).findFirst().orElse(null);
                    // One carried stack per consumable, three uses per stack, four carried types.
                    // Refusal leaves the shared pickup available to another knight.
                    if(existing!=null&&existing.count()>=3||existing==null&&temporaryItems.values().stream().filter(item->item.equippedSlot()==null).count()>=quickbar.length)
                        throw new IOException("Consumable capacity reached");
                    long id=existing==null?nextTemporaryItem--:existing.id();
                    int count=existing==null?1:existing.count()+1;
                    temporaryItems.put(id,new WorldProfile.Item(id,WorldProfile.Slot.OTHER,pickup.item(),null,0,null,false,false,false,count,false));
                    if(Arrays.stream(quickbar).noneMatch(value->value==id))for(int slot=0;slot<quickbar.length;slot++)if(quickbar[slot]==0){quickbar[slot]=id;break;}
                    }
                }else{
                    var reward=characters.services().town(scenes.town()).pickup(characters.accountId(),knight.id(),authRevision,membership.pickupReceipt(pickup.entityId()),pickup.item());
                    // A notification failure must not reopen a durably awarded actor.
                    success=true;
                    townChanged(reward,false);
                    continue;
                }
                var after=visibleProfile();output.send(()->codec.itemChanges(oid,knight,before,after));publishQuickbar();success=true;
            }catch(IOException failure){log.debug("Scene pickup refused: "+failure.getMessage());}
            finally{membership.completePickup(pickup.entityId(),success);}
        }
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
    MissionRun preflightMissionRoute(String missionId,String difficulty)throws IOException {
        if(closed||!selected()||forcedReceiver==null||portalMove!=null||deferredMove!=null||scene.map().kind().equals("level")
            ||!readyRoom&&(!entered||!subscribed||membership==null||membership.health().current()<=0))throw new IOException("m.internal_error");
        if(trade.active())throw new IOException("Finish the trade first");
        profile=loadProfile(profile);completedOpening=characters.services().openingMissions(scenes.town(),scenes.missions()).load(characters.accountId(),knight.id(),authRevision);
        var route=scenes.missionRoute(missionId,profile.worldId(),partyOid(),difficulty);
        if(route==null||!scenes.missions().eligible(missionId,profile.progress(),completedOpening.keySet()))throw new IOException("m.internal_error");
        scenes.parties().soloGate(partyMember,partyOid(),()->Boolean.TRUE);return route;
    }
    OpeningGate preflightOpeningGate(OpeningMission mission)throws IOException {
        if(closed||!selected()||forcedReceiver==null||portalMove!=null||deferredMove!=null||scene.map().id()!=217||!scene.map().town()
            ||!readyRoom&&(!entered||!subscribed||membership==null||membership.health().current()<=0))throw new IOException("m.internal_error");
        if(trade.active())throw new IOException("Finish the trade first");
        profile=loadProfile(profile);completedOpening=characters.services().openingMissions(scenes.town(),scenes.missions()).load(characters.accountId(),knight.id(),authRevision);
        var gate=scenes.openingGate(mission);
        if(gate==null||!scenes.missions().eligible(mission.id(),profile.progress(),completedOpening.keySet()))throw new IOException("m.internal_error");
        scenes.parties().soloGate(partyMember,partyOid(),()->Boolean.TRUE);return gate;
    }
    /** A retried start for the same listener and live run is answered without a second lease. */
    boolean sameGateStart(String missionId,String difficulty,short requestId){
        var active=portalMove!=null&&portalMove.destination().run()!=null?portalMove.destination():scene;
        return !closed&&lastGateStartRequest!=null&&requestId==lastGateStartRequest&&active.run()==lastGateStart
            &&lastGateStart.missionId().equals(missionId)&&lastGateStart.difficulty().equals(difficulty)
            &&scenes.authorized(active,profile.worldId(),partyMember);
    }
    /** Commit, acknowledge and force the move in that native order. */
    void beginMissionRun(MissionRun run,ScenePortals.Entrance start,short requestId)throws IOException {
        var next=scenes.startRun(run,profile.worldId(),partyMember);
        lastGateStart=run;lastGateStartRequest=requestId;
        closeConversation();closeTownDialog();portalMove=new PortalMove(next,start);departedSceneOid=scene.oid();
        partyState();leaveChat();subscribed=entered=false;sceneGeneration++;if(membership!=null)membership.pause();
        short receiver=forcedReceiver;
        output.send(()->codec.invocationConfirmed(oid,requestId));output.send(()->codec.forceScene(oid,receiver,next));
    }
    void settleMissionExchange(String missionId,long[] itemIds)throws IOException {
        profile=loadProfile(profile);var before=profile;boolean firstEver=completedOpening.isEmpty();
        var settled=characters.services().openingMissions(scenes.town(),scenes.missions()).exchange(characters.accountId(),knight.id(),authRevision,profile,missionId,itemIds);
        profile=settled.profile();
        if(!settled.completion().equals(completedOpening.get(missionId))){var completed=new LinkedHashMap<>(completedOpening);completed.put(missionId,settled.completion());completedOpening=Map.copyOf(completed);}
        if(settled.first()){
            membership.equipment(visibleProfile());missionDialogCreated=conversations.touch();
            output.send(()->codec.missionSettlement(oid,before,profile,settled.completion(),scenes.missions().grants(missionId),scenes.missions().prestige(missionId),true,missionDialogCreated));
            output.send(()->codec.itemChanges(oid,knight,before,profile));
            if(firstEver)output.send(()->codec.controllerAction(oid,"new_mission",null));partyState();
        }
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
    private static String friendUnjoinable(String name){return "%social:m.friend_unjoinable|~"+name;}
    /** Native method 3 in the current build: relationship-authorized travel, rechecked after the peer is captured. */
    void joinFriend(String name)throws IOException {
        int target;
        synchronized(this){requireFriendReady();target=friendNamed(name);}
        WorldSession peer;
        synchronized(ACTIVE){peer=ACTIVE.get(target);}
        if(peer==null||peer==this||peer.scenes!=scenes||!peer.characters.worldStorageFile().equals(characters.worldStorageFile()))throw new IOException(friendUnjoinable(name));
        // Like archive joinPlayerInstance: capture the ready target, then transfer on the
        // caller. Never acquire both session monitors; recheck presence before departure.
        var location=peer.adminLocation();var presence=peer.friendLocation;
        var peerMember=peer.partyMemberSnapshot();
        synchronized(this){
            requireFriendReady();if(friendNamed(name)!=target||!peer.friendOnline||peer.socket.isClosed()||presence!=peer.friendLocation
                    ||location.scene().oid()!=presence.instance())throw new IOException(friendUnjoinable(name));
            if(location.scene().map().town()){if(scene!=location.scene())adminTravel(location);return;}
            followIntoInterior(location,peerMember,true);
        }
    }
    /** Native method 3: the per-session join secret authorizes travel without a friendship. */
    void joinBySecret(String secret,boolean spectate)throws IOException {
        synchronized(this){requireFriendReady();}
        int comma=secret.lastIndexOf(',');
        if(comma<1||secret.length()-comma-1!=16)throw new IOException("%social:m.internal_error");
        long claimed;
        try{claimed=Long.parseUnsignedLong(secret.substring(comma+1),16);}catch(NumberFormatException invalid){throw new IOException("%social:m.internal_error");}
        String name=secret.substring(0,comma);
        if(name.equalsIgnoreCase(knight.name()))throw new IOException("%social:m.friend_self");
        List<WorldSession> candidates;
        synchronized(ACTIVE){
            candidates=ACTIVE.values().stream().filter(s->s.friendOnline&&!s.socket.isClosed()&&s.scenes==scenes
                &&s.characters.hasWorldStorage()&&s.characters.worldStorageFile().equals(characters.worldStorageFile())).toList();
        }
        WorldSession peer=null;int target=0;
        for(var candidate:candidates){
            var identity=candidate.identity();
            if(identity!=null&&identity.knight().equalsIgnoreCase(name)){peer=candidate;target=identity.worldId();break;}
        }
        // A forged secret must not learn whether the knight it names is joinable.
        if(peer==null||peer.externalSecret!=claimed)throw new IOException(friendUnjoinable(name));
        // Neither shipped 2026 sender sets spectate; the archive refuses a spectator through
        // joinability anyway, so the flag is carried and has no separate destination here.
        if(spectate&&log.debugEnabled())log.debug("joinOrSpectate arrived with spectate=true; joining as usual");
        var location=peer.adminLocation();var presence=peer.friendLocation;
        var peerMember=peer.partyMemberSnapshot();
        synchronized(this){
            requireFriendReady();
            if(peer!=ACTIVE.get(target)||!peer.friendOnline||peer.socket.isClosed()||presence!=peer.friendLocation
                    ||location.scene().oid()!=presence.instance())throw new IOException(friendUnjoinable(name));
            if(location.scene().map().town()){if(scene!=location.scene())adminTravel(location);return;}
            followIntoInterior(location,peerMember,friendsWithParty(peerMember));
        }
    }
    /** A secret proves consent to travel, not friendship; private parties still admit friends of a member. */
    private boolean friendsWithParty(PartyRegistry.Member member)throws IOException {
        var view=scenes.parties().view(member);
        var friends=characters.services().friends().list(characters.accountId(),knight.id(),authRevision);
        return view.members().stream().anyMatch(p->p.worldId()!=profile.worldId()
            &&friends.stream().anyMatch(f->f.world()==p.worldId()));
    }
    /** Follows a friend into a shared Training party room, rolling the party seat back if travel fails. */
    private void followIntoInterior(AdminLocation location,PartyRegistry.Member peerMember,boolean friend)throws IOException {
        boolean joined=false;
        try{
            joined=scenes.parties().joinParty(partyMember,peerMember,friend);
            refreshParty(true);
            if(scene!=location.scene())adminTravel(location);
        }catch(IOException|RuntimeException failed){
            if(joined)try{scenes.parties().abandon(partyMember);}catch(IOException ignored){}
            if(failed instanceof IOException io)throw io;
            throw new IOException("m.internal_error",failed);
        }
    }
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
    private void requireFriendReady()throws IOException {
        if(closed||!selected()||!entered||!subscribed||portalMove!=null||deferredMove!=null)throw new IOException("Enter a scene first");
        try{characters.worldProfile(knight.name(),authRevision,profile);}catch(CharacterStore.Refused refused){throw new IOException("Knight changed; reconnect",refused);}
    }
    private int friendNamed(String name)throws IOException {
        return characters.services().friends().list(characters.accountId(),knight.id(),authRevision).stream().filter(p->p.name().equalsIgnoreCase(name))
            .mapToInt(org.tortodrone.storage.FriendStore.Peer::world).findFirst().orElseThrow(()->new IOException("m.internal_error"));
    }
    long externalSecret(){return externalSecret;}
    boolean lookingForGroup(){return lookingForGroup;}
    private record Identity(int worldId,String knight) { }
    /** Snapshot taken under the session monitor, never under ACTIVE (close locks in that order). */
    private synchronized Identity identity(){return knight==null||profile==null?null:new Identity(profile.worldId(),knight.name());}
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
    private void closeConversation()throws IOException { conversations.close(); }
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
    }

    static final String RHENDON_CAMP="Character/NPC/Rescue Camp/Rhendon, Rescue Camp";
    synchronized boolean selected() { return knight != null; }
    boolean selectedKnightSelected(){return selected();}
    boolean equipmentAllowed(){return membership!=null&&membership.equipmentAllowed();}
    String selectedKnightName(){return knight==null?null:knight.name();}
    boolean inspectionHiddenValue(){return inspectionHidden;}
    int quickbarLength(){return quickbar.length;}
    boolean carriesConsumable(long id){return temporaryItems.containsKey(id)&&temporaryItems.get(id).equippedSlot()==null;}
    void clearQuickbarItem(long item){for(int index=0;index<quickbar.length;index++)if(quickbar[index]==item)quickbar[index]=0;}
    void quickbarSlot(int slot,long item){quickbar[slot]=item;}
    InspectionHandler.Inspection inspectionNow(SceneRegistry.Instance place)throws IOException{return inspection.snapshot(place);}
    SessionResponder responder(){return responder;}
    SessionOutput output(){return output;}
    SessionCodec codec(){return codec;}
    Socket socket(){return socket;}
    boolean sceneEntered(){return entered;}
    boolean sceneSubscribed(){return subscribed;}
    boolean inSubscribedScene(){return !closed&&selected()&&entered&&subscribed;}
    boolean sceneTravelPending(){return portalMove!=null;}
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
    synchronized boolean inReadyRoom(){return readyRoom;}
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
    synchronized boolean ownsScene(int oid) { return selected() && !readyRoom && scene.oid()==oid; }
    boolean partyInvocation(SessionDecoder.Invocation request)throws IOException{return parties.invocation(request);}
    synchronized boolean ownsInteriorParty(int target){return parties.ownsInterior(target);}
    /** Cross-session friend follow only reads the peer's stable party seat under its own monitor. */
    synchronized PartyRegistry.Member partyMemberSnapshot(){return partyMember;}
    void subscribeInteriorParty(short message)throws IOException{parties.subscribeInterior(message);}
    synchronized void unsubscribeInteriorParty(int party){parties.unsubscribeInterior(party);}
    synchronized boolean interiorPartyInvocation(SessionDecoder.Invocation request)throws IOException{return parties.interiorInvocation(request);}
    org.tortodrone.core.world.SceneInstanceView sceneNowForParty(){return portalMove!=null?portalMove.destination():scene;}
    void completeDeferredPartyMove()throws IOException {
        if(deferredMove!=null&&deferredMove.sceneOid()==scene.oid()){
            var move=deferredMove;deferredMove=null;completeMove(move.requestId(),move.zone());
        }
    }
    void publishedHealthStatus(SceneHealth.State health,SceneStatus.State status){publishedHealth=health;publishedStatus=status;}
    /** Publish the current equip policy on the ordered stream before an arsenal window opens. */
    void publishArsenalPolicy()throws IOException {
        boolean inZone=membership!=null&&membership.arsenalZone();
        output.send(()->codec.equipPolicy(oid,inZone));publishedArsenal=inZone;
    }
    FloorReport floorReport(){return floorReport;}
    boolean sceneTutorial(){return scene.tutorial()!=null;}
    MissionRun sceneRun(){return scene.run();}
    synchronized boolean departingScene(int oid) {
        // Returning from the client-local Ready Room can reuse the reserved town OID.
        // Once that place is subscribed again its enteredPlace is current, not stale.
        return !closed&&departedSceneOid!=0&&departedSceneOid==oid
            &&(readyRoom||portalMove!=null||!subscribed||scene.oid()!=oid);
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
        readyRoom=stageInReadyRoom;
        if(readyRoom)friendLocation=new FriendPresence("~",ReadyRoom.ZONE,0);
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
        if(!readyRoom)monitorScene();
        // Party events belong to the selected session, not its scene subscription.
        // Ready Room is client-local and never calls entered(); delaying this worker
        // until then strands invitations and can overflow the queue on world return.
        publications.start("SceneDelivery-"+oid,this::publish,this::deliverParty,failure->{
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
            var destination=portalMove==null?scene:portalMove.destination();
            if(readyRoom&&!returnRequested)output.send(()->codec.forceDestination(oid,receiver,ReadyRoom.ZONE,ReadyRoom.SCENE));
            else output.send(() -> codec.forceScene(oid, receiver,destination));
            forcedReceiver = receiver;
            flushPendingTravel();
        }
    }

    /** Replay a town destination command that arrived before the scene was ready. */
    private synchronized void flushPendingTravel()throws IOException {
        if(pendingTravel==null||closed||!selected()||forcedReceiver==null||portalMove!=null||deferredMove!=null)return;
        if(!readyRoom&&(!entered||!subscribed))return;
        var request=pendingTravel;pendingTravel=null;
        log.debug("Replaying queued town destination after scene entry");
        invocation(request);
    }

    synchronized boolean invocation(SessionDecoder.Invocation request) throws IOException {
        if(closed||socket.isClosed())throw new IOException("Session is closed");
        int service = request.service();
        var args = request.arguments();
        if(service==SessionCodec.KNIGHT_SERVICE&&request.method()==11){
            if(!args.isEmpty())throw new SessionDecoder.Rejected("Invalid return-to-last request");
            if(!selected()||forcedReceiver==null)throw new SessionDecoder.Rejected("Select a knight first");
            try{characters.worldProfile(knight.name(),authRevision,profile);}
            catch(CharacterStore.Refused refused){throw new IOException("Knight changed; reconnect",refused);}
            if(readyRoom){returnRequested=true;short receiver=forcedReceiver;output.send(()->codec.forceScene(oid,receiver,scene));}
            return true;
        }
        if(service==SessionCodec.KNIGHT_SERVICE&&request.method()==7){
            if(args.size()!=2||!(args.get(0) instanceof String destination)||!(args.get(1) instanceof SessionDecoder.Listener listener)||!listener.type().endsWith("$ResultMarshaller"))throw new SessionDecoder.Rejected("Invalid town destination request");
            try{
                if(!selected())throw new IOException("Select a knight first");
                if(forcedReceiver==null||portalMove!=null||(!readyRoom&&(!entered||!subscribed))){
                    if(pendingTravel!=null&&pendingTravel!=request&&pendingTravel.arguments().getLast() instanceof SessionDecoder.Listener old) {
                        short oldId=old.requestId();output.send(()->codec.invocationFailure(oid,oldId,"Travel request replaced"));
                    }
                    pendingTravel=request;
                    log.debug("Queued town destination; destination="+destination+"; entered="+entered+"; subscribed="+subscribed
                        +"; receiver="+forcedReceiver+"; portal="+(portalMove!=null)+"; readyRoom="+readyRoom);
                    return true;
                }
                characters.worldProfile(knight.name(),authRevision,profile);
                org.tortodrone.core.world.MapScene map=null;ScenePortals.Entrance entrance=null;AlchemyDestination alchemy=null;
                if(destination.equals("move_alchemy_machine")){
                    alchemy=selectAlchemy();if(alchemy!=null){entrance=alchemy.entrance();map=scene.map();}
                }
                if(destination.equals("haven"))map=scenes.catalog().entry();
                if(map==null){
                    int candidate=switch(destination){case "training"->294;case "lab"->30893;case "hoh"->318;default->-1;};
                    if(candidate>0&&scenes.catalog().contains(candidate))map=scenes.catalog().scene(candidate);
                    if(map==null)map=scenes.catalog().townByName("%town:m."+destination);
                }
                if(map==null&&destination.matches("[a-zA-Z0-9_-]{1,96}"))for(var candidate:scenes.catalog().towns()){
                    String prefix="destination."+destination+".";var stats=candidate.content().stats();if(!stats.containsKey(prefix+"x"))continue;
                    float x=stats.get(prefix+"x"),y=stats.get(prefix+"y");if(!candidate.collision().walkable(x,y))continue;
                    if(candidate.portals().triggers().stream().anyMatch(t->t.intersects(x,y)))continue;
                    map=candidate;entrance=new ScenePortals.Entrance(Math.round(stats.get(prefix+"id")),x,y,stats.getOrDefault(prefix+"rotation",0f));break;
                }
                if(map==null)throw new IOException("%chat:m.no_such_scene");
                var next=destination(map.id());
                if(entrance==null)entrance=new ScenePortals.Entrance(1,map.spawnX(),map.spawnY(),map.spawnRotation());
                if(!next.simulation().placement(entrance.x(),entrance.y()))throw new IOException("Destination is blocked");
                if(destination.equals("move_auction"))auctionSource(next);
                if(readyRoom){
                    auctionAfterArrival=destination.equals("move_auction");
                    short receiver=forcedReceiver;
                    enterScene(next,entrance);
                    if(alchemy!=null)alchemyTarget=alchemy.key();
                    output.send(()->codec.forceScene(oid,receiver,next));output.send(()->codec.townResult(oid,listener.requestId(),"",profile));
                    return true;
                }
                if(next==scene){
                    if(!membership.teleport(entrance.x(),entrance.y(),entrance.rotation()))throw new IOException("Destination is blocked or the knight is busy");
                    if(alchemy!=null)alchemyTarget=alchemy.key();
                    closeConversation();closeTownDialog();
                    if(destination.equals("move_auction"))openAuction();
                    output.send(()->codec.townResult(oid,listener.requestId(),"",profile));return true;
                }
                auctionAfterArrival=destination.equals("move_auction");
                closeConversation();closeTownDialog();portalMove=new PortalMove(next,entrance);departedSceneOid=scene.oid();leaveChat();subscribed=entered=false;sceneGeneration++;membership.pause();short receiver=forcedReceiver;
                output.send(()->codec.forceScene(oid,receiver,next));output.send(()->codec.townResult(oid,listener.requestId(),"",profile));
            }catch(CharacterStore.Refused|IOException refused){output.send(()->codec.invocationFailure(oid,listener.requestId(),refused.getMessage()));}return true;
        }
        // KnightMarshaller 11 is the stock /haven travel (EN()); it carries no arguments or listener,
        // so failures are reported through the native chat-feedback path.
        if(service==SessionCodec.KNIGHT_SERVICE&&request.method()==11){
            if(!args.isEmpty())throw new SessionDecoder.Rejected("Invalid haven request");
            try{
                if(!selected()||forcedReceiver==null||portalMove!=null)throw new IOException("Finish entering this scene first");characters.worldProfile(knight.name(),authRevision,profile);
                var map=scenes.catalog().entry();var next=destination(map.id());
                var entrance=new ScenePortals.Entrance(1,map.spawnX(),map.spawnY(),map.spawnRotation());
                if(readyRoom){
                    short receiver=forcedReceiver;
                    enterScene(next,entrance);
                    output.send(()->codec.forceScene(oid,receiver,next));
                    return true;
                }
                if(!entered||!subscribed)throw new IOException("Finish entering this scene first");
                if(next==scene)throw new IOException("You are already in that scene");
                closeConversation();closeTownDialog();portalMove=new PortalMove(next,entrance);departedSceneOid=scene.oid();leaveChat();subscribed=entered=false;sceneGeneration++;membership.pause();short receiver=forcedReceiver;
                output.send(()->codec.forceScene(oid,receiver,next));
            }catch(CharacterStore.Refused|IOException refused){unavailable(refused.getMessage());}
            return true;
        }
        if(service==SessionCodec.KNIGHT_SERVICE&&request.method()==13) {
            if(args.size()!=1||!(args.getFirst() instanceof Boolean hidden))throw new SessionDecoder.Rejected("Invalid inspect privacy request");
            characters.inspectionHidden(authRevision,hidden);inspectionHidden=hidden;
            gameMaster=roles.gameMaster();if(membership!=null)membership.appearance(actorAppearance());
            output.send(()->codec.playerFlags(oid,gameMaster,inspectionHidden,profile.progress().tutorialComplete()));return true;
        }
        if ((service == SessionCodec.SCENE_SERVICE || service == SessionCodec.ZONE_SERVICE) && request.method() == 1) {
            move(args, service == SessionCodec.ZONE_SERVICE);
            return true;
        }
        if(news.invocation(request))return true;
        if(loadouts.invocation(request))return true;
        if(equipment.invocation(request,args))return true;
        // ItemMarshaller 1 (Trash) is dispatched in townInvocation; scene item use is 14 only.
        if(itemUse.invocation(request,args))return true;
        return false;
    }

    void inspect(SessionDecoder.Invocation request)throws IOException { inspection.inspect(request); }

    private void move(List<Object> args, boolean zone) throws IOException {
        long transitionStarted=System.nanoTime();
        int count = zone ? 4 : 3;
        if (args.size() != count || !(args.get(count - 1) instanceof SessionDecoder.Listener listener)
                || !listener.type().endsWith(zone ? "$ZoneMoveMarshaller" : "$SceneMoveMarshaller")) {
            throw new SessionDecoder.Rejected("Invalid scene move listener");
        }
        String failure = null;
        if (!selected()) failure = "Select a knight first";
        else if(deferredMove!=null)failure="A scene move is already pending";
        else if (!(args.get(0) instanceof Integer target) || !(args.get(1) instanceof Integer)) {
            failure = "Invalid scene move request";
        }
        if (failure != null) {
            String reason = failure;
            output.send(() -> codec.invocationFailure(oid, listener.requestId(), reason));
            return;
        }
        try {
            try{characters.worldProfile(knight.name(),authRevision,profile);}
            catch(CharacterStore.Refused refused){throw new IOException("Knight changed; reconnect",refused);}
            int target=zone?(Integer)args.get(1):(Integer)args.get(0);
            if(zone&&args.get(0).equals(ReadyRoom.ZONE)){
                if(target!=ReadyRoom.SCENE||!(args.get(2) instanceof Integer))throw new IOException("%chat:m.no_such_scene");
                if(profile.progress().crashSiteIncomplete())throw new IOException("Finish the tutorial through its exit first");
                if(portalMove!=null||returnRequested)throw new IOException("Finish the current journey first");
                if(!readyRoom){
                    var returnScene=scene;
                    if(!scene.map().town()){
                        int saved=characters.lastTownScene(knight.id(),authRevision);
                        int permanent=scenes.catalog().towns().stream().filter(map->map.id()==saved)
                            .mapToInt(org.tortodrone.core.world.MapScene::id).findFirst().orElse(scenes.catalog().entry().id());
                        returnScene=scenes.destination(permanent,profile.worldId());
                    }
                    var before=visibleProfile();
                    closeConversation();closeTownDialog();leaveChat();subscribed=entered=false;sceneGeneration++;
                    departedSceneOid=scene.oid();membership.close();membership=null;
                    scenes.release(scene,profile.worldId());
                    // Private instances are not durable return destinations.
                    scene=returnScene;
                    floorReport=null;
                    temporaryItems.clear();lastSnapshot=null;reference=acknowledge=0;arrival=null;
                    readyRoom=true;refreshParty(true);
                    var after=visibleProfile();if(!before.equals(after))output.send(()->codec.itemChanges(oid,knight,before,after));
                }
                friendLocation=new FriendPresence("~",ReadyRoom.ZONE,0);
                output.send(()->codec.readyRoomLocation(oid));
                output.send(()->codec.readyRoomMove(oid,listener.requestId()));
                return;
            }
            if(readyRoom&&portalMove==null&&(!returnRequested||target!=scene.map().id()))throw new IOException("Return to game before entering a scene");
            if(profile.progress().openingStage()>=0&&profile.progress().openingStage()<3&&portalMove==null&&target!=scene.map().id())throw new IOException("Continue the opening adventure through its mission or exit");
            if(scene.tutorial()!=null&&target!=scene.map().id()&&portalMove==null)throw new IOException("Finish the tutorial through its exit first");
            var targetMap=scenes.catalog().scene(target);
            if(zone && (!(args.get(2) instanceof Integer) || !args.get(0).equals(targetMap.zone())))
                throw new IOException("Scene does not belong to that zone");
            if(portalMove!=null&&target!=portalMove.destination().map().id())throw new IOException("Finish the current portal journey first");
            var next=portalMove==null?destination(target):portalMove.destination();
            if(portalMove!=null&&next!=portalMove.destination())throw new IOException("Finish the current portal journey first");
            if(!scenes.authorized(next,profile.worldId(),partyMember))throw new IOException("Party membership changed");
            if(next!=scene||portalMove!=null||readyRoom) enterScene(next,portalMove==null?null:portalMove.entrance());
            int party=partyOid();
            // The stock PartyDirector resubscribes on every partyOid event,
            // even when its value is unchanged. Existing HUD listeners retain
            // the previous party object if a second snapshot replaces it.
            refreshParty(true);
            // The stock dungeon HUD creates its quickbar once, while the scene
            // model is attached. Let its party subscription complete first.
            if(scene.map().town()||parties.subscribedOid()==party)completeMove(listener.requestId(),zone);
            else deferredMove=new DeferredMove(listener.requestId(),zone,scene.oid());
            log.debug("Scene transition initialized; duration-ms="+((System.nanoTime()-transitionStarted)/1000000));
        } catch(IOException error) {
            output.send(() -> codec.invocationFailure(oid,listener.requestId(),error.getMessage()));
        }
    }

    private void completeMove(short requestId,boolean zone)throws IOException {
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
    private void enterScene(SceneRegistry.Instance next,ScenePortals.Entrance entrance)throws IOException {
        closeConversation();closeTownDialog();
        if(!temporaryItems.isEmpty()){var before=visibleProfile();temporaryItems.clear();output.send(()->codec.itemChanges(oid,knight,before,profile));publishQuickbar();}
        leaveChat();
        subscribed=entered=false;sceneGeneration++;occupantClients=Map.of();occupantRoles=Map.of();lastSnapshot=null;
        reference=acknowledge=0;alchemyTarget=null;departedSceneOid=scene.oid();if(membership!=null)membership.close();if(scene!=next)scenes.release(scene,profile.worldId());scene=next;
        readyRoom=returnRequested=false;
        arrival=entrance;portalMove=null;
        created=SceneClock.now();attach();
        monitorScene();
        // Arriving comlinks are delivered behind the move response (see completeMove),
        // before the client can answer enteredPlace from inside its loading screen.
        arrivalComlink=null;
        for(var candidate:List.of(OpeningMission.FIRST_CONTACT,OpeningMission.WELCOME_HAVEN))if(scene.map().id()==OpeningComlinks.scene(candidate)&&candidate!=deliveredComlink&&scenes.missions().eligible(candidate.id(),profile.progress(),completedOpening.keySet()))arrivalComlink=candidate;
    }

    record AdminLocation(SceneRegistry.Instance scene,int world,String name,float x,float y,float rotation) { }
    synchronized void requireAdminReady()throws IOException { requireAdminReady(false); }
    synchronized void requireAdminReady(boolean roomTravel)throws IOException {
        if(closed||socket.isClosed()||!selected()||(!(roomTravel&&readyRoom)&&(!subscribed||!entered))||forcedReceiver==null||portalMove!=null||deferredMove!=null)throw new IOException("Finish entering this scene first");
        try{characters.worldProfile(knight.name(),authRevision);}catch(CharacterStore.Refused refused){throw new IOException("Knight changed; reconnect",refused);}
        if(!roles.gameMaster())throw new IOException("Game Master permission required");
    }
    WorldSession adminTarget(String name)throws IOException {
        if(name.length()<3||name.length()>16||!name.matches("[A-Za-z]+(?:-[A-Za-z]+)*"))throw new IOException("Enter a valid knight name");
        synchronized(ACTIVE){return ACTIVE.values().stream().filter(w->w.knight.name().equalsIgnoreCase(name)
            &&w.scenes==scenes&&w.characters.worldStorageFile().equals(characters.worldStorageFile())).findFirst().orElseThrow(()->new IOException("That knight is not online"));}
    }
    synchronized AdminLocation adminLocation()throws IOException {
        if(closed||socket.isClosed()||!selected()||!subscribed||!entered||portalMove!=null)throw new IOException("That knight is changing scenes");
        try{characters.worldProfile(knight.name(),authRevision);}catch(CharacterStore.Refused refused){throw new IOException("That knight is no longer online",refused);}
        var actor=scene.simulation().snapshot().players().stream().filter(a->a.id()==profile.worldId()).findFirst().orElseThrow(()->new IOException("That knight is not in a ready scene"));
        return new AdminLocation(scene,profile.worldId(),knight.name(),actor.x(),actor.y(),actor.rotation());
    }
    synchronized void adminTravel(AdminLocation location)throws IOException {
        if(closed||socket.isClosed()||!selected()||(!readyRoom&&(!subscribed||!entered))||forcedReceiver==null||portalMove!=null||deferredMove!=null)throw new IOException("Finish entering this scene first");
        try{characters.worldProfile(knight.name(),authRevision);}catch(CharacterStore.Refused refused){throw new IOException("Knight changed; reconnect",refused);}
        if(trade.active())throw new IOException("Finish the trade first");
        var next=location.scene();
        if(!next.map().town()) {
            var owned=destination(next.map().id());
            if(owned!=next){if(owned!=scene)scenes.release(owned,profile.worldId());throw new IOException("That private instance belongs to another knight or party");}
            if(!scenes.authorized(next,profile.worldId(),partyMember))throw new IOException("Party membership changed");
        }
        if(!next.simulation().placement(location.x(),location.y()))throw new IOException("Destination is blocked");
        if(next==scene&&!readyRoom){
            if(!membership.teleport(location.x(),location.y(),location.rotation()))throw new IOException("Destination is blocked or the knight is busy");
            closeConversation();closeTownDialog();return;
        }
        closeConversation();closeTownDialog();
        portalMove=new PortalMove(next,new ScenePortals.Entrance(1,location.x(),location.y(),location.rotation()));
        departedSceneOid=readyRoom?0:scene.oid();leaveChat();subscribed=entered=false;sceneGeneration++;if(membership!=null)membership.pause();short receiver=forcedReceiver;
        output.send(()->codec.forceScene(oid,receiver,next));
    }
    /** Cross-session tools deliberately acquire only one session monitor at a time. */
    boolean adminPeople(SessionDecoder.Invocation request)throws IOException { return adminTools.people(request); }

    /** Stock admin service, resolved by the client from the bootstrap; every call is authorized here. */
    synchronized void admin(SessionDecoder.Invocation request) throws IOException { adminTools.dispatch(request); }

    synchronized void subscribe(short message) throws IOException {
        if (!selected()) throw new IOException("Select a knight before subscribing to a scene");
        if(readyRoom)throw new IOException("The Ready Room has no server place object");
        if(closed)throw new IOException("Session closed");
        if(portalMove!=null)throw new IOException("Scene is departing through a portal");
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
        if(portalMove!=null)return; // A source readiness RPC may already be queued before forceScene.
        if (!subscribed || closed) throw new IOException("Subscribe to town before entering it");
        if (entered) return;
        if(scene.map().town()){
            characters.lastTownScene(knight.id(),authRevision,scene.map().id());
            if(profile.progress().openingStage()>=0)output.send(()->codec.homeTown(oid,profile));
        }
        entered = true;
        friendLocation=presence(scenes.parties().view(partyMember));
        departedSceneOid=0;
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
        if(auctionAfterArrival){auctionAfterArrival=false;try{openAuction();}catch(IOException refused){unavailable(refused.getMessage());}}
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

    private record AlchemyDestination(String key,ScenePortals.Entrance entrance) { }
    /** Archive TudeySceneManager.getPortalTransform picks a random tagged destination.
     * Avoid the last successful station when another usable station is available. */
    private AlchemyDestination selectAlchemy()throws IOException {
        var machines=scene.map().content().entries().stream()
            .filter(e->e.spec().text("town-kind","").equals("OpenCrafter")&&e.spec().config().name().equals("Shop/Haven/Alchemy"))
            .toList();
        if(machines.isEmpty())return null;
        var candidates=new ArrayList<>(machines);
        Collections.shuffle(candidates);
        if(machines.size()>1&&alchemyTarget!=null){
            var previous=candidates.stream().filter(e->e.key().equals(alchemyTarget)).findFirst().orElse(null);
            if(previous!=null){candidates.remove(previous);candidates.add(previous);}
        }
        for(var machine:candidates){
            // The actor's facing identifies its front. Validate the complete pawn placement,
            // including live props, before relocating; never land inside the machine.
            for(float distance:new float[]{1.7f,2f,2.5f,3f}){
                float x=machine.x()+(float)Math.cos(machine.rotation())*distance;
                float y=machine.y()+(float)Math.sin(machine.rotation())*distance;
                if(scene.simulation().placement(x,y)){
                    return new AlchemyDestination(machine.key(),new ScenePortals.Entrance(1,x,y,(float)(machine.rotation()+Math.PI)));
                }
            }
        }
        throw new IOException("Alchemy machines are blocked");
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
    synchronized void unavailable(String reason)throws IOException {
        if(!closed&&selected()&&subscribed&&entered)chatFeedback(scene.oid(),sceneGeneration,reason);
    }
    /** The native {@code chatFeedback} for a scene that is still current. */
    synchronized void chatFeedback(int place,int generation,String reason)throws IOException {
        if(!closed&&subscribed&&entered&&scene.oid()==place&&sceneGeneration==generation)
            output.send(()->codec.chatFeedback(scene.map().town()?place:partyOid(),reason));
    }
    private void leaveChat(){
        friendLocation=new FriendPresence("~",0,0);
        town.reset();
        trade.leave();
        chats.leave();
        if(persistence!=null){persistence.close();persistence=null;}
    }

    synchronized void input(SessionDecoder.Input input) throws IOException {
        // Old frames can already be in flight when the server forces a portal move.
        // Ignore only the known departing scene until the destination becomes ready.
        if(!closed&&!socket.isClosed()&&departedSceneOid!=0&&input.oid()==departedSceneOid)return;
        if (closed || socket.isClosed() || !subscribed || !entered || input.oid() != scene.oid()) {
            throw new SessionDecoder.Rejected("Unauthorized movement input");
        }
        // TCP delivers every queued snapshot in order, even while scene loading delays input.
        // Client acknowledgements are for unreliable transport; they do not choose our baseline.
        long now = monotonicTime();
        int sceneTime = SceneClock.now();
        if(!scenes.authorized(scene,profile.worldId(),partyMember))return; // Revoked memberships await their queued safe return.
        ping = (short) Math.max(0, Math.min(Short.MAX_VALUE, (long) sceneTime - input.smoothedTime()));
        for (var frame : input.frames()) {
            if (membership.input(frame.timestamp(), frame.rotation(), frame.direction(),
                    frame.flags(),frame.actionChanged(), sceneTime, now)) acknowledge = frame.timestamp();
        }
    }

    /** Coalesce ordinary frames while retaining native data-dependent activity boundaries. */
    private void offer(SceneRegistry.Instance place,int generation,SceneSnapshot snapshot) {
        publications.offer(sceneGeneration,place,generation,snapshot);
    }
    /** Keep the encoder/writer cause when a downstream stream has become unusable. */
    private static String deliveryFailure(Throwable failure) {
        var message=new StringBuilder();var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        for(int depth=0;failure!=null&&depth<8&&seen.add(failure);depth++,failure=failure.getCause()){
            if(depth>0)message.append(" caused by ");message.append(failure.getClass().getSimpleName());
            if(failure.getMessage()!=null){String detail=failure.getMessage().replaceAll("[\\r\\n\\t]"," ");message.append(": ").append(detail,0,Math.min(256,detail.length()));}
        }
        return message.toString();
    }
    private synchronized void publish(SessionPublications.Publication publication)throws IOException {
        // Optional scene work is skipped before encoding when this reader is behind its byte
        // budget; the next publication re-encodes from the last snapshot actually sent.
        if(output.optionalOverBudget())return;
        var current=publication.snapshot();int stamp=current.timestamp();
        if(closed||!subscribed||!entered||scene!=publication.scene()||sceneGeneration!=publication.generation()||stamp-lastStamp<=0)return;
        boolean interior=!scene.map().town();
        var encoded=current.encoded(lastSnapshot,interior,
            (frameDelta,frameEffects,frameCombat,frameInterior)->SessionCodec.sceneDeltaTemplate(codec.catalog(),
                scene.oid(),stamp,frameDelta.added(),frameDelta.updated(),frameDelta.removed(),
                frameDelta.baseline(),frameInterior,frameEffects,frameCombat));
        var delta=encoded.delta();
        // Subscription already announces occupants; the first reliable delta has no baseline.
        var changedRoles=lastSnapshot==null?current.actors().stream().filter(a->occupantRoles.containsKey(a.id())&&occupantRoles.get(a.id())!=a.gameMaster()).toList():delta.changedRoles();
        if(!changedRoles.isEmpty())output.send(()->codec.occupantRoles(scene.oid(),changedRoles));
        var clients=current.clientOids();
        if(!clients.equals(occupantClients)){
            output.send(()->codec.occupantChanges(scene.oid(),occupantClients,current.players()));
            occupantClients=clients;
        }
        occupantRoles=current.roles();
        parties.publishVisited(scene.visited());
        short elapsed=(short)Math.max(1,Math.min(Short.MAX_VALUE,stamp-lastStamp));
        var frame=encoded.frame();
        // The first frame for a connection registers the symbols; later ones reuse the shared body.
        // This is an optional update: when the session is already over budget it is skipped before
        // encoding, so no unseen symbol mapping can be introduced.
        if(!output.sendOptional(()->{
            var shared=codec.materialize(frame,oid,acknowledge,ping,reference,elapsed);
            return shared!=null?shared:codec.sceneDelta(oid,scene.oid(),acknowledge,ping,reference,stamp,elapsed,
                delta.added(),delta.updated(),delta.removed(),delta.baseline(),interior,encoded.effects(),encoded.combat());
        }))return;
        var currentOwn=current.player(oid);
        var health=currentOwn==null?null:current.health(currentOwn.id());
        var previousOwn=lastSnapshot==null?null:lastSnapshot.actor(profile.worldId());
        Set<Long> previousCurse=previousOwn==null||previousOwn.sceneKnight()==null?Set.of():previousOwn.sceneKnight().status().cursedItems();
        Set<Long> currentCurse=currentOwn==null||currentOwn.sceneKnight()==null?Set.of():currentOwn.sceneKnight().status().cursedItems();
        if(!previousCurse.equals(currentCurse))output.send(()->codec.itemChanges(oid,knight,visibleProfile(previousCurse),visibleProfile(currentCurse)));
        if(health!=null&&currentOwn.sceneKnight()!=null){
            var status=currentOwn.sceneKnight().status();
            if(!health.equals(publishedHealth)||!status.equals(publishedStatus)){
                publishedHealth=health;publishedStatus=status;
            }
            partyState();
        }
        lastStamp=reference=stamp;lastSnapshot=current;
        var story=membership.takeStory();
        if(!story.memories().isEmpty()||!story.objectives().isEmpty()){
            profile=loadProfile(profile);
            var result=characters.knightStory(knight.id(),authRevision,profile,story.memories().stream().map(SceneSimulation.MemoryRequest::operation).toList(),story.objectives().stream().map(SceneSimulation.ObjectiveRequest::operation).toList());
            profile=result.profile();
            if(!memories.equals(result.memories())){var before=memories;memories=result.memories();output.send(()->codec.knightMemoryChanges(oid,before,memories));}
            if(!objectives.equals(result.objectives())){objectives=result.objectives();publishObjectives();}
        }
        collectPickups();
        var capsuleCounters=new TreeMap<String,Integer>();
        for(long id:membership.takeUsedItems()){
            var item=temporaryItems.get(id);if(item==null)continue;
            var used=scene.map().content().items().get(item.config());
            if(used!=null&&!used.text("stat","").isBlank())
                capsuleCounters.merge(used.text("stat",""),Math.max(1,used.integer("stat-delta",1)),Integer::sum);
            var before=visibleProfile();
            if(item.count()==1)temporaryItems.remove(id);else temporaryItems.put(id,new WorldProfile.Item(id,item.slot(),item.config(),item.accessory(),item.heat(),item.equippedSlot(),item.bound(),item.bindOnEquip(),item.starter(),item.count()-1,item.permanent()));
            var after=visibleProfile();output.send(()->codec.itemChanges(oid,knight,before,after));
            publishQuickbar();
        }
        if(!capsuleCounters.isEmpty()){
            var before=visibleProfile();
            try{
                var result=characters.services().town(scenes.town()).stats(characters.accountId(),knight.id(),authRevision,capsuleCounters);
                profile=result.profile();
                var changes=codec.achievementChanges(oid,before,visibleProfile());
                if(changes!=null)output.send(()->changes);
            }catch(IOException failure){
                // The scene capsule has already been consumed; a refused receipt is recorded
                // without turning a healthy scene stream into a disconnect.
                log.warn("Lifetime capsule counter not recorded; "+deliveryFailure(failure));
            }
        }
        var own=current.actor(profile.worldId());
        boolean arsenal=membership.arsenalZone();
        if(publishedArsenal==null||publishedArsenal!=arsenal){publishedArsenal=arsenal;output.send(()->codec.equipPolicy(oid,arsenal));}
        town.expire(own);
        if(conversations.expired(own))closeConversation();
        var scripted=membership.takeConversation();
        if(scripted!=null&&scripted.definition()==null){closeConversation();scripted=null;}
        if(scripted!=null&&own!=null){
            if(scripted.definition().initialAction()!=null&&!membership.conversationStart(scripted.definition(),scripted.source()))return;
            conversations.open(scripted.definition(),null,scripted.source());
        }
        if(arrivalComlink!=null&&own!=null&&!conversations.active()&&town.dialogOpen()==false&&missionDialogCreated==0){
            var pending=arrivalComlink;arrivalComlink=null;conversations.openComlink(pending.id());deliveredComlink=pending;
        }
        int target=membership.takeInteraction();
        if(target>0&&own!=null){
            var interaction=scene.simulation().interactionSource(target);
            var source=interaction.actor();
            var binding=source==null?null:scene.map().content().conversations().get(source.config().name());
            var sourceSpec=interaction.specification();
            var station=source==null?null:scenes.town().station(sourceSpec==null?source.config().name():sourceSpec.text("town-station",source.config().name()));
            if(sourceSpec!=null)binding=scene.map().content().conversations().get(sourceSpec.text("dialogue",source.config().name()));
            if(station!=null&&(!trade.active())&&own.interaction()!=null&&own.interaction().actorId()==target){openTownDialog(station,source);}
            else if(binding!=null&&(!trade.active())&&own.interaction()!=null&&own.interaction().actorId()==target){
                if(binding.actor().equals(RHENDON_CAMP)&&!completedOpening.containsKey(OpeningMission.SECURING_CAMP.id())&&!scenes.missions().eligible(OpeningMission.SECURING_CAMP.id(),profile.progress(),completedOpening.keySet()))return;
                if(binding.initialAction()!=null&&!membership.conversationStart(binding,source))return;
                // Repeat node is the archive's documented interim HasCompletedMission branch;
                // it cannot pay or set the mission completed merely by opening.
                String initial=binding.actor().equals(RHENDON_CAMP)&&completedOpening.containsKey(OpeningMission.SECURING_CAMP.id())?"part 4a":binding.initial();
                conversations.open(binding,null,source,initial);
            }
        }
        if(scene.tutorial()!=null&&forcedReceiver!=null&&parties.subscribedOid()==partyOid()){
            if(floorReport!=null&&!floorReport.valid())floorReport=null;
            if(floorReport==null){
                var request=membership.takeFloorAdvance();
                if(request!=null){
                    if(!scene.tutorial().exit(request)||!membership.ownsFloorAdvance(request))membership.cancelFloorAdvance(request);
                    else {
                        profile=loadProfile(profile);
                        if(scenes.parties().view(partyMember).members().size()!=1)throw new IOException("Tutorial party changed");
                        floorReport=new FloorReport(membership,request,request.timestamp());
                        int party=partyOid();output.send(()->codec.finalFloorReport(party));
                    }
                }
            }
            if(finishTutorialFloor(stamp))return;
        }
        if(scene.run()!=null&&forcedReceiver!=null&&parties.subscribedOid()==partyOid()){
            if(processOpeningFloor(stamp))return;
        }
        var trigger=current.portal(profile.worldId());
        if(trigger!=null&&forcedReceiver!=null)beginPortal(trigger);
    }
    /** Called only after the scene's original NextFloor and native reportComplete (or its
     * authored timeout). Storage commits before flags, forced movement or party publication. */
    boolean finishTutorialFloor(int stamp)throws IOException {
        if(floorReport==null||!floorReport.ready(stamp)||forcedReceiver==null)return false;
        var route=scene.tutorial();
        if(route==null||!subscribed||!entered||portalMove!=null) return false;
        var next=destination(route.destination().id());
        if(next.map()!=route.destination()||!floorReport.valid())return false;
        profile=loadProfile(profile);
        var before=profile;
        profile=characters.completeTutorial(knight.id(),authRevision,profile,membership.health().current()>0?membership.floorHeat():0,scenes.town());
        floorReport.finish();floorReport=null;
        var settled=profile;
        if(!before.items().equals(settled.items()))output.send(()->codec.itemChanges(oid,knight,before,settled));
        // The settlement's 2-1 arrival rank and orientation letter are already durable.
        if(!before.progress().equals(settled.progress()))output.send(()->codec.knightProgress(oid,settled));
        if(!before.town().equals(settled.town()))output.send(()->codec.townState(oid,settled));
        closeConversation();closeTownDialog();
        portalMove=new PortalMove(next,route.entrance());departedSceneOid=scene.oid();
        partyState();leaveChat();subscribed=entered=false;sceneGeneration++;membership.pause();
        short receiver=forcedReceiver;
        output.send(()->codec.playerFlags(oid,gameMaster,inspectionHidden,profile.progress().tutorialComplete()));
        output.send(()->codec.forceScene(oid,receiver,next));
        return true;
    }
    /** The native 1050 ms NextFloor call pays before it opens the floor report. */
    private boolean processOpeningFloor(int stamp)throws IOException {
        var run=scene.run();
        if(!scenes.authorized(scene,profile.worldId(),partyMember)){forcePartyExit();return true;}
        if(floorReport!=null&&!floorReport.valid()){floorReport=null;openingDropStarted=false;}
        if(openingAdvance!=null&&!membership.ownsFloorAdvance(openingAdvance))openingAdvance=null;
        if(floorReport==null){
            if(openingAdvance==null)openingAdvance=membership.takeFloorAdvance();
            if(openingAdvance!=null){
                var request=openingAdvance;
                if(!run.exit(request)||!new SceneElevator(1,true).equals(membership.elevatorConfiguration(request))){membership.cancelFloorAdvance(request);openingAdvance=null;return false;}
                if(openingPayoutRetry!=0&&stamp-openingPayoutRetry<0)return false;
                if(run.lastFloor()){
                    profile=loadProfile(profile);var before=profile;boolean firstEver=completedOpening.isEmpty();
                    org.tortodrone.storage.OpeningMissionStore.Settlement settled;
                    try{
                        settled=scenes.parties().soloGate(partyMember,run.partyOid(),()->membership.settleSoloFloor(request,
                            ()->characters.services().openingMissions(scenes.town(),scenes.missions()).complete(characters.accountId(),knight.id(),authRevision,before,run.missionId(),run.difficulty(),membership.floorHeat())));
                    }catch(IOException failure){
                        openingPayoutRetry=stamp+1000;
                        if(!openingPayoutWarning){openingPayoutWarning=true;log.warn("Opening mission settlement failed; keeping the owned ride for retry");}
                        return false;
                    }
                    profile=settled.profile();openingAdvance=null;openingPayoutRetry=0;openingPayoutWarning=false;
                    if(!memories.equals(settled.memories())){var old=memories;memories=settled.memories();output.send(()->codec.knightMemoryChanges(oid,old,memories));}
                    if(!objectives.equals(settled.objectives())){objectives=settled.objectives();publishObjectives();}
                    var gateMission=run.missionId();
                    if(!settled.completion().equals(completedOpening.get(gateMission))){var completed=new LinkedHashMap<>(completedOpening);completed.put(gateMission,settled.completion());completedOpening=Map.copyOf(completed);}
                    if(settled.first()){
                        closeConversation();closeTownDialog();membership.equipment(visibleProfile());missionDialogCreated=conversations.touch();
                        output.send(()->codec.missionSettlement(oid,before,profile,settled.completion(),scenes.missions().grants(gateMission),scenes.missions().prestige(gateMission),false,missionDialogCreated));
                        output.send(()->codec.itemChanges(oid,knight,before,profile));
                        if(firstEver)output.send(()->codec.controllerAction(oid,"new_mission",null));partyState();
                    }
                }else{
                    // Earlier floors only ride onward; the mission settles once on its last floor.
                    scenes.parties().soloGate(partyMember,run.partyOid(),()->membership.settleSoloFloor(request,()->Boolean.TRUE));
                    openingAdvance=null;
                }
                floorReport=new FloorReport(membership,request,stamp);openingDropStarted=false;
                int party=partyOid();output.send(()->codec.openingFloorReport(party));
            }else membership.authorizeElevator(run.exitKey(),Set.of(profile.worldId()));
        }
        return finishOpeningFloor(stamp);
    }
    boolean finishOpeningFloor(int stamp)throws IOException {
        if(scene.run()==null||floorReport==null||!floorReport.votedOrTimedOut(stamp)||forcedReceiver==null||!subscribed||!entered||portalMove!=null)return false;
        var run=scene.run();var request=floorReport.request();
        if(!scenes.authorized(scene,profile.worldId(),partyMember))return false;
        if(!openingDropStarted){if(!membership.dropElevator(request,floorReport.returning()))return false;openingDropStarted=true;}
        if(!membership.elevatorReady(request))return false;
        SceneRegistry.Instance next;ScenePortals.Entrance arrival;
        if(run.lastFloor()){
            next=destination(run.destination().id());
            if(next.map()!=run.destination())return false;
            arrival=run.arrival();
        }else{
            next=scenes.advanceRun(run,profile.worldId(),partyMember);
            if(next.run()==null||next.map()!=next.run().source())return false;
            arrival=next.run().entrance();
        }
        scenes.parties().soloGate(partyMember,run.partyOid(),()->membership.settleSoloElevator(request,()->Boolean.TRUE));
        floorReport.finish();floorReport=null;
        portalMove=new PortalMove(next,arrival);departedSceneOid=scene.oid();partyState();leaveChat();subscribed=entered=false;sceneGeneration++;membership.pause();
        short receiver=forcedReceiver;output.send(()->codec.forceScene(oid,receiver,next));return true;
    }
    /** Simulation selects the destination; the normal stock move callback loads its scene. */
    private void beginPortal(ScenePortals.Trigger trigger)throws IOException {
        closeConversation();
        try{characters.worldProfile(knight.name(),authRevision,profile);}
        catch(CharacterStore.Refused refused){throw new IOException("Knight changed; reconnect",refused);}
        var destination=destination(trigger.destination());
        var entrance=destination.map().portals().entrances().get(trigger.entrance());
        if(entrance==null)throw new IOException("Portal destination is unavailable");
        portalMove=new PortalMove(destination,entrance);departedSceneOid=scene.oid();
        partyState();
        leaveChat();subscribed=entered=false;sceneGeneration++;membership.pause();
        short receiver=forcedReceiver;
        output.send(()->codec.forceScene(oid,receiver,destination));
    }
    /** Batch metadata is validated against this exact subscription before publication.
     * Changed inventory loads without holding the session or simulation lock. */
    void refreshState(WorldStatePoller.Registration registration,org.tortodrone.character.WorldSummary state)throws IOException {
        WorldProfile before;
        synchronized(this) {
            if(closed||!entered||persistence!=registration)return;
            if(state==null||state.accountId()!=registration.accountId||state.knightId()!=registration.knightId
                    ||state.authRevision()!=registration.authRevision||!state.name().equalsIgnoreCase(registration.name))
                throw new IOException("Knight or account changed; reconnect");
            before=profile;
            trade.changed(state);
        }
        WorldProfile fresh=state.revision()>before.revision()?loadProfile(before):before;
        synchronized(this) {
            if(closed||!entered||persistence!=registration)return;
            if(state.gameMaster()!=gameMaster){gameMaster=state.gameMaster();membership.appearance(actorAppearance());output.send(()->codec.playerFlags(oid,gameMaster,inspectionHidden,profile.progress().tutorialComplete()));}
            // An equip may commit while the read is in flight: never roll back its publication.
            if(fresh.revision()>profile.revision()) {
                var visibleBefore=visibleProfile();var previous=profile;profile=fresh;
                if(fresh.crowns()!=previous.crowns()||fresh.energy()!=previous.energy())output.send(()->codec.balances(oid,profile));
                if(!fresh.progress().equals(previous.progress())){membership.equipment(visibleProfile());output.send(()->codec.knightProgress(oid,profile));}
                if(!fresh.town().equals(previous.town())){membership.equipment(visibleProfile());output.send(()->codec.townState(oid,profile));publishOutbids(previous,profile);}
                if(!fresh.town().loadouts().equals(previous.town().loadouts()))output.send(()->codec.loadoutChanges(oid,previous,profile));
                if(!fresh.items().equals(previous.items())){updateAppearance();var visibleAfter=visibleProfile();output.send(()->codec.itemChanges(oid,knight,visibleBefore,visibleAfter));}
            }
        }
    }
    private void publishOutbids(WorldProfile before,WorldProfile after)throws IOException{auction.publishOutbids(before,after);}
    private WorldProfile loadProfile(WorldProfile cached)throws IOException {
        try{return characters.worldProfile(knight.name(),authRevision,cached);}
        catch(CharacterStore.Refused refused){throw new IOException("Knight changed; reconnect",refused);}
    }
    synchronized void refreshFailed(WorldStatePoller.Registration registration,Exception failure) {
        if(persistence!=registration||closed)return;
        log.warn("World state refresh closed; "+deliveryFailure(failure));close();
        try{socket.close();}catch(IOException ignored){}
    }
    /** A scene tick failed; return this knight to character selection rather than a frozen world. */
    private void sceneFailed() {
        if(closed)return;
        log.warn("Scene tick failed; closing session for a safe return; scene="+(scene==null?0:scene.oid()));
        close();
        try{socket.close();}catch(IOException ignored){}
    }
    private void attach() {
        floorReport=null;
        openingAdvance=null;openingDropStarted=openingPayoutWarning=false;openingPayoutRetry=0;
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
        var checkpoint=arrival==null?scene.simulation().defaultEntrance():null;
        return new ActorState(profile.worldId(),oid,knight.name(),256|knight.color(),created,
            arrival==null?checkpoint==null?scene.map().spawnX():checkpoint.x():arrival.x(),arrival==null?checkpoint==null?scene.map().spawnY():checkpoint.y():arrival.y(),
            arrival==null?checkpoint==null?scene.map().spawnRotation():checkpoint.rotation():arrival.rotation(),0,0,0,created,appearance,gameMaster);
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
        floorReport=null;
        subscribed = false;
        if(membership!=null)membership.close();
        if(partyMember!=null){partyMember.close();partyMember=null;}
        if(profile!=null){scenes.release(scene,profile.worldId());if(portalMove!=null)scenes.release(portalMove.destination(),profile.worldId());}
        publications.stop();
        if (profile != null) synchronized (ACTIVE) { ACTIVE.remove(profile.worldId(), this); }
    }
}
