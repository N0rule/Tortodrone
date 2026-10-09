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
import org.tortodrone.world.TownSquare;
import static org.tortodrone.support.TestWire.check;

/** Exercise action readiness, original listener validation and immediate credential revocation. */
public final class SitSessionTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-sit-session-");
        try {
            var db=new Database(home,true);
            var protection=new Passwords.Protection(new byte[16],new byte[32]);
            int id=db.create("Alice",protection,false);
            var characters=new CharacterStore(db,id,"Alice");
            characters.create(new Knight(0,"Seated-Knight","Gear/Helm/Spiral Sallet (Blue)",
                "Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            var socket=new RecordingSocket();
            try(var output=new SessionOutput(socket);var world=new WorldSession(socket,characters,output,new SessionCodec(),1101,1,m->{})) {
                world.emote(request(10,"Sit"));socket.awaitText("Enter a scene");
                world.select("Seated-Knight",Map.of(TownSquare.ZONE_RECEIVER,(short)0));
                world.subscribe((short)1);
                world.emote(request(10,"Sit"));socket.awaitText("Enter a scene");
                world.entered();
                world.emote(request(999,"Sit"));socket.awaitText("Enter a scene");
                world.emote(request(10,"Unverified-Action"));socket.awaitText("not available");
                try {
                    world.emote(new SessionDecoder.Invocation(10,6,3,Arrays.asList("Sit",null,
                        new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ResultMarshaller",(short)7))));
                    throw new AssertionError("Wrong listener accepted");
                }catch(SessionDecoder.Rejected expected){ }
                world.emote(request(10,"Sit"));
                world.unsubscribe();world.emote(request(10,"Sit"));socket.awaitText("Enter a scene");
                world.subscribe((short)2);world.entered();
                db.password("Alice",protection,id);
                try{world.emote(request(10,"Sit"));throw new AssertionError("Revoked action accepted");}
                catch(IOException expected){ }
                check(characters.roster().size()==1,"action leaves saved characters intact");
            }
            System.out.println("PASS: sit selection/readiness, wrong scene/listener, unsupported actions, unsubscribe and immediate credential revocation");
        }finally{try(var paths=Files.walk(home)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
    private static SessionDecoder.Invocation request(int oid,String emote) {
        return new SessionDecoder.Invocation(oid,6,3,Arrays.asList(emote,null,
            new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller",(short)7)));
    }
    private static final class RecordingSocket extends Socket {
        private final BlockingQueue<String> frames=new LinkedBlockingQueue<>();
        public OutputStream getOutputStream(){return new OutputStream(){
            public void write(int value){throw new AssertionError("Expected whole frames");}
            public void write(byte[] bytes){frames.add(new String(bytes,StandardCharsets.ISO_8859_1));}
        };}
        void awaitText(String text)throws Exception {
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<end){var frame=frames.poll(100,TimeUnit.MILLISECONDS);if(frame!=null&&frame.contains(text))return;}
            throw new AssertionError("Missing response: "+text);
        }
    }
}
