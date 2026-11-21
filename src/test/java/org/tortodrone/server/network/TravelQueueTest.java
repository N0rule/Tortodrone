package org.tortodrone.server.network;

import java.io.*;
import java.lang.reflect.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import org.tortodrone.storage.Database;
import org.tortodrone.core.town.TownCatalog;
import org.tortodrone.world.*;
import static org.tortodrone.support.TestWire.check;
import org.tortodrone.core.character.Knight;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.resources.SceneCatalog;

/** Town destination commands sent from the loading screen or Ready Room are replayed
 *  after enteredPlace instead of failing with "Finish entering this scene first". */
public final class TravelQueueTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-travel-queue-");
        try {
            var db=new Database(home,true);
            int account=db.create("Alice",new Passwords.Protection(new byte[16],new byte[32]),false);
            var items=ItemCatalog.load(org.tortodrone.support.ContentFixture.path("items/catalog.json"));
            var characters=new CharacterStore(db,account,"Alice",items);
            var knight=characters.create(new Knight(0,"Travel-Knight","Gear/Helm/Spiral Sallet (Blue)",
                "Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            var scenes=new SceneRegistry(SceneCatalog.load(org.tortodrone.support.ContentFixture.path("maps")));
            scenes.town(TownCatalog.load(org.tortodrone.support.ContentFixture.path("town/catalog.json")));
            var socket=new RecordingSocket();
            try(var output=new SessionOutput(socket);var world=new WorldSession(socket,characters,output,
                    new SessionCodec(items),1001,1,m->{},scenes)) {
                world.select(knight.name(),Map.of(SceneRegistry.ZONE_RECEIVER,(short)0));
                world.invocation(destination("training"));
                check(pending(world)!=null,"a destination request during scene entry is queued");
                check(!socket.saw("Finish entering this scene first",200),
                    "a queued destination request does not fail while the scene loads");

                world.subscribe((short)1);
                world.entered();
                check(pending(world)==null,"enteredPlace replays and clears the queued request");
                var move=portalMove(world);
                check(move!=null&&destination(move).map().id()==294,
                    "queued /training starts the Training Hall transition");

                // A later command is handled by the same path, not left in the queue.
                world.close();
                check(pending(world)==null,"closing the session releases the queued request");
            }
            // The native commands also work while the client-local Ready Room is open.
            var readySocket=new RecordingSocket();
            try(var output=new SessionOutput(readySocket);var world=new WorldSession(readySocket,characters,output,
                    new SessionCodec(items),1002,1,m->{},scenes)) {
                world.select(knight.name(),Map.of(SceneRegistry.ZONE_RECEIVER,(short)0),true);
                check(readyRoom(world),"the session is staged in the client-local Ready Room");
                world.invocation(destination("training"));
                check(pending(world)==null,"Ready Room /training is not deferred to Haven");
                check(!readyRoom(world)&&sceneMap(world)==294,"Ready Room /training installs the Training Hall directly");
                check(portalMove(world)==null,"Ready Room travel attaches the destination without a portal wait");
            }
            System.out.println("PASS: early destination is queued after entry and travels directly from the Ready Room");
        } finally {
            try(var paths=Files.walk(home)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
        }
    }
    private static SessionDecoder.Invocation destination(String destination) {
        return new SessionDecoder.Invocation(SessionCodec.INVOCATION_OID,SessionCodec.KNIGHT_SERVICE,7,
            List.of(destination,new SessionDecoder.Listener(
                "com.threerings.presents.data.InvocationMarshaller$ResultMarshaller",(short)7)));
    }
    private static Object pending(WorldSession world)throws Exception {
        Field field=WorldSession.class.getDeclaredField("pendingTravel");field.setAccessible(true);return field.get(world);
    }
    private static Object portalMove(WorldSession world)throws Exception {
        Field field=WorldSession.class.getDeclaredField("portalMove");field.setAccessible(true);return field.get(world);
    }
    private static boolean readyRoom(WorldSession world)throws Exception {
        Field field=WorldSession.class.getDeclaredField("readyRoom");field.setAccessible(true);return field.getBoolean(world);
    }
    private static int sceneMap(WorldSession world)throws Exception {
        Field field=WorldSession.class.getDeclaredField("scene");field.setAccessible(true);
        return ((SceneRegistry.Instance)field.get(world)).map().id();
    }
    private static SceneRegistry.Instance destination(Object move)throws Exception {
        return (SceneRegistry.Instance)move.getClass().getMethod("destination").invoke(move);
    }
    private static final class RecordingSocket extends Socket {
        final BlockingQueue<String> frames=new LinkedBlockingQueue<>();
        public OutputStream getOutputStream(){return new OutputStream(){
            public void write(int value){throw new AssertionError("Expected a whole frame");}
            public void write(byte[] bytes){frames.add(new String(bytes,StandardCharsets.ISO_8859_1));}
        };}
        boolean saw(String text,long millis)throws Exception {
            long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
            while(System.nanoTime()<end){var frame=frames.poll(20,TimeUnit.MILLISECONDS);if(frame!=null&&frame.contains(text))return true;}
            return false;
        }
    }
}
