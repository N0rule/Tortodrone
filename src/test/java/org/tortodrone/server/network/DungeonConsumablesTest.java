package org.tortodrone.server.network;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import org.tortodrone.resources.Json;
import org.tortodrone.storage.Database;
import org.tortodrone.world.*;
import static org.tortodrone.support.TestWire.check;

/** Capacity refusal keeps a shared pickup claimable, without allocating an extra hidden stack. */
public final class DungeonConsumablesTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-consumables-");
        try {
            var db=new Database(home,true);int account=db.create("Collector",new Passwords.Protection(new byte[16],new byte[32]),false);
            var characters=new CharacterStore(db,account,"Collector");var knight=characters.create(new Knight(0,"Collector-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",0,2,0,0));
            var source=SceneRegistry.system().catalog();var base=source.entry();var training=source.scene(294).content();
            var content=new SceneContent(List.of(),training.scales(),Map.of(),Map.of("player.base-health",200f),training.items(),Map.of(),training.statuses());
            var fixtureMap=new SceneCatalog.MapScene(base.id(),base.zone(),base.zoneName(),base.name(),base.image(),base.kind(),base.version(),base.spawnX(),base.spawnY(),base.spawnRotation(),1,base.collision(),base.layout(),ScenePortals.EMPTY,content);
            var registry=new SceneRegistry(SceneCatalog.of(new TreeMap<>(Map.of(base.id(),fixtureMap)),base.id()));
            var frames=new java.util.concurrent.LinkedBlockingQueue<byte[]>();
            var socket=new Socket(){public OutputStream getOutputStream(){return new OutputStream(){
                public void write(int value){throw new AssertionError("Expected complete frames");}
                public void write(byte[] frame){frames.add(frame);}
            };}};
            try(var output=new SessionOutput(socket);var session=new WorldSession(socket,characters,output,new SessionCodec(),1201,1,m->{},registry)){
                synchronized(session){
                    session.select(knight.name(),Map.of());var member=(SceneSimulation.Membership)field(session,"membership");var simulation=registry.entry().simulation();
                    synchronized(simulation){
                        var entered=SceneSimulation.Membership.class.getDeclaredField("entered");entered.setAccessible(true);entered.setBoolean(member,true);
                        var player=simulation.snapshot().players().getFirst();var entities=(SceneEntities)field(simulation,"entities");
                        @SuppressWarnings("unchecked") var items=(Map<Long,WorldProfile.Item>)field(session,"temporaryItems");
                        long[] slots=(long[])field(session,"quickbar");
                        for(int i=0;i<3;i++)pickup(session,entities,player,"Pickup/Vial/Fire Vial");
                        check(items.size()==1&&items.values().iterator().next().count()==3,"three uses in one carried stack");
                        int refused=pickup(session,entities,player,"Pickup/Vial/Fire Vial");
                        check(items.size()==1&&items.values().iterator().next().count()==3&&entities.actor(refused)!=null,"fourth use remains in the scene");
                        check(entities.pendingPickups(player.id()).isEmpty(),"full stack cannot reserve the shared pickup");
                        int distant=spawn(entities,player,"Pickup/Vial/Fire Vial",1);
                        entities.advance(SceneClock.now()+50,List.of(player));entities.advance(SceneClock.now()+100,List.of(player));
                        check(entities.actor(distant).x()==player.x()+1,"full stack cannot attract a nearby vial");
                        long fireId=items.keySet().iterator().next();var fireStack=items.get(fireId);
                        items.put(fireId,new WorldProfile.Item(fireId,fireStack.slot(),fireStack.config(),null,0,null,false,false,false,2,false));
                        var publish=WorldSession.class.getDeclaredMethod("publishQuickbar");publish.setAccessible(true);publish.invoke(session);
                        spawn(entities,player,fireStack.config(),0);spawn(entities,player,fireStack.config(),0);
                        entities.advance(SceneClock.now(),List.of(player));
                        check(entities.pendingPickups(player.id()).size()==1,"same-tick contenders reserve only the one remaining stack use");collect(session);
                        check(items.get(fireId).count()==3,"reserved pickup fills exactly one stack use");
                        for(String name:List.of("Freeze","Shock","Poison"))pickup(session,entities,player,"Pickup/Vial/"+name+" Vial");
                        check(items.size()==4&&Arrays.stream(slots).allMatch(id->id<0)&&Arrays.stream(slots).distinct().count()==4,"four carried types fill four distinct quickbar slots");
                        int fifth=pickup(session,entities,player,"Pickup/Vial/Sleep Vial");
                        check(items.size()==4&&entities.actor(fifth)!=null,"fifth type remains available to another knight");
                        var peer=new ActorState(333,1333,"Peer",0,player.created(),player.x(),player.y(),0,0,0,player.config());
                        try(var peerMember=simulation.join(peer,s->{})){
                        entered.setBoolean(peerMember,true);entities.advance(SceneClock.now(),List.of(peer));var claims=entities.takePickups(peer.id());
                        check(claims.stream().anyMatch(p->p.entityId()==fifth),"another knight can claim the refused pickup");
                        for(var claim:claims)entities.completePickup(peer.id(),claim.entityId(),true);
                        check(entities.actor(fifth)==null,"successful peer claim removes it for both observers");
                        }
                        long id=slots[0];items.remove(id);publish.invoke(session);
                        check(slots[0]==0,"consumed or discarded stack frees its quickbar slot");
                        pickup(session,entities,player,"Pickup/Vial/Sleep Vial");check(items.size()==4&&slots[0]!=0,"new type reuses the freed slot");
                        // At the four-type limit an existing non-full stack remains eligible.
                        long sleep=slots[0];var stack=items.get(sleep);
                        items.put(sleep,new WorldProfile.Item(sleep,stack.slot(),stack.config(),null,0,null,false,false,false,2,false));publish.invoke(session);
                        int extra=pickup(session,entities,player,stack.config());check(items.get(sleep).count()==3&&entities.actor(extra)==null,"type capacity does not prevent filling an existing stack");
                        int retained=pickup(session,entities,player,stack.config());
                        items.put(sleep,stack);publish.invoke(session);
                        entities.advance(SceneClock.now(),List.of(player));collect(session);
                        check(entities.actor(retained)==null&&items.get(sleep).count()==2,"capacity becoming available re-enables a pickup without walking away");
                        int capsule=pickup(session,entities,player,"Pickup/Capsule/Health Capsule");
                        check(entities.actor(capsule)!=null,"capsules share the four carried-type limit with vials");
                        items.remove(sleep);publish.invoke(session);entities.advance(SceneClock.now(),List.of(player));collect(session);
                        long capsuleId=items.values().stream().filter(i->i.config().equals("Pickup/Capsule/Health Capsule")).findFirst().orElseThrow().id();
                        drain(output,frames);
                        var listener=new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller",(short)19);
                        for(String state:List.of("subscribed","entered")){var f=WorldSession.class.getDeclaredField(state);f.setAccessible(true);f.setBoolean(session,true);}
                        check(session.invocation(new SessionDecoder.Invocation(3,9,14,Arrays.asList(capsuleId,null,listener))),"full-health capsule reaches the native Use handler");
                        var replies=drain(output,frames);
                        check(replies.size()==1&&java.nio.ByteBuffer.wrap(replies.getFirst(),replies.getFirst().length-4,4).getInt()==((19<<16)|256),"full-health use sends only successful confirmation, with no chat or failure callback");
                        check(items.get(capsuleId).count()==1&&member.takeUsedItems().isEmpty(),"full-health native Use never decrements or queues the capsule");
                        check(characters.worldProfile(knight.name(),1).items().stream().noneMatch(item->item.id()<0),"temporary consumables never enter SQLite");
                    }
                }
            }
            System.out.println("PASS: three-use stacks, four carried types/slots, capacity refusal, shared peer claim, slot reuse and temporary ownership");
        }finally{org.tortodrone.support.ContentFixture.delete(home);}
    }
    private static Object field(Object value,String name)throws Exception{var f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}
    private static int pickup(WorldSession session,SceneEntities entities,ActorState player,String item)throws Exception{
        int id=spawn(entities,player,item,0);entities.advance(SceneClock.now(),List.of(player));collect(session);return id;
    }
    private static int spawn(SceneEntities entities,ActorState player,String item,float offset)throws Exception{
        var fields=Map.of("native-type","com.threerings.projectx.dungeon.data.actor.Pickup","config","Item/Pickups/Vial/Fire Vial","radius",".2","vacuum-expansion","2","vacuum-speed","10","pickup-item",item,"pickup-transient","true");
        // Capacity tests use an already activated pickup; spawn timing has its own regression.
        entities.spawnPickup(new StarterCatalog.Model("Pickup",Map.of("fields",Json.stringify(fields))),player.x()+offset,player.y(),0,SceneClock.now()-1000);
        return entities.actors().getLast().id();
    }
    private static void collect(WorldSession session)throws Exception{
        var collect=WorldSession.class.getDeclaredMethod("collectPickups");collect.setAccessible(true);collect.invoke(session);
    }
    private static List<byte[]> drain(SessionOutput output,java.util.concurrent.BlockingQueue<byte[]> frames)throws Exception{
        byte[] marker={0};output.send(()->marker);var result=new ArrayList<byte[]>();
        for(;;){var frame=frames.poll(3,java.util.concurrent.TimeUnit.SECONDS);check(frame!=null,"queued output drains");if(frame==marker)return result;result.add(frame);}
    }
}
