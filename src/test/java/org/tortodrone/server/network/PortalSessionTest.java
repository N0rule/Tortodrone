package org.tortodrone.server.network;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import org.tortodrone.storage.Database;
import org.tortodrone.world.*;
import static org.tortodrone.support.TestWire.check;

/** A real scene tick initiates travel; only the chosen destination can consume its arrival. */
public final class PortalSessionTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-portal-session-");
        try {
            Path maps=home.resolve("maps");
            try(var paths=Files.walk(Path.of("defaults/maps"))){for(var path:paths.toList()){
                Path target=maps.resolve(Path.of("defaults/maps").relativize(path));
                if(Files.isDirectory(path))Files.createDirectories(target);else Files.copy(path,target);
            }}
            Path descriptor=maps.resolve("haven/map.properties");var properties=new Properties();
            try(var in=Files.newInputStream(descriptor)){properties.load(in);}
            properties.setProperty("spawn-x","-38.25");properties.setProperty("spawn-y","0.4");
            try(var out=Files.newOutputStream(descriptor)){properties.store(out,"Disposable trigger spawn");}
            var registry=new SceneRegistry(SceneCatalog.load(maps));
            var db=new Database(home.resolve("data"),true);
            var protection=new Passwords.Protection(new byte[16],new byte[32]);
            int id=db.create("Alice",protection,false);var characters=new CharacterStore(db,id,"Alice");
            var knight=characters.create(new Knight(0,"Portal-Knight","Gear/Helm/Spiral Sallet (Blue)",
                "Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            var socket=new RecordingSocket();
            try(var output=new SessionOutput(socket);var world=new WorldSession(socket,characters,output,
                    new SessionCodec(),1201,1,m->{},registry)) {
                world.select(knight.name(),Map.of(SceneRegistry.ZONE_RECEIVER,(short)0));
                int source=registry.entry().oid();var destination=registry.destination(2,knight.id());
                world.subscribe((short)1);world.entered();
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(!world.departingScene(source)&&System.nanoTime()<end)Thread.sleep(10);
                check(world.departingScene(source)&&world.ownsScene(source),"tick freezes source pending the stock move callback");
                world.entered();world.input(new SessionDecoder.Input(source,0,0,List.of()));
                refuse(()->world.input(new SessionDecoder.Input(999,0,0,List.of())));
                world.invocation(move(1,0));socket.awaitText("current portal journey");
                check(world.ownsScene(source)&&registry.entry().simulation().playerCount()==1
                    &&destination.simulation().playerCount()==0,"wrong destination cannot consume the chosen arrival");
                world.invocation(move(2,Integer.MAX_VALUE));
                check(world.ownsScene(destination.oid())&&registry.entry().simulation().playerCount()==0,
                    "normal move callback transfers only the owned membership");
                var actor=destination.simulation().snapshot().actors().getFirst();
                check(actor.x()==16.5f&&actor.y()==7.5f&&Math.abs(actor.rotation()+Math.PI/2)<.001,
                    "server entrance supplies stock arrival position/facing regardless of client cache version");
                check(actor.activity()==0&&actor.flags()==0,"arrival clears departing input and activity");
                world.input(new SessionDecoder.Input(source,0,0,List.of()));
                refuse(()->world.input(new SessionDecoder.Input(destination.oid(),0,0,List.of())));
                world.subscribe((short)2);world.entered();
                check(!world.departingScene(source),"destination readiness ends the stale source grace");
                refuse(()->world.input(new SessionDecoder.Input(source,0,0,List.of())));
                world.input(new SessionDecoder.Input(destination.oid(),0,0,List.of()));
                world.unsubscribe();db.password("Alice",protection,id);
                world.invocation(move(1,0));socket.awaitText("account changed");
                check(world.ownsScene(destination.oid()),"revoked account cannot travel to another scene");
            }
            check(!WorldSession.active(id,knight.id())&&destinationEmpty(registry),"disconnect releases both scene and controller");
            System.out.println("PASS: server portal ownership, pending freeze, destination/cache-version validation, tagged arrival, stale-input grace, readiness and immediate revocation");
        }finally{try(var paths=Files.walk(home)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
    private static boolean destinationEmpty(SceneRegistry registry)throws IOException {
        return registry.entry().simulation().playerCount()==0&&registry.instance(2,0).simulation().playerCount()==0;
    }
    private static SessionDecoder.Invocation move(int destination,int version) {
        return new SessionDecoder.Invocation(3,5,1,List.of(1,destination,version,
            new SessionDecoder.Listener("com.threerings.whirled.zone.data.ZoneMarshaller$ZoneMoveMarshaller",(short)7)));
    }
    private static void refuse(Action action)throws Exception {
        try{action.run();throw new AssertionError("Unauthorized input accepted");}catch(IOException expected){}
    }
    @FunctionalInterface private interface Action{void run()throws Exception;}
    private static final class RecordingSocket extends Socket {
        final BlockingQueue<String> frames=new LinkedBlockingQueue<>();
        public OutputStream getOutputStream(){return new OutputStream(){
            public void write(int value){throw new AssertionError("Expected whole frame");}
            public void write(byte[] bytes){frames.add(new String(bytes,StandardCharsets.ISO_8859_1));}
        };}
        void awaitText(String text)throws Exception {
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<end){var frame=frames.poll(100,TimeUnit.MILLISECONDS);if(frame!=null&&frame.contains(text))return;}
            throw new AssertionError("Missing response: "+text);
        }
    }
}
