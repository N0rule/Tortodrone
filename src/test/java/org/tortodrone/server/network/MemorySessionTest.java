package org.tortodrone.server.network;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import org.tortodrone.storage.Database;
import org.tortodrone.world.*;
import static org.tortodrone.support.TestWire.check;

/** Synthetic scene sensor through production membership, session delivery, SQLite and wire output. */
public final class MemorySessionTest {
    private static final String MEMORY="talkto_rhendon_icon";
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-memory-session-");
        try {
            var db=new Database(home,true);var protection=new Passwords.Protection(new byte[16],new byte[32]);
            int a=db.create("MemoryAlice",protection,false),b=db.create("MemoryBob",protection,false);
            var alice=new CharacterStore(db,a,"MemoryAlice");var bob=new CharacterStore(db,b,"MemoryBob");
            var one=alice.create(knight("Memory-One"));var two=bob.create(knight("Memory-Two"));
            var original=SceneRegistry.system().entry().map();
            var sensor=new SceneContent.Entry("2977",original.spawnX(),original.spawnY(),0,new SceneContent.Spec(Map.of("kind","SceneTrigger","config","Player Enter/Base","event","signal-start","watch-tag","area:2977","signal","intersection","scene-sensor","true","sensor-mask","4","radius",".5","action","{\"kind\":\"knight-memory\",\"name\":\"talkto_rhendon_icon\",\"remember\":true}")));
            var content=new SceneContent(List.of(sensor),original.content().scales(),Map.of(),original.content().stats(),Map.of(),Map.of(),original.content().statuses());
            var map=new SceneCatalog.MapScene(900001,16777217,"%town:m.haven","Fixture",original.image(),"town",original.version(),original.spawnX(),original.spawnY(),0,1,original.collision(),original.layout(),ScenePortals.EMPTY,content);
            var maps=new TreeMap<Integer,SceneCatalog.MapScene>();maps.put(map.id(),map);var registry=new SceneRegistry(SceneCatalog.of(maps,map.id()));
            var receivers=Map.of(SceneRegistry.ZONE_RECEIVER,(short)0);var first=new RecordingSocket(alice,one.id());var second=new RecordingSocket(bob,two.id());
            var before=alice.worldProfile(one.name(),1);var peerBefore=bob.worldProfile(two.name(),1);
            try(var out1=new SessionOutput(first);var out2=new SessionOutput(second);
                    var world1=new WorldSession(first,alice,out1,new SessionCodec(),1201,1,m->{},registry);
                    var world2=new WorldSession(second,bob,out2,new SessionCodec(),1202,1,m->{},registry)){
                world1.select(one.name(),receivers);world2.select(two.name(),receivers);
                check(alice.knightMemories(one.id(),1).isEmpty()&&bob.knightMemories(two.id(),1).isEmpty(),"selection alone executes no memory actions");
                world1.subscribe((short)1);world1.entered();await(()->first.published);
                check(first.failure.get()==null&&alice.knightMemories(one.id(),1).equals(Set.of(MEMORY)),"authoritative memory commits before its native publication: "+first.failure.get());
                var after=alice.worldProfile(one.name(),1);
                check(after.revision()==before.revision()+1&&bob.worldProfile(two.name(),1).equals(peerBefore)&&bob.knightMemories(two.id(),1).isEmpty(),"only the entered Activator's revision/memory changes");
                int stamp=registry.entry().simulation().snapshot().timestamp();await(()->registry.entry().simulation().snapshot().timestamp()-stamp>100);
                check(alice.worldProfile(one.name(),1).revision()==after.revision(),"held intersection cannot repeat a memory write");
                String key="world."+peerBefore.worldId()+".memory."+MEMORY;
                db.write(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER reject_peer_memory BEFORE INSERT ON metadata WHEN NEW.key='"+key+"' BEGIN SELECT RAISE(ABORT,'injected scene memory failure'); END");}return null;});
                world2.subscribe((short)2);world2.entered();await(second::isClosed);
                check(!second.published&&second.failure.get()==null&&bob.knightMemories(two.id(),1).isEmpty()&&bob.worldProfile(two.name(),1).equals(peerBefore),"failed storage closes delivery without publishing or leaving a partial memory");
            }
            var reconnect=new RecordingSocket(alice,one.id());
            try(var out=new SessionOutput(reconnect);var world=new WorldSession(reconnect,alice,out,new SessionCodec(),1203,1,m->{},registry)){
                world.select(one.name(),receivers,true);await(()->reconnect.published);
                check(reconnect.failure.get()==null&&alice.knightMemories(one.id(),1).equals(Set.of(MEMORY)),"Ready Room activation publishes the durable native memory snapshot");
                check(registry.entry().simulation().playerCount()==0,"Ready Room restoration creates no scene pawn");
            }
            check(registry.entry().simulation().playerCount()==0,"all memory sessions release scene occupants");
        }finally{try(var paths=Files.walk(home)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
        System.out.println("PASS: scene sensor -> owned membership -> session -> atomic SQLite memory -> commit-before-publish; peer isolation, held sensor, injected failure, disconnect and Ready Room snapshot");
    }
    private static Knight knight(String name){return new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0);}
    private interface Check{boolean ok()throws Exception;}
    private static void await(Check check)throws Exception {long until=System.nanoTime()+5_000_000_000L;while(System.nanoTime()<until){if(check.ok())return;Thread.sleep(10);}throw new AssertionError("Timed out waiting for memory state/publication");}
    private static final class RecordingSocket extends Socket {
        final AtomicReference<Throwable> failure=new AtomicReference<>();volatile boolean published;
        private final CharacterStore characters;private final int knight;
        RecordingSocket(CharacterStore characters,int knight){this.characters=characters;this.knight=knight;}
        @Override public OutputStream getOutputStream(){return new OutputStream(){
            @Override public void write(int value){throw new AssertionError("Expected whole frame");}
            @Override public void write(byte[] bytes)throws IOException {if(new String(bytes,StandardCharsets.ISO_8859_1).contains(MEMORY)){
                try{if(!characters.knightMemories(knight,1).contains(MEMORY))throw new AssertionError("Memory published before durable commit");published=true;}
                catch(Throwable error){failure.set(error);throw new IOException("Memory publication failed",error);}
            }}
        };}
    }
}
