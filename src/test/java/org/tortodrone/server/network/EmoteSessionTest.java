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

/** Authenticated two-player regression for the stock ProjectX emote invocation. */
public final class EmoteSessionTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-emote-session-");
        try {
            var db=new Database(home,true);
            var protection=new Passwords.Protection(new byte[16],new byte[32]);
            int aliceId=db.create("Alice",protection,false),bobId=db.create("Bob",protection,false);
            var aliceStore=new CharacterStore(db,aliceId,"Alice");
            var bobStore=new CharacterStore(db,bobId,"Bob");
            aliceStore.create(choices("Alice-Knight"));bobStore.create(choices("Bob-Knight"));
            var registry=new SceneRegistry(SceneRegistry.system().catalog());
            var aliceSocket=new RecordingSocket();var bobSocket=new RecordingSocket();
            try(var aliceOutput=new SessionOutput(aliceSocket);var bobOutput=new SessionOutput(bobSocket);
                    var alice=new WorldSession(aliceSocket,aliceStore,aliceOutput,new SessionCodec(),1102,1,m->{},registry);
                    var bob=new WorldSession(bobSocket,bobStore,bobOutput,new SessionCodec(),1104,1,m->{},registry)) {
                invocation(alice,request(10,"Gaze",null));aliceSocket.awaitText("Enter a scene");
                alice.select("Alice-Knight",Map.of(SceneRegistry.ZONE_RECEIVER,(short)0));
                invocation(alice,request(10,"Gaze",null));aliceSocket.awaitText("Enter a scene");
                alice.subscribe((short)1);
                invocation(alice,request(10,"Gaze",null));aliceSocket.awaitText("Enter a scene");
                alice.entered();bob.select("Bob-Knight",Map.of(SceneRegistry.ZONE_RECEIVER,(short)0));
                bob.subscribe((short)2);bob.entered();
                int sharedScene=registry.destination(1,1).oid();
                check(alice.ownsScene(sharedScene)&&bob.ownsScene(sharedScene),"both authenticated knights enter the same scene instance");

                // Gaze is chat-only: both current members receive its native mode-5 event.
                invocation(alice,request(sharedScene,"Gaze",null));
                var gazeAlice=aliceSocket.awaitFrame("m.gaze_format");
                var gazeBob=bobSocket.awaitFrame("m.gaze_format");
                check(mode(gazeAlice)==5&&mode(gazeBob)==5
                    &&text(gazeAlice).contains("m.gaze_format|~Alice-Knight")
                    &&text(gazeBob).contains("m.gaze_format|~Alice-Knight"),"chat-only emote uses localized mode 5 and reaches sender and peer");
                check(activity(registry,1)==0,"chat-only emote leaves actor activity idle");

                // Wave is animated and targetable. The server resolves the case-insensitive
                // input to the canonical scene occupant name for both chat and activity.
                invocation(alice,request(sharedScene,"Wave","bOb-kNiGhT"));
                var waveAlice=aliceSocket.awaitFrame("m.wave_format_targeted");
                var waveBob=bobSocket.awaitFrame("m.wave_format_targeted");
                check(mode(waveAlice)==6&&mode(waveBob)==6
                    &&text(waveAlice).contains("m.wave_format_targeted|~Alice-Knight|~Bob-Knight")
                    &&text(waveBob).contains("m.wave_format_targeted|~Alice-Knight|~Bob-Knight"),"balloonless targeted Wave uses its stock mode and publishes the canonical target name to both members");
                check(activity(registry,1)==28,"Wave starts its verified animated activity");

                // A self-target is refused before it can replace the active Wave.
                invocation(alice,request(sharedScene,"Wave","aLiCe-KnIgHt"));
                aliceSocket.awaitText("can't do that to yourself");
                check(activity(registry,1)==28,"self-target refusal leaves active actor activity unchanged");
                aliceSocket.assertNoText("m.wave_format_targeted|~Alice-Knight|~Alice-Knight");
                bobSocket.assertNoText("m.wave_format_targeted|~Alice-Knight|~Alice-Knight");

                // Non-targetable Sit ignores the supplied name, including an absent knight.
                invocation(alice,request(sharedScene,"Sit","No-Such-Knight"));
                check(activity(registry,1)==26,"non-targetable Sit ignores a supplied target and starts the stock pose");
                aliceSocket.assertNoText("m.sit_format");bobSocket.assertNoText("m.sit_format");

                // Move Bob to a different town. His old chat membership must be removed;
                // a targeted action fails before changing Alice's current pose and cannot leak.
                int otherScene=registry.catalog().scenes().stream().mapToInt(SceneCatalog.MapScene::id)
                    .filter(id->id!=registry.catalog().entry().id()).findFirst().orElseThrow();
                bob.invocation(new SessionDecoder.Invocation(3,4,1,List.of(otherScene,
                    registry.catalog().scene(otherScene).version(),
                    new SessionDecoder.Listener("com.threerings.whirled.data.SceneMarshaller$SceneMoveMarshaller",(short)8))));
                check(bob.ownsScene(registry.destination(otherScene,2).oid()),"peer leaves the original town membership");
                bob.subscribe((short)3);bob.entered();
                int beforeOffline=activity(registry,1);
                invocation(alice,request(sharedScene,"Wave","Bob-Knight"));
                aliceSocket.awaitText("not in this scene");
                check(activity(registry,1)==beforeOffline,"offline target refusal leaves activity unchanged");
                aliceSocket.assertNoText("m.wave_format_targeted|~Alice-Knight|~Bob-Knight");
                bobSocket.assertNoText("m.wave_format_targeted|~Alice-Knight|~Bob-Knight");

                // Untargeted emotes remain local to the sender's current scene after Bob leaves.
                invocation(alice,request(sharedScene,"Gaze",null));
                var solo=aliceSocket.awaitFrame("m.gaze_format|~Alice-Knight");
                check(mode(solo)==5,"sender still receives its mode-5 emote after peer departure");
                bobSocket.assertNoText("m.gaze_format|~Alice-Knight");

                db.password("Alice",protection,aliceId);
                boolean revokedAtCall=false;
                try {invocation(alice,request(sharedScene,"Gaze",null));}
                catch(IOException expected) {revokedAtCall=true;}
                if(!revokedAtCall)aliceSocket.awaitText("Enter a scene"); // Poller may already have closed the revoked session.
                aliceSocket.assertNoText("m.gaze_format|~Alice-Knight");
                check(aliceStore.roster().size()==1&&bobStore.roster().size()==1,"emotes leave both saved rosters intact");

                // Force departure after emote validation but before the global chat route.
                // Holding CHAT blocks the worker in SceneChat.emote; unsubscribe removes
                // its membership reentrantly, so routing must become a callback failure.
                int bobScene=registry.destination(otherScene,2).oid();
                Object chat=chatMonitor();var done=new CountDownLatch(1);
                var workerFailure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
                Thread worker;
                synchronized(chat) {
                    worker=Thread.ofVirtual().name("emote-departure-regression").start(()->{
                        try{invocation(bob,request(bobScene,"Gaze",null));}
                        catch(Throwable failure){workerFailure.set(failure);}
                        finally{done.countDown();}
                    });
                    awaitBlockedOnChat(worker,chat);
                    bob.unsubscribe();
                }
                check(done.await(5,TimeUnit.SECONDS),"emote worker completes after scene departure");
                check(workerFailure.get()==null,"late chat membership failure is converted to an invocation callback");
                bobSocket.awaitText("Scene changed before the emote could be delivered");
                check(!bobSocket.isClosed(),"late emote failure does not disconnect its sender");
            }
            check(registry.instance(1,0).simulation().playerCount()==0,"closing sessions releases current scene memberships");
            System.out.println("PASS: authenticated two-player emotes, localized chat-only event, canonical target resolution, animation, self/offline guards, Sit target ignore, map isolation, readiness and revocation");
        } finally {try(var paths=Files.walk(home)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }

    private static void invocation(WorldSession world,SessionDecoder.Invocation request)throws IOException {
        world.emote(request);
    }
    private static SessionDecoder.Invocation request(int oid,String action,String target) {
        return new SessionDecoder.Invocation(oid,6,3,Arrays.asList(action,target,
            new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller",(short)7)));
    }
    private static int activity(SceneRegistry registry,int worldId)throws IOException {
        return registry.instance(registry.catalog().entry().id(),0).simulation().snapshot().actors().stream()
            .filter(a->a.id()==worldId).findFirst().orElseThrow().activity();
    }
    private static int mode(byte[] frame){return frame[frame.length-1]&0xff;}
    private static String text(byte[] frame){return new String(frame,StandardCharsets.ISO_8859_1);}
    private static Object chatMonitor()throws Exception {
        var field=WorldSession.class.getDeclaredField("CHAT");field.setAccessible(true);return field.get(null);
    }
    private static void awaitBlockedOnChat(Thread worker,Object chat)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<deadline) {
            if(worker.getState()==Thread.State.BLOCKED
                    &&Arrays.stream(worker.getStackTrace()).anyMatch(frame->frame.getClassName().equals(chat.getClass().getName())))return;
            if(!worker.isAlive())throw new AssertionError("Emote worker exited before contending for the chat lock");
            Thread.onSpinWait();
        }
        throw new AssertionError("Emote worker did not block on the chat monitor");
    }
    private static Knight choices(String name){return new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0);}

    private static final class RecordingSocket extends Socket {
        private final BlockingQueue<byte[]> frames=new LinkedBlockingQueue<>();
        @Override public OutputStream getOutputStream(){return new OutputStream(){
            @Override public void write(int value){throw new AssertionError("Expected whole frames");}
            @Override public void write(byte[] bytes){frames.add(bytes.clone());}
        };}
        byte[] awaitFrame(String token)throws Exception {
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<end){var frame=frames.poll(100,TimeUnit.MILLISECONDS);if(frame!=null&&text(frame).contains(token))return frame;}
            throw new AssertionError("Missing response: "+token);
        }
        void awaitText(String token)throws Exception {awaitFrame(token);}
        void assertNoText(String token) {
            for(var frame:frames)if(text(frame).contains(token))throw new AssertionError("Unexpected frame: "+token);
        }
    }
}
