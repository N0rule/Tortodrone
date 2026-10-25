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
import org.tortodrone.world.SceneRegistry;
import org.tortodrone.world.TownSquare;
import static org.tortodrone.support.TestWire.check;

/** The configured welcome notice appears once per login, before any scene re-entry. */
public final class WelcomeMessageTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-welcome-");
        try {
            var db=new Database(home,true);
            var protection=new Passwords.Protection(new byte[16],new byte[32]);
            int id=db.create("Alice",protection,false);
            var characters=new CharacterStore(db,id,"Alice");
            characters.create(new Knight(0,"Welcome-Knight","Gear/Helm/Spiral Sallet (Blue)",
                "Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            String welcome="Welcome to the test server!";
            var socket=new RecordingSocket();
            try(var output=new SessionOutput(socket);var world=new WorldSession(socket,characters,output,new SessionCodec(),1101,1,message->{},
                    SceneRegistry.system(),()->false,welcome)) {
                world.select("Welcome-Knight",Map.of(TownSquare.ZONE_RECEIVER,(short)0));
                world.subscribe((short)1);
                world.entered();
                check(socket.awaitText(welcome),"configured welcome notice reaches the chat log after login");
                world.unsubscribe();
                world.subscribe((short)2);
                world.entered();
                check(socket.absent(welcome,300),"re-entering a scene does not repeat the welcome notice");
            }
            var silent=new RecordingSocket();
            try(var output=new SessionOutput(silent);var world=new WorldSession(silent,characters,output,new SessionCodec(),1102,1,message->{},
                    SceneRegistry.system(),()->false,"")) {
                world.select("Welcome-Knight",Map.of(TownSquare.ZONE_RECEIVER,(short)0));
                world.subscribe((short)1);
                world.entered();
                check(silent.absent("com.threerings.crowd.chat.data.SystemMessage",300),"an empty welcome message stays silent");
            }
            System.out.println("PASS: configured welcome notice is delivered once per login and can be disabled");
        }finally{try(var paths=Files.walk(home)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
    private static final class RecordingSocket extends Socket {
        private final BlockingQueue<String> frames=new LinkedBlockingQueue<>();
        public OutputStream getOutputStream(){return new OutputStream(){
            public void write(int value){throw new AssertionError("Expected whole frames");}
            public void write(byte[] bytes){frames.add(new String(bytes,StandardCharsets.ISO_8859_1));}
        };}
        boolean awaitText(String text)throws Exception {
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<end){var frame=frames.poll(100,TimeUnit.MILLISECONDS);if(frame!=null&&frame.contains(text))return true;}
            return false;
        }
        boolean absent(String text,long millis)throws Exception {
            long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);
            while(System.nanoTime()<end){var frame=frames.poll(50,TimeUnit.MILLISECONDS);if(frame!=null&&frame.contains(text))return false;}
            return true;
        }
    }
}
