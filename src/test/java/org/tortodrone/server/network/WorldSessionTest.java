package org.tortodrone.server.network;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
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
            Socket one=new RecordingSocket(),two=new RecordingSocket(),stale=new RecordingSocket();
            try(var out1=new SessionOutput(one);var out2=new SessionOutput(two);var out3=new SessionOutput(stale);
                    var first=new WorldSession(one,characters,out1,new SessionCodec(),1001,1,message -> {});
                    var second=new WorldSession(two,characters,out2,new SessionCodec(),1002,1,message -> {});
                    var expired=new WorldSession(stale,characters,out3,new SessionCodec(),1003,999,message -> {})) {
                var receiver=Map.of(TownSquare.ZONE_RECEIVER,(short)0);
                var input=new SessionDecoder.Input(TownSquare.SCENE_OID,0,0,List.of());
                refuse(() -> first.input(input));
                refuse(() -> expired.select(knight.name(),receiver));
                first.select(knight.name(),receiver);
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
                refuse(() -> first.input(new SessionDecoder.Input(999,0,0,List.of())));
                first.refresh();
                first.unsubscribe();
                refuse(() -> first.entered());
                refuse(() -> first.input(input));
                first.close();
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
            System.out.println("PASS: town controller ownership, duplicate selection, auth revisions, scene input authorization and reconnect cleanup");
        } finally {try(var paths=Files.walk(home)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
    private static void refuse(Action action) throws Exception {
        try {action.run();throw new AssertionError("Invalid session operation accepted");}
        catch(IOException | CharacterStore.Refused expected) { }
    }
    @FunctionalInterface private interface Action {void run() throws Exception;}
    private static final class RecordingSocket extends Socket {
        private final OutputStream sink=new OutputStream() {public void write(int value) { }};
        @Override public OutputStream getOutputStream() { return sink; }
    }
}
