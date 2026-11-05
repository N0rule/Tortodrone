package org.tortodrone.server.network;

import java.io.IOException;
import java.net.Socket;
import java.util.*;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import org.tortodrone.storage.Database;
import org.tortodrone.world.*;

/** Selected character protocol/controller; authoritative entities live in their scene instance. */
final class WorldSession implements AutoCloseable {
    private final ClientDiagnostics diagnostics;
    void hardware(List<Object> args)throws IOException { diagnostics.hardware(args); }
    private static final Map<Integer, WorldSession> ACTIVE = new HashMap<>();
    private static final SceneChat CHAT=new SceneChat();
    private final SceneChat.RateLimit chatRate=new SceneChat.RateLimit();
    private SceneChat.Membership chat;
    private String awayMessage;
    private final Map<Integer,SceneChat.Membership> channelChats=new HashMap<>();
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
    private PlayerTrades trades;
    private PlayerTrades.Member tradeMember;
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
    private SceneSimulation.Snapshot lastSnapshot;
    private final Object publications=new Object();
    private record Publication(SceneRegistry.Instance scene,int generation,SceneSimulation.Snapshot snapshot) { }
    private Publication pending,lastOffered;
    private final ArrayDeque<Publication> activityPublications=new ArrayDeque<>();
    private boolean publicationOverflow;
    private boolean deliveryStopped;
    private Thread delivery;
    private StarterCatalog.Model appearance;
    private Knight knight;
    private WorldProfile profile;
    private Set<String> memories=Set.of();
    private Map<OpeningMission,OpeningCompletion> completedOpening=Map.of();
    private int acknowledge, created, reference, lastStamp;
    private volatile int sceneGeneration;
    private short ping;
    private WorldStatePoller.Registration persistence;
    private boolean subscribed, entered, closed, inputThrottleSent;
    private Short forcedReceiver;
    private record PortalMove(SceneRegistry.Instance destination,ScenePortals.Entrance entrance) { }
    private PortalMove portalMove;
    private FloorReport floorReport;
    private SceneSimulation.FloorAdvanceRequest openingAdvance;
    private boolean openingDropStarted,openingPayoutWarning;
    private int openingPayoutRetry;
    private OpeningGate.Attempt lastGateStart;
    private Short lastGateStartRequest;
    private ScenePortals.Entrance arrival;
    private boolean auctionAfterArrival;
    private String alchemyTarget;
    private int departedSceneOid;
    private record DeferredMove(short requestId,boolean zone,int sceneOid) { }
    private DeferredMove deferredMove;
    private int subscribedPartyOid;
    private int announcedPartyOid;
    private SceneHealth.State publishedHealth;
    private SceneStatus.State publishedStatus;
    private Boolean publishedArsenal;
    private PartyRegistry.Member partyMember;
    private PartyRegistry.View publishedParty;
    private final ArrayDeque<PartyRegistry.Event> partyEvents=new ArrayDeque<>();
    private boolean partyRefreshQueued,partyOverflow;
    private int partyOid()throws IOException{return scenes.parties().view(partyMember).oid();}
    private SceneRegistry.Instance destination(int template)throws IOException{return scenes.destination(template,profile.worldId(),partyMember);}
    private void partyState()throws IOException{if(partyMember!=null&&membership!=null)scenes.parties().state(partyMember,knight,profile,membership.health(),membership.status(),scene.map().town()&&portalMove==null,membership.floorHeat());}
    private void offerParty(PartyRegistry.Event event){synchronized(publications){
        if(deliveryStopped)return;if(event instanceof PartyRegistry.Refresh){if(partyRefreshQueued)return;partyRefreshQueued=true;}
        if(partyEvents.size()>=64)partyOverflow=true;else partyEvents.addLast(event);publications.notifyAll();
    }}
    private synchronized void deliverParty(PartyRegistry.Event event)throws IOException {
        if(closed||partyMember==null)return;
        if(event instanceof PartyRegistry.Invitation invite){output.send(()->codec.partyInvitation(oid,invite));return;}
        if(event instanceof PartyRegistry.Chat chat){if(scenes.parties().owns(partyMember,chat.partyOid()))output.send(()->codec.chat(chat.partyOid(),chat.message()));return;}
        refreshParty();
    }
    private void refreshParty()throws IOException {refreshParty(false);}
    private void refreshParty(boolean traveling)throws IOException {
        var view=scenes.parties().view(partyMember);
        if(entered)friendLocation=presence(view);
        // A private solo bookkeeping party is not an active dungeon party in Haven.
        // Publishing it there makes the stock command handler ask to abandon a mission.
        int visibleParty=readyRoom?0:scene.map().town()&&view.members().size()==1&&scenes.parties().owner(partyMember,profile.worldId())==0?0:view.oid();
        if(visibleParty!=announcedPartyOid){
            boolean changed=announcedPartyOid!=0;announcedPartyOid=visibleParty;subscribedPartyOid=0;publishedParty=null;
            output.send(()->codec.partyOid(oid,visibleParty));
            if(changed&&!traveling&&!readyRoom&&(!scene.map().town()||portalMove!=null))forcePartyExit();
        }else if(subscribedPartyOid==view.oid()&&publishedParty!=null&&!publishedParty.equals(view)){
            var before=publishedParty;output.send(()->codec.partyChanges(before,view));publishedParty=view;
        }
    }
    /** The native friend list's Join affordance is driven by this presence. */
    private FriendPresence presence(PartyRegistry.View view)throws IOException {
        if(scene.map().town())return new FriendPresence(scene.map().name(),scene.map().zone(),scene.oid());
        // Only Training rooms are party-shared; Laboratory and Hall of Heroes stay per-world.
        boolean shared=scene.map().kind().equals("training")&&scenes.parties().owner(partyMember,profile.worldId())!=0;
        boolean joinable=shared&&(view.automatic()||!view.inviteOnly()||view.allowFriends());
        return new FriendPresence(scene.map().name(),scene.map().zone(),joinable?scene.oid():0);
    }
    private void forcePartyExit()throws IOException {
        if(readyRoom)return;
        if(scene.map().town()&&portalMove==null)return;
        if(portalMove!=null&&portalMove.destination().map().town())return;
        if(portalMove!=null&&portalMove.destination()!=scene)scenes.release(portalMove.destination(),profile.worldId());
        var attempt=scene.opening()!=null?scene.opening():portalMove==null?null:portalMove.destination().opening();
        if(attempt!=null)profile=loadProfile(profile);
        int safeTown=attempt==null?scenes.catalog().entry().id():profile.progress().openingStage()>=2?attempt.gate().destination().id():217;
        var next=destination(safeTown);
        portalMove=new PortalMove(next,new ScenePortals.Entrance(1,next.map().spawnX(),next.map().spawnY(),next.map().spawnRotation()));
        deferredMove=null;departedSceneOid=scene.oid();leaveChat();subscribed=entered=false;sceneGeneration++;membership.pause();
        if(forcedReceiver!=null){short receiver=forcedReceiver;output.send(()->codec.forceScene(oid,receiver,next));}
    }
    boolean partyInvocation(SessionDecoder.Invocation request)throws IOException {
        var args=request.arguments();
        boolean response=request.oid()==SessionCodec.INVOCATION_OID&&request.service()==12&&request.method()==5
            &&!args.isEmpty()&&args.getFirst() instanceof String key&&key.startsWith("PARTY_INV:");
        boolean operation=request.service()==20&&request.method()>=1&&request.method()<=3;
        if(!response&&!operation)return false;
        if(args.isEmpty()||!(args.getLast() instanceof SessionDecoder.Listener listener)||!listener.type().equals("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller"))throw new SessionDecoder.Rejected("Invalid party listener");
        boolean valid=response?args.size()==3&&args.get(1) instanceof Integer:request.method()==3?args.size()==1:args.size()==2&&args.getFirst() instanceof String;
        if(!valid)throw new SessionDecoder.Rejected("Invalid party arguments");
        try{
            PartyRegistry.Member member;String self;
            synchronized(this){if(closed||!selected())throw new IOException("Select a knight first");if(scene.tutorial()!=null)throw new IOException("Finish the tutorial before changing parties");if(operation&&!scenes.parties().owns(partyMember,request.oid()))throw new IOException("Party membership changed");characters.worldProfile(knight.name(),authRevision,profile);member=partyMember;self=knight.name();partyState();}
            if(response)scenes.parties().respond(member,(String)args.get(0),(Integer)args.get(1));
            else if(request.method()==2){if(!chatRate.allow(System.nanoTime()))throw new IOException("Please slow down before inviting again");scenes.parties().invite(member,(String)args.get(0));}
            else scenes.parties().boot(member,request.method()==3?self:(String)args.get(0));
            synchronized(this){refreshParty();if(!response&&request.method()==3)forcePartyExit();output.send(()->codec.invocationConfirmed(oid,listener.requestId()));}
        }catch(CharacterStore.Refused|IOException refused){output.send(()->codec.invocationFailure(oid,listener.requestId(),refused.getMessage()));}
        return true;
    }
    private Map<Integer,Integer> occupantClients=Map.of();
    private Map<Integer,Boolean> occupantRoles=Map.of();
    private static final String DISABLED="Sorry, but P2W features are disabled on this server.";
    private org.tortodrone.town.TownCatalog.Station townDialog;
    private ActorState townSource;
    private long townCreated;
    private int townService=100;
    private long townShopCycle,townTradeGeneration;
    private float townX,townY;
    private SceneConversation conversation;
    private Map<String,org.tortodrone.character.KnightObjective.State> objectives=Map.of();
    private final Set<Short> conversationResponses=new LinkedHashSet<>();
    private String conversationNode;
    private ActorState conversationSource;
    private OpeningMission conversationMission;
    private OpeningMission arrivalComlink;
    private long conversationCreated;
    private long missionDialogCreated;
    private float conversationX,conversationY;
    private final Map<Long,WorldProfile.Item> temporaryItems=new LinkedHashMap<>();
    private long nextTemporaryItem=-1;
    private final long[] quickbar=new long[4];
    private void publishQuickbar()throws IOException {
        if(membership!=null)membership.inventory(visibleProfile());
        for(int slot=0;slot<quickbar.length;slot++)if(!temporaryItems.containsKey(quickbar[slot]))quickbar[slot]=0;
        var slots=quickbar.clone();output.send(()->codec.quickbar(oid,slots));
    }
    private WorldProfile visibleProfile(){
        return visibleProfile(membership==null?Set.of():membership.status().cursedItems());
    }
    private WorldProfile visibleProfile(Set<Long> cursed){
        if(temporaryItems.isEmpty()&&cursed.isEmpty())return profile;
        var items=new ArrayList<>(profile.items());items.addAll(temporaryItems.values());
        items.replaceAll(item->item.curse(cursed.contains(item.id())));
        return new WorldProfile(profile.worldId(),profile.accountId(),profile.knightId(),profile.crowns(),profile.energy(),profile.revision(),items,profile.progress(),profile.town());
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
                    var reward=characters.town(scenes.town()).pickup(characters.accountId(),knight.id(),authRevision,membership.pickupReceipt(pickup.entityId()),pickup.item());
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

    private void closeTownDialog()throws IOException {
        if(townDialog==null)return;townDialog=null;townSource=null;townService++;
        output.send(()->codec.townDialog(oid,scene.oid(),null,null,townCreated,townService));
    }
    private void openTownDialog(org.tortodrone.town.TownCatalog.Station station,ActorState source)throws IOException {
        if(trades!=null&&trades.active(tradeMember))throw new IOException("Finish the trade before using a town station");
        if(station.kind().equals("OpenArsenal")||station.kind().equals("OpenAuction")){
            if(station.kind().equals("OpenArsenal")){
                // The window reads equipPolicy while opening; publish the policy on this
                // same ordered output stream even if the scene publication was coalesced.
                boolean inZone=membership!=null&&membership.arsenalZone();
                output.send(()->codec.equipPolicy(oid,inZone));publishedArsenal=inZone;
            }
            String command=station.kind().equals("OpenAuction")?"auction":"show_arsenal_station";
            closeConversation();closeTownDialog();output.send(()->codec.controllerAction(oid,command,source));return;
        }
        townTradeGeneration=tradeMember==null?0:tradeMember.dialogGeneration;closeConversation();closeTownDialog();
        var own=scene.simulation().snapshot().players().stream().filter(a->a.id()==profile.worldId()).findFirst().orElseThrow(()->new IOException("Knight left scene"));townX=own.x();townY=own.y();
        townDialog=station;townSource=source;townCreated=Math.max(System.currentTimeMillis(),townCreated+1);townService++;
        try{
            if(station.kind().equals("OpenShop")||station.kind().equals("OpenHarnessInstaller")){townShopCycle=scenes.town().shopCycle(station.shop());publishShop();}
            output.send(()->codec.townDialog(oid,scene.oid(),station,source,townCreated,townService));
        }catch(IOException failure){
            try{closeTownDialog();}catch(IOException closing){failure.addSuppressed(closing);}throw failure;
        }
    }
    private void publishShop()throws IOException {
        var goods=scenes.town().goods(townDialog,townShopCycle,profile.worldId()).stream().map(g->{var def=characters.catalog().find(g.item());int price=g.price()>=0?g.price():def==null?0:scenes.town().price(g.item(),def.rarity(),true);return new org.tortodrone.town.TownCatalog.Good(g.item(),g.count(),price,g.energy(),g.limit(),g.token(),g.tokenCount());}).toList();
        var available=characters.town(scenes.town()).availability(characters.accountId(),knight.id(),authRevision,townDialog,townShopCycle);
        output.send(()->codec.townShop(oid,goods,available));
    }
    private void townChanged(org.tortodrone.storage.TownStore.Result result)throws IOException {
        townChanged(result,true);
    }
    private void townChanged(org.tortodrone.storage.TownStore.Result result,boolean publishTown)throws IOException {
        var before=visibleProfile();profile=result.profile();updateAppearance();var after=visibleProfile();
        // The durable operation already committed. A failed buyer connection must
        // not suppress its shared scene effect for remaining observers.
        if(before.energy()>after.energy()&&membership!=null)membership.effect(new StarterCatalog.Model("Energy Spent",Map.of()));
        output.send(()->codec.itemChanges(oid,knight,before,after));output.send(()->codec.balances(oid,after));
        if(publishTown)output.send(()->codec.townState(oid,after));
        publishOutbids(before,after);
        if(!before.town().loadouts().equals(after.town().loadouts())||before.town().equippedSprite()!=after.town().equippedSprite()
                ||!before.town().sprites().equals(after.town().sprites()))output.send(()->codec.loadoutChanges(oid,before,after));
    }
    private boolean exchangeSubscribed;
    private long exchangeRevision=-1;
    synchronized boolean wantsExchange(){return !closed&&exchangeSubscribed;}
    synchronized void subscribeExchange(short message)throws IOException{
        if(closed||!selected())throw new IOException("Select a knight first");
        try{characters.worldProfile(knight.name(),authRevision,profile);}catch(CharacterStore.Refused refused){throw new IOException("Knight changed; reconnect",refused);}var book=characters.exchange().book();exchangeSubscribed=true;exchangeRevision=book.revision();output.send(()->codec.exchange(message,book));
    }
    synchronized void unsubscribeExchange(){exchangeSubscribed=false;exchangeRevision=-1;}
    synchronized void refreshExchange(org.tortodrone.town.ExchangeState.Book book)throws IOException{
        if(!closed&&exchangeSubscribed&&book.revision()>exchangeRevision){exchangeRevision=book.revision();output.send(()->codec.exchangeChanged(book));}
    }
    synchronized boolean exchangeInvocation(SessionDecoder.Invocation request)throws IOException{
        if(request.oid()!=SessionCodec.EXCHANGE_OID||request.service()!=SessionCodec.EXCHANGE_SERVICE)return false;
        var a=request.arguments();int method=request.method();
        if(a.isEmpty()||!(a.getLast() instanceof SessionDecoder.Listener listener)||!listener.type().equals("com.threerings.presents.data.InvocationMarshaller$"+(method==1?"ConfirmMarshaller":"ResultMarshaller")))throw new SessionDecoder.Rejected("Invalid Energy Exchange listener");
        boolean valid=switch(method){case 1->a.size()==2&&a.getFirst() instanceof Integer;case 2->a.size()==1;case 3->a.size()==5&&a.get(0) instanceof Integer&&a.get(1) instanceof Integer&&a.get(2) instanceof Boolean&&a.get(3) instanceof Boolean;default->false;};
        if(!valid)throw new SessionDecoder.Rejected("Invalid Energy Exchange request");
        try{
            if(closed||!selected()||!entered||!subscribed||!exchangeSubscribed||!scene.map().town()&&!scene.map().kind().equals("training"))throw new IOException("Open the Energy Exchange in town first");
            if(trades!=null&&trades.active(tradeMember))throw new IOException("Finish the trade first");
            var exchange=characters.exchange();var result=switch(method){case 1->exchange.cancel(characters.accountId(),knight.id(),authRevision,(Integer)a.getFirst());case 2->exchange.offers(characters.accountId(),knight.id(),authRevision);default->exchange.trade(characters.accountId(),knight.id(),authRevision,(Integer)a.get(0),(Integer)a.get(1),(Boolean)a.get(2),(Boolean)a.get(3));};
            townChanged(result);refreshExchange(exchange.book());
            if(method==1)output.send(()->codec.invocationConfirmed(oid,listener.requestId()));else output.send(()->codec.townResult(oid,listener.requestId(),result.result(),profile));
        }catch(IOException failure){output.send(()->codec.invocationFailure(oid,listener.requestId(),failure.getMessage()));}return true;
    }
    private final Map<Integer,org.tortodrone.storage.DepotStore.Quote> depotOffers=new HashMap<>();
    private boolean depotInvocation(SessionDecoder.Invocation request)throws IOException{
        if(request.oid()!=SessionCodec.INVOCATION_OID||request.service()!=16)return false;
        if(!Set.of(4,5,6,10).contains(request.method()))return openingMissionInvocation(request);
        var a=request.arguments();int method=request.method();boolean purchase=method==10;
        if(a.isEmpty()||!(a.getLast() instanceof SessionDecoder.Listener listener)||!listener.type().equals("com.threerings.presents.data.InvocationMarshaller$"+(purchase?"ConfirmMarshaller":"ResultMarshaller")))throw new SessionDecoder.Rejected("Invalid depot listener");
        try{
            if(closed||!selected()||!entered||!subscribed||!scene.map().town()&&!scene.map().kind().equals("training"))throw new IOException("Enter a town first");
            if(purchase){
                if(a.size()!=3||!(a.get(0) instanceof Integer id)||!(a.get(1) instanceof Integer price)||!depotOffers.containsKey(id))throw new IOException("e.good_unavailable");
                if(trades!=null&&trades.active(tradeMember))throw new IOException("Finish the trade first");
                townChanged(characters.town(scenes.town()).depot(characters.accountId(),knight.id(),authRevision,id,price,depotOffers.get(id)));output.send(()->codec.invocationConfirmed(oid,listener.requestId()));
            }else{
                if(!((method==4||method==5)&&a.size()==1||method==6&&a.size()==2&&a.getFirst() instanceof List<?> names&&names.size()<=128&&names.stream().allMatch(v->v instanceof String)))throw new IOException("Unsupported Town depot operation");
                var offers=new LinkedHashMap<Integer,org.tortodrone.town.TownCatalog.DepotGood>();var rules=scenes.town();
                var snapshot=characters.depot(rules).catalog();
                for(var offer:snapshot.offers()){var good=offer.good();var def=characters.catalog().find(good.item());if(!offer.enabled()||def==null||!def.grantable()||method==5&&!offer.featured()&&!offer.sale()&&offer.promotionId()==0||method==6&&!((List<?>)a.getFirst()).contains(good.item()))continue;
                    int price=good.price()<0?rules.price(good.item(),def.rarity(),true):good.price();offers.put(offer.id(),org.tortodrone.town.TownCatalog.DepotGood.timed(new org.tortodrone.town.TownCatalog.Good(good.item(),good.count(),price,good.energy(),good.limit(),good.token(),good.tokenCount()),offer.featured(),offer.originalPrice(),offer.sale(),offer.promotionEnds(),System.currentTimeMillis()));
                    depotOffers.put(offer.id(),new org.tortodrone.storage.DepotStore.Quote(snapshot.revision(),offer));
                }
                output.send(()->codec.townResult(oid,listener.requestId(),new org.tortodrone.town.TownCatalog.DepotOffers(offers),profile));
            }
        }catch(IOException failure){output.send(()->codec.invocationFailure(oid,listener.requestId(),failure.getMessage()));}return true;
    }
    private boolean openingMissionInvocation(SessionDecoder.Invocation request)throws IOException {
        var args=request.arguments();int method=request.method();
        if(method==8){
            if(args.size()!=1||!(args.getFirst() instanceof List<?> values)||values.size()>128||values.stream().anyMatch(v->!(v instanceof SessionDecoder.ConfigReference)))throw new SessionDecoder.Rejected("Invalid mission crafting intent");
            return true; // Native no-listener operation; no unsupported item mutation.
        }
        boolean result=method==7;
        if(args.isEmpty()||!(args.getLast() instanceof SessionDecoder.Listener listener)||!listener.type().equals("com.threerings.presents.data.InvocationMarshaller$"+(result?"ResultMarshaller":"ConfirmMarshaller")))throw new SessionDecoder.Rejected("Invalid mission listener");
        boolean valid=switch(method){
            case 1->args.size()==3&&args.getFirst() instanceof String&&args.get(1) instanceof long[];
            case 2,7->args.size()==6&&args.getFirst() instanceof String&&args.get(1) instanceof SessionDecoder.EnumValue band&&band.type().equals("com.threerings.projectx.dungeon.data.DungeonCodes$Difficulty")&&args.get(2) instanceof Boolean&&args.get(3) instanceof Boolean&&args.get(4) instanceof Boolean;
            case 3,9->args.size()==2&&args.getFirst() instanceof String;
            default->false;
        };
        if(!valid)throw new SessionDecoder.Rejected("Invalid mission arguments");
        if(method==3){
            try{
                var mission=OpeningMission.byId((String)args.getFirst());
                openComlink(mission);arrivalComlink=null;
                output.send(()->codec.invocationConfirmed(oid,listener.requestId()));
            }catch(IOException failure){output.send(()->codec.invocationFailure(oid,listener.requestId(),failure.getMessage()));}
            return true;
        }
        if(method==2||method==7){
            try{
                var mission=OpeningMission.byId((String)args.getFirst());int band=((SessionDecoder.EnumValue)args.get(1)).ordinal();
                if(band<0||band>2||!Boolean.TRUE.equals(args.get(2))||method==2&&(!Boolean.FALSE.equals(args.get(3))||!Boolean.FALSE.equals(args.get(4))))throw new IOException("m.internal_error");
                String difficulty=List.of("EASY","MEDIUM","HARD").get(band);
                var active=portalMove!=null&&portalMove.destination().opening()!=null?portalMove.destination():scene;
                if(method==2&&!closed&&lastGateStartRequest!=null&&listener.requestId()==lastGateStartRequest&&active.opening()==lastGateStart
                    &&lastGateStart.gate().mission()==mission&&lastGateStart.difficulty().equals(difficulty)&&scenes.authorized(active,profile.worldId(),partyMember)){
                    profile=loadProfile(profile);
                    output.send(()->codec.invocationConfirmed(oid,listener.requestId()));return true;
                }
                var gate=preflightOpeningGate(mission);
                if(method==7){output.send(()->codec.townResult(oid,listener.requestId(),Boolean.TRUE,profile));return true;}
                var next=scenes.startOpening(gate,profile.worldId(),partyMember,difficulty);
                lastGateStart=next.opening();lastGateStartRequest=listener.requestId();
                closeConversation();closeTownDialog();portalMove=new PortalMove(next,gate.entrance());departedSceneOid=scene.oid();
                partyState();leaveChat();subscribed=entered=false;sceneGeneration++;if(membership!=null)membership.pause();
                short receiver=forcedReceiver;
                output.send(()->codec.invocationConfirmed(oid,listener.requestId()));output.send(()->codec.forceScene(oid,receiver,next));
            }catch(IOException failure){output.send(()->codec.invocationFailure(oid,listener.requestId(),failure.getMessage()));}
            return true;
        }
        // A served object/service must fail unsupported cards through the native listener.
        // Gate start is enabled only when its actual floor gameplay/route is implemented.
        output.send(()->codec.invocationFailure(oid,listener.requestId(),"m.internal_error"));return true;
    }
    private OpeningGate preflightOpeningGate(OpeningMission mission)throws IOException {
        if(closed||!selected()||forcedReceiver==null||portalMove!=null||deferredMove!=null||scene.map().id()!=217||!scene.map().town()
            ||!readyRoom&&(!entered||!subscribed||membership==null||membership.health().current()<=0))throw new IOException("m.internal_error");
        if(trades!=null&&trades.active(tradeMember))throw new IOException("Finish the trade first");
        profile=loadProfile(profile);completedOpening=characters.openingMissions(scenes.town()).load(characters.accountId(),knight.id(),authRevision);
        var gate=scenes.openingGate(mission);
        if(gate==null||!mission.eligible(profile.progress(),completedOpening.keySet()))throw new IOException("m.internal_error");
        scenes.parties().soloGate(partyMember,partyOid(),()->Boolean.TRUE);return gate;
    }
    private void openComlink(OpeningMission mission)throws IOException {
        if(mission==null||OpeningComlinks.get(mission)==null||closed||!selected()||!entered||!subscribed||membership==null||scene.map().id()!=OpeningComlinks.scene(mission))throw new IOException("m.internal_error");
        profile=loadProfile(profile);if(!mission.eligible(profile.progress(),completedOpening.keySet()))throw new IOException("m.internal_error");
        var snapshot=scene.simulation().snapshot();var own=snapshot.players().stream().filter(a->a.id()==profile.worldId()).findFirst().orElse(null);
        if(own==null||snapshot.portal(own.id())!=null||membership.health().current()<=0)throw new IOException("m.internal_error");
        if(OpeningComlinks.owns(mission,conversation)&&conversationMission==mission)return;
        closeTownDialog();closeConversation();conversationMission=mission;conversation=OpeningComlinks.get(mission);conversationSource=own;conversationNode=conversation.initial();
        conversationCreated=Math.max(System.currentTimeMillis(),conversationCreated+1);conversationX=own.x();conversationY=own.y();
        output.send(()->codec.conversationDialog(oid,scene.oid(),conversation,conversationNode,conversationSource,conversationCreated));
    }
    synchronized boolean auctionInvocation(SessionDecoder.Invocation request)throws IOException {
        if(request.oid()!=SessionCodec.INVOCATION_OID)return false;var a=request.arguments();int method=request.method();
        boolean auction=request.service()==15,mail=request.service()==10&&(method==1||method==2||method==4||method==5||method==6||method==11);
        if(!auction&&!mail)return false;
        boolean resultListener=auction||method==6;
        if(a.isEmpty()||!(a.getLast() instanceof SessionDecoder.Listener listener)||!listener.type().equals("com.threerings.presents.data.InvocationMarshaller$"+(resultListener?"ResultMarshaller":"ConfirmMarshaller")))throw new SessionDecoder.Rejected("Invalid auction/mail listener");
        boolean valid=auction?switch(method){case 1->a.size()==3&&a.get(0) instanceof Long&&a.get(1) instanceof Integer;case 2,7->a.size()==2&&a.get(0) instanceof Long;case 3,4->a.size()==1;case 5->a.size()==3&&a.get(0) instanceof SessionDecoder.AuctionSearch&&a.get(1) instanceof Integer;case 6->a.size()==7&&a.get(0) instanceof Long&&a.get(1) instanceof Integer&&a.get(2) instanceof Integer&&a.get(3) instanceof Integer&&a.get(4) instanceof SessionDecoder.EnumValue e&&e.type().equals("com.threerings.projectx.auction.data.Duration")&&a.get(5) instanceof Boolean;default->false;}:a.size()==2&&(method==5?a.get(0) instanceof SessionDecoder.SentMail:method==6?a.get(0)==null||a.get(0) instanceof long[]:method==11?a.get(0) instanceof long[]:a.get(0) instanceof Long);
        if(!valid)throw new SessionDecoder.Rejected("Invalid auction/mail arguments");
        try{if(closed||!selected()||!entered||!subscribed)throw new IOException("Enter a scene first");
            if(trades!=null&&trades.active(tradeMember)&&(auction&&(method==1||method==2||method==6)||mail&&(method==1||method==2||method==5)))throw new IOException("Finish the trade first");
            var house=characters.auction(scenes.town());var result=auction?house.operate(characters.accountId(),knight.id(),authRevision,method,a,roles.gameMaster(),auctionPresence(characters.worldStorageFile())):house.mail(characters.accountId(),knight.id(),authRevision,method,a);
            townChanged(result);if(resultListener)output.send(()->codec.townResult(oid,listener.requestId(),result.result(),profile));else output.send(()->codec.invocationConfirmed(oid,listener.requestId()));
        }catch(IOException refused){output.send(()->codec.invocationFailure(oid,listener.requestId(),refused.getMessage()));}return true;
    }

    boolean friendInvocation(SessionDecoder.Invocation request)throws IOException {
        int method=request.method();
        // The friend service shares service 12 with scene chat. Scene chat addresses the scene
        // object (not the global invocation object), so anything without the global oid is not a
        // friend request and must fall through to the chat dispatcher instead of being rejected.
        if(request.oid()!=SessionCodec.INVOCATION_OID||request.service()!=12||!Set.of(1,2,3,4,6).contains(method))return false;
        var args=request.arguments();
        if(args.isEmpty()||!(args.getLast() instanceof SessionDecoder.Listener listener)
                ||!listener.type().equals("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller"))throw new SessionDecoder.Rejected("Invalid friend-list listener");
        boolean valid=switch(method){
            case 1->args.size()==4&&args.get(0) instanceof String&&args.get(1) instanceof String&&args.get(2) instanceof String;
            case 2,6->args.size()==2&&args.get(0) instanceof String;
            // joinOrSpectate: the secret is PlayerObject.ML(), spectate is carried but unobserved.
            case 3->args.size()==3&&args.get(0) instanceof String&&args.get(1) instanceof Boolean;
            // setLookingForGroup: the client sends the inverted toggle, the saved difficulty and
            // the saved party_with_any preference. Only the toggle has player-visible behavior.
            case 4->args.size()==4&&args.get(0) instanceof Boolean
                &&(args.get(1)==null||args.get(1) instanceof SessionDecoder.EnumValue e&&e.type().equals(DIFFICULTY))
                &&args.get(2) instanceof Boolean;
            default->false;
        };
        if(!valid)throw new SessionDecoder.Rejected("Invalid friend-list arguments");
        try {
            switch(method){
                case 2->joinFriend((String)args.get(0));
                case 3->joinBySecret((String)args.get(0),(Boolean)args.get(1));
                case 4->setLookingForGroup((Boolean)args.get(0),(SessionDecoder.EnumValue)args.get(1),(Boolean)args.get(2));
                default->{synchronized(this){
                    requireFriendReady();var friends=characters.friends();String name=(String)args.get(0);
                    if(method==1){if(!chatRate.allow(System.nanoTime()))throw new IOException("Please slow down before sending another friend request");friends.request(characters.accountId(),knight.id(),authRevision,name,(String)args.get(1),(String)args.get(2));}
                    else{friends.remove(characters.accountId(),knight.id(),authRevision,name);profile=characters.worldProfile(knight.name(),authRevision,profile);refreshFriends(null,friends.snapshot(),friendPresence(characters.worldStorageFile()));}
                }}
            }
            output.send(()->codec.invocationConfirmed(oid,listener.requestId()));
        }catch(CharacterStore.Refused|IOException refused){output.send(()->codec.invocationFailure(oid,listener.requestId(),refused.getMessage()));}
        return true;
    }
    private static final String DIFFICULTY="com.threerings.projectx.dungeon.data.DungeonCodes$Difficulty";
    private static String friendUnjoinable(String name){return "%social:m.friend_unjoinable|~"+name;}
    /** Native method 2: relationship-authorized travel, rechecked after the peer is captured. */
    private void joinFriend(String name)throws IOException {
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
    private void joinBySecret(String secret,boolean spectate)throws IOException {
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
        var friends=characters.friends().list(characters.accountId(),knight.id(),authRevision);
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
    private void setLookingForGroup(boolean enabled,SessionDecoder.EnumValue difficulty,boolean anyLanguage)throws IOException {
        synchronized(this){
            if(closed||!selected())throw new IOException("Select a knight first");
            try{characters.worldProfile(knight.name(),authRevision,profile);}catch(CharacterStore.Refused refused){throw new IOException("Knight changed; reconnect",refused);}
            if((profile.progress().progressFlags()&4)==0)throw new IOException("m.internal_error");
            lookingForGroupDifficulty=enabled&&difficulty!=null?difficulty.ordinal():-1;
            lookingForGroupAnyLanguage=enabled&&anyLanguage;
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
        return characters.friends().list(characters.accountId(),knight.id(),authRevision).stream().filter(p->p.name().equalsIgnoreCase(name))
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
    synchronized boolean billingInvocation(SessionDecoder.Invocation request)throws IOException {
        if(request.oid()!=SessionCodec.INVOCATION_OID||request.service()!=SessionCodec.BILLING_SERVICE)return false;
        var a=request.arguments();
        if(a.isEmpty()||!(a.getLast() instanceof SessionDecoder.Listener listener)
                ||!Set.of("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller",
                    "com.threerings.presents.data.InvocationMarshaller$ResultMarshaller").contains(listener.type()))return true;
        boolean result=listener.type().endsWith("$ResultMarshaller");
        boolean buy=result&&switch(request.method()){
            case 1->a.size()==4&&a.get(0) instanceof String&&a.get(1) instanceof Integer&&a.get(2) instanceof Boolean;
            case 2->a.size()==9&&a.get(0) instanceof String&&a.get(1) instanceof String
                &&a.get(2) instanceof Integer&&a.get(3) instanceof Integer&&a.get(4) instanceof String
                &&a.get(5) instanceof String&&a.get(6) instanceof Integer&&a.get(7) instanceof Boolean;
            default->false;
        };
        // Native buy callbacks accept BillingResponseCode.ERROR (-1). All other calls,
        // including getBillingInfo and unknown shapes, use the native requestFailed path.
        output.send(buy?()->codec.townResult(oid,listener.requestId(),-1,profile)
            :()->codec.invocationFailure(oid,listener.requestId(),DISABLED));
        return true;
    }

    private record BoxRequest(int world,short request) { }
    private record ConfettiReply(long item,String failure) { }
    private final LinkedHashMap<BoxRequest,ConfettiReply> confettiReplies=new LinkedHashMap<>();
    private record BoxReply(long box,int option,Object rewards,String failure) { }
    // Stock listener IDs wrap. Keep only recent requests on this connection, never persist a client ID.
    private final java.util.LinkedHashMap<BoxRequest,BoxReply> boxReplies=new java.util.LinkedHashMap<>();
    private boolean boxInvocation(SessionDecoder.Invocation request)throws IOException {
        if(request.oid()!=SessionCodec.INVOCATION_OID||request.service()!=9||!Set.of(6,11,12).contains(request.method()))return false;
        var args=request.arguments();
        boolean normal=request.method()==12;
        if(args.size()!=(normal?3:2)||!(args.get(0) instanceof Long id)||normal&&!(args.get(1) instanceof Integer)
                ||!(args.getLast() instanceof SessionDecoder.Listener listener)
                ||!listener.type().equals("com.threerings.presents.data.InvocationMarshaller$ResultMarshaller"))
            throw new SessionDecoder.Rejected("Invalid box opening request");
        int option=normal?(Integer)args.get(1):-request.method();
        BoxRequest token=null;
        try {
            if(closed||!selected()||!entered||!subscribed)throw new IOException("m.internal_error");
            var current=characters.worldProfile(knight.name(),authRevision,profile);
            if(trades!=null&&trades.active(tradeMember))throw new IOException("Finish the trade first");
            if(request.method()==6){var table=characters.boxes(scenes.boxes()).spawnTable(characters.accountId(),knight.id(),authRevision,id);output.send(()->codec.townResult(oid,listener.requestId(),table,current));return true;}
            token=new BoxRequest(current.worldId(),listener.requestId());var prior=boxReplies.get(token);
            if(prior!=null) {
                if(prior.box()!=id||prior.option()!=option||prior.failure()!=null)throw new IOException(prior.failure()==null?"m.internal_error":prior.failure());
                Object rewards=prior.rewards();output.send(()->codec.townResult(oid,listener.requestId(),rewards,profile));return true;
            }
            if(!membership.canOpenBox())throw new IOException("Finish your current action before opening a box");
            var boxes=characters.boxes(scenes.boxes());
            var result=normal?boxes.open(characters.accountId(),knight.id(),authRevision,current.revision(),id,option):boxes.spawnAll(characters.accountId(),knight.id(),authRevision,current.revision(),id);
            // Retain the receipt before publication: a queued-output failure must never cause a second write.
            rememberBox(token,new BoxReply(id,option,normal?result.result():null,null));
            if(!normal){townChanged(result,false);output.send(()->codec.townResult(oid,listener.requestId(),null,result.profile()));return true;}
            var references=new ArrayList<StarterCatalog.Model>();
            references.add(new StarterCatalog.Model(current.items().stream().filter(i->i.id()==id).findFirst().orElseThrow().config(),Map.of()));
            var rewards=((SessionDecoder.Multiset)result.result()).counts();
            result.profile().items().stream().filter(i->rewards.containsKey(i.id())).map(WorldProfile.Item::config).distinct()
                .forEach(config->references.add(new StarterCatalog.Model(config,Map.of())));
            membership.openBox(List.copyOf(references));
            // Capture: suppress ordinary acquisition popups while consuming/granting, then
            // clear the delay before the native reward-window callback. Publish only after commit.
            output.send(()->codec.itemAcquireDelay(oid,7500));
            townChanged(result,false);
            output.send(()->codec.itemAcquireDelay(oid,0));
            output.send(()->codec.townResult(oid,listener.requestId(),result.result(),result.profile()));
        }catch(CharacterStore.Refused|IOException failure) {
            if(token!=null&&!boxReplies.containsKey(token))rememberBox(token,new BoxReply(id,option,null,failure.getMessage()));
            output.send(()->codec.invocationFailure(oid,listener.requestId(),failure.getMessage()));
        }
        return true;
    }
    private void rememberBox(BoxRequest request,BoxReply result) {
        boxReplies.put(request,result);if(boxReplies.size()>256)boxReplies.remove(boxReplies.keySet().iterator().next());
    }

    private boolean styleInvocation(SessionDecoder.Invocation request)throws IOException {
        if(request.oid()!=SessionCodec.INVOCATION_OID)return false;
        boolean apply=request.service()==1&&request.method()==1;
        var args=request.arguments();
        boolean use=request.service()==9&&request.method()==14&&args.size()==3&&args.getFirst() instanceof Long id&&profile!=null
            &&profile.items().stream().anyMatch(item->item.id()==id&&org.tortodrone.storage.StyleStore.kit(item.config())!=null);
        if(!apply&&!use)return false;
        if(args.size()!=(apply?2:3)||!(args.getLast() instanceof SessionDecoder.Listener listener)||!listener.type().equals("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller")||use&&args.get(1)!=null)
            throw new SessionDecoder.Rejected("Invalid style request");
        try{
            if(closed||!selected()||!entered||!subscribed||portalMove!=null)throw new IOException("Enter a scene first");
            if(trades!=null&&trades.active(tradeMember))throw new IOException("Finish the trade first");
            if(!membership.canOpenBox())throw new IOException("e.not_use_busy");
            org.tortodrone.storage.StyleStore.Style style=null;
            if(apply){
                var value=args.getFirst();var kind=org.tortodrone.storage.StyleStore.Kind.Color;int ordinal;
                if(value instanceof Integer color)ordinal=color;
                else if(value instanceof SessionDecoder.EnumValue e&&e.type().equals("com.threerings.projectx.data.ProjectXCodes$EyeType")){kind=org.tortodrone.storage.StyleStore.Kind.Eye;ordinal=e.ordinal();}
                else if(value instanceof SessionDecoder.EnumValue e&&e.type().equals("com.threerings.projectx.data.ProjectXCodes$KnightHeight")){kind=org.tortodrone.storage.StyleStore.Kind.Height;ordinal=e.ordinal();}
                else throw new IOException("m.internal_error");
                try{style=new org.tortodrone.storage.StyleStore.Style(kind,ordinal);}catch(IllegalArgumentException invalid){throw new IOException("m.internal_error");}
            }
            var current=characters.worldProfile(knight.name(),authRevision,profile);
            var result=characters.styles().change(characters.accountId(),knight.id(),authRevision,current.revision(),use?(Long)args.getFirst():null,style);
            characters.refresh();knight=characters.roster().stream().filter(k->k.id()==knight.id()).findFirst().orElseThrow();
            townChanged(result);publishStyles();partyState();
            output.send(()->codec.invocationConfirmed(oid,listener.requestId()));
        }catch(CharacterStore.Refused|IOException refused){output.send(()->codec.invocationFailure(oid,listener.requestId(),refused.getMessage()));}
        return true;
    }
    private void publishStyles()throws IOException {
        if(!characters.hasWorldStorage())return;
        var saved=characters.styles().saved(characters.accountId(),knight.id(),authRevision);
        output.send(()->codec.styles(oid,knight,saved));
    }

    synchronized boolean townInvocation(SessionDecoder.Invocation request)throws IOException {
        if(styleInvocation(request))return true;
        if(boxInvocation(request))return true;
        if(depotInvocation(request))return true;
        var a=request.arguments();boolean global=request.oid()==SessionCodec.INVOCATION_OID;
        if(billingInvocation(request))return true;
        // KnightMarshaller 2 is canRename (30-day window check); 8 is renameKnight [Name, Confirm].
        if(global&&request.service()==1&&(request.method()==2||request.method()==8)){
            boolean rename=request.method()==8;
            if(a.size()!=(rename?2:1)||rename&&!(a.getFirst() instanceof String)||!(a.getLast() instanceof SessionDecoder.Listener listener)||!listener.type().endsWith("$ConfirmMarshaller"))throw new SessionDecoder.Rejected("Invalid knight rename request");
            try{
                if(closed||!selected()||!entered||!subscribed||!scene.map().town())throw new IOException("Enter a town first");
                if(trades!=null&&trades.active(tradeMember))throw new IOException("Finish the trade first");
                if(rename){
                    var result=characters.town(scenes.town()).rename(characters.accountId(),knight.id(),authRevision,(String)a.getFirst());
                    characters.refresh();knight=characters.roster().stream().filter(k->k.id()==knight.id()).findFirst().orElseThrow();
                    closeConversation();closeTownDialog();leaveChat();townChanged(result);partyState();
                    output.send(()->codec.knightName(oid,knight.name()));
                    output.send(()->codec.invocationConfirmed(oid,listener.requestId()));
                    // Official dV companion dY ends the session after a rename and returns to the
                    // login window carrying the new name, so the server closes it here.
                    renameDisconnect=true;
                }else{
                    characters.town(scenes.town()).canRename(characters.accountId(),knight.id(),authRevision);
                    output.send(()->codec.invocationConfirmed(oid,listener.requestId()));
                }
            }catch(IOException failure){output.send(()->codec.invocationFailure(oid,listener.requestId(),failure.getMessage()));}
            return true;
        }
        // ItemMarshaller 1 is the stock Trash action [itemId, Confirm]; every persistent owned
        // non-permanent item is deletable, whole. Scene-item Use (method 14) stays separate.
        if(global&&request.service()==9&&request.method()==1){
            if(a.size()!=2||!(a.getFirst() instanceof Long id)||!(a.getLast() instanceof SessionDecoder.Listener listener)||!listener.type().endsWith("$ConfirmMarshaller"))throw new SessionDecoder.Rejected("Invalid item deletion request");
            try{
                if(closed||!selected()||!entered||!subscribed)throw new IOException("Select a knight first");
                characters.worldProfile(knight.name(),authRevision,profile);
                if(trades!=null&&trades.active(tradeMember))throw new IOException("Finish the trade first");
                if(temporaryItems.containsKey(id)){
                    var item=temporaryItems.get(id);
                    if(item.permanent()||item.equippedSlot()!=null)throw new IOException("m.internal_error");
                    var before=visibleProfile();temporaryItems.remove(id);var after=visibleProfile();
                    output.send(()->codec.itemChanges(oid,knight,before,after));publishQuickbar();
                }else townChanged(characters.town(scenes.town()).trash(characters.accountId(),knight.id(),authRevision,id));
                output.send(()->codec.invocationConfirmed(oid,listener.requestId()));
            }catch(CharacterStore.Refused|IOException refused){output.send(()->codec.invocationFailure(oid,listener.requestId(),refused.getMessage()));}
            return true;
        }
        if(townDialog!=null&&tradeMember!=null&&townTradeGeneration!=tradeMember.dialogGeneration)closeTownDialog();
        if(townDialog!=null&&ownsScene(request.oid())&&request.service()==6&&request.method()==1){
            if(a.size()!=1||!(a.getFirst() instanceof Long created))throw new SessionDecoder.Rejected("Invalid Town dialog close");if(created!=townCreated)return true;closeTownDialog();return true;
        }
        if(townDialog==null&&conversation==null&&ownsScene(request.oid())&&request.service()==6&&request.method()==1&&a.size()==1&&a.getFirst() instanceof Long created){
            if(missionDialogCreated!=0&&created!=missionDialogCreated)return true;
            missionDialogCreated=0;
            // A stale close from a client still holding a replaced dialog: acknowledge idempotently.
            output.send(()->codec.conversationDialog(oid,scene.oid(),null,null,null,0));return true;
        }
        boolean open=ownsScene(request.oid())&&request.service()==11;
        boolean operation=ownsScene(request.oid())&&request.service()>=100;
        boolean sprite=global&&request.service()==14;
        boolean hatch=global&&request.service()==9&&request.method()==7;
        boolean wrap=global&&request.service()==9&&request.method()==15;
        boolean unwrap=global&&request.service()==9&&request.method()==13;
        boolean forge=global&&request.service()==9&&request.method()==8;
        // The stock inventory's Use action sends method 14 [item, data, confirm].
        // Method 1 is deleteItem, and must never learn a recipe as a side effect.
        boolean use=global&&request.service()==9&&request.method()==14&&a.size()==3&&a.get(1)==null;
        boolean learn=use&&a.getFirst() instanceof Long id&&selected()&&profile.items().stream().anyMatch(i->i.id()==id&&scenes.town().item(i.config()).containsKey("recipe"));
        boolean harness=use&&a.getFirst() instanceof Long id&&selected()&&profile.items().stream().anyMatch(i->i.id()==id&&scenes.town().item(i.config()).containsKey("harness"));
        boolean extend=global&&request.service()==9&&request.method()==5;
        boolean slotUpgrade=use&&a.getFirst() instanceof Long id&&selected()&&profile.items().stream().anyMatch(i->i.id()==id&&SlotUpgrades.find(i.config())!=null);
        if(!open&&!operation&&!sprite&&!hatch&&!forge&&!learn&&!harness&&!wrap&&!unwrap&&!slotUpgrade&&!extend)return false;
        if(a.isEmpty()||!(a.getLast() instanceof SessionDecoder.Listener listener))throw new SessionDecoder.Rejected("Missing Town listener");
        boolean resultListener=hatch||forge||unwrap||operation&&(townDialog==null||!Set.of("OpenShop","OpenHarnessInstaller").contains(townDialog.kind()))||sprite&&request.method()==7;
        if(!listener.type().equals("com.threerings.presents.data.InvocationMarshaller$"+(resultListener?"ResultMarshaller":"ConfirmMarshaller")))throw new SessionDecoder.Rejected("Invalid Town listener");
        try{
            if(closed||!selected()||!entered||!subscribed)throw new IOException("Enter a scene first");
            characters.worldProfile(knight.name(),authRevision,profile);
            boolean staff=roles.gameMaster();
            if(!scene.map().town()&&!scene.map().kind().equals("training")&&!staff)throw new IOException("This operation requires a town");
            var store=characters.town(scenes.town());org.tortodrone.storage.TownStore.Result result;
            var priorRecipes=profile.town().recipes();
            if(extend){
                if(a.size()!=3||!(a.get(0) instanceof Long active)||!(a.get(1) instanceof Long unused))throw new SessionDecoder.Rejected("Invalid upgrade extension request");
                if(trades!=null&&trades.active(tradeMember))throw new IOException("Finish the trade first");
                result=store.extendUpgrade(characters.accountId(),knight.id(),authRevision,active,unused);
                townChanged(result);output.send(()->codec.invocationConfirmed(oid,listener.requestId()));return true;
            }else if(slotUpgrade){
                if(trades!=null&&trades.active(tradeMember))throw new IOException("Finish the trade first");
                result=store.activateUpgrade(characters.accountId(),knight.id(),authRevision,(Long)a.getFirst());
                townChanged(result);output.send(()->codec.invocationConfirmed(oid,listener.requestId()));return true;
            }else if(open){
                if(!scene.map().town()||a.size()!=1||request.method()<1||request.method()>4)throw new IOException("Invalid town service");
                String kind=switch(request.method()){case 1->"OpenAccessorizer";case 2->"OpenCrafter";case 3->"OpenUnbinder";default->"OpenVariantMaker";};
                openTownDialog(new org.tortodrone.town.TownCatalog.Station("",kind,"","NORMAL","~","~","","",List.of()),null);
                output.send(()->codec.invocationConfirmed(oid,listener.requestId()));return true;
            }else if(operation){
                if(townDialog==null||request.service()!=townService||trades!=null&&trades.active(tradeMember))throw new IOException("Town dialog expired");
                if(townDialog.kind().equals("OpenShop")){
                    if(request.method()!=1||a.size()!=3||!(a.get(0) instanceof SessionDecoder.Multiset buys)||!(a.get(1) instanceof SessionDecoder.Multiset sells)||!buys.counts().keySet().stream().allMatch(k->k instanceof Integer)||!sells.counts().keySet().stream().allMatch(k->k instanceof Long))throw new SessionDecoder.Rejected("Invalid shop transaction");
                    var purchases=new java.util.HashMap<Integer,Integer>();buys.counts().forEach((key,value)->purchases.put((Integer)key,value));var sales=new java.util.HashMap<Long,Integer>();sells.counts().forEach((key,value)->sales.put((Long)key,value));
                    result=store.shop(characters.accountId(),knight.id(),authRevision,townDialog,townShopCycle,purchases,sales);
                }else if(townDialog.kind().equals("OpenHarnessInstaller")){
                    if(request.method()!=1||a.size()!=2||!(a.getFirst() instanceof List<?> indices)||indices.isEmpty()||indices.size()>100||!indices.stream().allMatch(v->v instanceof Integer))throw new SessionDecoder.Rejected("Invalid harness installation");
                    var selected=new HashSet<Integer>();for(var index:indices)if(!selected.add((Integer)index))throw new IOException("Duplicate harness offer");
                    result=store.installHarnesses(characters.accountId(),knight.id(),authRevision,townDialog,townShopCycle,selected);
                }else if(townDialog.kind().equals("OpenUnbinder")){
                    if(request.method()!=1||a.size()!=3||!(a.get(0) instanceof Long)||!(a.get(1) instanceof Long))throw new SessionDecoder.Rejected("Invalid unbind request");
                    result=store.unbind(characters.accountId(),knight.id(),authRevision,(Long)a.get(0),(Long)a.get(1));
                }else if(townDialog.kind().equals("OpenAccessorizer")){
                    if(a.size()!=3||!(a.get(0) instanceof Long gear)||request.method()<1||request.method()>3||!(request.method()==2?a.get(1) instanceof SessionDecoder.EnumValue e&&e.type().equals("com.threerings.projectx.item.data.ItemCodes$AccessoryLocation"):a.get(1) instanceof Long))throw new SessionDecoder.Rejected("Invalid accessory request");
                    result=store.accessorize(characters.accountId(),knight.id(),authRevision,request.method(),gear,a.get(1),knight.costume());
                }else if(townDialog.kind().equals("OpenVariantMaker")){
                    boolean custom=request.method()==1;int offset=custom?0:1;
                    if(request.method()<1||request.method()>2||a.size()!=(custom?4:5)||!custom&&!(a.get(0) instanceof Integer)||!(a.get(offset) instanceof Long)||!(a.get(offset+1) instanceof Long)||!(a.get(offset+2) instanceof Integer))throw new SessionDecoder.Rejected("Invalid variant request");
                    result=store.variant(characters.accountId(),knight.id(),authRevision,custom?1:(Integer)a.get(0),(Long)a.get(offset),(Long)a.get(offset+1),(Integer)a.get(offset+2),custom);
                }else if(townDialog.kind().equals("OpenCrafter")||townDialog.kind().equals("OpenCatalogCrafter")){
                    if(a.size()!=4||!(a.get(1) instanceof long[] ids)||!(a.get(2) instanceof Boolean upgrade))throw new SessionDecoder.Rejected("Invalid craft request");
                    String recipe;boolean catalog=request.method()==1;
                    if(catalog){if(!(a.get(0) instanceof Integer index)||index<0||index>=townDialog.recipes().size())throw new IOException("Invalid recipe index");recipe=townDialog.recipes().get(index);}
                    else{if(request.method()!=2||!(a.get(0) instanceof Long id))throw new SessionDecoder.Rejected("Invalid recipe request");recipe=profile.town().recipes().stream().filter(r->r.id()==id).map(org.tortodrone.town.TownState.LearnedRecipe::config).findFirst().orElseThrow(()->new IOException("Recipe not learned"));if(!townDialog.recipes().isEmpty()&&!townDialog.recipes().contains(recipe))throw new IOException("Recipe not served here");}
                    result=store.craft(characters.accountId(),knight.id(),authRevision,recipe,ids,upgrade,catalog);
                }else throw new IOException("This station's operation is unavailable");
            }else if(wrap||unwrap){
                if(trades!=null&&trades.active(tradeMember))throw new IOException("Finish the trade first");
                if(unwrap){if(a.size()!=2||!(a.getFirst() instanceof Long id))throw new SessionDecoder.Rejected("Invalid gift unwrap request");result=store.unwrap(characters.accountId(),knight.id(),authRevision,id);}
                else{
                    if(a.size()!=4||!(a.get(0) instanceof Long paper)||!(a.get(1) instanceof SessionDecoder.Multiset contents)||!(a.get(2) instanceof String message)||!contents.counts().keySet().stream().allMatch(k->k instanceof Long))throw new SessionDecoder.Rejected("Invalid gift wrap request");
                    var selected=new LinkedHashMap<Long,Integer>();contents.counts().forEach((key,count)->selected.put((Long)key,count));
                    result=store.wrap(characters.accountId(),knight.id(),authRevision,paper,selected,message,knight.costume());
                }
            }else if(forge){
                if(a.size()!=4||!(a.get(0) instanceof Long item)||!(a.get(1) instanceof Integer crystals)||!(a.get(2) instanceof Integer level))throw new SessionDecoder.Rejected("Invalid forge request");
                if(trades!=null&&trades.active(tradeMember))throw new IOException("Finish the trade first");
                result=store.forge(characters.accountId(),knight.id(),authRevision,item,crystals,level);
            }else if(hatch||learn||harness){if(a.size()!=(hatch?2:3)||!(a.get(0) instanceof Long id))throw new SessionDecoder.Rejected("Invalid Town item request");
                if(trades!=null&&trades.active(tradeMember))throw new IOException("Finish the trade first");
                result=hatch?store.hatch(characters.accountId(),knight.id(),authRevision,id):harness?store.installHarness(characters.accountId(),knight.id(),authRevision,id):store.learn(characters.accountId(),knight.id(),authRevision,id);
            }else{
                int method=request.method();int[] counts={0,4,6,3,2,3,3,4,3,4};if(method<1||method>9||a.size()!=counts[method])throw new SessionDecoder.Rejected("Invalid sprite argument count");
                boolean valid=switch(method){case 1->a.get(0) instanceof Integer&&a.get(1) instanceof Integer&&a.get(2) instanceof Integer;case 2->a.subList(0,5).stream().allMatch(v->v instanceof Integer);case 3->a.get(0) instanceof Integer&&(a.get(1)==null||a.get(1) instanceof SessionDecoder.EnumValue e&&e.type().equals("com.threerings.projectx.sprites.data.SpritePerk"));case 4->a.get(0) instanceof Integer;case 5->a.get(0) instanceof Integer&&a.get(1) instanceof Long;case 6->a.get(0) instanceof Long&&a.get(1) instanceof Integer;case 7->a.get(0) instanceof Integer&&a.get(1) instanceof Long&&a.get(2) instanceof Boolean;case 8->a.get(0) instanceof Integer&&a.get(1) instanceof String;case 9->a.get(0) instanceof Integer&&a.get(1) instanceof Integer&&a.get(2) instanceof SessionDecoder.EnumValue e&&e.type().equals("com.threerings.projectx.sprites.data.SpriteCodes$Ultimate");default->false;};
                if(!valid)throw new SessionDecoder.Rejected("Invalid sprite arguments");result=store.sprite(characters.accountId(),knight.id(),authRevision,method,a,staff);
            }
            // Learning publishes its committed recipe through EntryAddedEvent below. Replacing
            // recipes first makes the stock DSet reject that addition and suppress its observer.
            if(learn)membership.effect(new StarterCatalog.Model("Player/Learn Recipe",Map.of()));
            townChanged(result,!learn);if(operation&&townDialog!=null&&Set.of("OpenShop","OpenHarnessInstaller").contains(townDialog.kind()))publishShop();Object payload=result.result();
            if(learn){
                var fresh=result.profile().town().recipes().stream()
                    .filter(r->priorRecipes.stream().noneMatch(p->p.id()==r.id())).toList();
                for(var recipe:fresh){var entry=recipe;output.send(()->codec.recipeAdded(oid,entry));}
            }if(hatch&&payload instanceof Integer id)payload=profile.town().sprites().stream().filter(s->s.id()==id).findFirst().orElseThrow();
            final Object response=payload;if(resultListener)output.send(()->codec.townResult(oid,listener.requestId(),response,profile));else output.send(()->codec.invocationConfirmed(oid,listener.requestId()));
        }catch(CharacterStore.Refused|IOException refused){if(refused instanceof SessionDecoder.Rejected rejected)throw rejected;output.send(()->forge?codec.forgeFailure(oid,listener.requestId(),refused.getMessage()):codec.invocationFailure(oid,listener.requestId(),refused.getMessage()));}
        return true;
    }

    synchronized boolean conversationInvocation(SessionDecoder.Invocation request)throws IOException {
        if(conversation!=null&&ownsScene(request.oid())&&request.service()==6&&request.method()==1){
            if(request.arguments().size()!=1||!(request.arguments().getFirst() instanceof Long created))throw new SessionDecoder.Rejected("Invalid conversation close");
            if(created!=conversationCreated)return true;closeConversation();return true;
        }
        if(!ownsScene(request.oid())||request.service()!=SceneConversation.SERVICE)return false;
        var args=request.arguments();
        if(!ownsScene(request.oid())||!entered||request.method()!=1||args.size()!=2
                ||!(args.get(0) instanceof Integer choice)||!(args.get(1) instanceof SessionDecoder.Listener listener)
                ||!listener.type().endsWith("$ConfirmMarshaller"))throw new SessionDecoder.Rejected("Invalid conversation response");
        if(conversationResponses.contains(listener.requestId())){output.send(()->codec.invocationConfirmed(oid,listener.requestId()));return true;}
        if(conversation==null)throw new SessionDecoder.Rejected("No active conversation");
        var responses=conversation.nodes().get(conversationNode).responses();
        if(choice<0||choice>=responses.size()){output.send(()->codec.invocationFailure(oid,listener.requestId(),"Invalid conversation choice"));return true;}
        var response=responses.get(choice);String destination=response.destination();
        var terminal=scene.map().id()==217&&conversation.actor().equals(RHENDON_CAMP)&&conversationNode.equals("part 3a")&&choice==0?OpeningMission.SECURING_CAMP:
            OpeningComlinks.terminal(conversationMission,conversation,conversationNode,choice)?conversationMission:null;
        if(terminal!=null){
            var own=scene.simulation().snapshot().players().stream().filter(a->a.id()==profile.worldId()).findFirst().orElse(null);
            boolean validSource=terminal==OpeningMission.SECURING_CAMP?membership.conversationValid(conversation,conversationNode,choice,conversationSource):
                entered&&subscribed&&scene.map().id()==OpeningComlinks.scene(terminal)&&membership.health().current()>0&&conversationSource!=null&&conversationSource.id()==profile.worldId()&&scene.simulation().snapshot().portal(profile.worldId())==null;
            if(own==null||terminal==OpeningMission.SECURING_CAMP&&Math.hypot(own.x()-conversationX,own.y()-conversationY)>1||!validSource){
                output.send(()->codec.invocationFailure(oid,listener.requestId(),"Conversation source is unavailable"));return true;
            }
            try{
                var expectedTerminal=new SceneActions.Sequence(List.of(new SceneActions.Memory(new KnightMemory("talkto_rhendon_icon",false)),new SceneActions.Objective(new KnightObjective("findRhendon",null))),false);
                if(terminal==OpeningMission.SECURING_CAMP?!expectedTerminal.equals(response.action()):response.action()!=null)throw new IOException("Unsupported mission terminal action graph");
                profile=loadProfile(profile);var before=profile;boolean firstEver=completedOpening.isEmpty();
                var settled=characters.openingMissions(scenes.town()).complete(characters.accountId(),knight.id(),authRevision,profile,terminal,null);
                closeConversation();profile=settled.profile();
                if(!memories.equals(settled.memories())){var old=memories;memories=settled.memories();output.send(()->codec.knightMemoryChanges(oid,old,memories));}
                if(!objectives.equals(settled.objectives())){objectives=settled.objectives();output.send(()->codec.knightObjectives(oid,objectives));}
                if(settled.first()){
                    var completed=new EnumMap<OpeningMission,OpeningCompletion>(OpeningMission.class);completed.putAll(completedOpening);completed.put(terminal,settled.completion());completedOpening=Map.copyOf(completed);
                    membership.equipment(visibleProfile());conversationCreated=Math.max(System.currentTimeMillis(),conversationCreated+1);missionDialogCreated=conversationCreated;
                    output.send(()->codec.missionSettlement(oid,before,profile,settled.completion(),conversationCreated));
                    output.send(()->codec.itemChanges(oid,knight,before,profile));
                    if(firstEver)output.send(()->codec.controllerAction(oid,"new_mission",own));partyState();
                }
                rememberConversationResponse(listener.requestId());output.send(()->codec.invocationConfirmed(oid,listener.requestId()));
            }catch(IOException failure){output.send(()->codec.invocationFailure(oid,listener.requestId(),failure.getMessage()));}
            return true;
        }
        if(response.action()!=null&&!membership.conversationResponse(conversation,conversationNode,choice,conversationSource)){
            output.send(()->codec.invocationFailure(oid,listener.requestId(),"Conversation source is unavailable"));return true;
        }
        if(!response.station().isEmpty()){
            var station=scenes.town().station(response.station());
            if(station==null){output.send(()->codec.invocationFailure(oid,listener.requestId(),"Town service is unavailable"));return true;}
            try{openTownDialog(station,conversationSource);}catch(IOException failure){output.send(()->codec.invocationFailure(oid,listener.requestId(),failure.getMessage()));return true;}
        }else if(!response.command().isEmpty()){
            var source=conversationSource;closeConversation();output.send(()->codec.controllerAction(oid,response.command(),source));
        }else if(destination.isEmpty())closeConversation();else{
            conversationNode=destination;output.send(()->codec.conversationDialog(oid,scene.oid(),conversation,conversationNode,conversationSource,conversationCreated));
        }
        rememberConversationResponse(listener.requestId());
        output.send(()->codec.invocationConfirmed(oid,listener.requestId()));return true;
    }
    private void rememberConversationResponse(short id){conversationResponses.add(id);if(conversationResponses.size()>256){var oldest=conversationResponses.iterator();oldest.next();oldest.remove();}}
    private static final String RHENDON_CAMP="Character/NPC/Rescue Camp/Rhendon, Rescue Camp";
    private void closeConversation()throws IOException {
        if(conversation==null)return;conversation=null;conversationSource=null;conversationNode=null;conversationMission=null;
        output.send(()->codec.conversationDialog(oid,scene.oid(),null,null,null,conversationCreated));
    }

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
    }

    synchronized boolean selected() { return knight != null; }
    boolean hasInteriors(){return scenes.catalog().scenes().stream().anyMatch(map->!map.town());}
    synchronized boolean ownsScene(int oid) { return selected() && !readyRoom && scene.oid()==oid; }
    synchronized boolean ownsInteriorParty(int target){return selected()&&!readyRoom&&scenes.parties().owns(partyMember,target);}
    /** Cross-session friend follow only reads the peer's stable party seat under its own monitor. */
    synchronized PartyRegistry.Member partyMemberSnapshot(){return partyMember;}
    synchronized void subscribeInteriorParty(short message)throws IOException {
        if(readyRoom)throw new IOException("The Ready Room has no scene party");
        profile=loadProfile(profile);
        partyState();var view=scenes.parties().view(partyMember);
        var health=membership.health();var status=membership.status();output.send(()->codec.party(message,view,scene,floorReport!=null&&floorReport.valid()));
        subscribedPartyOid=view.oid();publishedParty=view;
        if(floorReport!=null&&floorReport.valid())output.send(()->scene.opening()==null?codec.finalFloorReport(view.oid()):codec.openingFloorReport(view.oid()));
        publishedHealth=health;publishedStatus=status;
        if(deferredMove!=null&&deferredMove.sceneOid()==scene.oid()){
            var move=deferredMove;deferredMove=null;completeMove(move.requestId(),move.zone());
        }
    }
    synchronized void unsubscribeInteriorParty(int party){if(subscribedPartyOid==party){subscribedPartyOid=0;publishedParty=null;}}
    synchronized boolean interiorPartyInvocation(SessionDecoder.Invocation request)throws IOException {
        if(!ownsInteriorParty(request.oid()))return false;
        if(request.service()==SceneChat.PARTY_SERVICE)return false;
        var args=request.arguments();
        if(request.service()==SessionCodec.LEVEL_PARTY_SERVICE&&request.method()==3){
            if(args.size()!=1||!(args.getFirst() instanceof Boolean descend))throw new SessionDecoder.Rejected("Invalid floor report");
            // Native reportComplete has no callback. A late or premature vote does nothing.
            if(!closed&&subscribed&&entered&&portalMove==null&&floorReport!=null&&floorReport.report(descend,SceneClock.now())){
                if(scene.tutorial()!=null)finishTutorialFloor(SceneClock.now());
                else if(scene.opening()!=null)finishOpeningFloor(SceneClock.now());
            }
            return true;
        }
        if(request.service()==20&&request.method()==4){
            if(args.size()!=3||!(args.get(0) instanceof String target)||!(args.get(1) instanceof Integer cost)||!(args.get(2) instanceof SessionDecoder.Listener listener)||!listener.type().equals("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller"))throw new SessionDecoder.Rejected("Invalid revive request");
            try{
                if(closed||!subscribed||!entered||scene.map().town())throw new IOException("m.no_revive");
                if(trades!=null&&trades.active(tradeMember))throw new IOException("Finish the trade first");
                var result=scenes.parties().revive(partyMember,request.oid(),target,world->
                    scene.simulation().revive(membership,world,()->characters.town(scenes.town()).revive(characters.accountId(),knight.id(),authRevision,cost)));
                townChanged(result);
                output.send(()->codec.invocationConfirmed(oid,listener.requestId()));
            }catch(IOException refused){output.send(()->codec.reviveFailure(oid,listener.requestId(),refused.getMessage()));}
            return true;
        }
        if(request.service()==SessionCodec.LEVEL_PARTY_SERVICE&&request.method()>=4&&request.method()<=6){
            if(args.size()!=2||!(args.get(0) instanceof Boolean flag)||!(args.get(1) instanceof SessionDecoder.Listener listener)||!listener.type().equals("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller"))throw new SessionDecoder.Rejected("Invalid party settings request");
            try{
                if(closed||!subscribed||!entered||scene.tutorial()!=null)throw new IOException("m.internal_error");
                switch(request.method()){
                    case 4->scenes.parties().setAllowFriends(partyMember,flag);
                    case 5->scenes.parties().setAllowGuild(partyMember,flag);
                    default->scenes.parties().setInviteOnly(partyMember,flag);
                }
                refreshParty();
                output.send(()->codec.invocationConfirmed(oid,listener.requestId()));
            }catch(IOException refused){output.send(()->codec.invocationFailure(oid,listener.requestId(),refused.getMessage()));}
            return true;
        }
        throw new SessionDecoder.Rejected("Unsupported interior party operation");
    }
    synchronized boolean departingScene(int oid) {
        // Returning from the client-local Ready Room can reuse the reserved town OID.
        // Once that place is subscribed again its enteredPlace is current, not stale.
        return !closed&&departedSceneOid!=0&&departedSceneOid==oid
            &&(readyRoom||portalMove!=null||!subscribed||scene.oid()!=oid);
    }
    synchronized boolean ownsTrade(int objectId){return trades!=null&&trades.owns(tradeMember,objectId);}
    void subscribeTrade(int objectId,short message)throws IOException {
        PlayerTrades broker;PlayerTrades.Member member;
        synchronized(this){broker=trades;member=tradeMember;}
        if(broker==null)throw new IOException("No such trade");broker.subscribe(member,objectId,message);
    }
    synchronized void unsubscribeTrade(int objectId){if(trades!=null)trades.unsubscribe(tradeMember,objectId);}

    /** Route across players without holding either WorldSession's monitor. */
    boolean tradeInvocation(SessionDecoder.Invocation request)throws IOException {
        boolean invite=request.service()==6&&request.method()==4&&ownsScene(request.oid());
        boolean close=request.service()==6&&request.method()==1&&ownsScene(request.oid());
        boolean response=request.oid()==SessionCodec.INVOCATION_OID&&request.service()==12&&request.method()==5;
        boolean operation=request.service()==1&&request.oid()>=1001&&(request.oid()&1)==1;
        if(!invite&&!close&&!response&&!operation)return false;
        var args=request.arguments();PlayerTrades broker;PlayerTrades.Member member;
        synchronized(this){broker=trades;member=tradeMember;}
        if(close){if(args.size()!=1||!(args.getFirst() instanceof Long))throw new SessionDecoder.Rejected("Invalid dialog close");
            if(broker!=null)broker.close(member,(Long)args.getFirst());return true;}
        if(args.isEmpty()||!(args.getLast() instanceof SessionDecoder.Listener listener)
                ||!listener.type().equals("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller"))
            throw new SessionDecoder.Rejected("Invalid trade confirmation listener");
        boolean valid=invite?args.size()==2&&args.getFirst() instanceof String:
            response?args.size()==3&&args.get(0) instanceof String&&args.get(1) instanceof Integer:
            request.method()>=1&&request.method()<=5&&(request.method()==2||request.method()==3?
                args.size()==3&&args.get(0) instanceof Long&&args.get(1) instanceof Integer:
                args.size()==2&&args.get(0) instanceof Integer);
        if(!valid)throw new SessionDecoder.Rejected("Invalid trade arguments");
        try {
            if(member==null||broker==null)throw new IOException("Enter a scene before trading");
            if(invite){if(!chatRate.allow(System.nanoTime()))throw new IOException("Please slow down before inviting another trade");broker.invite(member,(String)args.get(0));}
            else if(response)broker.respond(member,(String)args.get(0),(Integer)args.get(1));
            else broker.operate(member,request.oid(),request.method(),args);
            output.send(()->codec.invocationConfirmed(oid,listener.requestId()));
        }catch(IOException refused){output.send(()->codec.invocationFailure(oid,listener.requestId(),refused.getMessage()));}
        return true;
    }

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
        if(scenes.catalog().scenes().isEmpty())throw new IOException("Maps are unavailable; install default maps and restart the server");
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
            int destination=scenes.catalog().scenes().stream().filter(map->map.id()==saved&&map.town())
                .mapToInt(SceneCatalog.MapScene::id).findFirst().orElse(scenes.catalog().entry().id());
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
        if(characters.hasWorldStorage()){characters.friends().session(characters.accountId(),knight.id(),authRevision);profile=characters.worldProfile(knight.name(),authRevision,profile);}
        friendOnline=true;
        readyRoom=stageInReadyRoom;
        if(readyRoom)friendLocation=new FriendPresence("~",ReadyRoom.ZONE,0);
        else attach();
        partyMember=scenes.parties().join(oid,knight,profile,this::offerParty);
        if(profile.progress().crashSiteIncomplete())scenes.parties().enterTutorial(partyMember);
        partyState();
        if(characters.hasWorldStorage())memories=characters.knightMemories(knight.id(),authRevision);
        if(characters.hasWorldStorage())objectives=characters.knightObjectives(knight.id(),authRevision);
        if(characters.hasWorldStorage())completedOpening=characters.openingMissions(scenes.town()).load(characters.accountId(),knight.id(),authRevision);
        output.send(() -> codec.selected(oid, knight, profile));
        output.send(() -> codec.knightMemories(oid,memories));
        output.send(() -> codec.knightObjectives(oid,objectives));
        output.send(()->codec.completedMissions(oid,completedOpening.values()));
        output.send(() -> codec.townState(oid,profile));
        publishStyles();
        output.send(() -> codec.playerFlags(oid,gameMaster,inspectionHidden,profile.progress().tutorialComplete()));
        if(characters.hasWorldStorage())refreshFriends(null,characters.friends().snapshot(),friendPresence(characters.worldStorageFile()));
        refreshParty();
        receiverChanged(receivers);
        log.selected(knight.id(),profile.worldId(),knight.name());
        log.info("Character selected; knight=" + knight.name() + " (#" + profile.worldId() + "); rosterId="+knight.id());
        if(!readyRoom)monitorScene();
        log.debug("Knight/scene initialization completed; duration-ms="+((System.nanoTime()-selectionStarted)/1000000));
    }

    /** Call outside the CharacterStore publication lock to preserve session/account lock order. */
    synchronized void refresh() throws IOException {
        if (selected()) {
            var visible=visibleProfile();output.send(() -> codec.selected(oid, knight, visible));
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
        }
    }

    synchronized boolean invocation(SessionDecoder.Invocation request) throws IOException {
        if(closed||socket.isClosed())throw new IOException("Session is closed");
        int service = request.service();
        var args = request.arguments();
        if(service==1&&request.method()==11){
            if(!args.isEmpty())throw new SessionDecoder.Rejected("Invalid return-to-last request");
            if(!selected()||forcedReceiver==null)throw new SessionDecoder.Rejected("Select a knight first");
            try{characters.worldProfile(knight.name(),authRevision,profile);}
            catch(CharacterStore.Refused refused){throw new IOException("Knight changed; reconnect",refused);}
            if(readyRoom){returnRequested=true;short receiver=forcedReceiver;output.send(()->codec.forceScene(oid,receiver,scene));}
            return true;
        }
        if(service==1&&request.method()==7){
            if(args.size()!=2||!(args.get(0) instanceof String destination)||!(args.get(1) instanceof SessionDecoder.Listener listener)||!listener.type().endsWith("$ResultMarshaller"))throw new SessionDecoder.Rejected("Invalid town destination request");
            try{
                if(!selected()||!entered||!subscribed||forcedReceiver==null||portalMove!=null)throw new IOException("Finish entering this scene first");characters.worldProfile(knight.name(),authRevision,profile);
                SceneCatalog.MapScene map=null;ScenePortals.Entrance entrance=null;AlchemyDestination alchemy=null;
                if(destination.equals("move_alchemy_machine")){
                    alchemy=selectAlchemy();if(alchemy!=null){entrance=alchemy.entrance();map=scene.map();}
                }
                if(destination.equals("haven"))map=scenes.catalog().entry();
                if(map==null)for(var candidate:scenes.catalog().scenes())if(candidate.name().equals("%town:m."+destination)||candidate.id()==switch(destination){case "training"->294;case "lab"->30893;case "hoh"->318;default->-1;}){map=candidate;break;}
                if(map==null&&destination.matches("[a-zA-Z0-9_-]{1,96}"))for(var candidate:scenes.catalog().scenes())if(candidate.town()){
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
        if(service==1&&request.method()==11){
            if(!args.isEmpty())throw new SessionDecoder.Rejected("Invalid haven request");
            try{
                if(!selected()||!entered||!subscribed||forcedReceiver==null||portalMove!=null)throw new IOException("Finish entering this scene first");characters.worldProfile(knight.name(),authRevision,profile);
                var map=scenes.catalog().entry();var next=destination(map.id());
                if(next==scene)throw new IOException("You are already in that scene");
                var entrance=new ScenePortals.Entrance(1,map.spawnX(),map.spawnY(),map.spawnRotation());
                closeConversation();closeTownDialog();portalMove=new PortalMove(next,entrance);departedSceneOid=scene.oid();leaveChat();subscribed=entered=false;sceneGeneration++;membership.pause();short receiver=forcedReceiver;
                output.send(()->codec.forceScene(oid,receiver,next));
            }catch(CharacterStore.Refused|IOException refused){unavailable(refused.getMessage());}
            return true;
        }
        if(service==1&&request.method()==13) {
            if(args.size()!=1||!(args.getFirst() instanceof Boolean hidden))throw new SessionDecoder.Rejected("Invalid inspect privacy request");
            characters.inspectionHidden(authRevision,hidden);inspectionHidden=hidden;
            gameMaster=roles.gameMaster();if(membership!=null)membership.appearance(actorAppearance());
            output.send(()->codec.playerFlags(oid,gameMaster,inspectionHidden,profile.progress().tutorialComplete()));return true;
        }
        if ((service == 4 || service == 5) && request.method() == 1) {
            move(args, service == 5);
            return true;
        }
        if (service == 10 && request.method() == 7) {
            if (args.size() != 2 || !(args.get(0) instanceof String)
                    || !(args.get(1) instanceof SessionDecoder.Listener listener)
                    || !listener.type().endsWith("$ResultMarshaller")) {
                throw new SessionDecoder.Rejected("Invalid Uplink News request");
            }
            if (selected()) { var news=scenes.news(); output.send(() -> codec.listResult(oid, listener.requestId(), news.propertyList())); }
            else output.send(() -> codec.invocationFailure(oid, listener.requestId(), "Select a knight first"));
            return true;
        }
        if (service == 11 && request.method() == 1) {
            if (args.size() != 2 || !(args.get(0) instanceof Long)
                    || !(args.get(1) instanceof SessionDecoder.Listener listener)
                    || !listener.type().endsWith("$ResultMarshaller")) {
                throw new SessionDecoder.Rejected("Invalid social feed request");
            }
            if (selected()) output.send(() -> codec.emptyListResult(oid, listener.requestId()));
            else output.send(() -> codec.invocationFailure(oid, listener.requestId(), "Select a knight first"));
            return true;
        }
        if(service==9&&(request.method()==4||request.method()==10)) {
            if(args.size()!=2||!(args.getFirst() instanceof String name)||!(args.getLast() instanceof SessionDecoder.Listener listener)||!listener.type().equals("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller"))throw new SessionDecoder.Rejected("Invalid loadout request");
            try {
                if(!selected()||!subscribed||!entered||portalMove!=null)throw new IOException("Enter a scene first");
                if(trades!=null&&trades.active(tradeMember))throw new IOException("Finish the trade first");
                if(request.method()==4&&!membership.equipmentAllowed())throw new IOException("Equipment is locked outside an Arsenal Station in this level");
                var result=characters.town(scenes.town()).loadout(characters.accountId(),knight.id(),authRevision,name,request.method()==10);
                townChanged(result);output.send(()->codec.invocationConfirmed(oid,listener.requestId()));
            }catch(IOException refused){output.send(()->codec.invocationFailure(oid,listener.requestId(),refused.getMessage()));}
            return true;
        }
        if(service==9&&request.method()==2){
            if(request.oid()!=SessionCodec.INVOCATION_OID||args.size()!=2||!(args.getFirst() instanceof String name)||!(args.getLast() instanceof SessionDecoder.Listener listener)||!listener.type().endsWith("$ConfirmMarshaller"))throw new SessionDecoder.Rejected("Invalid loadout removal");
            try{if(!selected()||!entered||!subscribed||portalMove!=null)throw new IOException("Enter a scene first");
                townChanged(characters.town(scenes.town()).removeLoadout(characters.accountId(),knight.id(),authRevision,name));
                output.send(()->codec.invocationConfirmed(oid,listener.requestId()));
            }catch(IOException refused){output.send(()->codec.invocationFailure(oid,listener.requestId(),refused.getMessage()));}return true;
        }
        if (service == 9 && request.method() == 9) {
            equip(args);
            return true;
        }
        if(service==1&&request.method()==15){
            if(request.oid()!=SessionCodec.INVOCATION_OID||args.size()!=3||!(args.get(0) instanceof String name)||!(args.get(1) instanceof Boolean enabled)
                    ||!(args.get(2) instanceof SessionDecoder.Listener listener)||!listener.type().equals("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller"))throw new SessionDecoder.Rejected("Invalid PvP loadout request");
            try{
                if(!selected()||!subscribed||!entered||portalMove!=null)throw new IOException("Enter a scene first");
                townChanged(characters.town(scenes.town()).loadoutPvp(characters.accountId(),knight.id(),authRevision,name,enabled));
                output.send(()->codec.invocationConfirmed(oid,listener.requestId()));
            }catch(IOException refused){output.send(()->codec.invocationFailure(oid,listener.requestId(),refused.getMessage()));}
            return true;
        }
        // ItemMarshaller 1 (Trash) is dispatched in townInvocation; scene item use is 14 only.
        if(request.oid()==SessionCodec.INVOCATION_OID&&service==9&&request.method()==14){
            if(args.size()!=3||!(args.getFirst() instanceof Long id)||!(args.getLast() instanceof SessionDecoder.Listener listener)||!listener.type().equals("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller")||args.get(1)!=null)throw new SessionDecoder.Rejected("Invalid item use request");
            if(!selected()||!subscribed||!entered||portalMove!=null){output.send(()->codec.invocationFailure(oid,listener.requestId(),"Enter a scene first"));return true;}
            var token=new BoxRequest(profile.worldId(),listener.requestId());var receipt=confettiReplies.get(token);
            if(receipt!=null){
                try{characters.worldProfile(knight.name(),authRevision,profile);}
                catch(CharacterStore.Refused failure){output.send(()->codec.invocationFailure(oid,listener.requestId(),failure.getMessage()));return true;}
                if(receipt.item()!=id||receipt.failure()!=null)output.send(()->codec.invocationFailure(oid,listener.requestId(),receipt.failure()==null?"m.internal_error":receipt.failure()));
                else output.send(()->codec.invocationConfirmed(oid,listener.requestId()));return true;
            }
            var held=profile.items().stream().filter(i->i.id()==id).findFirst().orElse(null);
            if(held!=null&&(Confetti.supports(held.config())||org.tortodrone.character.AnimatedItems.supports(held.config()))){
                try{
                    var current=characters.worldProfile(knight.name(),authRevision,profile);
                    if(trades!=null&&trades.active(tradeMember))throw new IOException("Finish the trade first");
                    if(!membership.canOpenBox())throw new IOException("e.not_use_busy");
                    var store=characters.town(scenes.town());
                    var result=Confetti.supports(held.config())?store.confetti(characters.accountId(),knight.id(),authRevision,current.revision(),id)
                        :store.animatedItem(characters.accountId(),knight.id(),authRevision,current.revision(),id);
                    confettiReplies.put(token,new ConfettiReply(id,null));
                    String config=(String)result.result();
                    if(Confetti.supports(config))membership.confetti(config);else membership.animatedAction(config);
                    townChanged(result,false);
                    output.send(()->codec.invocationConfirmed(oid,listener.requestId()));
                }catch(CharacterStore.Refused|IOException failure){
                    confettiReplies.putIfAbsent(token,new ConfettiReply(id,failure.getMessage()));
                    output.send(()->codec.invocationFailure(oid,listener.requestId(),failure.getMessage()));
                }
                if(confettiReplies.size()>256)confettiReplies.remove(confettiReplies.keySet().iterator().next());
                return true;
            }
            var item=temporaryItems.get(id);var spec=item==null||!selected()?null:scene.map().content().items().get(item.config());
            if(spec!=null&&membership.useItem(id,spec))output.send(()->codec.invocationConfirmed(oid,listener.requestId()));
            else output.send(()->codec.invocationFailure(oid,listener.requestId(),"That item cannot be used now"));return true;
        }
        // Arsenal's equip action uses method 3, distinct from moveItem (method 9).
        if (service == 9 && request.method() == 3) {
            if (args.size() != 3 || !(args.get(0) instanceof Long)
                    || !(args.get(1) instanceof Integer) || !(args.get(2) instanceof SessionDecoder.Listener)) {
                throw new SessionDecoder.Rejected("Invalid equip item request");
            }
            equip(List.of(args.get(0), new SessionDecoder.EnumValue(
                "com.threerings.projectx.item.data.ItemCodes$Location", 1), args.get(1), args.get(2)));
            return true;
        }
        return false;
    }

    private record Inspection(Knight knight,WorldProfile profile,boolean hidden) { }
    /** Capture one target at a time, with no sender monitor held while inspecting a peer. */
    void inspect(SessionDecoder.Invocation request)throws IOException {
        var args=request.arguments();
        if(request.service()!=6||request.method()!=2||args.size()!=2||!(args.get(0) instanceof String target)
                ||!(args.get(1) instanceof SessionDecoder.Listener listener)
                ||!listener.type().equals("com.threerings.presents.data.InvocationMarshaller$ResultMarshaller"))
            throw new SessionDecoder.Rejected("Invalid inspection request");
        SceneRegistry.Instance place;
        synchronized(this) {
            if(closed||socket.isClosed()||!subscribed||!entered||!ownsScene(request.oid()))throw new SessionDecoder.Rejected("Unauthorized inspection");
            try{characters.worldProfile(knight.name(),authRevision);}catch(CharacterStore.Refused refused){throw new IOException("Knight changed; reconnect",refused);}
            place=scene;
        }
        WorldSession peer;
        synchronized(ACTIVE) {peer=ACTIVE.values().stream().filter(w->w.knight.name().equalsIgnoreCase(target)
            &&w.characters.worldStorageFile().equals(characters.worldStorageFile())).findFirst().orElse(null);}
        Inspection inspection=peer==null?null:peer.inspection(place);
        synchronized(this) {
            if(closed||scene!=place||!subscribed||!entered)return;
            if(inspection==null)output.send(()->codec.invocationFailure(oid,listener.requestId(),"That knight is not in this scene"));
            else output.send(()->codec.inspected(oid,listener.requestId(),inspection.knight(),inspection.profile(),inspection.hidden()));
        }
    }
    private synchronized Inspection inspection(SceneRegistry.Instance place)throws IOException {
        if(closed||socket.isClosed()||!subscribed||!entered||scene!=place)return null;
        try {var current=characters.worldProfile(knight.name(),authRevision);return new Inspection(knight,current,inspectionHidden);}
        catch(CharacterStore.Refused refused){return null;}
    }

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
                        int permanent=scenes.catalog().scenes().stream().filter(map->map.id()==saved&&map.town())
                            .mapToInt(SceneCatalog.MapScene::id).findFirst().orElse(scenes.catalog().entry().id());
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
            if(next!=scene||portalMove!=null||readyRoom) {
                closeConversation();
                if(!temporaryItems.isEmpty()){var before=visibleProfile();temporaryItems.clear();output.send(()->codec.itemChanges(oid,knight,before,profile));publishQuickbar();}
                leaveChat();
                subscribed=entered=false;sceneGeneration++;occupantClients=Map.of();occupantRoles=Map.of();lastSnapshot=null;
                reference=acknowledge=0;alchemyTarget=null;departedSceneOid=scene.oid();if(membership!=null)membership.close();if(scene!=next)scenes.release(scene,profile.worldId());scene=next;
                readyRoom=returnRequested=false;
                arrival=portalMove==null?null:portalMove.entrance();portalMove=null;
                created=SceneClock.now();attach();
                monitorScene();
            }
            int party=partyOid();
            // The stock PartyDirector resubscribes on every partyOid event,
            // even when its value is unchanged. Existing HUD listeners retain
            // the previous party object if a second snapshot replaces it.
            refreshParty(true);
            // The stock dungeon HUD creates its quickbar once, while the scene
            // model is attached. Let its party subscription complete first.
            if(scene.map().town()||subscribedPartyOid==party)completeMove(listener.requestId(),zone);
            else deferredMove=new DeferredMove(listener.requestId(),zone,scene.oid());
            log.debug("Scene transition initialized; duration-ms="+((System.nanoTime()-transitionStarted)/1000000));
        } catch(IOException error) {
            output.send(() -> codec.invocationFailure(oid,listener.requestId(),error.getMessage()));
        }
    }

    private void completeMove(short requestId,boolean zone)throws IOException {
        output.send(() -> codec.sceneMove(oid,requestId,zone,scene,scenes.catalog()));
        output.send(() -> codec.sceneLocation(oid,scene,scenes.channelOid(scene.map().zone(),false),scenes.channelOid(scene.map().zone(),true)));
    }

    private record AdminLocation(SceneRegistry.Instance scene,int world,String name,float x,float y,float rotation) { }
    private synchronized void requireAdminReady()throws IOException { requireAdminReady(false); }
    private synchronized void requireAdminReady(boolean roomTravel)throws IOException {
        if(closed||socket.isClosed()||!selected()||(!(roomTravel&&readyRoom)&&(!subscribed||!entered))||forcedReceiver==null||portalMove!=null||deferredMove!=null)throw new IOException("Finish entering this scene first");
        try{characters.worldProfile(knight.name(),authRevision);}catch(CharacterStore.Refused refused){throw new IOException("Knight changed; reconnect",refused);}
        if(!roles.gameMaster())throw new IOException("Game Master permission required");
    }
    private WorldSession adminTarget(String name)throws IOException {
        if(name.length()<3||name.length()>16||!name.matches("[A-Za-z]+(?:-[A-Za-z]+)*"))throw new IOException("Enter a valid knight name");
        synchronized(ACTIVE){return ACTIVE.values().stream().filter(w->w.knight.name().equalsIgnoreCase(name)
            &&w.scenes==scenes&&w.characters.worldStorageFile().equals(characters.worldStorageFile())).findFirst().orElseThrow(()->new IOException("That knight is not online"));}
    }
    private synchronized AdminLocation adminLocation()throws IOException {
        if(closed||socket.isClosed()||!selected()||!subscribed||!entered||portalMove!=null)throw new IOException("That knight is changing scenes");
        try{characters.worldProfile(knight.name(),authRevision);}catch(CharacterStore.Refused refused){throw new IOException("That knight is no longer online",refused);}
        var actor=scene.simulation().snapshot().players().stream().filter(a->a.id()==profile.worldId()).findFirst().orElseThrow(()->new IOException("That knight is not in a ready scene"));
        return new AdminLocation(scene,profile.worldId(),knight.name(),actor.x(),actor.y(),actor.rotation());
    }
    private synchronized void adminTravel(AdminLocation location)throws IOException {
        if(closed||socket.isClosed()||!selected()||(!readyRoom&&(!subscribed||!entered))||forcedReceiver==null||portalMove!=null||deferredMove!=null)throw new IOException("Finish entering this scene first");
        try{characters.worldProfile(knight.name(),authRevision);}catch(CharacterStore.Refused refused){throw new IOException("Knight changed; reconnect",refused);}
        if(trades!=null&&trades.active(tradeMember))throw new IOException("Finish the trade first");
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
    boolean adminPeople(SessionDecoder.Invocation request)throws IOException {
        int method=request.method();if(!Set.of(13,21,24,27,28,30,50).contains(method))return false;
        var args=request.arguments();
        if(request.oid()!=SessionCodec.INVOCATION_OID||request.service()!=SessionCodec.ADMIN_SERVICE||args.size()!=(method==24?3:2)
                ||!(args.getLast() instanceof SessionDecoder.Listener listener)||!listener.type().equals("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller")
                ||(method==24?!(args.get(0) instanceof Integer)||!(args.get(1) instanceof Integer):!(args.getFirst() instanceof String)))throw new SessionDecoder.Rejected("Invalid player admin request");
        String name=method==24?null:(String)args.getFirst();
        try {
            requireAdminReady(method==28||method==30);
            if(!chatRate.allow(System.nanoTime()))throw new IOException("Please slow down before sending another command");
            if(method==24) {
                var own=adminLocation();adminTravel(new AdminLocation(own.scene(),own.world(),own.name(),(Integer)args.get(0),(Integer)args.get(1),own.rotation()));
            }else if(method==28||method==30) {
                SceneCatalog.MapScene map=null;
                String key=name.strip();
                for(var candidate:scenes.catalog().scenes()) {
                    boolean match=method==30?candidate.town()&&(candidate.zoneName().equalsIgnoreCase(key)||candidate.zoneName().equalsIgnoreCase("%town:m."+key)||candidate.zoneName().equalsIgnoreCase("%zone:m."+key)||Integer.toString(candidate.zone()).equals(key)):
                        candidate.name().equalsIgnoreCase(key)||Integer.toString(candidate.id()).equals(key)||candidate.name().equalsIgnoreCase("%town:m."+key);
                    if(match){map=candidate;break;}
                }
                if(map==null)throw new IOException("No deployed scene or zone matches that name or ID");
                AdminLocation location;
                synchronized(this){requireAdminReady(true);var next=destination(map.id());if(next==scene&&!readyRoom)throw new IOException("You are already in that scene");location=new AdminLocation(next,profile.worldId(),knight.name(),map.spawnX(),map.spawnY(),map.spawnRotation());}
                adminTravel(location);
            }else {
                var target=adminTarget(name);var location=target.adminLocation();
                if(method==13) {
                    synchronized(this){requireAdminReady();feedback(scene.oid(),sceneGeneration,location.name()+"; knight="+location.world()+"; scene="+location.scene().map().name()+" ("+location.scene().map().id()+"); instance="+location.scene().oid());}
                }else if(method==21) {
                    if(target==this)throw new IOException("You cannot kick yourself");
                    // Recheck identity/readiness immediately before ending only this live connection.
                    synchronized(target){target.adminLocation();target.close();}
                    target.output.disconnect();
                }else if(method==27){requireAdminReady();if(location.scene()==scene)throw new IOException("That knight is already in this scene");adminTravel(location);}
                else {if(target==this)throw new IOException("You are already here");var own=adminLocation();requireAdminReady();if(location.scene()==own.scene())throw new IOException("That knight is already in this scene");target.adminTravel(own);}
            }
            output.send(()->codec.invocationConfirmed(oid,listener.requestId()));
        }catch(IOException refused){output.send(()->codec.invocationFailure(oid,listener.requestId(),refused.getMessage()));}
        return true;
    }

    /** Stock admin service, resolved by the client from the bootstrap; every call is authorized here. */
    synchronized void admin(SessionDecoder.Invocation request) throws IOException {
        if(request.service()!=SessionCodec.ADMIN_SERVICE||request.oid()!=SessionCodec.INVOCATION_OID)return;
        var args=request.arguments();
        SessionDecoder.Listener listener=args.isEmpty()||!(args.getLast() instanceof SessionDecoder.Listener last)?null:last;
        String failure=null;
        int method=request.method();
        if(method!=1&&method!=5&&method!=17&&method!=44&&method!=7&&method!=8&&method!=41&&method!=42&&method!=23&&method!=45&&method!=46&&method!=47) {
            // Unknown tools must not leave the stock dashboard waiting for a callback.
            String reason=roles.gameMaster()?"Placeholder: this admin tool is not implemented yet":"Game Master permission required";
            if(listener!=null){short id=listener.requestId();output.send(()->codec.invocationFailure(oid,id,reason));}
            else unavailable(reason);
            return;
        }
        if(listener==null||!listener.type().equals("com.threerings.presents.data.InvocationMarshaller$"+(method==23?"ResultMarshaller":"ConfirmMarshaller")))throw new SessionDecoder.Rejected("Invalid admin listener");
        if(method==1||method==5) {
            if(args.size()!=(method==1?3:2)||!(args.getFirst() instanceof Integer slot)||method==1&&!(args.get(1) instanceof String))throw new SessionDecoder.Rejected("Invalid accessory admin request");
            try {
                requireAdminReady();
                if(trades!=null&&trades.active(tradeMember))throw new IOException("Finish the trade first");
                var result=characters.town(scenes.town()).adminAccessory(characters.accountId(),knight.id(),authRevision,slot,method==1?(String)args.get(1):null,knight.costume());
                townChanged(result);output.send(()->codec.invocationConfirmed(oid,listener.requestId()));
            }catch(IOException refused){output.send(()->codec.invocationFailure(oid,listener.requestId(),refused.getMessage()));}
            return;
        }
        if(method==17||method==44) {
            if(args.size()!=(method==17?2:3)||!(args.getFirst() instanceof Integer)||method==44&&!(args.get(1) instanceof Integer))throw new SessionDecoder.Rejected("Invalid progress admin request");
            try {
                if(closed||!selected())throw new IOException("Select a knight first");
                if(!roles.gameMaster())throw new IOException("Game Master permission required");
                if(trades!=null&&trades.active(tradeMember))throw new IOException("Finish the trade first");
                var result=characters.town(scenes.town()).adminProgress(characters.accountId(),knight.id(),authRevision,method,args);
                townChanged(result);if(method==44)output.send(()->codec.knightProgress(oid,profile));
                output.send(()->codec.invocationConfirmed(oid,listener.requestId()));
            }catch(IOException refused){output.send(()->codec.invocationFailure(oid,listener.requestId(),refused.getMessage()));}
            return;
        }
        if(method==41||method==42||method==23){
            if(args.size()!=3||!(args.getFirst() instanceof Integer)||!(method==41?args.get(1) instanceof Float:args.get(1) instanceof Integer))throw new SessionDecoder.Rejected("Invalid gear admin request");
            try{
                if(closed||!selected())throw new IOException("Select a knight first");if(!roles.gameMaster())throw new IOException("Game Master permission required");
                if(trades!=null&&trades.active(tradeMember))throw new IOException("Finish the trade first");
                var result=characters.town(scenes.town()).adminGear(characters.accountId(),knight.id(),authRevision,method,args);townChanged(result);
                if(method==23)output.send(()->codec.townResult(oid,listener.requestId(),result.result(),profile));else output.send(()->codec.invocationConfirmed(oid,listener.requestId()));
            }catch(IOException refused){output.send(()->codec.invocationFailure(oid,listener.requestId(),refused.getMessage()));}return;
        }
        failure=method==7?createItem(args):adminSprite(method,args);
        if(failure==null)output.send(()->codec.invocationConfirmed(oid,listener.requestId()));
        else {String reason=failure;output.send(()->codec.invocationFailure(oid,listener.requestId(),reason));}
    }

    private String adminSprite(int method,List<Object> args)throws IOException{
        boolean valid=switch(method){
            case 8->args.size()==2&&args.getFirst() instanceof SessionDecoder.ConfigReference;
            case 45->args.size()==2&&args.getFirst() instanceof Integer;
            case 46->args.size()==4&&args.subList(0,3).stream().allMatch(v->v instanceof Integer);
            case 47->args.size()==3&&args.subList(0,2).stream().allMatch(v->v instanceof Integer);
            default->false;
        };
        if(!valid)return "Invalid sprite admin request";
        try{
            if(closed||!selected())return "Select a knight first";
            if(!roles.gameMaster())return "Game Master permission required";
            var result=characters.town(scenes.town()).adminSprite(characters.accountId(),knight.id(),authRevision,method,args);
            townChanged(result);
            log.info("Updated battle sprite; knightId="+profile.worldId()+"; adminMethod="+method);
            return null;
        }catch(IOException refused){return refused.getMessage();}
    }

    /** ProjectXAdminMarshaller.createItem(config, quantity, locked, expireDays), stock method seven. */
    private String createItem(List<Object> args)throws IOException {
        if(args.size()!=5||!(args.get(0) instanceof SessionDecoder.ConfigReference reference)
                ||reference.name()==null||!(args.get(1) instanceof Integer quantity)
                ||!(args.get(2) instanceof Boolean locked)||!(args.get(3) instanceof Integer expireDays))
            return "Invalid item creation request";
        try {
            if(!selected())return "Select a knight first";
            // The stored grant re-checks this account's privileges in the same transaction.
            if(!roles.gameMaster())return "Game Master permission required";
            if(quantity<1||quantity>ItemCatalog.GRANT_LIMIT)
                return "Quantity must be between 1 and "+ItemCatalog.GRANT_LIMIT;
            if(expireDays<0||expireDays>3650)return "Expiry must be between 0 and 3650 days";
            var entry=characters.catalog().require(reference.name());
            try{characters.worldProfile(knight.name(),authRevision);}
            catch(CharacterStore.Refused refused){throw new IOException("Knight changed; reconnect",refused);}
            var before=visibleProfile();
            profile=characters.createItem(knight.name(),entry.config(),quantity,authRevision,locked,expireDays);
            var after=visibleProfile();output.send(()->codec.itemChanges(oid,knight,before,after));
            log.info("Created "+quantity+" x "+entry.displayName()+"; knightId="+profile.worldId()+"; config="+entry.config());
            return null;
        }catch(IOException refused){return refused.getMessage();}
    }

    private void equip(List<Object> args) throws IOException {
        if (args.size() != 4 || !(args.get(0) instanceof Long item)
                || !(args.get(1) instanceof SessionDecoder.EnumValue location)
                || !location.type().equals("com.threerings.projectx.item.data.ItemCodes$Location")
                || !(args.get(2) instanceof Integer slot)
                || !(args.get(3) instanceof SessionDecoder.Listener listener)
                || !listener.type().endsWith("$ConfirmMarshaller")) {
            throw new SessionDecoder.Rejected("Invalid equipment request");
        }
        try {
            if (!selected()) throw new IOException("Select a knight first");
            if(location.ordinal()==2){
                if(slot<0||slot>=quickbar.length||item!=0&&(!temporaryItems.containsKey(item)||temporaryItems.get(item).equippedSlot()!=null))
                    throw new IOException("That item or quickbar slot is not available");
                for(int index=0;index<quickbar.length;index++)if(quickbar[index]==item)quickbar[index]=0;
                quickbar[slot]=item;publishQuickbar();output.send(()->codec.invocationConfirmed(oid,listener.requestId()));return;
            }
            if (location.ordinal() != 0 && location.ordinal() != 1) {
                throw new IOException("Only inventory and equipment moves are available");
            }
            if(!subscribed||!entered||!membership.equipmentAllowed())throw new IOException("Equipment is locked outside an Arsenal Station in this level");
            var before=visibleProfile();
            profile = characters.equip(knight.id(), authRevision, item, location.ordinal() == 0 ? null : slot);
            updateAppearance();
            var after=visibleProfile();output.send(() -> codec.itemChanges(oid, knight, before,after));
            output.send(() -> codec.invocationConfirmed(oid, listener.requestId()));
        } catch (IOException failure) {
            String reason = failure.getMessage();
            output.send(() -> codec.invocationFailure(oid, listener.requestId(), reason));
        }
    }

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
        chat=CHAT.join(place,oid,knight.name(),chatRate,message->deliverChat(place,generation,message));CHAT.away(chat,awayMessage);
        if(!welcomed&&!welcomeMessage.isEmpty()) {
            welcomed=true;
            output.send(()->codec.systemNotice(place,welcomeMessage));
        }
        trades=PlayerTrades.forStore(characters);
        tradeMember=trades.join(characters,knight,profile,authRevision,place,oid,output,codec,socket);
        lastStamp = SceneClock.now();
        var placeInstance=scene;
        membership.enter(snapshot->offer(placeInstance,generation,snapshot));
        if(scene.opening()!=null&&(!scenes.authorized(scene,profile.worldId(),partyMember)||!membership.authorizeElevator(Integer.toString(OpeningGate.GENERATOR_EXIT),Set.of(profile.worldId()))))throw new IOException("Opening mission exit is unavailable");
        arrivalComlink=null;
        for(var candidate:List.of(OpeningMission.FIRST_CONTACT,OpeningMission.WELCOME_HAVEN))if(scene.map().id()==OpeningComlinks.scene(candidate)&&candidate.eligible(profile.progress(),completedOpening.keySet()))arrivalComlink=candidate;
        if(auctionAfterArrival){auctionAfterArrival=false;try{openAuction();}catch(IOException refused){unavailable(refused.getMessage());}}
        persistence=WorldStatePoller.join(this,characters,profile,authRevision,knight.name());
        if(delivery==null)delivery=Thread.ofVirtual().name("SceneDelivery-"+oid).start(this::deliverPublications);
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
    private ActorState auctionSource(SceneRegistry.Instance destination)throws IOException {
        var source=destination.simulation().station("OpenAuction");
        if(source==null)throw new IOException("No Auction House in this scene");
        return source;
    }
    private void openAuction()throws IOException {
        var source=auctionSource(scene);
        output.send(()->codec.controllerAction(oid,"auction",source));
    }

    synchronized void unsubscribe() { leaveChat();subscribed = entered = false; sceneGeneration++;if(membership!=null)membership.pause(); }

    /** ProjectXSceneMarshaller.emote(String, Name, ConfirmListener), stock method three. */
    void emote(SessionDecoder.Invocation request)throws IOException {
        var args=request.arguments();
        if(request.service()!=6||request.method()!=3||args.size()!=3
                ||!(args.get(0) instanceof String)
                ||!(args.get(1)==null||args.get(1) instanceof String)
                ||!(args.get(2) instanceof SessionDecoder.Listener listener)
                ||!listener.type().equals("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller"))
            throw new SessionDecoder.Rejected("Invalid emote request");
        String failure=null,target=null;SceneChat.Membership sender=null;
        var descriptor=PlayerEmotes.find((String)args.getFirst());
        synchronized(this) {
          if(closed||socket.isClosed()||!subscribed||!entered||!ownsScene(request.oid()))
            failure="Enter a scene before using an emote";
          else {
            try{characters.worldProfile(knight.name(),authRevision);}
            catch(CharacterStore.Refused e){throw new IOException("Knight changed; reconnect",e);}
            if(descriptor==null)failure="This emote is not available";
            else if(descriptor.targetable()&&args.get(1)!=null) {
                String supplied=(String)args.get(1);
                target=scene.simulation().snapshot().actors().stream().map(ActorState::name)
                    .filter(n->n.equalsIgnoreCase(supplied)).findFirst().orElse(null);
                if(target==null)failure="That knight is not in this scene";
                else if(target.equalsIgnoreCase(knight.name()))failure="You can't do that to yourself";
            }
            if(failure==null) {
                if(!chatRate.allow(System.nanoTime()))failure="Please slow down before using another emote";
                else if(!membership.emote(descriptor,SceneClock.now(),monotonicTime()))failure="Finish your current action before using an emote";
                else sender=chat;
            }
          }
        }
        // Route outside the session lock: peer delivery acquires its own session monitor.
        if(failure==null)try{CHAT.emote(sender,descriptor,target);}
        catch(IOException departed){failure="Scene changed before the emote could be delivered";}
        if(failure==null)output.send(()->codec.invocationConfirmed(oid,listener.requestId()));
        else {String reason=failure;output.send(()->codec.invocationFailure(oid,listener.requestId(),reason));}
    }

    synchronized boolean ownsChannel(int channel){return selected()&&!readyRoom&&(channel==scenes.channelOid(scene.map().zone(),false)||channel==scenes.channelOid(scene.map().zone(),true));}
    synchronized void subscribeChannel(short message,int channel)throws IOException {
        if(closed||!ownsChannel(channel))throw new IOException("This chat channel belongs to another zone");
        try{characters.worldProfile(knight.name(),authRevision);}catch(CharacterStore.Refused refused){throw new IOException("Knight changed; reconnect",refused);}
        if(!channelChats.containsKey(channel))channelChats.put(channel,scenes.channel(channel).join(channel,oid,knight.name(),chatRate,m->deliverChannel(channel,m)));
        output.send(()->codec.chatChannel(message,channel,scenes.channelType(channel)));
    }
    synchronized void unsubscribeChannel(int channel){var member=channelChats.remove(channel);if(member!=null)member.close();}
    private synchronized boolean deliverChannel(int channel,SceneChat.Message message)throws IOException {
        if(closed||!subscribed||!entered||!ownsChannel(channel)||!channelChats.containsKey(channel))return false;
        try{characters.worldProfile(knight.name(),authRevision);}catch(CharacterStore.Refused refused){return false;}
        output.send(()->codec.chat(channel,message));return true;
    }
    boolean channelSpeak(SessionDecoder.Invocation request)throws IOException {
        if(scenes.channelType(request.oid())<0)return false;
        var args=request.arguments();SceneChat.Membership member;
        synchronized(this) {
            if(closed||!subscribed||!entered||!ownsChannel(request.oid())||(member=channelChats.get(request.oid()))==null)throw new SessionDecoder.Rejected("Unauthorized zone chat");
            if(request.service()!=SceneChat.SERVICE||request.method()!=1||args.size()!=2||!(args.get(0) instanceof String)||!(args.get(1) instanceof Byte))throw new SessionDecoder.Rejected("Invalid zone chat request");
            try{characters.worldProfile(knight.name(),authRevision);}catch(CharacterStore.Refused refused){throw new IOException("Knight changed; reconnect",refused);}
        }
        try{if(!scenes.channel(request.oid()).speak(member,(String)args.get(0),(Byte)args.get(1)))unavailable("Please slow down before sending another message");}
        catch(IOException refused){unavailable(refused.getMessage());}
        return true;
    }
    void speak(SessionDecoder.Invocation request)throws IOException {
        if(request.service()==SceneChat.PARTY_SERVICE&&scenes.parties().owns(partyMember,request.oid())){
            if(request.method()!=1||request.arguments().size()!=2||!(request.arguments().get(0) instanceof String text)||!(request.arguments().get(1) instanceof Byte mode))throw new SessionDecoder.Rejected("Invalid party chat");
            synchronized(this){if(closed||!selected())throw new IOException("Session closed");try{characters.worldProfile(knight.name(),authRevision);}catch(CharacterStore.Refused refused){throw new IOException("Knight changed; reconnect",refused);}}
            if(chatRate.allow(System.nanoTime()))scenes.parties().speak(partyMember,text,mode);return;
        }
        SceneChat.Membership sender;
        int place,generation;
        synchronized(this) {
            if(departingScene(request.oid()))return;
            boolean party=ownsInteriorParty(request.oid())&&request.service()==SceneChat.PARTY_SERVICE;
            if(closed||socket.isClosed()||!subscribed||!entered||!party&&!ownsScene(request.oid()))throw new SessionDecoder.Rejected("Unauthorized scene chat");
            if((!party&&request.service()!=SceneChat.SERVICE)||request.method()!=1||request.arguments().size()!=2
                    ||!(request.arguments().get(0) instanceof String)||!(request.arguments().get(1) instanceof Byte))
                throw new SessionDecoder.Rejected("Invalid scene chat request");
            try{characters.worldProfile(knight.name(),authRevision);}catch(CharacterStore.Refused e){throw new IOException("Knight changed; reconnect",e);}
            sender=chat;place=scene.oid();generation=sceneGeneration;
        }
        try {
            if(!CHAT.speak(sender,(String)request.arguments().get(0),(Byte)request.arguments().get(1)))
                feedback(place,generation,"Please slow down before sending another message.");
        }catch(IOException refused){feedback(place,generation,refused.getMessage());}
    }
    private synchronized void feedback(int place,int generation,String reason)throws IOException {
        if(!closed&&subscribed&&entered&&scene.oid()==place&&sceneGeneration==generation)output.send(()->codec.chatFeedback(scene.map().town()?place:partyOid(),reason));
    }
    synchronized void unavailable(String reason)throws IOException {
        if(!closed&&selected()&&subscribed&&entered)feedback(scene.oid(),sceneGeneration,reason);
    }
    /** Crowd ChatService method 2. ListenerMarshaller has failure only, no success response. */
    void broadcast(SessionDecoder.Invocation request)throws IOException {
        var args=request.arguments();
        if(request.oid()!=SessionCodec.INVOCATION_OID||request.service()!=3||request.method()!=2
                ||args.size()!=2||!(args.get(0) instanceof String text)
                ||!(args.get(1) instanceof SessionDecoder.Listener listener)
                ||!listener.type().equals("com.threerings.presents.data.InvocationMarshaller$ListenerMarshaller"))
            throw new SessionDecoder.Rejected("Invalid broadcast request");
        SceneChat.Membership sender;
        try {
            synchronized(this) {
                if(closed||socket.isClosed()||!subscribed||!entered)throw new IOException("Enter a scene before broadcasting");
                if(!roles.gameMaster())throw new IOException("Game Master permission required");
                try{characters.worldProfile(knight.name(),authRevision);}catch(CharacterStore.Refused e){throw new IOException("Knight changed; reconnect",e);}
                sender=chat;
            }
            if(!CHAT.broadcast(sender,text))throw new IOException("Please slow down before sending another message.");
        }catch(IOException refused){output.send(()->codec.invocationFailure(oid,listener.requestId(),refused.getMessage()));}
    }
    synchronized void away(SessionDecoder.Invocation request)throws IOException {
        var args=request.arguments();
        if(request.oid()!=SessionCodec.INVOCATION_OID||request.service()!=3||request.method()!=1||args.size()!=1||args.getFirst()!=null&&!(args.getFirst() instanceof String))throw new SessionDecoder.Rejected("Invalid away request");
        if(closed||!selected())throw new IOException("Select a knight first");
        try{characters.worldProfile(knight.name(),authRevision);}catch(CharacterStore.Refused refused){throw new IOException("Knight changed; reconnect",refused);}
        if(!chatRate.allow(System.nanoTime())){unavailable("Please slow down before changing your away message");return;}
        awayMessage=args.getFirst()==null?null:SceneChat.validate((String)args.getFirst());
        if(chat!=null)CHAT.away(chat,awayMessage);
        output.send(()->codec.awayMessage(oid,awayMessage));
    }
    void tell(SessionDecoder.Invocation request)throws IOException {
        var args=request.arguments();
        if(request.oid()!=SessionCodec.INVOCATION_OID||request.service()!=3||request.method()!=3
                ||args.size()!=3||!(args.get(0) instanceof String target)||!(args.get(1) instanceof String text)
                ||!(args.get(2) instanceof SessionDecoder.Listener listener)
                ||!listener.type().equals("com.threerings.crowd.chat.data.ChatMarshaller$TellMarshaller"))
            throw new SessionDecoder.Rejected("Invalid tell request");
        SceneChat.Membership sender;
        try {
            synchronized(this) {
                if(closed||socket.isClosed()||!subscribed||!entered)throw new IOException("Enter a scene before sending a tell");
                try{characters.worldProfile(knight.name(),authRevision);}catch(CharacterStore.Refused e){throw new IOException("Knight changed; reconnect",e);}
                sender=chat;
            }
            String away=CHAT.tell(sender,target,text);
            output.send(()->codec.tellSucceeded(oid,listener.requestId(),away));
        }catch(IOException refused){output.send(()->codec.invocationFailure(oid,listener.requestId(),refused.getMessage()));}
    }
    private synchronized boolean deliverChat(int place,int generation,SceneChat.Message message)throws IOException {
        if(closed||socket.isClosed()||!subscribed||!entered||scene.oid()!=place||sceneGeneration!=generation)return false;
        if(message.personal()) {
            try{characters.worldProfile(knight.name(),authRevision);}catch(CharacterStore.Refused refused){return false;}
        }
        output.send(()->codec.chat(message.personal()?oid:scene.map().town()?place:partyOid(),message));return true;
    }
    private void leaveChat(){
        friendLocation=new FriendPresence("~",0,0);
        townDialog=null;townSource=null;townService++;
        if(tradeMember!=null){trades.leave(tradeMember);tradeMember=null;}
        if(chat!=null){chat.close();chat=null;}
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
    private void offer(SceneRegistry.Instance place,int generation,SceneSimulation.Snapshot snapshot) {
        synchronized(publications){
            if(deliveryStopped||generation!=sceneGeneration)return;
            if(lastOffered!=null&&lastOffered.generation()!=generation){lastOffered=pending=null;activityPublications.clear();}
            if(lastOffered!=null&&snapshot.timestamp()-lastOffered.snapshot().timestamp()<=0)return;
            var next=new Publication(place,generation,snapshot);
            if(lastOffered!=null&&snapshot.startsDataActivity(lastOffered.snapshot())){
                // Native interpolation requires the first data-bearing record, even if
                // the delivery thread is busy while later movement records arrive.
                if(activityPublications.size()>=16)publicationOverflow=true;else activityPublications.addLast(next);
                pending=null;
            }else pending=next;
            lastOffered=next;publications.notifyAll();
        }
    }
    private void deliverPublications() {
        try {
            while(true) {
                Publication publication;List<PartyRegistry.Event> events;
                synchronized(publications) {
                    while(pending==null&&activityPublications.isEmpty()&&partyEvents.isEmpty()&&!partyOverflow&&!publicationOverflow&&!deliveryStopped)publications.wait();
                    if(deliveryStopped)return;
                    if(partyOverflow||publicationOverflow)throw new IOException("Scene/party publication queue full");
                    events=List.copyOf(partyEvents);partyEvents.clear();partyRefreshQueued=false;
                    publication=activityPublications.pollFirst();
                    if(publication==null){publication=pending;pending=null;}
                }
                for(var event:events)deliverParty(event);
                if(publication!=null)publish(publication);
            }
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        catch(IOException|RuntimeException failure) {
            log.warn("Scene delivery closed; "+deliveryFailure(failure));close();
            try{socket.close();}catch(IOException ignored){}
        }
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
    private synchronized void publish(Publication publication)throws IOException {
        var current=publication.snapshot();int stamp=current.timestamp();
        if(closed||!subscribed||!entered||scene!=publication.scene()||sceneGeneration!=publication.generation()||stamp-lastStamp<=0)return;
        var delta=current.delta(lastSnapshot);
        // Subscription already announces occupants; the first reliable delta has no baseline.
        var changedRoles=lastSnapshot==null?current.actors().stream().filter(a->occupantRoles.containsKey(a.id())&&occupantRoles.get(a.id())!=a.gameMaster()).toList():delta.changedRoles();
        if(!changedRoles.isEmpty())output.send(()->codec.occupantRoles(scene.oid(),changedRoles));
        var clients=current.players().stream().collect(java.util.stream.Collectors.toMap(ActorState::id,ActorState::clientOid));
        if(!clients.equals(occupantClients)){
            output.send(()->codec.occupantChanges(scene.oid(),occupantClients,current.players()));
            occupantClients=clients;
        }
        occupantRoles=current.roles();
        var effects=current.effectsSince(lastSnapshot);
        short elapsed=(short)Math.max(1,Math.min(Short.MAX_VALUE,stamp-lastStamp));
        output.send(()->codec.sceneDelta(oid,scene.oid(),acknowledge,ping,reference,stamp,elapsed,
            delta.added(),delta.updated(),delta.removed(),delta.baseline(),!scene.map().town(),effects));
        var currentOwn=current.players().stream().filter(a->a.clientOid()==oid).findFirst().orElse(null);
        var health=currentOwn==null?null:current.health(currentOwn.id());
        var previousOwn=lastSnapshot==null?null:lastSnapshot.players().stream().filter(a->a.id()==profile.worldId()).findFirst().orElse(null);
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
        if(!scene.map().town())for(var event:current.combatEventsSince(lastSnapshot)){int party=partyOid();output.send(()->codec.partyDamage(party,event));}
        lastStamp=reference=stamp;lastSnapshot=current;
        var story=membership.takeStory();
        if(!story.memories().isEmpty()||!story.objectives().isEmpty()){
            profile=loadProfile(profile);
            var result=characters.knightStory(knight.id(),authRevision,profile,story.memories().stream().map(SceneSimulation.MemoryRequest::operation).toList(),story.objectives().stream().map(SceneSimulation.ObjectiveRequest::operation).toList());
            profile=result.profile();
            if(!memories.equals(result.memories())){var before=memories;memories=result.memories();output.send(()->codec.knightMemoryChanges(oid,before,memories));}
            if(!objectives.equals(result.objectives())){objectives=result.objectives();output.send(()->codec.knightObjectives(oid,objectives));}
        }
        collectPickups();
        for(long id:membership.takeUsedItems()){
            var item=temporaryItems.get(id);if(item==null)continue;var before=visibleProfile();
            if(item.count()==1)temporaryItems.remove(id);else temporaryItems.put(id,new WorldProfile.Item(id,item.slot(),item.config(),item.accessory(),item.heat(),item.equippedSlot(),item.bound(),item.bindOnEquip(),item.starter(),item.count()-1,item.permanent()));
            var after=visibleProfile();output.send(()->codec.itemChanges(oid,knight,before,after));
            publishQuickbar();
        }
        var own=current.players().stream().filter(a->a.id()==profile.worldId()).findFirst().orElse(null);
        boolean arsenal=membership.arsenalZone();
        if(publishedArsenal==null||publishedArsenal!=arsenal){publishedArsenal=arsenal;output.send(()->codec.equipPolicy(oid,arsenal));}
        if(townDialog!=null&&tradeMember!=null&&townTradeGeneration!=tradeMember.dialogGeneration)closeTownDialog();
        if(townDialog!=null&&(own==null||Math.hypot(own.x()-townX,own.y()-townY)>1))closeTownDialog();
        if(conversation!=null&&(own==null||conversationMission==null&&Math.hypot(own.x()-conversationX,own.y()-conversationY)>1))closeConversation();
        var scripted=membership.takeConversation();
        if(scripted!=null&&scripted.definition()==null){closeConversation();scripted=null;}
        if(scripted!=null&&own!=null){
            if(scripted.definition().initialAction()!=null&&!membership.conversationStart(scripted.definition(),scripted.source()))return;
            closeTownDialog();conversationMission=null;conversation=scripted.definition();conversationSource=scripted.source();conversationNode=conversation.initial();
            conversationCreated=Math.max(System.currentTimeMillis(),conversationCreated+1);conversationX=own.x();conversationY=own.y();
            output.send(()->codec.conversationDialog(oid,scene.oid(),conversation,conversationNode,conversationSource,conversationCreated));
        }
        if(arrivalComlink!=null&&own!=null&&conversation==null&&townDialog==null&&missionDialogCreated==0){
            var pending=arrivalComlink;arrivalComlink=null;openComlink(pending);
        }
        int target=membership.takeInteraction();
        if(target>0&&own!=null){
            var interaction=scene.simulation().interactionSource(target);
            var source=interaction.actor();
            var binding=source==null?null:scene.map().content().conversations().get(source.config().name());
            var sourceSpec=interaction.specification();
            var station=source==null?null:scenes.town().station(sourceSpec==null?source.config().name():sourceSpec.text("town-station",source.config().name()));
            if(sourceSpec!=null)binding=scene.map().content().conversations().get(sourceSpec.text("dialogue",source.config().name()));
            if(station!=null&&(trades==null||!trades.active(tradeMember))&&own.interaction()!=null&&own.interaction().actorId()==target){openTownDialog(station,source);}
            else if(binding!=null&&(trades==null||!trades.active(tradeMember))&&own.interaction()!=null&&own.interaction().actorId()==target){
                if(binding.actor().equals(RHENDON_CAMP)&&!completedOpening.containsKey(OpeningMission.SECURING_CAMP)&&!OpeningMission.SECURING_CAMP.eligible(profile.progress(),completedOpening.keySet()))return;
                if(binding.initialAction()!=null&&!membership.conversationStart(binding,source))return;
                closeTownDialog();conversationMission=null;conversation=binding;conversationNode=binding.initial();conversationSource=source;
                // Repeat node is the archive's documented interim HasCompletedMission branch;
                // it cannot pay or set the mission completed merely by opening.
                if(binding.actor().equals(RHENDON_CAMP)&&completedOpening.containsKey(OpeningMission.SECURING_CAMP))conversationNode="part 4a";
                conversationCreated=Math.max(System.currentTimeMillis(),conversationCreated+1);conversationX=own.x();conversationY=own.y();
                output.send(()->codec.conversationDialog(oid,scene.oid(),conversation,conversationNode,source,conversationCreated));
            }
        }
        if(scene.tutorial()!=null&&forcedReceiver!=null&&subscribedPartyOid==partyOid()){
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
        if(scene.opening()!=null&&forcedReceiver!=null&&subscribedPartyOid==partyOid()){
            if(processOpeningFloor(stamp))return;
        }
        var trigger=current.portal(profile.worldId());
        if(trigger!=null&&forcedReceiver!=null)beginPortal(trigger);
    }
    /** Called only after the scene's original NextFloor and native reportComplete (or its
     * authored timeout). Storage commits before flags, forced movement or party publication. */
    private boolean finishTutorialFloor(int stamp)throws IOException {
        if(floorReport==null||!floorReport.ready(stamp)||forcedReceiver==null)return false;
        var route=scene.tutorial();
        if(route==null||!subscribed||!entered||portalMove!=null) return false;
        var next=destination(route.camp().id());
        if(next.map()!=route.camp()||!floorReport.valid())return false;
        profile=loadProfile(profile);
        var before=profile;
        profile=characters.completeTutorial(knight.id(),authRevision,profile,membership.health().current()>0?membership.floorHeat():0,scenes.town());
        floorReport.finish();floorReport=null;
        var settled=profile;
        if(!before.items().equals(settled.items()))output.send(()->codec.itemChanges(oid,knight,before,settled));
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
        var attempt=scene.opening();
        if(!scenes.authorized(scene,profile.worldId(),partyMember)){forcePartyExit();return true;}
        if(floorReport!=null&&!floorReport.valid()){floorReport=null;openingDropStarted=false;}
        if(openingAdvance!=null&&!membership.ownsFloorAdvance(openingAdvance))openingAdvance=null;
        if(floorReport==null){
            if(openingAdvance==null)openingAdvance=membership.takeFloorAdvance();
            if(openingAdvance!=null){
                var request=openingAdvance;
                if(!attempt.gate().exit(request)||!new SceneElevator(1,true).equals(membership.elevatorConfiguration(request))){membership.cancelFloorAdvance(request);openingAdvance=null;return false;}
                if(openingPayoutRetry!=0&&stamp-openingPayoutRetry<0)return false;
                profile=loadProfile(profile);var before=profile;
                org.tortodrone.storage.OpeningMissionStore.Settlement settled;
                try{
                    settled=scenes.parties().soloGate(partyMember,attempt.partyOid(),()->membership.settleSoloFloor(request,
                        ()->characters.openingMissions(scenes.town()).complete(characters.accountId(),knight.id(),authRevision,before,attempt.gate().mission(),attempt.difficulty(),membership.floorHeat())));
                }catch(IOException failure){
                    openingPayoutRetry=stamp+1000;
                    if(!openingPayoutWarning){openingPayoutWarning=true;log.warn("Opening mission settlement failed; keeping the owned ride for retry");}
                    return false;
                }
                profile=settled.profile();openingAdvance=null;openingPayoutRetry=0;openingPayoutWarning=false;
                if(!memories.equals(settled.memories())){var old=memories;memories=settled.memories();output.send(()->codec.knightMemoryChanges(oid,old,memories));}
                if(!objectives.equals(settled.objectives())){objectives=settled.objectives();output.send(()->codec.knightObjectives(oid,objectives));}
                if(settled.first()){
                    var completed=new EnumMap<OpeningMission,OpeningCompletion>(OpeningMission.class);completed.putAll(completedOpening);completed.put(attempt.gate().mission(),settled.completion());completedOpening=Map.copyOf(completed);
                    closeConversation();closeTownDialog();membership.equipment(visibleProfile());conversationCreated=Math.max(System.currentTimeMillis(),conversationCreated+1);missionDialogCreated=conversationCreated;
                    output.send(()->codec.missionSettlement(oid,before,profile,settled.completion(),missionDialogCreated));
                    output.send(()->codec.itemChanges(oid,knight,before,profile));partyState();
                }
                floorReport=new FloorReport(membership,request,stamp);openingDropStarted=false;
                int party=partyOid();output.send(()->codec.openingFloorReport(party));
            }else membership.authorizeElevator(Integer.toString(OpeningGate.GENERATOR_EXIT),Set.of(profile.worldId()));
        }
        return finishOpeningFloor(stamp);
    }
    private boolean finishOpeningFloor(int stamp)throws IOException {
        if(scene.opening()==null||floorReport==null||!floorReport.votedOrTimedOut(stamp)||forcedReceiver==null||!subscribed||!entered||portalMove!=null)return false;
        var attempt=scene.opening();var request=floorReport.request();
        if(!scenes.authorized(scene,profile.worldId(),partyMember))return false;
        if(!openingDropStarted){if(!membership.dropElevator(request,floorReport.returning()))return false;openingDropStarted=true;}
        if(!membership.elevatorReady(request))return false;
        var next=destination(attempt.gate().destination().id());
        scenes.parties().soloGate(partyMember,attempt.partyOid(),()->membership.settleSoloElevator(request,()->Boolean.TRUE));
        floorReport.finish();floorReport=null;
        portalMove=new PortalMove(next,attempt.gate().arrival());departedSceneOid=scene.oid();partyState();leaveChat();subscribed=entered=false;sceneGeneration++;membership.pause();
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
    void refreshState(WorldStatePoller.Registration registration,Database.WorldState state)throws IOException {
        WorldProfile before;
        synchronized(this) {
            if(closed||!entered||persistence!=registration)return;
            if(state==null||state.accountId()!=registration.accountId||state.knightId()!=registration.knightId
                    ||state.authRevision()!=registration.authRevision||!state.name().equalsIgnoreCase(registration.name))
                throw new IOException("Knight or account changed; reconnect");
            before=profile;
            if(tradeMember!=null)trades.changed(tradeMember,state);
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
    private void publishOutbids(WorldProfile before,WorldProfile after)throws IOException {
        for(var notice:after.town().outbids())if(!before.town().outbids().contains(notice)) {
            var item=characters.catalog().require(notice.item());
            output.send(()->codec.auctionOutbid(scene.oid(),"%item:"+item.nameKey(),notice.crowns()));
        }
    }
    private WorldProfile loadProfile(WorldProfile cached)throws IOException {
        try{return characters.worldProfile(knight.name(),authRevision,cached);}
        catch(CharacterStore.Refused refused){throw new IOException("Knight changed; reconnect",refused);}
    }
    synchronized void refreshFailed(WorldStatePoller.Registration registration,Exception failure) {
        if(persistence!=registration||closed)return;
        log.warn("World state refresh closed; "+deliveryFailure(failure));close();
        try{socket.close();}catch(IOException ignored){}
    }
    private void attach() {
        floorReport=null;
        openingAdvance=null;openingDropStarted=openingPayoutWarning=false;openingPayoutRetry=0;
        if(!scene.map().kind().equals("training")&&partyMember!=null)try{scenes.parties().leaveTraining(partyMember);}catch(IOException invalid){throw new IllegalStateException(invalid);}
        publishedArsenal=null;
        membership=null; // The old scene membership is closed before rebuilding its replacement.
        rebuildAppearance();var place=scene;
        membership=place.simulation().join(actorAppearance(),snapshot->{});
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
    private void updateAppearance(){rebuildAppearance();if(membership!=null){membership.equipment(visibleProfile());membership.appearance(actorAppearance());}}
    private ActorState actorAppearance() {
        var checkpoint=arrival==null?scene.simulation().defaultEntrance():null;
        return new ActorState(profile.worldId(),oid,knight.name(),256|knight.color(),created,
            arrival==null?checkpoint==null?scene.map().spawnX():checkpoint.x():arrival.x(),arrival==null?checkpoint==null?scene.map().spawnY():checkpoint.y():arrival.y(),
            arrival==null?checkpoint==null?scene.map().spawnRotation():checkpoint.rotation():arrival.rotation(),0,0,0,created,appearance,gameMaster);
    }

    private static long monotonicTime() { return System.nanoTime() / 1_000_000L; }
    private void monitorScene() {
        log.scene(scene.map().id(),scene.oid(),scene.map().name());
        log.info("Scene entered; scene="+scene.map().name()+" (#"+scene.map().id()+"); instanceId="+scene.oid());
    }
    /** Set once a successful rename answered its confirm; honored by the connection loop. */
    private boolean renameDisconnect;
    synchronized boolean takeRenameDisconnect(){boolean requested=renameDisconnect;renameDisconnect=false;return requested;}

    @Override public synchronized void close() {
        friendOnline=false;
        for(var member:channelChats.values())member.close();channelChats.clear();
        leaveChat();
        closed = true;
        floorReport=null;
        subscribed = false;
        if(membership!=null)membership.close();
        if(partyMember!=null){partyMember.close();partyMember=null;}
        if(profile!=null){scenes.release(scene,profile.worldId());if(portalMove!=null)scenes.release(portalMove.destination(),profile.worldId());}
        synchronized(publications){deliveryStopped=true;pending=lastOffered=null;activityPublications.clear();publications.notifyAll();}
        if (profile != null) synchronized (ACTIVE) { ACTIVE.remove(profile.worldId(), this); }
    }
}
