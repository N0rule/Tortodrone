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
import org.tortodrone.core.character.Knight;
import org.tortodrone.core.character.ItemCatalog;

/** Stock uplink/social services stay reachable from the client-local Ready Room. */
public final class ReadyRoomMailSessionTest {
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("tortodrone-ready-room-mail-");
        try{
            var content=ContentManager.load(Path.of("."));var scenes=content.createScenes();var db=new Database(root,false);
            try(var alice=ready(db,content.items(),scenes,"Alice");var bob=person(db,content.items(),scenes,"Bobby")){
                // The panel's opening request (null ids) against an empty mailbox.
                alice.world.auctionInvocation(new SessionDecoder.Invocation(3,SessionCodec.UPLINK_SERVICE,6,Arrays.asList((Object)null,result())));
                // Mail reaches the Room knight while staged; loading it is the reported regression.
                bob.world.friendInvocation(new SessionDecoder.Invocation(3,SessionCodec.SOCIAL_SERVICE,1,List.of("Alice","Hello","Town greeting",confirm())));
                check(alice.chars.worldProfile("Alice",1).town().mail().size()==1,"an in-scene request reaches the staged knight's mailbox");
                alice.world.auctionInvocation(new SessionDecoder.Invocation(3,SessionCodec.UPLINK_SERVICE,6,Arrays.asList((Object)null,result())));
                var loaded=alice.socket.await("Town greeting");
                check(!loaded.contains("Enter a scene first"),"Ready Room mail loads with a native result, not a scene failure");
                // Native method 1 from the Ready Room: the social panel's request dialog.
                alice.world.friendInvocation(new SessionDecoder.Invocation(3,SessionCodec.SOCIAL_SERVICE,1,List.of("Bobby","Ready subject","Ready Room body",confirm())));
                var bobProfile=bob.chars.worldProfile("Bobby",1);long foreign=bobProfile.town().mail().getFirst().id();
                // Ownership: another knight's mail is never readable, by id or by enumeration.
                alice.world.auctionInvocation(new SessionDecoder.Invocation(3,SessionCodec.UPLINK_SERVICE,6,List.of(new long[]{foreign},result())));
                alice.world.auctionInvocation(new SessionDecoder.Invocation(3,SessionCodec.UPLINK_SERVICE,6,Arrays.asList((Object)null,result())));
                var own=alice.socket.await("Town greeting");
                check(!own.contains("Ready subject"),"a listed mailbox never carries another knight's mail");
                alice.world.auctionInvocation(new SessionDecoder.Invocation(3,SessionCodec.UPLINK_SERVICE,2,List.of(foreign,confirm())));
                alice.socket.await("Unknown owned mail");
                // Native method 8 from the Ready Room after the friend request is accepted.
                bob.chars.auction(content.town()).mail(bobProfile.accountId(),bobProfile.knightId(),1,2,List.of(foreign));
                check(alice.chars.friends().list(1,1,1).size()==1&&bob.chars.friends().list(2,1,1).size()==1,"request and acceptance create mutual friendship");
                alice.world.friendInvocation(new SessionDecoder.Invocation(3,SessionCodec.SOCIAL_SERVICE,8,List.of("Bobby",confirm())));
                check(alice.chars.friends().list(1,1,1).isEmpty()&&bob.chars.friends().list(2,1,1).isEmpty(),"native method 8 removes both edges from the Ready Room");
                check(!alice.socket.written.contains("Enter a scene first"),"mail and social edits from the Ready Room never hit the scene gate");
                // The auction station stays bound to its town scene; the stock gate text remains.
                alice.world.auctionInvocation(new SessionDecoder.Invocation(3,SessionCodec.AUCTION_SERVICE,3,List.of(result())));
                alice.socket.await("Enter a scene first");
            }
            // Stock parity for a call with no selected knight: the native internal error key.
            db.create("Carol",new Passwords.Protection(new byte[16],new byte[32]),false);
            var strangerSocket=new RecordingSocket();
            try(var output=new SessionOutput(strangerSocket)){
                var characters=new CharacterStore(db,3,"Carol",content.items());
                try(var stranger=new WorldSession(strangerSocket,characters,output,new SessionCodec(content.items()),1399,1,m->{},scenes)){
                    stranger.auctionInvocation(new SessionDecoder.Invocation(3,SessionCodec.UPLINK_SERVICE,6,Arrays.asList((Object)null,result())));
                    strangerSocket.await("m.internal_error");
                }
            }
            System.out.println("PASS: Ready Room mail load/ownership, friend request/unfriend, auction scene gate and unselected guard");
        }finally{org.tortodrone.support.ContentFixture.delete(root);}
    }
    private static SessionDecoder.Listener confirm(){return new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller",(short)5);}
    private static SessionDecoder.Listener result(){return new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ResultMarshaller",(short)6);}
    private record Person(WorldSession world,RecordingSocket socket,SessionOutput output,CharacterStore chars)implements AutoCloseable{
        public void close(){world.close();output.close();}
    }
    private static Person ready(Database db,ItemCatalog items,SceneRegistry scenes,String name)throws Exception {
        int account=db.create(name,new Passwords.Protection(new byte[16],new byte[32]),false);
        var chars=new CharacterStore(db,account,name,items);
        chars.create(new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
        var socket=new RecordingSocket();var output=new SessionOutput(socket);
        var world=new WorldSession(socket,chars,output,new SessionCodec(items),1300+account,1,m->{},scenes);
        world.select(name,Map.of(SceneRegistry.ZONE_RECEIVER,(short)0),true);return new Person(world,socket,output,chars);
    }
    private static Person person(Database db,ItemCatalog items,SceneRegistry scenes,String name)throws Exception {
        int account=db.create(name,new Passwords.Protection(new byte[16],new byte[32]),false);
        var chars=new CharacterStore(db,account,name,items);
        chars.create(new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
        var socket=new RecordingSocket();var output=new SessionOutput(socket);
        var world=new WorldSession(socket,chars,output,new SessionCodec(items),1300+account,1,m->{},scenes);
        world.select(name,Map.of(SceneRegistry.ZONE_RECEIVER,(short)0));world.subscribe((short)1);world.entered();return new Person(world,socket,output,chars);
    }
    private static final class RecordingSocket extends Socket {
        final BlockingQueue<String> frames=new LinkedBlockingQueue<>();
        final List<String> written=new CopyOnWriteArrayList<>();
        public OutputStream getOutputStream(){return new OutputStream(){public void write(int b){throw new AssertionError();}public void write(byte[] bytes){var frame=new String(bytes,java.nio.charset.StandardCharsets.ISO_8859_1);frames.add(frame);written.add(frame);}};}
        String await(String text)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<end){String frame=frames.poll(100,TimeUnit.MILLISECONDS);if(frame!=null&&frame.contains(text))return frame;}throw new AssertionError("Missing response: "+text);}
    }
}
