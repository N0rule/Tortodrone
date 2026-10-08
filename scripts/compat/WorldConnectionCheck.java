import com.samskivert.util.at;import com.threerings.presents.client.*;import com.threerings.presents.dobj.*;import com.threerings.projectx.client.aU;import com.threerings.projectx.data.*;import com.threerings.projectx.town.data.*;import com.threerings.whirled.zone.data.*;import com.threerings.whirled.data.*;import com.threerings.crowd.data.*;import com.threerings.tudey.data.*;import com.threerings.tudey.dobj.*;import com.threerings.projectx.item.data.*;import com.threerings.util.Name;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;import java.util.function.*;
public class WorldConnectionCheck {
 static LinkedBlockingQueue<Runnable> tasks=new LinkedBlockingQueue<>();
 static void waitFor(BooleanSupplier ready)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!ready.getAsBoolean()&&System.nanoTime()<end){var task=tasks.poll(100,TimeUnit.MILLISECONDS);if(task!=null)task.run();}if(!ready.getAsBoolean())throw new AssertionError("Client fixture timeout");}
 static class Reply implements A.a{boolean done;String reason;public void xx(){done=true;}public void aT(String s){reason=s;done=true;}}
 static class Zone {boolean done;int oid;SceneModel model;}

 static TownSceneObject scene;static SceneDeltaEvent last;static int deltas, repeatedAdds, configReplacements;
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
 static com.threerings.tudey.data.actor.Actor controlled(PlayerObject player){return snapshots.get(last.QY()).get(player.pawnId);}
 public static void main(String[]args)throws Exception { run(args,true); run(args,false); }
 static void run(String[]args,boolean fresh)throws Exception {
  tasks.clear();snapshots.clear();scene=null;last=null;deltas=repeatedAdds=configReplacements=0;
  System.setProperty("com.threerings.io.enumPolicy","ORDINAL");Thread dispatch=Thread.currentThread();at queue=new at(){public void postRunnable(Runnable r){tasks.add(r);}public boolean isDispatchThread(){return Thread.currentThread()==dispatch;}public boolean isRunning(){return true;}};
  var client=new Client(new ProjectXCredentials(new Name("LanPlayer"),"lan-test-password"),queue);client.b("127.0.0.1",new int[]{Integer.parseInt(args[0])},new int[0]);client.dr(Files.readString(Path.of(args[1])).trim());client.ds("world-fixture");client.dt("projectx");client.xg();
  try {
   waitFor(client::isLoggedOn);var player=(PlayerObject)client.xc();System.out.println("PASS: real client normal login");
   Class<?> receiver=Class.forName("com.threerings.whirled.zone.a.f");Object receiverInstance=java.lang.reflect.Proxy.newProxyInstance(receiver.getClassLoader(),new Class[]{receiver},(proxy,method,values)->null);var decoder=Class.forName("com.threerings.whirled.zone.a.a").getConstructor(receiver).newInstance(receiverInstance);client.xd().a((com.threerings.presents.client.w)decoder);
   // A round-trip ensures receiver registration arrives before the save request.
   Reply validation=new Reply();((aU)client.bU(aU.class)).f(new Name("Town-Knight"),validation);waitFor(()->validation.done);
   Reply create=new Reply();if(fresh)((aU)client.bU(aU.class)).a(null,new Name("Town-Knight"),"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,ProjectXCodes.KnightHeight.BASE,ProjectXCodes.EyeType.DEFAULT,null,List.of(),null,create);else ((aU)client.bU(aU.class)).b(new Name("Town-Knight"),null,create);waitFor(()->create.done);
   if(create.reason!=null)throw new AssertionError(create.reason);if(player.knightId<=0||player.pawnId!=player.knightId||player.bagId!=player.knightId||player.items.size()!=5||player.equipment.length!=16||player.homeTown==null)throw new AssertionError("Selected player invalid");
   System.out.println("PASS: "+(fresh?"creation":"reconnect")+" selects durable identity and five equipped starter items");
   Zone move=new Zone();Class<?> zoneListener=Class.forName("com.threerings.whirled.zone.a.g$a"),zoneService=Class.forName("com.threerings.whirled.zone.a.g");Object listener=java.lang.reflect.Proxy.newProxyInstance(zoneListener.getClassLoader(),new Class[]{zoneListener},(proxy,method,values)->{if(method.getName().equals("aT"))throw new AssertionError(values[0]);if(method.getName().equals("a")&&values.length==4&&values[3] instanceof SceneModel m){move.oid=(Integer)values[0];move.model=m;move.done=true;}return null;});Object zs=client.bU(zoneService);zoneService.getMethod("a",int.class,int.class,int.class,zoneListener).invoke(zs,1,1,0,listener);waitFor(()->move.done);if(move.model.sceneId!=1||!(move.model instanceof TudeySceneModel)||move.oid!=10)throw new AssertionError("Town Square invalid");
   player.a(new com.threerings.tudey.dobj.a(){public void c(SceneDeltaEvent e){received(e);}});
   client.xb().a(move.oid,new com.threerings.presents.dobj.t<TownSceneObject>(){public void a(TownSceneObject obj){scene=obj;}public void a(int id,ObjectAccessException error){throw new AssertionError(error);}});waitFor(()->scene!=null);
   if(deltas!=0)throw new AssertionError("Scene deltas sent before enteredPlace");
   scene.tudeySceneService.adB();waitFor(()->deltas>0);if(last.aej()==null||last.aej().length!=1||last.aej()[0].vw()!=player.pawnId)throw new AssertionError("Actor not received");
   if(last.aej()[0] instanceof com.threerings.tudey.data.actor.Mobile mobile && mobile.aeb()<=0)throw new AssertionError("Movement prediction limit is zero");
   if(!((com.threerings.projectx.data.actor.Knight)last.aej()[0]).Np())throw new AssertionError("Spawn activity blocks controls");
   if(player.loadouts.isEmpty())throw new AssertionError("Current loadout missing");
   System.out.println("PASS: zone callback loads original Town Square, scene subscription and controlled actor");
   // Loading can postpone input/acknowledgement for longer than the old 40-snapshot history.
   waitFor(()->deltas>=45);
   if(repeatedAdds!=0||configReplacements!=0)throw new AssertionError("Idle entry repeatedly replaces actor/model: adds="+repeatedAdds+", configs="+configReplacements);
   System.out.println("PASS: delayed entry acknowledgements retain the actor and its model configuration");
   int count=deltas;scene.a(last.QY(),last.QY(),new InputFrame[]{new ProjectXInputFrame(last.QY()+50,1.5f,0,1,0)});waitFor(()->deltas>count+3);if(last.aek()==null)throw new AssertionError("Movement delta missing");
   var actor=controlled(player);
   if(actor.tz().x<=0 || Math.abs(actor.tA()-1.5f)>.01)throw new AssertionError("Movement/facing ignored: "+actor);System.out.println("PASS: movement and independent mouse-facing updates");
   var beforeEquipment=actor.Mv();
   long helm=player.equipment[0];var itemService=client.bU(com.threerings.projectx.item.client.L.class);Reply equip=new Reply();itemService.a(helm,ItemCodes.Location.ARSENAL,0,equip);waitFor(()->equip.done);if(equip.reason!=null||player.equipment[0]!=0)throw new AssertionError("Unequip failed: "+equip.reason);
   waitFor(()->controlled(player).Mv()!=beforeEquipment);
   var withoutHelm=controlled(player).Mv();
   Reply re=new Reply();itemService.a(helm,ItemCodes.Location.EQUIPMENT,0,re);waitFor(()->re.done);if(re.reason!=null||player.equipment[0]!=helm)throw new AssertionError("Equip failed: "+re.reason);System.out.println("PASS: real item service unequips and re-equips owned helm");
   waitFor(()->controlled(player).Mv()!=withoutHelm);
   var withHelm=controlled(player).Mv();int afterEquip=deltas;waitFor(()->deltas>=afterEquip+10);
   if(controlled(player).Mv()!=withHelm)throw new AssertionError("Equipped model replaced on unchanged ticks");
   System.out.println("PASS: equipment changes update the actor model once and retain it afterward");
   if(fresh)try(var accounts=new org.tortodrone.account.AccountStore(Path.of(args[1]).getParent().getParent())){accounts.currency("LanPlayer","Town-Knight",org.tortodrone.character.Currency.CROWNS,100,-1,-1);accounts.currency("LanPlayer","Town-Knight",org.tortodrone.character.Currency.ENERGY,25,-1,-1);}
   waitFor(()->player.crowns==100&&player.energy==25);System.out.println("PASS: local administration changes live crowns and energy counters");
   multiplayer(args,queue,fresh,player);
  }finally{client.Q(false);}
 }
 static void multiplayer(String[] args,at queue,boolean fresh,PlayerObject primary)throws Exception {
  var peer=new Client(new ProjectXCredentials(new Name("PeerPlayer"),"lan-test-password"),queue);
  int peerId=0;
  try {
   peer.b("127.0.0.1",new int[]{Integer.parseInt(args[0])},new int[0]);peer.dr(Files.readString(Path.of(args[1])).trim());peer.ds("world-fixture");peer.dt("projectx");peer.xg();waitFor(peer::isLoggedOn);
   var player=(PlayerObject)peer.xc();
   Class<?> receiver=Class.forName("com.threerings.whirled.zone.a.f");
   Object receiverInstance=java.lang.reflect.Proxy.newProxyInstance(receiver.getClassLoader(),new Class[]{receiver},(proxy,method,values)->null);
   var decoder=Class.forName("com.threerings.whirled.zone.a.a").getConstructor(receiver).newInstance(receiverInstance);peer.xd().a((com.threerings.presents.client.w)decoder);
   Reply validation=new Reply();((aU)peer.bU(aU.class)).f(new Name("Peer-Knight"),validation);waitFor(()->validation.done);
   Reply select=new Reply();
   if(fresh)((aU)peer.bU(aU.class)).a(null,new Name("Peer-Knight"),"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,ProjectXCodes.KnightHeight.BASE,ProjectXCodes.EyeType.DEFAULT,null,List.of(),null,select);
   else ((aU)peer.bU(aU.class)).b(new Name("Peer-Knight"),null,select);
   waitFor(()->select.done);if(select.reason!=null)throw new AssertionError(select.reason);
   peerId=player.pawnId;final int id=peerId;
   if(id==primary.pawnId)throw new AssertionError("Accounts share a world identity");
   waitFor(()->snapshots.get(last.QY()).containsKey(id));
   if(scene.occupantInfo.size()!=2)throw new AssertionError("Peer metadata did not precede its actor");
   // Load and subscribe through the peer's real zone marshaller.
   Zone move=new Zone();Class<?> zoneListener=Class.forName("com.threerings.whirled.zone.a.g$a"),zoneService=Class.forName("com.threerings.whirled.zone.a.g");
   Object listener=java.lang.reflect.Proxy.newProxyInstance(zoneListener.getClassLoader(),new Class[]{zoneListener},(proxy,method,values)->{if(method.getName().equals("aT"))throw new AssertionError(values[0]);if(method.getName().equals("a")&&values.length==4){move.oid=(Integer)values[0];move.done=true;}return null;});
   zoneService.getMethod("a",int.class,int.class,int.class,zoneListener).invoke(peer.bU(zoneService),1,1,0,listener);waitFor(()->move.done);
   TownSceneObject[] peerScene={null};SceneDeltaEvent[] peerDelta={null};
   player.a(new com.threerings.tudey.dobj.a(){public void c(SceneDeltaEvent event){peerDelta[0]=event;}});
   peer.xb().a(move.oid,new com.threerings.presents.dobj.t<TownSceneObject>(){public void a(TownSceneObject obj){peerScene[0]=obj;}public void a(int oid,ObjectAccessException error){throw new AssertionError(error);}});
   waitFor(()->peerScene[0]!=null);peerScene[0].tudeySceneService.adB();waitFor(()->peerDelta[0]!=null);
   if(peerDelta[0].aej()==null||peerDelta[0].aej().length!=2||peerScene[0].occupantInfo.size()!=2)throw new AssertionError("Peer did not receive both town knights");
   peerScene[0].a(peerDelta[0].QY(),peerDelta[0].QY(),new InputFrame[]{new ProjectXInputFrame(peerDelta[0].QY()+50,.7f,0,1,0)});
   waitFor(()->snapshots.get(last.QY()).get(id).tz().x>0);
   var actor=snapshots.get(last.QY()).get(id);
   if(Math.abs(actor.tA()-.7f)>.01||!actor.bR(2))throw new AssertionError("Peer movement/facing/animation flags not synchronized");
   System.out.println("PASS: two normal accounts receive peer actors, movement, facing and movement-animation flags");
  } finally {peer.Q(false);}
  final int departed=peerId;
  if(departed!=0) {
   waitFor(()->!snapshots.get(last.QY()).containsKey(departed));
   if(scene.occupantInfo.size()!=1)throw new AssertionError("Departed peer metadata retained");
   System.out.println("PASS: disconnect removes peer actor and occupant metadata");
  }
 }
}
