package org.tortodrone.server.network;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.account.AccountStore;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import org.tortodrone.storage.Database;
import org.tortodrone.world.TownSquare;
import static org.tortodrone.support.TestWire.check;

public final class WorldSessionTest {
    public static void main(String[] args) throws Exception {
        Path home=Files.createTempDirectory("tortodrone-town-sessions-");
        try(var accounts=new AccountStore(home)) {
            accounts.create("Alice","alice-password".toCharArray());
            var characters=new CharacterStore(new Database(home,false),1,"Alice");
            var knight=characters.create(new Knight(0,"Town-Knight","Gear/Helm/Spiral Sallet (Blue)",
                "Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            RecordingSocket unavailable=new RecordingSocket();
            try(var output=new SessionOutput(unavailable);var empty=new WorldSession(unavailable,characters,output,new SessionCodec(),1000,1,message->{},
                    new org.tortodrone.world.SceneRegistry(org.tortodrone.world.SceneCatalog.empty()))) {
                refuse(()->empty.select(knight.name(),Map.of(TownSquare.ZONE_RECEIVER,(short)0)));
                check(!empty.selected()&&!WorldSession.active(1,knight.id()),"missing maps refuse world entry without acquiring a controller");
            }
            RecordingSocket one=new RecordingSocket(),two=new RecordingSocket(),stale=new RecordingSocket();
            try(var out1=new SessionOutput(one);var out2=new SessionOutput(two);var out3=new SessionOutput(stale);
                    var first=new WorldSession(one,characters,out1,new SessionCodec(),1001,1,message -> {});
                    var second=new WorldSession(two,characters,out2,new SessionCodec(),1002,1,message -> {});
                    var expired=new WorldSession(stale,characters,out3,new SessionCodec(),1003,999,message -> {})) {
                var receiver=Map.of(TownSquare.ZONE_RECEIVER,(short)0);
                var input=new SessionDecoder.Input(TownSquare.SCENE_OID,0,0,List.of());
                refuse(() -> first.input(input));
                refuse(() -> expired.select(knight.name(),receiver));
                first.select(knight.name(),receiver);
                var chatRequest=new SessionDecoder.Invocation(TownSquare.SCENE_OID,org.tortodrone.world.SceneChat.SERVICE,1,List.of("hello",(byte)0));
                refuse(()->first.speak(chatRequest));
                check(first.selected() && WorldSession.active(1,knight.id()),"owned character acquires controller");
                refuse(() -> second.select(knight.name(),receiver));
                check(!second.selected(),"duplicate selection leaves its session unselected");
                second.close();
                check(WorldSession.active(1,knight.id()),"closing refused session cannot release another controller");
                refuse(() -> first.select(knight.name(),receiver));
                first.subscribe((short)1);
                refuse(() -> first.input(input));
                first.entered();
                first.entered(); // repeated readiness must not create a second ticker
                first.input(input);
                var reader = new DeltaReader(one);
                var initial = reader.next();
                check(initial.reference()==0 && initial.added()==1, "initial delta contains the controlled actor");
                int previous = initial.timestamp();
                // No acknowledgements for more than the old two-second history window.
                for (int i=0;i<45;i++) {
                    var delta = reader.next();
                    check(delta.reference()==previous && delta.timestamp()>previous && delta.added()==0,
                        "reliable stream retains actors while entry delays acknowledgements");
                    previous = delta.timestamp();
                }
                // Stale/zero/fabricated acknowledgements cannot reset the reliable baseline.
                for (int ack : new int[] {initial.timestamp(),0,Integer.MAX_VALUE}) {
                    first.input(new SessionDecoder.Input(TownSquare.SCENE_OID,ack,0,List.of()));
                    var delta = reader.next();
                    check(delta.reference()==previous && delta.added()==0, "client cannot rewind TCP baseline");
                    previous = delta.timestamp();
                }
                refuse(() -> first.input(new SessionDecoder.Input(999,0,0,List.of())));
                first.refresh();
                first.unsubscribe();
                refuse(()->first.speak(chatRequest));
                refuse(() -> first.entered());
                refuse(() -> first.input(input));
                // Re-entry starts a new baseline, then resumes the same reliable stream policy.
                first.subscribe((short)2);
                first.entered();
                var reentry = reader.nextAfterSubscription((short)2);
                check(reentry.reference()==0 && reentry.added()==1, "resubscription resets the scene baseline");
                var afterReentry = reader.next();
                check(afterReentry.reference()==reentry.timestamp() && afterReentry.added()==0,
                    "re-entry retains actor after its initial snapshot");
                long helm=characters.worldProfile(knight.name(),1).equipment()[0];
                var confirm=new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller",(short)9);
                first.invocation(new SessionDecoder.Invocation(3,9,9,List.of(helm,
                    new SessionDecoder.EnumValue("com.threerings.projectx.item.data.ItemCodes$Location",0),0,confirm)));
                check(characters.worldProfile(knight.name(),1).equipment()[0]==0,"moveItem unequips");
                first.invocation(new SessionDecoder.Invocation(3,9,3,List.of(helm,0,confirm)));
                check(characters.worldProfile(knight.name(),1).equipment()[0]==helm
                    && characters.worldProfile(knight.name(),1).items().get(0).bound(),"Arsenal equipItem restores bound item");
                first.invocation(new SessionDecoder.Invocation(3,9,3,List.of(helm,7,confirm)));
                check(characters.worldProfile(knight.name(),1).equipment()[0]==helm,"invalid Arsenal slot cannot change equipment");
                first.close();
                refuse(()->first.speak(chatRequest));
                check(!WorldSession.active(1,knight.id()),"disconnect releases controller");
            }
            // A new session can select the persistent character after the old controller exits.
            Socket reconnect=new RecordingSocket();
            try(var output=new SessionOutput(reconnect);var world=new WorldSession(reconnect,characters,output,
                    new SessionCodec(),1004,1,message -> {})) {
                world.select(knight.name(),Map.of(TownSquare.ZONE_RECEIVER,(short)0));
                check(world.selected(),"reconnect selects saved character");
            }
            check(!WorldSession.active(1,knight.id()),"reconnect cleanup releases controller");
            System.out.println("PASS: town ownership, auth revisions, input readiness, delayed/invalid acknowledgements, reliable deltas, re-entry and reconnect cleanup");
        } finally {try(var paths=Files.walk(home)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
    private static void refuse(Action action) throws Exception {
        try {action.run();throw new AssertionError("Invalid session operation accepted");}
        catch(IOException | CharacterStore.Refused expected) { }
    }
    @FunctionalInterface private interface Action {void run() throws Exception;}
    private record Delta(int reference, int timestamp, int added) { }
    /** Independent frame-header reader; actor payloads are checked by the installed reader fixture. */
    private static final class DeltaReader {
        private final RecordingSocket socket;
        private final Map<Integer,String> types = new HashMap<>();
        DeltaReader(RecordingSocket socket) { this.socket=socket; }
        private String type(DataInputStream in) throws IOException {
            int code=in.readShort();
            if(code<0) { String name=in.readUTF(); types.put(-code,name); return name; }
            return types.get(code);
        }
        Delta next() throws Exception { return nextAfterSubscription((short)0); }
        Delta nextAfterSubscription(short subscription) throws Exception {
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<end) {
                byte[] frame=socket.frames.poll(100,TimeUnit.MILLISECONDS);
                if(frame==null) continue;
                var in=new DataInputStream(new ByteArrayInputStream(frame));
                check(in.readInt()==frame.length,"complete downstream frame");
                String message=type(in); short messageId=in.readShort();
                if(subscription!=0) {
                    if("com.threerings.presents.net.ObjectResponse".equals(message) && messageId==subscription)
                        subscription=0;
                    continue;
                }
                if(!"com.threerings.presents.net.EventNotification".equals(message)) continue;
                if(!"com.threerings.tudey.dobj.SceneDeltaEvent".equals(type(in))) continue;
                check(in.readInt()==1001 && in.readInt()==TownSquare.SCENE_OID,"delta recipient and scene");
                in.readInt(); in.readShort();
                int reference=in.readInt(),timestamp=in.readInt();
                check(in.readShort()>0,"positive scene elapsed time");
                int addedType=in.readShort(),added=0;
                if(addedType!=0) { if(addedType<0) in.readUTF(); added=in.readInt(); }
                return new Delta(reference,timestamp,added);
            }
            throw new AssertionError("Town delta timed out");
        }
    }
    private static final class RecordingSocket extends Socket {
        final BlockingQueue<byte[]> frames=new LinkedBlockingQueue<>();
        private final OutputStream sink=new OutputStream() {
            public void write(int value) { throw new AssertionError("Expected complete frames"); }
            public void write(byte[] bytes) { frames.add(bytes.clone()); }
        };
        @Override public OutputStream getOutputStream() { return sink; }
    }
}
