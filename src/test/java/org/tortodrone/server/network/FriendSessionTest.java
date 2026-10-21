package org.tortodrone.server.network;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import org.tortodrone.resources.ContentManager;
import org.tortodrone.storage.*;
import org.tortodrone.world.*;
import static org.tortodrone.support.TestWire.check;

/** Actual native social IDs, authenticated friend travel and private/transition isolation. */
public final class FriendSessionTest {
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("tortodrone-friend-session-");
        try{
            var content=ContentManager.load(Path.of("."));var scenes=content.createScenes();var db=new Database(root,false);
            try(var alice=person(db,content.items(),scenes,"Alice");var bob=person(db,content.items(),scenes,"Bob");var other=person(db,content.items(),scenes,"Other")){
                alice.world.friendInvocation(rpc(2,"Bob"));alice.socket.await("m.internal_error");
                check(alice.chars.friends().list(1,1,1).isEmpty(),"join cannot create or remove a friendship");
                alice.world.friendInvocation(new SessionDecoder.Invocation(3,12,1,List.of("Bob","Hello","Friend request",confirm())));
                var mail=bob.chars.worldProfile("Bob",1).town().mail().getFirst();
                bob.chars.auction(content.town()).mail(2,1,1,2,List.of(mail.id()));
                check(alice.chars.friends().list(1,1,1).size()==1,"native request and acceptance create mutual friendship");
                var town=scenes.catalog().scenes().stream().filter(m->m.town()&&m.id()!=scenes.catalog().entry().id()).findFirst().orElseThrow();
                bob.world.invocation(new SessionDecoder.Invocation(3,1,7,List.of(destination(town),new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ResultMarshaller",(short)8))));
                alice.world.friendInvocation(rpc(2,"Bob"));alice.socket.await("changing scenes");
                move(bob,town);
                var presence=WorldSession.friendPresence(db.file());int bobWorld=bob.chars.worldProfile("Bob",1).worldId();
                check(presence.get(bobWorld).instance()>0&&presence.get(bobWorld).zone()==town.zone(),"Town presence includes joinable native instance and zone");
                alice.world.friendInvocation(rpc(2,"Bob"));
                var pending=WorldSession.class.getDeclaredField("portalMove");pending.setAccessible(true);
                check(pending.get(alice.world)!=null,"friend join starts native forced travel; queued="+alice.socket.frames);
                check(alice.chars.friends().list(1,1,1).size()==1,"method 2 joins without unfriending");move(alice,town);
                check(alice.world.ownsScene(scenes.destination(town.id(),bobWorld).oid()),"friend travel reaches the exact Town instance");
                alice.world.friendInvocation(rpc(2,"Bob"));
                check(alice.world.ownsScene(scenes.destination(town.id(),bobWorld).oid()),"same-scene join keeps the scene active");
                // Native method 3: PlayerObject.ML() is the authorization; friendship is not.
                move(alice,scenes.catalog().entry());
                String secret="Bob,"+String.format("%016x",bob.world.externalSecret());
                alice.world.friendInvocation(new SessionDecoder.Invocation(3,12,3,List.of("Bob,"+String.format("%016x",bob.world.externalSecret()^1),false,confirm())));
                String refusedSecret=alice.socket.await("%social:m.friend_unjoinable");
                check(!refusedSecret.contains("~%social:"),"qualified social failures keep their bundle prefix");
                alice.world.friendInvocation(new SessionDecoder.Invocation(3,12,3,List.of("Bob,not-hex",false,confirm())));alice.socket.await("m.internal_error");
                alice.world.friendInvocation(new SessionDecoder.Invocation(3,12,3,List.of("Alice,"+String.format("%016x",alice.world.externalSecret()),false,confirm())));alice.socket.await("m.friend_self");
                alice.world.friendInvocation(new SessionDecoder.Invocation(3,12,3,List.of(secret,false,confirm())));
                check(pending.get(alice.world)!=null,"secret join starts native forced travel");
                move(alice,town);
                check(alice.world.ownsScene(scenes.destination(town.id(),bobWorld).oid()),"secret join reaches the exact Town instance without a friendship edge");
                // Native method 4: only the toggle is published; matching itself is not simulated.
                var difficulty=new SessionDecoder.EnumValue("com.threerings.projectx.dungeon.data.DungeonCodes$Difficulty",1);
                alice.world.friendInvocation(new SessionDecoder.Invocation(3,12,4,List.of(true,difficulty,false,confirm())));alice.socket.await("lfg");
                check(alice.world.lookingForGroup(),"party-finder toggle publishes its native lfg attribute");
                alice.world.friendInvocation(new SessionDecoder.Invocation(3,12,4,List.of(false,difficulty,true,confirm())));alice.socket.await("lfg");
                check(!alice.world.lookingForGroup(),"party-finder toggle clears the native lfg attribute");
                other.world.friendInvocation(rpc(2,"Bob"));other.socket.await("m.internal_error");
                bob.world.invocation(new SessionDecoder.Invocation(3,1,7,List.of("lab",new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ResultMarshaller",(short)8))));move(bob,scenes.catalog().scene(30893));
                alice.world.friendInvocation(rpc(2,"Bob"));alice.socket.await("friend_unjoinable");
                check(WorldSession.friendPresence(db.file()).get(bobWorld).instance()==0,"private interior is published as unjoinable");
                bob.world.close();alice.world.friendInvocation(rpc(2,"Bob"));alice.socket.await("friend_unjoinable");
                alice.world.friendInvocation(rpc(6,"Bob"));
                check(alice.chars.friends().list(1,1,1).isEmpty()&&bob.chars.friends().list(2,1,1).isEmpty(),"native method 6 removes both friendship edges");
                db.password("Other",new Passwords.Protection(new byte[16],new byte[32]),3);
                other.world.friendInvocation(rpc(6,"Alice"));other.socket.await("reconnect");
            }
            System.out.println("PASS: native friend request/join/secret join/looking-for-group/remove, Town instance presence and travel, same-scene join, stranger/offline/private/transition/auth guards");
        }finally{org.tortodrone.support.ContentFixture.delete(root);}
    }
    private static String destination(SceneCatalog.MapScene map){return map.name().substring("%town:m.".length());}
    private static SessionDecoder.Listener confirm(){return new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller",(short)5);}
    private static SessionDecoder.Invocation rpc(int method,String name){return new SessionDecoder.Invocation(3,12,method,List.of(name,confirm()));}
    private static Person person(Database db,ItemCatalog items,SceneRegistry scenes,String name)throws Exception {
        int account=db.create(name,new Passwords.Protection(new byte[16],new byte[32]),false);var chars=new CharacterStore(db,account,name,items);
        chars.create(new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
        var socket=new RecordingSocket();var output=new SessionOutput(socket);var world=new WorldSession(socket,chars,output,new SessionCodec(items),1200+account,1,m->{},scenes);
        world.select(name,Map.of(SceneRegistry.ZONE_RECEIVER,(short)0));world.subscribe((short)1);world.entered();return new Person(world,socket,output,chars);
    }
    private static void move(Person person,SceneCatalog.MapScene map)throws Exception {
        person.world.invocation(new SessionDecoder.Invocation(3,5,1,List.of(map.zone(),map.id(),map.version(),new SessionDecoder.Listener("com.threerings.whirled.zone.data.ZoneMarshaller$ZoneMoveMarshaller",(short)9))));
        if(!map.town())person.world.subscribeInteriorParty((short)11);
        person.world.subscribe((short)10);person.world.entered();
    }
    private record Person(WorldSession world,RecordingSocket socket,SessionOutput output,CharacterStore chars)implements AutoCloseable{
        public void close(){world.close();output.close();}
    }
    private static final class RecordingSocket extends Socket {
        final BlockingQueue<String> frames=new LinkedBlockingQueue<>();
        public OutputStream getOutputStream(){return new OutputStream(){public void write(int b){throw new AssertionError();}public void write(byte[] bytes){frames.add(new String(bytes,java.nio.charset.StandardCharsets.ISO_8859_1));}};}
        String await(String text)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<end){String frame=frames.poll(100,TimeUnit.MILLISECONDS);if(frame!=null&&frame.contains(text))return frame;}throw new AssertionError("Missing response: "+text);}
    }
}
