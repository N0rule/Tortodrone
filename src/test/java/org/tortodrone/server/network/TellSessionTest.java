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

/** Tell ownership/authentication gates; the installed fixture verifies the full wire schema. */
public final class TellSessionTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-tell-sessions-");
        try {
            var db=new Database(home,true);
            // Synthetic verifier is confined to this disposable deployment; no login is attempted.
            var protection=new Passwords.Protection(new byte[16],new byte[32]);
            int alice=db.create("Alice",protection,false),bob=db.create("Bob",protection,false);
            var source=new CharacterStore(db,alice,"Alice");var target=new CharacterStore(db,bob,"Bob");
            source.create(knight("Sender"));target.create(knight("Recipient"));
            var one=new RecordingSocket();var two=new RecordingSocket();
            try(var out1=new SessionOutput(one);var out2=new SessionOutput(two);
                    var sender=new WorldSession(one,source,out1,new SessionCodec(),1101,1,m->{});
                    var recipient=new WorldSession(two,target,out2,new SessionCodec(),1102,1,m->{})) {
                var receivers=Map.of(TownSquare.ZONE_RECEIVER,(short)0);
                sender.tell(request("before-selection"));one.awaitText("Enter a scene");
                sender.select("Sender",receivers);recipient.select("Recipient",receivers);
                sender.subscribe((short)1);recipient.subscribe((short)1);
                sender.tell(request("before-readiness"));one.awaitText("Enter a scene");
                sender.entered();recipient.entered();
                sender.tell(request("accepted-private-marker"));two.awaitText("accepted-private-marker");
                refuse(()->sender.tell(new SessionDecoder.Invocation(999,3,3,request("wrong-object").arguments())));
                refuse(()->sender.tell(new SessionDecoder.Invocation(3,3,3,List.of("Recipient","wrong-listener",
                    new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller",(short)7)))));
                recipient.unsubscribe();sender.tell(request("unsubscribed-marker"));one.awaitText("not online");
                recipient.subscribe((short)2);recipient.entered();
                // Revocation must refuse immediately, including before the periodic poll notices it.
                db.password("Bob",protection,bob);sender.tell(request("revoked-recipient-marker"));one.awaitText("not online");
                check(!two.contains("revoked-recipient-marker"),"revoked recipient gets no tell");
                recipient.close();
                var three=new RecordingSocket();
                try(var out3=new SessionOutput(three);var replacement=new WorldSession(three,target,out3,new SessionCodec(),1103,2,m->{})) {
                    replacement.select("Recipient",receivers);replacement.subscribe((short)3);replacement.entered();
                    sender.tell(request("replacement-ready-marker"));three.awaitText("replacement-ready-marker");
                    db.password("Alice",protection,alice);
                    try{sender.tell(request("revoked-sender-marker"));one.awaitText("reconnect");}catch(IOException closedByPoll){ }
                    check(!three.contains("revoked-sender-marker"),"revoked sender cannot deliver to an authorized ready recipient");
                }
                check(source.roster().size()==1&&target.roster().size()==1,"tells do not mutate saved characters");
            }
            System.out.println("PASS: tell selection/readiness, RPC target/listener, unsubscribe, sender/recipient revocation and saved-state preservation");
        }finally{try(var paths=Files.walk(home)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
    private static Knight knight(String name){return new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)",
        "Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0);}
    private static SessionDecoder.Invocation request(String text){return new SessionDecoder.Invocation(3,3,3,List.of("Recipient",text,
        new SessionDecoder.Listener("com.threerings.crowd.chat.data.ChatMarshaller$TellMarshaller",(short)7)));}
    private static void refuse(Action action)throws Exception{try{action.run();throw new AssertionError("Malformed tell accepted");}catch(SessionDecoder.Rejected expected){}}
    @FunctionalInterface interface Action{void run()throws Exception;}
    private static final class RecordingSocket extends Socket {
        private final BlockingQueue<String> frames=new LinkedBlockingQueue<>();
        public OutputStream getOutputStream(){return new OutputStream(){
            public void write(int value){throw new AssertionError("Expected complete frames");}
            public void write(byte[] bytes){frames.add(new String(bytes,StandardCharsets.ISO_8859_1));}
        };}
        void awaitText(String text)throws Exception {
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<end){String frame=frames.poll(100,TimeUnit.MILLISECONDS);if(frame!=null&&frame.contains(text))return;}
            throw new AssertionError("Missing response: "+text);
        }
        boolean contains(String text){return frames.stream().anyMatch(frame->frame.contains(text));}
    }
}
