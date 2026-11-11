package org.tortodrone.server.network;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.tortodrone.account.AccountStore;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import org.tortodrone.storage.Database;
import org.tortodrone.support.TestWire;
import org.tortodrone.world.*;
import static org.tortodrone.support.TestWire.check;

public final class ReadyRoomSessionTest {
    private static final Map<String,Short> RECEIVERS=Map.of(SceneRegistry.ZONE_RECEIVER,(short)0);
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-ready-room-");
        try(var accounts=new AccountStore(home,false,ItemCatalog.starter())){
            accounts.create("RoomUser","room-password".toCharArray());
            accounts.create("RoomPeer","peer-password".toCharArray());
            var characters=new CharacterStore(new Database(home,false),1,"RoomUser");
            var peerCharacters=new CharacterStore(new Database(home,false),2,"RoomPeer");
            var peerKnight=peerCharacters.create(new Knight(0,"Peer-Knight","Gear/Helm/Spiral Sallet (Blue)",
                "Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            var knight=characters.create(new Knight(0,"Room-Knight","Gear/Helm/Spiral Sallet (Blue)",
                "Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            var registry=SceneRegistry.system();var entry=registry.entry();int initial=entry.simulation().playerCount();
            partyDelivery(characters,knight,peerCharacters,peerKnight,registry,initial);
            socketDelivery(characters,knight,peerCharacters,peerKnight,registry,initial);
            for(int reconnect=0;reconnect<2;reconnect++){
                var socket=new RecordingSocket();
                try(var output=new SessionOutput(socket);var world=new WorldSession(socket,characters,output,new SessionCodec(),1900,1,m->{},registry)){
                    refuse(()->world.select("Not-Owned",RECEIVERS,true));
                    world.select(knight.name(),RECEIVERS,true);
                    check(world.selected()&&WorldSession.active(1,knight.id()),"selection retains authenticated ownership");
                    check(entry.simulation().playerCount()==initial,"selection creates no world pawn");
                    check(!world.ownsScene(entry.oid())&&!world.ownsScene(0),"Ready Room owns no server place");
                    var competingSocket=new RecordingSocket();
                    try(var competingOutput=new SessionOutput(competingSocket);var competing=new WorldSession(competingSocket,characters,competingOutput,new SessionCodec(),1901,1,m->{},registry)){
                        refuse(()->competing.select(knight.name(),RECEIVERS,true));
                        check(!competing.selected(),"duplicate selection cannot acquire the Ready Room owner");
                    }
                    check(WorldSession.active(1,knight.id()),"closing refused selection preserves owner");
                    var peerSocket=new RecordingSocket();
                    try(var peerOutput=new SessionOutput(peerSocket);var peer=new WorldSession(peerSocket,peerCharacters,peerOutput,new SessionCodec(),1904,1,m->{},registry)){
                        peer.select(peerKnight.name(),RECEIVERS,true);move(peer,ReadyRoom.ZONE,ReadyRoom.SCENE);
                        check(WorldSession.active(1,knight.id())&&WorldSession.active(2,peerKnight.id()),"simultaneous knights own independent Ready Room sessions");
                        check(entry.simulation().playerCount()==initial,"simultaneous Ready Room players create no shared scene pawns");
                    }
                    refuse(()->world.subscribe((short)2));refuse(()->world.subscribeInteriorParty((short)3));
                    move(world,ReadyRoom.ZONE,ReadyRoom.SCENE);
                    move(world,ReadyRoom.ZONE,5);move(world,ReadyRoom.ZONE+1,ReadyRoom.SCENE);
                    move(world,entry.map().zone(),entry.map().id());
                    check(entry.simulation().playerCount()==initial,"invalid routes and unsolicited world entry do not attach");
                    refuse(()->world.invocation(new SessionDecoder.Invocation(3,1,11,List.of(1))));
                    world.invocation(new SessionDecoder.Invocation(3,1,11,List.of()));
                    world.invocation(new SessionDecoder.Invocation(3,1,11,List.of()));
                    move(world,entry.map().zone(),entry.map().id());
                    check(world.ownsScene(entry.oid())&&entry.simulation().playerCount()==initial+1,"return attaches exactly one owned pawn");
                    check(world.departingScene(entry.oid()),"pre-subscription readiness from the reserved old OID remains stale");
                    world.subscribe((short)4);
                    check(!world.departingScene(entry.oid()),"ClientSession must route the newly subscribed town's enteredPlace even when the Ready Room reserved the same OID");
                    world.entered();
                    move(world,ReadyRoom.ZONE,ReadyRoom.SCENE);
                    check(world.departingScene(entry.oid()),"readiness for the departed world place is ignored while in the client-local room");
                    check(entry.simulation().playerCount()==initial&&!world.ownsScene(entry.oid()),"Ready Room travel removes the pawn and scene authorization");
                    refuse(()->world.entered());refuse(()->world.subscribe((short)5));
                }
                check(!WorldSession.active(1,knight.id())&&entry.simulation().playerCount()==initial,"disconnect releases owner and scene state");
            }
            var otherTown=registry.catalog().scenes().stream().filter(map->map.town()&&map.id()!=entry.map().id()).findFirst().orElseThrow();
            // A successful enteredPlace, not a client-selected scene number, records the return.
            var travelSocket=new RecordingSocket();
            try(var output=new SessionOutput(travelSocket);var world=new WorldSession(travelSocket,characters,output,new SessionCodec(),1902,1,m->{},registry)){
                world.select(knight.name(),RECEIVERS);
                move(world,otherTown.zone(),otherTown.id());world.subscribe((short)7);world.entered();
                check(characters.lastTownScene(knight.id(),1)==otherTown.id(),"acknowledged town arrival saves destination");
            }
            var reconnectSocket=new RecordingSocket();
            try(var output=new SessionOutput(reconnectSocket);var world=new WorldSession(reconnectSocket,characters,output,new SessionCodec(),1903,1,m->{},registry)){
                world.select(knight.name(),RECEIVERS,true);
                world.invocation(new SessionDecoder.Invocation(3,1,11,List.of()));
                move(world,otherTown.zone(),otherTown.id());
                world.subscribe((short)8);
                int place=registry.destination(otherTown.id(),characters.worldProfile(knight.name(),1).worldId()).oid();
                check(!world.departingScene(place),"saved non-entry town readiness also reaches ClientSession instead of being discarded");
                world.entered();
                check(characters.lastTownScene(knight.id(),1)==otherTown.id(),"reconnect returns to the saved non-entry town");
            }
        }finally{try(var files=Files.walk(home)){for(var file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
        System.out.println("PASS: Ready Room selection, authorization, invalid routes, return, scene cleanup, reconnect, bounded party delivery and live socket invitation without scene entry");
    }
    private static void move(WorldSession world,int zone,int scene)throws IOException{
        world.invocation(new SessionDecoder.Invocation(3,5,1,List.of(zone,scene,1,
            new SessionDecoder.Listener("com.threerings.whirled.zone.data.ZoneMarshaller$ZoneMoveMarshaller",(short)6))));
    }
    private static void partyDelivery(CharacterStore characters,Knight knight,CharacterStore peerCharacters,Knight peerKnight,SceneRegistry registry,int initial)throws Exception {
        var socket=new RecordingSocket();var peerSocket=new RecordingSocket();
        var messages=new CopyOnWriteArrayList<String>();
        try(var output=new SessionOutput(socket);var peerOutput=new SessionOutput(peerSocket);
                var world=new WorldSession(socket,characters,output,new SessionCodec(),1910,1,messages::add,registry);
                var peer=new WorldSession(peerSocket,peerCharacters,peerOutput,new SessionCodec(),1912,1,m->{},registry)) {
            world.select(knight.name(),RECEIVERS,true);peer.select(peerKnight.name(),RECEIVERS,true);
            var sender=peer.partyMemberSnapshot();
            // Refresh events coalesce; invitations do not. Repeated invitations to the same
            // pending party remain valid and used to overflow an undrained Ready Room queue.
            for(int i=0;i<70;i++){
                int expected=i+1;
                registry.parties().invite(sender,knight.name());
                await(()->socket.notifications>=expected,"invitation delivered before entering any server scene");
            }
            Thread delivery=delivery(world);
            check(delivery!=null&&delivery.isAlive()&&registry.entry().simulation().playerCount()==initial,
                "selected Ready Room session drains party events without a server pawn");
            world.invocation(new SessionDecoder.Invocation(3,1,11,List.of()));
            move(world,registry.entry().map().zone(),registry.entry().map().id());
            world.subscribe((short)20);world.entered();
            registry.parties().invite(sender,knight.name());
            await(()->socket.notifications>=71||socket.isClosed(),"party delivery continues after Return to Game");
            check(!socket.isClosed()&&WorldSession.active(characters.accountId(),knight.id()),
                "Ready Room invitations must not kick the player on Return to Game: "+messages);
            move(world,ReadyRoom.ZONE,ReadyRoom.SCENE);
            check(delivery(world)==delivery,"world/Ready Room travel reuses the session delivery worker");
            check(registry.entry().simulation().playerCount()==initial,"party delivery never gives Ready Room a pawn");
            int before=socket.notifications;
            registry.parties().invite(sender,knight.name());
            await(()->socket.notifications>before,"invitations are delivered while in Ready Room after world travel");
            world.close();delivery.join(3000);
            check(!delivery.isAlive(),"disconnect stops the Ready Room event worker");
            refuse(()->registry.parties().invite(sender,knight.name()));
        }
        check(!WorldSession.active(characters.accountId(),knight.id()),"party delivery cleanup releases Ready Room ownership");
    }
    private static Thread delivery(WorldSession world)throws Exception {
        synchronized(world){var field=WorldSession.class.getDeclaredField("delivery");field.setAccessible(true);return (Thread)field.get(world);}
    }
    /** Exercise the actual connection loop/decoder and writer, not only WorldSession methods. */
    private static void socketDelivery(CharacterStore characters,Knight knight,CharacterStore peerCharacters,Knight peerKnight,SceneRegistry registry,int initial)throws Exception {
        var selected=new CountDownLatch(1);
        SessionLog log=new SessionLog(){
            @Override public void debug(String message) { }
            @Override public void selected(int roster,int world,String name){selected.countDown();}
        };
        try(var listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress());var executor=Executors.newSingleThreadExecutor();
                var peerSocket=new RecordingSocket();var peerOutput=new SessionOutput(peerSocket);
                var peer=new WorldSession(peerSocket,peerCharacters,peerOutput,new SessionCodec(),1914,1,m->{},registry)) {
            peer.select(peerKnight.name(),RECEIVERS,true);
            Future<?> server=executor.submit(()->{
                try(var socket=listener.accept()){
                    ClientSession.run(socket,new HandshakeCodec(),10,"127.0.0.1",listener.getLocalPort(),log,characters,1);
                }catch(Exception failure){throw new CompletionException(failure);}
            });
            try(var socket=new Socket(listener.getInetAddress(),listener.getLocalPort())) {
                socket.setSoTimeout(3000);var wire=new TestWire();var writer=new TestWire.Writer();
                int clientOid=wire.bootstrap(wire.frame(socket.getInputStream()),listener.getLocalPort());
                writer.send(socket.getOutputStream(),out->{
                    writer.message(out,"ForwardEventRequest",1);
                    writer.type(out,TestWire.DOB+"EntryAddedEvent");out.writeInt(clientOid);
                    out.writeBoolean(true);out.writeUTF("receivers");
                    writer.type(out,"com.threerings.presents.client.InvocationReceiver$Registration");
                    out.writeBoolean(true);out.writeUTF(SceneRegistry.ZONE_RECEIVER);out.writeShort(0);
                });
                writer.send(socket.getOutputStream(),out->{
                    writer.message(out,"ForwardEventRequest",2);
                    writer.type(out,TestWire.DOB+"InvocationRequestEvent");out.writeInt(3);out.writeInt(1);out.writeByte(4);
                    out.writeBoolean(true);out.writeInt(3);
                    writer.type(out,"java.lang.String");out.writeUTF(knight.name());out.writeShort(0);
                    writer.type(out,TestWire.INV+"ConfirmMarshaller");out.writeShort(2);
                });
                check(selected.await(3,TimeUnit.SECONDS),"live connection selects the Ready Room knight");
                registry.parties().invite(peer.partyMemberSnapshot(),knight.name());
                boolean invitation=false;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(!invitation&&System.nanoTime()<deadline){
                    byte[] frame=HandshakeCodec.readFrame(socket.getInputStream());
                    check(frame!=null,"Ready Room connection remains open until invitation delivery");
                    invitation=new String(frame,java.nio.charset.StandardCharsets.ISO_8859_1).contains("Notification$PartyInvite");
                }
                check(invitation&&registry.entry().simulation().playerCount()==initial,"live socket receives invitation without subscribing to any server place");
                writer.send(socket.getOutputStream(),out->writer.message(out,"LogoffRequest",3));
                server.get(3,TimeUnit.SECONDS);
                check(!WorldSession.active(characters.accountId(),knight.id()),"live Ready Room logoff releases ownership");
            }
        }
    }
    private static void await(BooleanSupplier condition,String message)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(5);
        check(condition.getAsBoolean(),message);
    }
    private static void refuse(Operation operation)throws Exception{try{operation.run();throw new AssertionError("Expected refusal");}catch(IOException|CharacterStore.Refused expected){}}
    private interface Operation{void run()throws Exception;}
    private static final class RecordingSocket extends Socket{
        volatile int frames,notifications;
        private final OutputStream output=new OutputStream(){
            @Override public void write(int value) { }
            @Override public void write(byte[] bytes){
                frames++;
                if(new String(bytes,java.nio.charset.StandardCharsets.ISO_8859_1).contains("notifications"))notifications++;
            }
        };
        @Override public OutputStream getOutputStream(){return output;}
    }
}
