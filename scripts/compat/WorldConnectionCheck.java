import com.samskivert.util.at;import com.threerings.presents.client.*;import com.threerings.presents.dobj.*;import com.threerings.projectx.client.aU;import com.threerings.projectx.data.*;import com.threerings.projectx.town.data.*;import com.threerings.whirled.zone.data.*;import com.threerings.whirled.data.*;import com.threerings.crowd.data.*;import com.threerings.tudey.data.*;import com.threerings.tudey.dobj.*;import com.threerings.projectx.item.data.*;import com.threerings.util.Name;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;import java.util.function.*;
public class WorldConnectionCheck {
 static void registerSceneReceiver(Client client)throws Exception {
  Class<?> receiver=Class.forName("com.threerings.whirled.zone.a.f");
  Object instance=java.lang.reflect.Proxy.newProxyInstance(receiver.getClassLoader(),new Class[]{receiver},(proxy,method,values)->null);
  client.xd().a((com.threerings.presents.client.w)Class.forName("com.threerings.whirled.zone.a.a").getConstructor(receiver).newInstance(instance));
 }
 static LinkedBlockingQueue<Runnable> tasks=new LinkedBlockingQueue<>();
 static void waitFor(BooleanSupplier ready)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!ready.getAsBoolean()&&System.nanoTime()<end){var task=tasks.poll(100,TimeUnit.MILLISECONDS);if(task!=null)task.run();}if(!ready.getAsBoolean())throw new AssertionError("Client fixture timeout");}
 static class Reply implements A.a{boolean done;String reason;public void xx(){done=true;}public void aT(String s){reason=s;done=true;}}
 static class Zone {boolean done;int oid;SceneModel model;}

 static TownSceneObject scene;static SceneDeltaEvent last;static int deltas, repeatedAdds, configReplacements;
 static com.threerings.presents.dobj.t<TownSceneObject> primarySubscription;
 static final List<com.threerings.crowd.chat.data.UserMessage> primaryChat=new ArrayList<>();
 static final Map<Integer,Map<Integer,com.threerings.tudey.data.actor.Actor>> snapshots=new LinkedHashMap<>();
 static void received(SceneDeltaEvent event) {
  if(last!=null && (event.QY()<=last.QY() || event.aeh()!=last.QY()))
   throw new AssertionError("TCP delta does not reference its preceding snapshot");
  Map<Integer,com.threerings.tudey.data.actor.Actor> baseline=event.aeh()==0?Map.of():snapshots.get(event.aeh());
  if(baseline==null)throw new AssertionError("Missing scene reference "+event.aeh());
  var current=new HashMap<>(baseline);
  if(event.aej()!=null)for(var actor:event.aej())current.put(actor.vw(),actor);
  if(event.aek()!=null)for(var delta:event.aek()) {
   var actor=(com.threerings.tudey.data.actor.Actor)delta.apply(current.get(delta.vw()));
   current.put(actor.vw(),actor);
  }
  if(event.ael()!=null)for(int id:event.ael())current.remove(id);
  if(last!=null)for(var actor:current.values()) {
   var previous=snapshots.get(last.QY()).get(actor.vw());
   if(previous!=null) {
    if(event.aej()!=null)for(var added:event.aej())if(added.vw()==actor.vw())repeatedAdds++;
    if(previous.Mv()!=actor.Mv())configReplacements++;
   }
  }
  snapshots.put(event.QY(),Map.copyOf(current));
  while(snapshots.size()>128)snapshots.remove(snapshots.keySet().iterator().next());
  last=event;deltas++;
 }
 static com.threerings.projectx.data.actor.Knight controlled(PlayerObject player){return (com.threerings.projectx.data.actor.Knight)snapshots.get(last.QY()).get(player.pawnId);}
 static boolean bound(PlayerObject player,long id){for(var item:player.items)if(item.VA()==id)return item.Cs();return false;}
 static boolean isolation;
 public static void main(String[]args)throws Exception { isolation=args.length>2&&args[2].equals("isolated");if(!isolation)run(args,true);run(args,false); }
 static void run(String[]args,boolean fresh)throws Exception {
  tasks.clear();snapshots.clear();primaryChat.clear();scene=null;last=null;deltas=repeatedAdds=configReplacements=0;
  System.setProperty("com.threerings.io.enumPolicy","ORDINAL");Thread dispatch=Thread.currentThread();at queue=new at(){public void postRunnable(Runnable r){tasks.add(r);}public boolean isDispatchThread(){return Thread.currentThread()==dispatch;}public boolean isRunning(){return true;}};
  var client=new Client(new ProjectXCredentials(new Name("LanPlayer"),"lan-test-password"),queue);client.b("127.0.0.1",new int[]{Integer.parseInt(args[0])},new int[0]);client.dr(Files.readString(Path.of(args[1])).trim());client.ds("world-fixture");client.dt("projectx");client.xg();
  // Closing a stock connection can still post its last network callback to this shared queue.
  // Retire its observer before the next run resets the scene's baseline history.
  boolean[] observing={true};
  try {
   waitFor(client::isLoggedOn);var player=(PlayerObject)client.xc();System.out.println("PASS: real client normal login");
   refusedStockOptions(client,player);
   Class<?> receiver=Class.forName("com.threerings.whirled.zone.a.f");Object receiverInstance=java.lang.reflect.Proxy.newProxyInstance(receiver.getClassLoader(),new Class[]{receiver},(proxy,method,values)->null);var decoder=Class.forName("com.threerings.whirled.zone.a.a").getConstructor(receiver).newInstance(receiverInstance);client.xd().a((com.threerings.presents.client.w)decoder);
   // A round-trip ensures receiver registration arrives before the save request.
   Reply validation=new Reply();((aU)client.bU(aU.class)).f(new Name("Town-Knight"),validation);waitFor(()->validation.done);
   Reply create=new Reply();if(fresh)((aU)client.bU(aU.class)).a(null,new Name("Town-Knight"),"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,ProjectXCodes.KnightHeight.BASE,ProjectXCodes.EyeType.DEFAULT,null,List.of(),null,create);else ((aU)client.bU(aU.class)).b(new Name("Town-Knight"),null,create);waitFor(()->create.done);
   if(create.reason!=null)throw new AssertionError(create.reason);if(player.knightId<=0||player.pawnId!=player.knightId||player.bagId!=player.knightId||player.items.size()!=5||player.equipment.length!=16||player.homeTown==null)throw new AssertionError("Selected player invalid");
   for(var item:player.items)if(!item.Cs())throw new AssertionError("Starter not bound on "+(fresh?"creation":"reconnect"));
   System.out.println("PASS: "+(fresh?"creation":"reconnect")+" selects durable identity and five bound starter items");
   Zone move=moveScene(client,1);if(move.model.sceneId!=1||!(move.model instanceof TudeySceneModel)||move.oid!=(isolation?11:10))throw new AssertionError("Town Square instance invalid");
   player.a(new com.threerings.tudey.dobj.a(){public void c(SceneDeltaEvent e){if(observing[0])received(e);}});
   subscribePrimary(client,move.oid);waitFor(()->scene!=null);
   if(deltas!=0)throw new AssertionError("Scene deltas sent before enteredPlace");
   scene.tudeySceneService.adB();waitFor(()->deltas>0);if(last.aej()==null||last.aej().length!=1||last.aej()[0].vw()!=player.pawnId)throw new AssertionError("Actor not received");
   if(last.aej()[0] instanceof com.threerings.tudey.data.actor.Mobile mobile && mobile.aeb()<=0)throw new AssertionError("Movement prediction limit is zero");
   if(!((com.threerings.projectx.data.actor.Knight)last.aej()[0]).Np())throw new AssertionError("Spawn activity blocks controls");
   if(player.loadouts.isEmpty())throw new AssertionError("Current loadout missing");
   peaceful(controlled(player));
   refusedStockOptions(client,player);
   System.out.println("PASS: zone callback loads original Town Square, scene subscription and controlled actor");
   // Loading can postpone input/acknowledgement for longer than the old 40-snapshot history.
   waitFor(()->deltas>=45);
   if(repeatedAdds!=0||configReplacements!=0)throw new AssertionError("Idle entry repeatedly replaces actor/model: adds="+repeatedAdds+", configs="+configReplacements);
   System.out.println("PASS: delayed entry acknowledgements retain the actor and its model configuration");
   float initialX=controlled(player).tz().x;int count=deltas;scene.a(last.QY(),last.QY(),new InputFrame[]{new ProjectXInputFrame(last.QY()+50,1.5f,0,1,0)});waitFor(()->deltas>count+3);if(last.aek()==null)throw new AssertionError("Movement delta missing");
   var actor=controlled(player);
   if(actor.tz().x<=initialX || Math.abs(actor.tA()-1.5f)>.01)throw new AssertionError("Movement/facing ignored: "+actor);System.out.println("PASS: movement and independent mouse-facing updates");
   scene.a(last.QY(),last.QY(),new InputFrame[]{new ProjectXInputFrame(last.QY()+50,1.5f,0,3,0)});
   waitFor(()->controlled(player).adU()==3);
   int inventoryStarted=controlled(player).adV();float inventoryX=controlled(player).tz().x;
   scene.a(last.QY(),last.QY(),new InputFrame[]{new ProjectXInputFrame(last.QY()+50,2,0,3,0)});
   int inventoryCount=deltas;waitFor(()->deltas>=inventoryCount+3);
   if(controlled(player).adV()!=inventoryStarted || controlled(player).bR(2)
       || controlled(player).tz().x!=inventoryX)throw new AssertionError("Inventory activity restarted or allowed movement");
   scene.a(last.QY(),last.QY(),new InputFrame[]{new ProjectXInputFrame(last.QY()+50,2,0,0,0)});
   waitFor(()->controlled(player).adU()==4);
   int closingStarted=controlled(player).adV();
   waitFor(()->controlled(player).adU()==0);
   if(controlled(player).adV()!=closingStarted+org.tortodrone.world.TownMovement.INVENTORY_END_MILLIS)
       throw new AssertionError("Inventory closing activity did not finish at its deadline");
   System.out.println("PASS: inventory blocks movement, retains its animation start and closes into idle");
   Reply sit=new Reply();scene.projectXSceneService.b("Sit",null,sit);waitFor(()->sit.done);
   if(sit.reason!=null)throw new AssertionError("Stock sit RPC rejected: "+sit.reason);
   waitFor(()->controlled(player).adU()==26);int seatedStarted=controlled(player).adV();
   float seatedX=controlled(player).tz().x;var seatedModel=controlled(player).Mv();
   int seatedCount=deltas;waitFor(()->deltas>=seatedCount+15);
   if(controlled(player).adU()!=26||controlled(player).adV()!=seatedStarted
       ||controlled(player).tz().x!=seatedX||controlled(player).bR(2)||controlled(player).Mv()!=seatedModel)
    throw new AssertionError("Seated pose expired, moved, restarted or replaced its model");
   scene.a(last.QY(),last.QY(),new InputFrame[]{new ProjectXInputFrame(last.QY()+50,2,0,1,0)});
   waitFor(()->controlled(player).adU()==46);int standingStarted=controlled(player).adV();
   while(last.QY()<standingStarted+1333){
    if(controlled(player).tz().x!=seatedX||controlled(player).bR(2))throw new AssertionError("Walk starts before standing completes");
    int beforeStanding=deltas;scene.a(last.QY(),last.QY(),new InputFrame[]{new ProjectXInputFrame(last.QY()+50,2,0,1,0)});
    waitFor(()->deltas>beforeStanding);
   }
   waitFor(()->controlled(player).adU()==0&&controlled(player).tz().x>seatedX);
   if(controlled(player).adV()!=standingStarted+1333)throw new AssertionError("Standing deadline differs from stock");
   scene.a(last.QY(),last.QY(),new InputFrame[]{new ProjectXInputFrame(last.QY()+50,2,0,0,0)});
   System.out.println("PASS: stock sit RPC, persistent pose, stable model and standing deadline before walking");
   Reply sitForInventory=new Reply();scene.projectXSceneService.b("Sit",null,sitForInventory);
   waitFor(()->sitForInventory.done);if(sitForInventory.reason!=null)throw new AssertionError(sitForInventory.reason);
   waitFor(()->controlled(player).adU()==26);
   scene.a(last.QY(),last.QY(),new InputFrame[]{new ProjectXInputFrame(last.QY()+50,2,0,2,0)});
   waitFor(()->controlled(player).adU()==3);
   scene.a(last.QY(),last.QY(),new InputFrame[]{new ProjectXInputFrame(last.QY()+50,2,0,0,0)});
   waitFor(()->controlled(player).adU()==4);waitFor(()->controlled(player).adU()==0);
   System.out.println("PASS: inventory input interrupts sitting and closes back into idle");
   var slotUpdates=new ArrayList<Integer>();int[] loadoutUpdates={0};
   player.a(new j(){public void a(ElementUpdatedEvent e){if(e.getName().equals("equipment"))slotUpdates.add(e.pN());}});
   player.a(new com.threerings.presents.dobj.s<Loadout>(){public void a(EntryAddedEvent<Loadout> e){}public void a(EntryRemovedEvent<Loadout> e){}
    public void a(EntryUpdatedEvent<Loadout> e){if(e.getName().equals("loadouts"))loadoutUpdates[0]++;}});
   var beforeEquipment=actor.Mv();
   long helm=player.equipment[0];var itemService=client.bU(com.threerings.projectx.item.client.L.class);Reply equip=new Reply();itemService.a(helm,ItemCodes.Location.ARSENAL,0,equip);waitFor(()->equip.done);if(equip.reason!=null||player.equipment[0]!=0)throw new AssertionError("Unequip failed: "+equip.reason);
   if(!bound(player,helm))throw new AssertionError("Unequip lost binding");
   if(!slotUpdates.equals(List.of(0))||loadoutUpdates[0]!=1||player.loadouts.f("").equipment[0]!=0)
    throw new AssertionError("Open equipment/loadout observers missed unequip before confirmation");
   waitFor(()->controlled(player).Mv()!=beforeEquipment);
   var withoutHelm=controlled(player).Mv();
   Reply re=new Reply();itemService.a(helm,0,re);waitFor(()->re.done);if(re.reason!=null||player.equipment[0]!=helm||!bound(player,helm))throw new AssertionError("Arsenal equip failed or lost binding: "+re.reason);System.out.println("PASS: real Arsenal equip RPC re-equips the bound helm after unequipping");
   if(!slotUpdates.equals(List.of(0,0))||loadoutUpdates[0]!=2||player.loadouts.f("").equipment[0]!=helm)
    throw new AssertionError("Open equipment/loadout observers missed re-equip before confirmation");
   waitFor(()->controlled(player).Mv()!=withoutHelm);
   var withHelm=controlled(player).Mv();int afterEquip=deltas;waitFor(()->deltas>=afterEquip+10);
   if(controlled(player).Mv()!=withHelm)throw new AssertionError("Equipped model replaced on unchanged ticks");
   System.out.println("PASS: equipment changes update the actor model once and retain it afterward");
   int eventCount=slotUpdates.size(),loadoutCount=loadoutUpdates[0];
   Reply denied=new Reply();itemService.a(helm,7,denied);waitFor(()->denied.done);
   if(denied.reason==null||slotUpdates.size()!=eventCount||loadoutUpdates[0]!=loadoutCount)
    throw new AssertionError("Rejected equip published equipment/loadout updates");
   long sword=player.equipment[6],gun=player.equipment[7];Reply swap=new Reply();
   itemService.a(sword,7,swap);waitFor(()->swap.done);
   if(swap.reason!=null||player.equipment[6]!=0||player.equipment[7]!=sword
       ||!slotUpdates.subList(eventCount,slotUpdates.size()).equals(List.of(6,7))||loadoutUpdates[0]!=loadoutCount+1
       ||player.loadouts.f("").equipment[6]!=0||player.loadouts.f("").equipment[7]!=sword)
    throw new AssertionError("Weapon displacement missed source/destination slot or current-loadout events");
   Reply restoreSword=new Reply();itemService.a(sword,6,restoreSword);waitFor(()->restoreSword.done);
   Reply restoreGun=new Reply();itemService.a(gun,7,restoreGun);waitFor(()->restoreGun.done);
   if(restoreSword.reason!=null||restoreGun.reason!=null||controlled(player).Mv()!=withHelm)
    throw new AssertionError("Weapon edits reset peaceful town appearance");
   peaceful(controlled(player));
   int combatCount=deltas;scene.a(last.QY(),last.QY(),new InputFrame[]{new ProjectXInputFrame(last.QY()+50,1.5f,0,4|8|16|32,0)});
   waitFor(()->deltas>=combatCount+4);
   if(controlled(player).adU()!=0||controlled(player).bR(2))throw new AssertionError("Town combat input changed activity/movement");
   System.out.println("PASS: peaceful town stance, ignored combat inputs and live equipment/loadout observer updates");
   if(fresh)try(var accounts=new org.tortodrone.account.AccountStore(Path.of(args[1]).getParent().getParent())){accounts.currency("LanPlayer","Town-Knight",org.tortodrone.character.Currency.CROWNS,100,-1,-1);accounts.currency("LanPlayer","Town-Knight",org.tortodrone.character.Currency.ENERGY,25,-1,-1);}
   waitFor(()->player.crowns==100&&player.energy==25);System.out.println("PASS: local administration changes live crowns and energy counters");
   multiplayer(args,queue,fresh,player,client);
  }finally{observing[0]=false;client.Q(false);}
 }
 static void refusedStockOptions(Client client,PlayerObject player)throws Exception {
  var knight=(aU)client.bU(aU.class);
  int flags=player.flags,crowns=player.crowns,energy=player.energy,party=player.partyOid;
  long[] equipment=player.equipment.clone();var report=player.lootReportLevel;var prefs=player.prefs;
  for(var level:LootReportLevel.values()) {
   Reply reply=new Reply();knight.a(Pref.LOOT_CHAT_FILTER,level,reply);waitFor(()->reply.done);
   if(reply.reason==null||!reply.reason.contains("Server operation not implemented yet")||!client.isLoggedOn())
    throw new AssertionError("Loot-report option did not fail cleanly: "+reply.reason);
  }
  var social=client.bU(com.threerings.projectx.social.client.G.class);
  for(var difficulty:com.threerings.projectx.dungeon.data.DungeonCodes.Difficulty.values()) {
   Reply reply=new Reply();social.a(false,difficulty,true,reply);waitFor(()->reply.done);
   if(reply.reason==null||!reply.reason.contains("Server operation not implemented yet")||!client.isLoggedOn())
    throw new AssertionError("Party option did not fail cleanly: "+reply.reason);
  }
  Reply validation=new Reply();knight.f(new Name("AuditNameCheck"),validation);waitFor(()->validation.done);
  if(validation.reason!=null||!client.isLoggedOn()||player.flags!=flags||player.crowns!=crowns
    ||player.energy!=energy||player.partyOid!=party||!Arrays.equals(player.equipment,equipment)
    ||player.lootReportLevel!=report||player.prefs!=prefs)
   throw new AssertionError("Option refusal changed player state or broke a subsequent supported RPC");
  System.out.println("PASS: stock loot/party option requests receive all six refusals, retain player state and accept subsequent RPCs");
 }
 static void peaceful(com.threerings.projectx.data.actor.Knight actor) {
  var model=(com.threerings.config.ConfigReference<?>)actor.Mv().kW().get("Model");
  if(!"True".equals(model.kW().get("Shield Away"))||model.kW().containsKey("Weapon")||model.kW().containsKey("Weapon Idle"))
   throw new AssertionError("Town actor carries combat equipment in its hands");
 }
 @FunctionalInterface interface Await {void until(BooleanSupplier ready)throws Exception;}
 static Zone moveScene(Client client,int sceneId)throws Exception {return moveScene(client,sceneId,WorldConnectionCheck::waitFor);}
 static Zone moveScene(Client client,int sceneId,Await await)throws Exception {
  if(scene!=null&&primarySubscription!=null)client.xb().b(scene.xL(),primarySubscription);
  Zone move=new Zone();Class<?> listenerType=Class.forName("com.threerings.whirled.zone.a.g$a"),serviceType=Class.forName("com.threerings.whirled.zone.a.g");
  Object listener=java.lang.reflect.Proxy.newProxyInstance(listenerType.getClassLoader(),new Class[]{listenerType},(proxy,method,values)->{
   if(method.getName().equals("aT"))throw new AssertionError(values[0]);
   if(method.getName().equals("a")&&values.length==4&&values[3] instanceof SceneModel model){move.oid=(Integer)values[0];move.model=model;move.done=true;}return null;});
  serviceType.getMethod("a",int.class,int.class,int.class,listenerType).invoke(client.bU(serviceType),1,sceneId,0,listener);
  await.until(()->move.done);return move;
 }
 static void enterPrimary(Client client,PlayerObject player,Zone place)throws Exception {
  snapshots.clear();last=null;scene=null;deltas=0;
  subscribePrimary(client,place.oid);
  waitFor(()->scene!=null);if(deltas!=0)throw new AssertionError("Deltas before instance readiness");
  scene.tudeySceneService.adB();waitFor(()->deltas>0);
  if(last.aeh()!=0||!snapshots.get(last.QY()).containsKey(player.pawnId))throw new AssertionError("Scene transition has no fresh controlled baseline");
 }
 static void subscribePrimary(Client client,int oid){
  primarySubscription=new com.threerings.presents.dobj.t<TownSceneObject>(){
   public void a(TownSceneObject object){scene=object;listenChat(object,primaryChat);}public void a(int id,ObjectAccessException error){throw new AssertionError(error);}};
  client.xb().a(oid,primarySubscription);
 }
 static void listenChat(TownSceneObject object,List<com.threerings.crowd.chat.data.UserMessage> messages){
  object.a(new com.threerings.presents.dobj.l(){public void a(MessageEvent event){
   if(event.getName().equals("crowd.chat")&&event.yd()[0] instanceof com.threerings.crowd.chat.data.UserMessage message)messages.add(message);
  }});
 }
 static void multiplayer(String[] args,at queue,boolean fresh,PlayerObject primary,Client primaryClient)throws Exception {
  var peer=new Client(new ProjectXCredentials(new Name("PeerPlayer"),"lan-test-password"),queue);
  int peerId=0;
  try {
   peer.b("127.0.0.1",new int[]{Integer.parseInt(args[0])},new int[0]);peer.dr(Files.readString(Path.of(args[1])).trim());peer.ds("world-fixture");peer.dt("projectx");peer.xg();waitFor(peer::isLoggedOn);
   var player=(PlayerObject)peer.xc();
   Class<?> receiver=Class.forName("com.threerings.whirled.zone.a.f");
   Object receiverInstance=java.lang.reflect.Proxy.newProxyInstance(receiver.getClassLoader(),new Class[]{receiver},(proxy,method,values)->null);
   var decoder=Class.forName("com.threerings.whirled.zone.a.a").getConstructor(receiver).newInstance(receiverInstance);peer.xd().a((com.threerings.presents.client.w)decoder);
   if(fresh) {
    Reply taken=new Reply();((aU)peer.bU(aU.class)).f(new Name("TOWN-KNIGHT"),taken);waitFor(()->taken.done);
    if(taken.reason==null||!taken.reason.contains("already in use"))throw new AssertionError("Cross-account name validation accepted");
    Reply duplicate=new Reply();((aU)peer.bU(aU.class)).a(null,new Name("town-knight"),"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,ProjectXCodes.KnightHeight.BASE,ProjectXCodes.EyeType.DEFAULT,null,List.of(),null,duplicate);waitFor(()->duplicate.done);
    if(duplicate.reason==null||!duplicate.reason.contains("already in use")||player.knightId!=0)throw new AssertionError("Duplicate creation selected a knight");
    System.out.println("PASS: stock picker validation and creation reject another account's name case-insensitively");
   }
   Reply validation=new Reply();((aU)peer.bU(aU.class)).f(new Name("Peer-Knight"),validation);waitFor(()->validation.done);
   Reply select=new Reply();
   if(fresh)((aU)peer.bU(aU.class)).a(null,new Name("Peer-Knight"),"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,ProjectXCodes.KnightHeight.BASE,ProjectXCodes.EyeType.DEFAULT,null,List.of(),null,select);
   else ((aU)peer.bU(aU.class)).b(new Name("Peer-Knight"),null,select);
   waitFor(()->select.done);if(select.reason!=null)throw new AssertionError(select.reason);
   peerId=player.pawnId;final int id=peerId;
   if(id==primary.pawnId)throw new AssertionError("Accounts share a world identity");
   if(isolation){int before=deltas;waitFor(()->deltas>=before+3);if(snapshots.get(last.QY()).containsKey(id)||scene.occupantInfo.size()!=1)throw new AssertionError("Actors leaked across instances");}
   else {waitFor(()->snapshots.get(last.QY()).containsKey(id));if(scene.occupantInfo.size()!=2)throw new AssertionError("Peer metadata did not precede its actor");}
   // Load and subscribe through the peer's real zone marshaller.
   Zone move=new Zone();Class<?> zoneListener=Class.forName("com.threerings.whirled.zone.a.g$a"),zoneService=Class.forName("com.threerings.whirled.zone.a.g");
   Object listener=java.lang.reflect.Proxy.newProxyInstance(zoneListener.getClassLoader(),new Class[]{zoneListener},(proxy,method,values)->{if(method.getName().equals("aT"))throw new AssertionError(values[0]);if(method.getName().equals("a")&&values.length==4){move.oid=(Integer)values[0];move.done=true;}return null;});
   zoneService.getMethod("a",int.class,int.class,int.class,zoneListener).invoke(peer.bU(zoneService),1,1,0,listener);waitFor(()->move.done);
   TownSceneObject[] peerScene={null};SceneDeltaEvent[] peerDelta={null};var peerChat=new ArrayList<com.threerings.crowd.chat.data.UserMessage>();
   player.a(new com.threerings.tudey.dobj.a(){public void c(SceneDeltaEvent event){peerDelta[0]=event;}});
   peer.xb().a(move.oid,new com.threerings.presents.dobj.t<TownSceneObject>(){public void a(TownSceneObject obj){peerScene[0]=obj;listenChat(obj,peerChat);}public void a(int oid,ObjectAccessException error){throw new AssertionError(error);}});
   waitFor(()->peerScene[0]!=null);peerScene[0].tudeySceneService.adB();waitFor(()->peerDelta[0]!=null);
   int occupants=isolation?1:2;
   if(peerDelta[0].aej()==null||peerDelta[0].aej().length!=occupants||peerScene[0].occupantInfo.size()!=occupants)throw new AssertionError("Peer received incorrect instance occupants");
   scene.speakService.a("Hello 🌍",(byte)0);waitFor(()->primaryChat.size()==1);
   if(!primaryChat.get(0).speaker.toString().equals("Town-Knight")||!primaryChat.get(0).message.equals("Hello 🌍"))throw new AssertionError("Chat sender/text mismatch");
   if(!isolation)waitFor(()->peerChat.size()==1);
   peerScene[0].speakService.a("waves",(byte)2);waitFor(()->peerChat.size()==(isolation?1:2));
   if(!isolation)waitFor(()->primaryChat.size()==2);
   int chatDeltas=deltas;waitFor(()->deltas>chatDeltas+3);
   if(primaryChat.size()!=(isolation?1:2)||peerChat.size()!=(isolation?1:2))throw new AssertionError("Chat leaks across instances or sender echo duplicated");
   System.out.println("PASS: stock scene speak RPC and user messages preserve Unicode/names/emote mode, sender echo and instance isolation");
   float peerX=isolation?0:snapshots.get(last.QY()).get(id).tz().x;
   peerScene[0].a(peerDelta[0].QY(),peerDelta[0].QY(),new InputFrame[]{new ProjectXInputFrame(peerDelta[0].QY()+50,.7f,0,1,0)});
   if(isolation){waitFor(()->peerDelta[0].aek()!=null);if(snapshots.get(last.QY()).containsKey(id))throw new AssertionError("Movement leaked across instances");System.out.println("PASS: two instances isolate occupants, actors and movement for different accounts");}
   else {
    waitFor(()->snapshots.get(last.QY()).get(id).tz().x>peerX);
    var actor=snapshots.get(last.QY()).get(id);
    if(Math.abs(actor.tA()-.7f)>.01||!actor.bR(2))throw new AssertionError("Peer movement/facing/animation flags not synchronized");
    System.out.println("PASS: two normal accounts receive peer actors, movement, facing and movement-animation flags");
    Zone bazaar=moveScene(primaryClient,2);if(bazaar.model.sceneId!=2||bazaar.oid==move.oid)throw new AssertionError("Distinct external Bazaar scene not loaded");
    enterPrimary(primaryClient,primary,bazaar);waitFor(()->peerScene[0].occupantInfo.size()==1);
    int heard=primaryChat.size(),peerHeard=peerChat.size();
    peerScene[0].speakService.a("stays in Haven",(byte)0);waitFor(()->peerChat.size()==peerHeard+1);
    int separated=deltas;waitFor(()->deltas>separated+3);
    if(primaryChat.size()!=heard)throw new AssertionError("Chat crossed a scene transition");
    if(scene.occupantInfo.size()!=1||snapshots.get(last.QY()).containsKey(id))throw new AssertionError("Old scene occupants retained after transition");
    var spawn=controlled(primary);var map=org.tortodrone.world.SceneRegistry.system().catalog().scene(2);
    if(!map.collision().walkable(spawn.tz().x,spawn.tz().y))throw new AssertionError("Invalid Bazaar spawn");
    float direction=0;boolean found=false;for(int heading=0;heading<8;heading++){float candidate=(float)(heading*Math.PI/4);if(map.collision().walkable(spawn.tz().x+(float)Math.cos(candidate)*.5f,spawn.tz().y+(float)Math.sin(candidate)*.5f)){direction=candidate;found=true;break;}}
    if(!found)throw new AssertionError("No walkable movement from Bazaar spawn");
    float x=spawn.tz().x,y=spawn.tz().y;
    scene.a(last.QY(),last.QY(),new InputFrame[]{new ProjectXInputFrame(last.QY()+50,1.5f,direction,1,0)});
    waitFor(()->controlled(primary).tz().x!=x||controlled(primary).tz().y!=y);
    Zone haven=moveScene(primaryClient,1);enterPrimary(primaryClient,primary,haven);waitFor(()->peerScene[0].occupantInfo.size()==2);
    if(scene.occupantInfo.size()!=2||!snapshots.get(last.QY()).containsKey(id))throw new AssertionError("Round-trip occupants missing");
    System.out.println("PASS: external Town Square/Bazaar round trip resets input and baselines, moves on matching collision and removes old-scene occupants");
   }
  } finally {peer.Q(false);}
  final int departed=peerId;
  if(departed!=0) {
   waitFor(()->!snapshots.get(last.QY()).containsKey(departed));
   if(scene.occupantInfo.size()!=1)throw new AssertionError("Departed peer metadata retained");
   System.out.println("PASS: disconnect removes peer actor and occupant metadata");
  }
 }
}
