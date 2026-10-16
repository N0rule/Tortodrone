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
    private static final Map<Integer, WorldSession> ACTIVE = new HashMap<>();
    private static final SceneChat CHAT=new SceneChat();
    private final SceneChat.RateLimit chatRate=new SceneChat.RateLimit();
    private SceneChat.Membership chat;
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
    @FunctionalInterface interface RoleSource { boolean gameMaster()throws IOException; }
    private final RoleSource roles;
    private boolean gameMaster,inspectionHidden;
    private SceneRegistry.Instance scene;
    private SceneSimulation.Membership membership;
    private SceneSimulation.Snapshot lastSnapshot;
    private final Object publications=new Object();
    private record Publication(SceneRegistry.Instance scene,SceneSimulation.Snapshot snapshot) { }
    private Publication pending;
    private boolean deliveryStopped;
    private Thread delivery;
    private StarterCatalog.Model appearance;
    private Knight knight;
    private WorldProfile profile;
    private int acknowledge, created, reference, lastStamp, sceneGeneration;
    private short ping;
    private WorldStatePoller.Registration persistence;
    private boolean subscribed, entered, closed, interiorThrottleSent;
    private Short forcedReceiver;
    private record PortalMove(SceneRegistry.Instance destination,ScenePortals.Entrance entrance) { }
    private PortalMove portalMove;
    private ScenePortals.Entrance arrival;
    private int departedSceneOid;
    private record DeferredMove(short requestId,boolean zone,int sceneOid) { }
    private DeferredMove deferredMove;
    private int subscribedPartyOid;
    private int announcedPartyOid;
    private SceneHealth.State publishedHealth;
    private SceneStatus.State publishedStatus;
    private Set<Integer> occupants = Set.of();
    private Map<Integer,Boolean> occupantRoles=Map.of();
    private org.tortodrone.town.TownCatalog.Station townDialog;
    private ActorState townSource;
    private long townCreated;
    private int townService=100;
    private long townShopCycle,townTradeGeneration;
    private float townX,townY;
    private SceneConversation conversation;
    private String conversationNode;
    private ActorState conversationSource;
    private long conversationCreated;
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
                    long id=existing==null?nextTemporaryItem--:existing.id();
                    int count=existing==null?1:Math.addExact(existing.count(),1);
                    temporaryItems.put(id,new WorldProfile.Item(id,WorldProfile.Slot.OTHER,pickup.item(),null,0,null,false,false,false,count,false));
                    if(Arrays.stream(quickbar).noneMatch(value->value==id))for(int slot=0;slot<quickbar.length;slot++)if(quickbar[slot]==0){quickbar[slot]=id;break;}
                    }
                }else throw new IOException("Persistent scene pickups are not supported");
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
        townTradeGeneration=tradeMember==null?0:tradeMember.dialogGeneration;closeConversation();closeTownDialog();townDialog=station;townSource=source;townCreated=Math.max(System.currentTimeMillis(),townCreated+1);townService++;
        var own=scene.simulation().snapshot().players().stream().filter(a->a.id()==profile.worldId()).findFirst().orElseThrow(()->new IOException("Knight left scene"));townX=own.x();townY=own.y();
        if(station.kind().equals("OpenShop")||station.kind().equals("OpenHarnessInstaller")){townShopCycle=scenes.town().shopCycle(station.shop());publishShop();}
        output.send(()->codec.townDialog(oid,scene.oid(),station,source,townCreated,townService));
    }
    private void publishShop()throws IOException {
        var goods=scenes.town().goods(townDialog.shop(),townShopCycle,profile.worldId()).stream().map(g->{var def=characters.catalog().find(g.item());int price=g.price()>=0?g.price():def==null?0:scenes.town().price(g.item(),def.rarity(),true);return new org.tortodrone.town.TownCatalog.Good(g.item(),g.count(),price,g.energy(),g.limit(),g.token(),g.tokenCount());}).toList();
        var available=characters.town(scenes.town()).availability(characters.accountId(),knight.id(),authRevision,townDialog,townShopCycle);
        output.send(()->codec.townShop(oid,goods,available));
    }
    private void townChanged(org.tortodrone.storage.TownStore.Result result)throws IOException {
        var before=visibleProfile();profile=result.profile();updateAppearance();var after=visibleProfile();
        output.send(()->codec.itemChanges(oid,knight,before,after));output.send(()->codec.balances(oid,after));output.send(()->codec.townState(oid,after));
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
                var snapshot=characters.depot(rules).snapshot();
                for(var offer:snapshot.offers()){var good=offer.good();var def=characters.catalog().find(good.item());if(!offer.enabled()||def==null||!def.grantable()||method==5&&!offer.featured()||method==6&&!((List<?>)a.getFirst()).contains(good.item()))continue;
                    int price=good.price()<0?rules.price(good.item(),def.rarity(),true):good.price();offers.put(offer.id(),new org.tortodrone.town.TownCatalog.DepotGood(new org.tortodrone.town.TownCatalog.Good(good.item(),good.count(),price,good.energy(),good.limit(),good.token(),good.tokenCount()),offer.featured()));
                    depotOffers.put(offer.id(),new org.tortodrone.storage.DepotStore.Quote(snapshot.revision(),offer));
                }
                output.send(()->codec.townResult(oid,listener.requestId(),new org.tortodrone.town.TownCatalog.DepotOffers(offers),profile));
            }
        }catch(IOException failure){output.send(()->codec.invocationFailure(oid,listener.requestId(),failure.getMessage()));}return true;
    }
    synchronized boolean auctionInvocation(SessionDecoder.Invocation request)throws IOException {
        if(request.oid()!=SessionCodec.INVOCATION_OID)return false;var a=request.arguments();int method=request.method();
        boolean auction=request.service()==15,mail=request.service()==10&&(method==1||method==2||method==4||method==6||method==11);
        if(!auction&&!mail)return false;
        boolean resultListener=auction||method==6;
        if(a.isEmpty()||!(a.getLast() instanceof SessionDecoder.Listener listener)||!listener.type().equals("com.threerings.presents.data.InvocationMarshaller$"+(resultListener?"ResultMarshaller":"ConfirmMarshaller")))throw new SessionDecoder.Rejected("Invalid auction/mail listener");
        boolean valid=auction?switch(method){case 1->a.size()==3&&a.get(0) instanceof Long&&a.get(1) instanceof Integer;case 2,7->a.size()==2&&a.get(0) instanceof Long;case 3,4->a.size()==1;case 5->a.size()==3&&a.get(0) instanceof SessionDecoder.AuctionSearch&&a.get(1) instanceof Integer;case 6->a.size()==7&&a.get(0) instanceof Long&&a.get(1) instanceof Integer&&a.get(2) instanceof Integer&&a.get(3) instanceof Integer&&a.get(4) instanceof SessionDecoder.EnumValue e&&e.type().equals("com.threerings.projectx.auction.data.Duration")&&a.get(5) instanceof Boolean;default->false;}:a.size()==2&&(method==6?a.get(0)==null||a.get(0) instanceof long[]:method==11?a.get(0) instanceof long[]:a.get(0) instanceof Long);
        if(!valid)throw new SessionDecoder.Rejected("Invalid auction/mail arguments");
        try{if(closed||!selected()||!entered||!subscribed)throw new IOException("Enter a scene first");
            if(trades!=null&&trades.active(tradeMember)&&(auction&&(method==1||method==2||method==6)||mail&&(method==1||method==2)))throw new IOException("Finish the trade first");
            var house=characters.auction(scenes.town());var result=auction?house.operate(characters.accountId(),knight.id(),authRevision,method,a,roles.gameMaster()):house.mail(characters.accountId(),knight.id(),authRevision,method,a);
            townChanged(result);if(resultListener)output.send(()->codec.townResult(oid,listener.requestId(),result.result(),profile));else output.send(()->codec.invocationConfirmed(oid,listener.requestId()));
        }catch(IOException refused){output.send(()->codec.invocationFailure(oid,listener.requestId(),refused.getMessage()));}return true;
    }

    synchronized boolean townInvocation(SessionDecoder.Invocation request)throws IOException {
        if(depotInvocation(request))return true;
        var a=request.arguments();boolean global=request.oid()==SessionCodec.INVOCATION_OID;
        if(townDialog!=null&&tradeMember!=null&&townTradeGeneration!=tradeMember.dialogGeneration){townDialog=null;townSource=null;townService++;}
        if(townDialog!=null&&ownsScene(request.oid())&&request.service()==6&&request.method()==1){
            if(a.size()!=1||!(a.getFirst() instanceof Long created))throw new SessionDecoder.Rejected("Invalid Town dialog close");if(created!=townCreated)return false;closeTownDialog();return true;
        }
        boolean open=ownsScene(request.oid())&&request.service()==11;
        boolean operation=ownsScene(request.oid())&&request.service()>=100;
        boolean sprite=global&&request.service()==14;
        boolean hatch=global&&request.service()==9&&request.method()==7;
        boolean forge=global&&request.service()==9&&request.method()==8;
        boolean learn=global&&request.service()==9&&request.method()==1&&a.size()==2&&a.getFirst() instanceof Long id&&selected()&&profile.items().stream().anyMatch(i->i.id()==id&&scenes.town().item(i.config()).containsKey("recipe"));
        boolean harness=global&&request.service()==9&&request.method()==1&&a.size()==2&&a.getFirst() instanceof Long id&&selected()&&profile.items().stream().anyMatch(i->i.id()==id&&scenes.town().item(i.config()).containsKey("harness"));
        if(!open&&!operation&&!sprite&&!hatch&&!forge&&!learn&&!harness)return false;
        if(a.isEmpty()||!(a.getLast() instanceof SessionDecoder.Listener listener))throw new SessionDecoder.Rejected("Missing Town listener");
        boolean resultListener=hatch||forge||operation&&(townDialog==null||!Set.of("OpenShop","OpenHarnessInstaller").contains(townDialog.kind()))||sprite&&request.method()==7;
        if(!listener.type().equals("com.threerings.presents.data.InvocationMarshaller$"+(resultListener?"ResultMarshaller":"ConfirmMarshaller")))throw new SessionDecoder.Rejected("Invalid Town listener");
        try{
            if(closed||!selected()||!entered||!subscribed)throw new IOException("Enter a scene first");
            characters.worldProfile(knight.name(),authRevision,profile);
            boolean staff=roles.gameMaster();
            if(!scene.map().town()&&!scene.map().kind().equals("training")&&!staff)throw new IOException("This operation requires a town");
            var store=characters.town(scenes.town());org.tortodrone.storage.TownStore.Result result;
            if(open){
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
            }else if(forge){
                if(a.size()!=4||!(a.get(0) instanceof Long item)||!(a.get(1) instanceof Integer crystals)||!(a.get(2) instanceof Integer level))throw new SessionDecoder.Rejected("Invalid forge request");
                if(trades!=null&&trades.active(tradeMember))throw new IOException("Finish the trade first");
                result=store.forge(characters.accountId(),knight.id(),authRevision,item,crystals,level);
            }else if(hatch||learn||harness){if(a.size()!=2||!(a.get(0) instanceof Long id))throw new SessionDecoder.Rejected("Invalid Town item request");result=hatch?store.hatch(characters.accountId(),knight.id(),authRevision,id):harness?store.installHarness(characters.accountId(),knight.id(),authRevision,id):store.learn(characters.accountId(),knight.id(),authRevision,id);
            }else{
                int method=request.method();int[] counts={0,4,6,3,2,3,3,4,3,4};if(method<1||method>9||a.size()!=counts[method])throw new SessionDecoder.Rejected("Invalid sprite argument count");
                boolean valid=switch(method){case 1->a.get(0) instanceof Integer&&a.get(1) instanceof Integer&&a.get(2) instanceof Integer;case 2->a.subList(0,5).stream().allMatch(v->v instanceof Integer);case 3->a.get(0) instanceof Integer&&(a.get(1)==null||a.get(1) instanceof SessionDecoder.EnumValue e&&e.type().equals("com.threerings.projectx.sprites.data.SpritePerk"));case 4->a.get(0) instanceof Integer;case 5->a.get(0) instanceof Integer&&a.get(1) instanceof Long;case 6->a.get(0) instanceof Long&&a.get(1) instanceof Integer;case 7->a.get(0) instanceof Integer&&a.get(1) instanceof Long&&a.get(2) instanceof Boolean;case 8->a.get(0) instanceof Integer&&a.get(1) instanceof String;case 9->a.get(0) instanceof Integer&&a.get(1) instanceof Integer&&a.get(2) instanceof SessionDecoder.EnumValue e&&e.type().equals("com.threerings.projectx.sprites.data.SpriteCodes$Ultimate");default->false;};
                if(!valid)throw new SessionDecoder.Rejected("Invalid sprite arguments");result=store.sprite(characters.accountId(),knight.id(),authRevision,method,a,staff);
            }
            townChanged(result);if(operation&&townDialog!=null&&Set.of("OpenShop","OpenHarnessInstaller").contains(townDialog.kind()))publishShop();Object payload=result.result();if(hatch&&payload instanceof Integer id)payload=profile.town().sprites().stream().filter(s->s.id()==id).findFirst().orElseThrow();
            final Object response=payload;if(resultListener)output.send(()->codec.townResult(oid,listener.requestId(),response,profile));else output.send(()->codec.invocationConfirmed(oid,listener.requestId()));
        }catch(CharacterStore.Refused|IOException refused){if(refused instanceof SessionDecoder.Rejected rejected)throw rejected;output.send(()->forge?codec.forgeFailure(oid,listener.requestId(),refused.getMessage()):codec.invocationFailure(oid,listener.requestId(),refused.getMessage()));}
        return true;
    }

    synchronized boolean conversationInvocation(SessionDecoder.Invocation request)throws IOException {
        if(conversation!=null&&ownsScene(request.oid())&&request.service()==6&&request.method()==1){
            if(request.arguments().size()!=1||!(request.arguments().getFirst() instanceof Long created))throw new SessionDecoder.Rejected("Invalid conversation close");
            if(created!=conversationCreated)return false;closeConversation();return true;
        }
        if(!ownsScene(request.oid())||request.service()!=SceneConversation.SERVICE)return false;
        var args=request.arguments();
        if(!ownsScene(request.oid())||!entered||conversation==null||request.method()!=1||args.size()!=2
                ||!(args.get(0) instanceof Integer choice)||!(args.get(1) instanceof SessionDecoder.Listener listener)
                ||!listener.type().endsWith("$ConfirmMarshaller"))throw new SessionDecoder.Rejected("Invalid conversation response");
        var responses=conversation.nodes().get(conversationNode).responses();
        if(choice<0||choice>=responses.size()){output.send(()->codec.invocationFailure(oid,listener.requestId(),"Invalid conversation choice"));return true;}
        var response=responses.get(choice);String destination=response.destination();
        if(!response.station().isEmpty()){
            var station=scenes.town().station(response.station());
            if(station==null){output.send(()->codec.invocationFailure(oid,listener.requestId(),"Town service is unavailable"));return true;}
            try{openTownDialog(station,conversationSource);}catch(IOException failure){output.send(()->codec.invocationFailure(oid,listener.requestId(),failure.getMessage()));return true;}
        }else if(destination.isEmpty())closeConversation();else{
            conversationNode=destination;output.send(()->codec.conversationDialog(oid,scene.oid(),conversation,conversationNode,conversationSource,conversationCreated));
        }
        output.send(()->codec.invocationConfirmed(oid,listener.requestId()));return true;
    }
    private void closeConversation()throws IOException {
        if(conversation==null)return;conversation=null;conversationSource=null;conversationNode=null;
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
        this.socket = socket;
        this.characters = characters;
        this.output = output;
        this.codec = codec;
        this.oid = oid;
        authRevision = revision;
        this.log = log;
        this.scenes = scenes;
        this.weapons=scenes.weapons();
        this.roles=roles;
    }

    synchronized boolean selected() { return knight != null; }
    boolean hasInteriors(){return scenes.catalog().scenes().stream().anyMatch(map->!map.town());}
    synchronized boolean ownsScene(int oid) { return selected() && scene.oid()==oid; }
    synchronized boolean ownsInteriorParty(int target){return selected()&&!scene.map().town()&&target==SessionCodec.interiorPartyOid(scene);}
    synchronized void subscribeInteriorParty(short message)throws IOException {
        profile=loadProfile(profile);
        var health=membership.health();var status=membership.status();output.send(()->codec.interiorParty(message,SessionCodec.interiorPartyOid(scene),oid,knight,profile,scene,health,status));
        subscribedPartyOid=SessionCodec.interiorPartyOid(scene);
        publishedHealth=health;publishedStatus=status;
        if(deferredMove!=null&&deferredMove.sceneOid()==scene.oid()){
            var move=deferredMove;deferredMove=null;completeMove(move.requestId(),move.zone());
        }
    }
    synchronized boolean interiorPartyInvocation(SessionDecoder.Invocation request)throws IOException {
        if(!ownsInteriorParty(request.oid()))return false;
        if(request.service()==SceneChat.PARTY_SERVICE)return false;
        var args=request.arguments();
        if(request.service()==20&&request.method()==4){
            if(args.size()!=3||!(args.get(0) instanceof String target)||!(args.get(1) instanceof Integer cost)||!(args.get(2) instanceof SessionDecoder.Listener listener)||!listener.type().equals("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller"))throw new SessionDecoder.Rejected("Invalid revive request");
            try{
                if(closed||!subscribed||!entered||!knight.name().equalsIgnoreCase(target)||!membership.beginRevive())throw new IOException("m.no_revive");
                boolean success=false;
                try{var result=characters.town(scenes.town()).revive(characters.accountId(),knight.id(),authRevision,cost);success=true;townChanged(result);}
                finally{membership.finishRevive(success);}
                output.send(()->codec.invocationConfirmed(oid,listener.requestId()));
            }catch(IOException refused){output.send(()->codec.reviveFailure(oid,listener.requestId(),refused.getMessage()));}
            return true;
        }
        if(request.service()!=20||request.method()!=3||args.size()!=1
                ||!(args.getFirst() instanceof SessionDecoder.Listener listener)
                ||!listener.type().endsWith("$ConfirmMarshaller"))throw new SessionDecoder.Rejected("Unsupported interior party operation");
        profile=loadProfile(profile);
        var exit=scene.map().portals().triggers().stream().filter(p->{
            try{return scenes.catalog().scene(p.destination()).town();}catch(IOException e){return false;}
        }).findFirst().orElse(null);
        if(!subscribed||!entered||exit==null||forcedReceiver==null)output.send(()->codec.invocationFailure(oid,listener.requestId(),"Interior exit is not available"));
        else{beginPortal(exit);output.send(()->codec.invocationConfirmed(oid,listener.requestId()));}
        return true;
    }
    synchronized boolean departingScene(int oid) { return !closed&&departedSceneOid!=0&&departedSceneOid==oid; }
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
            synchronized (ACTIVE) {
                if (ACTIVE.containsKey(loaded.worldId())) throw new IOException("This knight is already online");
                profile = loaded;
                knight = choice;
                scene = scenes.destination(scenes.catalog().entry().id(),loaded.worldId());
                ACTIVE.put(loaded.worldId(), this);
            }
        }
        created = SceneClock.now();
        attach();
        output.send(() -> codec.selected(oid, knight, profile));
        output.send(() -> codec.townState(oid,profile));
        output.send(() -> codec.playerFlags(oid,gameMaster,inspectionHidden));
        receiverChanged(receivers);
        log.selected(knight.id(),profile.worldId(),knight.name());
        log.info("Character selected; knight=" + knight.name() + " (#" + profile.worldId() + "); rosterId="+knight.id());
        monitorScene();
        log.debug("Knight/scene initialization completed; duration-ms="+((System.nanoTime()-selectionStarted)/1000000));
    }

    /** Call outside the CharacterStore publication lock to preserve session/account lock order. */
    synchronized void refresh() throws IOException {
        if (selected()) {
            var visible=visibleProfile();output.send(() -> codec.selected(oid, knight, visible));
            publishQuickbar();
            output.send(() -> codec.playerFlags(oid,gameMaster,inspectionHidden));
            if (subscribed) output.send(() -> codec.sceneLocation(oid,scene));
        }
    }

    synchronized void receiverChanged(Map<String, Short> receivers) throws IOException {
        Short receiver = receivers.get(SceneRegistry.ZONE_RECEIVER);
        if (receiver == null) forcedReceiver = null;
        if (selected() && receiver != null && !receiver.equals(forcedReceiver)) {
            var destination=portalMove==null?scene:portalMove.destination();
            output.send(() -> codec.forceScene(oid, receiver,destination));
            forcedReceiver = receiver;
        }
    }

    synchronized boolean invocation(SessionDecoder.Invocation request) throws IOException {
        if(closed||socket.isClosed())throw new IOException("Session is closed");
        int service = request.service();
        var args = request.arguments();
        if(service==1&&request.method()==7){
            if(args.size()!=2||!(args.get(0) instanceof String destination)||!(args.get(1) instanceof SessionDecoder.Listener listener)||!listener.type().endsWith("$ResultMarshaller"))throw new SessionDecoder.Rejected("Invalid town destination request");
            try{
                if(!selected()||!entered||!subscribed||forcedReceiver==null||portalMove!=null)throw new IOException("Finish entering this scene first");characters.worldProfile(knight.name(),authRevision,profile);
                SceneCatalog.MapScene map=null;ScenePortals.Entrance entrance=null;
                if(destination.equals("haven"))map=scenes.catalog().entry();
                if(map==null)for(var candidate:scenes.catalog().scenes())if(candidate.town()&&candidate.name().equals("%town:m."+destination)){map=candidate;break;}
                if(map==null&&destination.matches("[a-zA-Z0-9_-]{1,96}"))for(var candidate:scenes.catalog().scenes())if(candidate.town()){
                    String prefix="destination."+destination+".";var stats=candidate.content().stats();if(!stats.containsKey(prefix+"x"))continue;
                    float x=stats.get(prefix+"x"),y=stats.get(prefix+"y");if(!candidate.collision().walkable(x,y))continue;
                    if(candidate.portals().triggers().stream().anyMatch(t->t.intersects(x,y)))continue;
                    map=candidate;entrance=new ScenePortals.Entrance(Math.round(stats.get(prefix+"id")),x,y,stats.getOrDefault(prefix+"rotation",0f));break;
                }
                if(map==null)throw new IOException("%chat:m.no_such_scene");
                var next=scenes.destination(map.id(),profile.worldId());if(entrance==null)entrance=new ScenePortals.Entrance(1,map.spawnX(),map.spawnY(),map.spawnRotation());
                closeConversation();closeTownDialog();portalMove=new PortalMove(next,entrance);departedSceneOid=scene.oid();leaveChat();subscribed=entered=false;sceneGeneration++;membership.pause();short receiver=forcedReceiver;
                output.send(()->codec.forceScene(oid,receiver,next));output.send(()->codec.townResult(oid,listener.requestId(),null,profile));
            }catch(CharacterStore.Refused|IOException refused){output.send(()->codec.invocationFailure(oid,listener.requestId(),refused.getMessage()));}return true;
        }
        if(service==1&&request.method()==13) {
            if(args.size()!=1||!(args.getFirst() instanceof Boolean hidden))throw new SessionDecoder.Rejected("Invalid inspect privacy request");
            characters.inspectionHidden(authRevision,hidden);inspectionHidden=hidden;
            gameMaster=roles.gameMaster();if(membership!=null)membership.appearance(actorAppearance());
            output.send(()->codec.playerFlags(oid,gameMaster,inspectionHidden));return true;
        }
        if ((service == 4 || service == 5) && request.method() == 1) {
            move(args, service == 5);
            return true;
        }
        if ((service == 10 && request.method() == 7) || (service == 11 && request.method() == 1)) {
            if (args.size() != 2 || !(args.get(1) instanceof SessionDecoder.Listener listener)
                    || !listener.type().endsWith("$ResultMarshaller")
                    || (service == 10 ? !(args.get(0) instanceof String) : !(args.get(0) instanceof Long))) {
                throw new SessionDecoder.Rejected("Invalid town feed request");
            }
            if (selected()) output.send(() -> codec.emptyListResult(oid, listener.requestId()));
            else output.send(() -> codec.invocationFailure(oid, listener.requestId(), "Select a knight first"));
            return true;
        }
        if (service == 9 && request.method() == 9) {
            equip(args);
            return true;
        }
        if(service==9&&(request.method()==1||request.method()==14)){
            int count=request.method()==1?2:3;
            if(args.size()!=count||!(args.getFirst() instanceof Long id)||!(args.getLast() instanceof SessionDecoder.Listener listener)||!listener.type().endsWith("$ConfirmMarshaller")
                ||count==3&&args.get(1)!=null)throw new SessionDecoder.Rejected("Invalid item use request");
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
            var targetMap=scenes.catalog().scene(target);
            if(zone && (!(args.get(2) instanceof Integer) || !args.get(0).equals(targetMap.zone())))
                throw new IOException("Scene does not belong to that zone");
            if(portalMove!=null&&target!=portalMove.destination().map().id())throw new IOException("Finish the current portal journey first");
            var next=scenes.destination(target,profile.worldId());
            if(portalMove!=null&&next!=portalMove.destination())throw new IOException("Finish the current portal journey first");
            if(next!=scene||portalMove!=null) {
                subscribedPartyOid=0;
                closeConversation();
                if(!temporaryItems.isEmpty()){var before=visibleProfile();temporaryItems.clear();output.send(()->codec.itemChanges(oid,knight,before,profile));publishQuickbar();}
                leaveChat();
                subscribed=entered=false;sceneGeneration++;occupants=Set.of();occupantRoles=Map.of();lastSnapshot=null;
                reference=acknowledge=0;departedSceneOid=scene.oid();membership.close();scenes.release(scene,profile.worldId());scene=next;
                arrival=portalMove==null?null:portalMove.entrance();portalMove=null;
                created=SceneClock.now();attach();
                monitorScene();
            }
            if(!scene.map().town()&&!interiorThrottleSent) {
                // Keep native level inputs from accumulating ahead of travel/keepalive
                // requests in the client's ten-message TCP fallback queue.
                output.send(codec::interiorInputThrottle);interiorThrottleSent=true;
            }
            int party=scene.map().town()?0:SessionCodec.interiorPartyOid(scene);
            // The stock PartyDirector resubscribes on every partyOid event,
            // even when its value is unchanged. Existing HUD listeners retain
            // the previous party object if a second snapshot replaces it.
            if(party!=announcedPartyOid){output.send(() -> codec.sceneParty(oid,scene));announcedPartyOid=party;}
            // The stock dungeon HUD creates its quickbar once, while the scene
            // model is attached. Let its party subscription complete first.
            if(scene.map().town()||subscribedPartyOid==SessionCodec.interiorPartyOid(scene))completeMove(listener.requestId(),zone);
            else deferredMove=new DeferredMove(listener.requestId(),zone,scene.oid());
            log.debug("Scene transition initialized; duration-ms="+((System.nanoTime()-transitionStarted)/1000000));
        } catch(IOException error) {
            output.send(() -> codec.invocationFailure(oid,listener.requestId(),error.getMessage()));
        }
    }

    private void completeMove(short requestId,boolean zone)throws IOException {
        output.send(() -> codec.sceneMove(oid,requestId,zone,scene,scenes.catalog()));
        output.send(() -> codec.sceneLocation(oid,scene));
    }

    /** Stock admin service, resolved by the client from the bootstrap; every call is authorized here. */
    synchronized void admin(SessionDecoder.Invocation request) throws IOException {
        if(request.service()!=SessionCodec.ADMIN_SERVICE||request.oid()!=SessionCodec.INVOCATION_OID)return;
        var args=request.arguments();
        SessionDecoder.Listener listener=args.isEmpty()||!(args.getLast() instanceof SessionDecoder.Listener last)?null:last;
        String failure=null;
        int method=request.method();
        if(method!=7&&method!=8&&method!=41&&method!=42&&method!=23&&method!=45&&method!=46&&method!=47) {
            // Unknown tools must not leave the stock dashboard waiting for a callback.
            if(listener!=null){short id=listener.requestId();output.send(()->codec.invocationFailure(oid,id,"This admin tool is not available yet"));}
            return;
        }
        if(listener==null||!listener.type().equals("com.threerings.presents.data.InvocationMarshaller$"+(method==23?"ResultMarshaller":"ConfirmMarshaller")))throw new SessionDecoder.Rejected("Invalid admin listener");
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
            if(expireDays>0)return "Item expiry is not supported yet";
            var entry=characters.catalog().require(reference.name());
            if(locked&&!entry.locked()&&!entry.permanent())
                return "Locked items are not supported for this catalogue entry";
            try{characters.worldProfile(knight.name(),authRevision);}
            catch(CharacterStore.Refused refused){throw new IOException("Knight changed; reconnect",refused);}
            var before=visibleProfile();
            profile=characters.grantItems(characters.username(),knight.name(),entry.config(),quantity,authRevision);
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

    void subscribe(short message) throws IOException {
        if (!selected()) throw new IOException("Select a knight before subscribing to a scene");
        SceneRegistry.Instance place;
        synchronized(this){place=scene;}
        var snapshot = place.simulation().snapshot();
        synchronized (this) {
            if(scene!=place||closed)throw new IOException("Scene changed during subscription");
            if(portalMove!=null)throw new IOException("Scene is departing through a portal");
            output.send(() -> codec.scene(message, scene, snapshot.players()));
            leaveChat();
            lastSnapshot = null;
            reference = acknowledge = 0;
            occupants = snapshot.occupants();
            occupantRoles=snapshot.roles();
            subscribed = true;
            entered = false;
            sceneGeneration++;
            membership.pause();
        }
    }

    /** The stock controller sends enteredPlace after attaching its scene-delta listener. */
    synchronized void entered() throws IOException {
        if(portalMove!=null)return; // A source readiness RPC may already be queued before forceScene.
        if (!subscribed || closed) throw new IOException("Subscribe to town before entering it");
        if (entered) return;
        entered = true;
        departedSceneOid=0;
        int generation=sceneGeneration,place=scene.oid();
        chat=CHAT.join(place,oid,knight.name(),chatRate,message->deliverChat(place,generation,message));
        trades=PlayerTrades.forStore(characters);
        tradeMember=trades.join(characters,knight,profile,authRevision,place,oid,output,codec,socket);
        lastStamp = SceneClock.now();
        membership.enter();
        persistence=WorldStatePoller.join(this,characters,profile,authRevision,knight.name());
        if(delivery==null)delivery=Thread.ofVirtual().name("SceneDelivery-"+oid).start(this::deliverPublications);
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

    void speak(SessionDecoder.Invocation request)throws IOException {
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
        String text=(String)request.arguments().get(0);
        if(text.stripLeading().startsWith("!")&&roles.gameMaster()) {
            try {
                if(!chatRate.allow(System.nanoTime()))throw new IOException("Please slow down before sending another command.");
                String result=org.tortodrone.server.ItemCommands.chat(SceneChat.validate(text).substring(1),characters.catalog(),
                    (user,name,config,count)->characters.grantItems(user,name,config,count,authRevision));
                feedback(place,generation,result);
            }catch(IOException refused){feedback(place,generation,refused.getMessage());}
            return;
        }
        try {
            if(!CHAT.speak(sender,(String)request.arguments().get(0),(Byte)request.arguments().get(1)))
                feedback(place,generation,"Please slow down before sending another message.");
        }catch(IOException refused){feedback(place,generation,refused.getMessage());}
    }
    private synchronized void feedback(int place,int generation,String reason)throws IOException {
        if(!closed&&subscribed&&entered&&scene.oid()==place&&sceneGeneration==generation)output.send(()->codec.chatFeedback(scene.map().town()?place:SessionCodec.interiorPartyOid(scene),reason));
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
            CHAT.tell(sender,target,text);
            output.send(()->codec.tellSucceeded(oid,listener.requestId()));
        }catch(IOException refused){output.send(()->codec.invocationFailure(oid,listener.requestId(),refused.getMessage()));}
    }
    private synchronized boolean deliverChat(int place,int generation,SceneChat.Message message)throws IOException {
        if(closed||socket.isClosed()||!subscribed||!entered||scene.oid()!=place||sceneGeneration!=generation)return false;
        if(message.personal()) {
            try{characters.worldProfile(knight.name(),authRevision);}catch(CharacterStore.Refused refused){return false;}
        }
        output.send(()->codec.chat(message.personal()?oid:scene.map().town()?place:SessionCodec.interiorPartyOid(scene),message));return true;
    }
    private void leaveChat(){
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
        ping = (short) Math.max(0, Math.min(Short.MAX_VALUE, (long) sceneTime - input.smoothedTime()));
        for (var frame : input.frames()) {
            if (membership.input(frame.timestamp(), frame.rotation(), frame.direction(),
                    frame.flags(),frame.actionChanged(), sceneTime, now)) acknowledge = frame.timestamp();
        }
    }

    /** One latest immutable publication, replacing obsolete frames before encoding. */
    private void offer(SceneRegistry.Instance place,SceneSimulation.Snapshot snapshot) {
        synchronized(publications){if(deliveryStopped)return;pending=new Publication(place,snapshot);publications.notifyAll();}
    }
    private void deliverPublications() {
        try {
            while(true) {
                Publication publication;
                synchronized(publications) {
                    while(pending==null&&!deliveryStopped)publications.wait();
                    if(deliveryStopped)return;
                    publication=pending;pending=null;
                }
                publish(publication);
            }
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        catch(IOException|RuntimeException failure) {
            log.debug("Town session closed; "+failure.getClass().getSimpleName());close();
            try{socket.close();}catch(IOException ignored){}
        }
    }
    private synchronized void publish(Publication publication)throws IOException {
        var current=publication.snapshot();int stamp=current.timestamp();
        if(closed||!subscribed||!entered||scene!=publication.scene()||stamp-lastStamp<=0)return;
        var delta=current.delta(lastSnapshot);
        // Subscription already announces occupants; the first reliable delta has no baseline.
        var changedRoles=lastSnapshot==null?current.actors().stream().filter(a->occupantRoles.containsKey(a.id())&&occupantRoles.get(a.id())!=a.gameMaster()).toList():delta.changedRoles();
        if(!changedRoles.isEmpty())output.send(()->codec.occupantRoles(scene.oid(),changedRoles));
        if(!current.occupants().equals(occupants)){output.send(()->codec.occupants(scene.oid(),current.players()));occupants=current.occupants();}
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
        if(subscribedPartyOid==SessionCodec.interiorPartyOid(scene)&&health!=null&&currentOwn.sceneKnight()!=null){
            var status=currentOwn.sceneKnight().status();
            if(!health.equals(publishedHealth)||!status.equals(publishedStatus)){
                output.send(()->codec.partyVitals(subscribedPartyOid,oid,health,status));
                publishedHealth=health;publishedStatus=status;
            }
        }
        if(!scene.map().town())for(var event:current.combatEventsSince(lastSnapshot))output.send(()->codec.partyDamage(SessionCodec.interiorPartyOid(scene),event));
        lastStamp=reference=stamp;lastSnapshot=current;
        collectPickups();
        for(long id:membership.takeUsedItems()){
            var item=temporaryItems.get(id);if(item==null)continue;var before=visibleProfile();
            if(item.count()==1)temporaryItems.remove(id);else temporaryItems.put(id,new WorldProfile.Item(id,item.slot(),item.config(),item.accessory(),item.heat(),item.equippedSlot(),item.bound(),item.bindOnEquip(),item.starter(),item.count()-1,item.permanent()));
            var after=visibleProfile();output.send(()->codec.itemChanges(oid,knight,before,after));
            publishQuickbar();
        }
        var own=current.players().stream().filter(a->a.id()==profile.worldId()).findFirst().orElse(null);
        if(townDialog!=null&&tradeMember!=null&&townTradeGeneration!=tradeMember.dialogGeneration){townDialog=null;townSource=null;townService++;}
        if(townDialog!=null&&(own==null||Math.hypot(own.x()-townX,own.y()-townY)>1))closeTownDialog();
        if(conversation!=null&&(own==null||Math.hypot(own.x()-conversationX,own.y()-conversationY)>1))closeConversation();
        int target=membership.takeInteraction();
        if(target>0&&own!=null){
            var source=current.actors().stream().filter(a->a.id()==target).findFirst().orElse(null);
            var binding=source==null?null:scene.map().content().conversations().get(source.config().name());
            var sourceSpec=source==null||source.entity()==null?null:scene.map().content().entries().stream().filter(e->e.key().equals(source.entity().key())).map(SceneContent.Entry::spec).findFirst().orElse(null);
            var station=source==null?null:scenes.town().station(sourceSpec==null?source.config().name():sourceSpec.text("town-station",source.config().name()));
            if(sourceSpec!=null)binding=scene.map().content().conversations().get(sourceSpec.text("dialogue",source.config().name()));
            if(station!=null&&(trades==null||!trades.active(tradeMember))&&own.interaction()!=null&&own.interaction().actorId()==target){openTownDialog(station,source);}
            else if(binding!=null&&(trades==null||!trades.active(tradeMember))&&own.interaction()!=null&&own.interaction().actorId()==target){
                closeTownDialog();conversation=binding;conversationNode=binding.initial();conversationSource=source;
                conversationCreated=System.currentTimeMillis();conversationX=own.x();conversationY=own.y();
                output.send(()->codec.conversationDialog(oid,scene.oid(),conversation,conversationNode,source,conversationCreated));
            }
        }
        var trigger=current.portal(profile.worldId());
        if(trigger!=null&&forcedReceiver!=null)beginPortal(trigger);
    }
    /** Simulation selects the destination; the normal stock move callback loads its scene. */
    private void beginPortal(ScenePortals.Trigger trigger)throws IOException {
        closeConversation();
        try{characters.worldProfile(knight.name(),authRevision,profile);}
        catch(CharacterStore.Refused refused){throw new IOException("Knight changed; reconnect",refused);}
        var destination=scenes.destination(trigger.destination(),profile.worldId());
        var entrance=destination.map().portals().entrances().get(trigger.entrance());
        if(entrance==null)throw new IOException("Portal destination is unavailable");
        portalMove=new PortalMove(destination,entrance);departedSceneOid=scene.oid();
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
            if(state.gameMaster()!=gameMaster){gameMaster=state.gameMaster();membership.appearance(actorAppearance());output.send(()->codec.playerFlags(oid,gameMaster,inspectionHidden));}
            // An equip may commit while the read is in flight: never roll back its publication.
            if(fresh.revision()>profile.revision()) {
                var visibleBefore=visibleProfile();var previous=profile;profile=fresh;
                if(fresh.crowns()!=previous.crowns()||fresh.energy()!=previous.energy())output.send(()->codec.balances(oid,profile));
                if(!fresh.progress().equals(previous.progress())){membership.equipment(visibleProfile());output.send(()->codec.knightProgress(oid,profile));}
                if(!fresh.town().equals(previous.town())){membership.equipment(visibleProfile());output.send(()->codec.townState(oid,profile));}
                if(!fresh.items().equals(previous.items())){updateAppearance();var visibleAfter=visibleProfile();output.send(()->codec.itemChanges(oid,knight,visibleBefore,visibleAfter));}
            }
        }
    }
    private WorldProfile loadProfile(WorldProfile cached)throws IOException {
        try{return characters.worldProfile(knight.name(),authRevision,cached);}
        catch(CharacterStore.Refused refused){throw new IOException("Knight changed; reconnect",refused);}
    }
    synchronized void refreshFailed(WorldStatePoller.Registration registration,Exception failure) {
        if(persistence!=registration||closed)return;
        log.debug("Town session closed; "+failure.getClass().getSimpleName());close();
        try{socket.close();}catch(IOException ignored){}
    }
    private void attach() {
        membership=null; // The old scene membership is closed before rebuilding its replacement.
        rebuildAppearance();var place=scene;
        membership=place.simulation().join(actorAppearance(),snapshot->offer(place,snapshot));
        membership.equipment(visibleProfile());
        if(!place.map().town())membership.combat(weapons,scenes.equipment(),visibleProfile());
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
    private void updateAppearance(){rebuildAppearance();membership.equipment(visibleProfile());membership.appearance(actorAppearance());}
    private ActorState actorAppearance() {
        return new ActorState(profile.worldId(),oid,knight.name(),256|knight.color(),created,
            arrival==null?scene.map().spawnX():arrival.x(),arrival==null?scene.map().spawnY():arrival.y(),
            arrival==null?scene.map().spawnRotation():arrival.rotation(),0,0,0,created,appearance,gameMaster);
    }

    private static long monotonicTime() { return System.nanoTime() / 1_000_000L; }
    private void monitorScene() {
        log.scene(scene.map().id(),scene.oid(),scene.map().name());
        log.info("Scene entered; scene="+scene.map().name()+" (#"+scene.map().id()+"); instanceId="+scene.oid());
    }
    @Override public synchronized void close() {
        leaveChat();
        closed = true;
        subscribed = false;
        if(membership!=null)membership.close();
        if(profile!=null){scenes.release(scene,profile.worldId());if(portalMove!=null)scenes.release(portalMove.destination(),profile.worldId());}
        synchronized(publications){deliveryStopped=true;pending=null;publications.notifyAll();}
        if (profile != null) synchronized (ACTIVE) { ACTIVE.remove(profile.worldId(), this); }
    }
}
