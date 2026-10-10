import com.samskivert.util.at;
import com.threerings.presents.client.*;
import com.threerings.presents.dobj.*;
import com.threerings.tudey.dobj.SceneDeltaEvent;
import com.threerings.tudey.data.InputFrame;
import com.threerings.projectx.data.ProjectXInputFrame;
import com.threerings.projectx.data.*;
import com.threerings.projectx.town.data.TownSceneObject;
import com.threerings.util.Name;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.tortodrone.world.PlayerEmotes;

/** Optional disposable stock-client exercise of every emote RPC in an ordinary town instance. */
public final class EmoteConnectionCheck {
    static final LinkedBlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
    static void waitFor(BooleanSupplier ready, long seconds) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!ready.getAsBoolean() && System.nanoTime() < end) {
            Runnable task = tasks.poll(100, TimeUnit.MILLISECONDS); if (task != null) task.run();
        }
        if (!ready.getAsBoolean()) throw new AssertionError("Emote fixture timeout");
    }
    static void delayWithDispatch(long millis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        waitFor(() -> System.nanoTime() >= deadline, millis / 1000 + 3);
    }
    static final class Reply implements A.a {
        boolean done; String reason;
        public void xx() { done = true; }
        public void aT(String value) { reason = value; done = true; }
    }
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 6) throw new IllegalArgumentException(
            "Usage: EmoteConnectionCheck port public-key [host username password knight]");
        System.setProperty("com.threerings.io.enumPolicy", "ORDINAL");
        String host=args.length>2?args[2]:"127.0.0.1", user=args.length>3?args[3]:"LanPlayer",
            password=args.length>4?args[4]:"lan-test-password", knight=args.length>5?args[5]:"Town-Knight";
        Thread dispatch=Thread.currentThread(); at queue=new at(){
            public void postRunnable(Runnable task){tasks.add(task);}
            public boolean isDispatchThread(){return Thread.currentThread()==dispatch;}
            public boolean isRunning(){return true;}
        };
        var client=new Client(new ProjectXCredentials(new Name(user),password),queue);
        boolean[] observing={true};
        client.b(host,new int[]{Integer.parseInt(args[0])},new int[0]);
        client.dr(Files.readString(Path.of(args[1])).trim()); client.ds("emote-check"); client.dt("projectx"); client.xg();
        try {
            waitFor(client::isLoggedOn,15);
            var player=(PlayerObject)client.xc();
            WorldConnectionCheck.registerSceneReceiver(client);
            var selected=new Reply(); ((com.threerings.projectx.client.aU)client.bU(com.threerings.projectx.client.aU.class))
                .b(new Name(knight),null,selected); waitFor(()->selected.done,12);
            if(selected.reason!=null)throw new AssertionError("Select knight: "+selected.reason);
            var place=WorldConnectionCheck.moveScene(client,1,ready->waitFor(ready,12));
            final TownSceneObject[] scene={null};
            client.xb().a(place.oid,new com.threerings.presents.dobj.t<TownSceneObject>(){
                public void a(TownSceneObject value){scene[0]=value;WorldConnectionCheck.listenChat(value,WorldConnectionCheck.primaryChat);}
                public void a(int oid,ObjectAccessException error){throw new AssertionError(error);}
            });
            waitFor(()->scene[0]!=null,12);
            player.a(new com.threerings.tudey.dobj.a(){public void c(SceneDeltaEvent event){if(observing[0])WorldConnectionCheck.received(event);}});
            scene[0].tudeySceneService.adB(); waitFor(()->WorldConnectionCheck.deltas>0,12);
            if(WorldConnectionCheck.controlled(player)==null)throw new AssertionError("No controlled actor in town");
            int checked=0;
            for(var descriptor:PlayerEmotes.all()) {
                WorldConnectionCheck.primaryChat.clear();
                int before=WorldConnectionCheck.deltas;
                var reply=new Reply(); scene[0].projectXSceneService.b(descriptor.name(),null,reply);
                waitFor(()->reply.done,12);
                if(reply.reason!=null)throw new AssertionError("/"+descriptor.name()+" rejected: "+reply.reason);
                if(descriptor.activity()!=0) {
                    waitFor(()->WorldConnectionCheck.controlled(player).adU()==descriptor.activity(),12);
                    if(WorldConnectionCheck.controlled(player).adV()<=0)throw new AssertionError("Missing activity start for "+descriptor.name());
                    if(descriptor.duration()>0) {
                        int start=WorldConnectionCheck.controlled(player).adV();
                        waitFor(()->WorldConnectionCheck.controlled(player).adU()==0,descriptor.duration()/1000+8);
                        if(WorldConnectionCheck.controlled(player).adV()!=start+descriptor.duration())
                            throw new AssertionError("Wrong expiry for "+descriptor.name());
                    }
                }
                if(descriptor.name().equals("Sit")) {
                    var seated=WorldConnectionCheck.controlled(player);
                    if(seated.adU()!=26 || seated.bR(2))throw new AssertionError("Sit did not lock movement");
                    float x=seated.tz().x;
                    for(int n=0;n<4;n++) {
                        int oldDeltas=WorldConnectionCheck.deltas;
                        sendMove(scene[0]);
                        waitFor(()->WorldConnectionCheck.deltas>oldDeltas,12);
                        var standing=WorldConnectionCheck.controlled(player);
                        if(standing.adU()!=46 || standing.tz().x!=x || standing.bR(2))
                            throw new AssertionError("Sit movement did not enter standing transition");
                    }
                    waitFor(()->WorldConnectionCheck.controlled(player).adU()==0,5);
                    sendMove(scene[0]);
                    waitFor(()->WorldConnectionCheck.controlled(player).tz().x!=x,5);
                    System.out.println("CHECK Sit: input enters activity 46, remains stationary through its deadline, then walks.");
                    sendStop(scene[0]);
                } else if(descriptor.name().equals("Sleep")) {
                    if(WorldConnectionCheck.controlled(player).adU()!=27)throw new AssertionError("Sleep activity missing");
                    sendMove(scene[0]);
                    waitFor(()->WorldConnectionCheck.controlled(player).adU()!=27,8);
                    if(WorldConnectionCheck.controlled(player).adU()!=0 || !WorldConnectionCheck.controlled(player).bR(2))
                        throw new AssertionError("Movement did not cancel Sleep into idle");
                    System.out.println("CHECK Sleep: first movement input clears persistent activity 27 to idle.");
                    sendStop(scene[0]);
                }
                if(WorldConnectionCheck.primaryChat.isEmpty()) {
                    // Chat-only and animated balloonless variants may be silent in the town actor stream.
                    if(!descriptor.balloonless() && !descriptor.spawnsTransient())
                        throw new AssertionError("No stock chat event for visible emote "+descriptor.name());
                } else {
                    var message=WorldConnectionCheck.primaryChat.get(0);
                    if(message.mode!=(descriptor.balloonless()?6:5) || message.mC()!=null)
                        throw new AssertionError("Emote event must use native ProjectX bubble mode: "+descriptor.name());
                }
                checked++;
                delayWithDispatch(1050);
            }
            System.out.println("PASS: stock login/town entry accepted all "+checked+" emote RPCs; animated activity/expiry and mode-5 event checks completed.");
            System.out.println("NOTE: Run against a disposable server with the emote RPC implemented; this fixture does not assert damage, NPC, quest or transient visual semantics.");
        } finally { observing[0]=false; if(client.isLoggedOn())client.Q(false); }
    }
    static void sendMove(TownSceneObject scene) {
        scene.a(WorldConnectionCheck.last.QY(),WorldConnectionCheck.last.QY(),
            new InputFrame[]{new ProjectXInputFrame(WorldConnectionCheck.last.QY()+50,1.5f,0,1,0)});
    }
    static void sendStop(TownSceneObject scene) {
        scene.a(WorldConnectionCheck.last.QY(),WorldConnectionCheck.last.QY(),
            new InputFrame[]{new ProjectXInputFrame(WorldConnectionCheck.last.QY()+50,1.5f,0,0,0)});
    }
}
