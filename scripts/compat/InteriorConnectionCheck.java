import com.samskivert.util.at;
import com.threerings.presents.client.*;
import com.threerings.presents.dobj.*;
import com.threerings.projectx.client.aU;
import com.threerings.projectx.data.*;
import com.threerings.projectx.dungeon.data.LevelPartyObject;
import com.threerings.projectx.dungeon.data.actor.Dungeoneer;
import com.threerings.tudey.data.InputFrame;
import com.threerings.tudey.data.actor.Actor;
import com.threerings.tudey.dobj.SceneDeltaEvent;
import com.threerings.util.Name;
import com.threerings.whirled.data.SceneModel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.tortodrone.world.*;

/** Two stock clients exercise the native Haven interior links and party exit operation. */
public final class InteriorConnectionCheck {
    private static final LinkedBlockingQueue<Runnable> TASKS = new LinkedBlockingQueue<>();
    private static final List<int[]> FORCED = new CopyOnWriteArrayList<>();
    private static Thread dispatchThread;
    private static at queue;

    private InteriorConnectionCheck() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 5 || args.length==5&&!args[4].equals("--weapons")) throw new IllegalArgumentException(
            "Usage: InteriorConnectionCheck port public-key [host maps-dir [--weapons]]");
        System.setProperty("com.threerings.io.enumPolicy", "ORDINAL");
        dispatchThread = Thread.currentThread();
        queue = new at() {
            public void postRunnable(Runnable task) { TASKS.add(task); }
            public boolean isDispatchThread() { return Thread.currentThread() == dispatchThread; }
            public boolean isRunning() { return true; }
        };
        String host = args.length > 2 ? args[2] : "127.0.0.1";
        Path mapsPath = args.length > 3 ? Path.of(args[3]) : Path.of("defaults/maps");
        var maps = SceneCatalog.load(mapsPath);
        requireScene(maps, 1, "town"); requireScene(maps, 2, "town");
        requireScene(maps, 3, "town"); requireScene(maps, 445, "town");
        requireScene(maps, 294, "training"); requireScene(maps, 437, "level");
        requireScene(maps, 449, "level");

        String key = Files.readString(Path.of(args[1])).trim();
        if(args.length==5){try(var endpoint=new Endpoint(host,Integer.parseInt(args[0]),key,"LanPlayer","Town-Knight")){
            endpoint.enter(294);endpoint.entered();endpoint.awaitActor();endpoint.weaponControls();
        }return;}
        try (Endpoint lan = new Endpoint(host, Integer.parseInt(args[0]), key, "LanPlayer", "Town-Knight");
             Endpoint peer = new Endpoint(host, Integer.parseInt(args[0]), key, "PeerPlayer", "Peer-Knight")) {
            lan.enter(1); peer.enter(1);
            if (lan.place.xL() != peer.place.xL()) throw new AssertionError("Town clients did not share their configured instance");
            lan.entered(); peer.entered();
            lan.awaitActor(); peer.awaitActor();
            await(() -> lan.currentActors().containsKey(peer.player.pawnId) && peer.currentActors().containsKey(lan.player.pawnId));
            if (!(lan.actor() instanceof com.threerings.projectx.data.actor.Knight) ||
                !(peer.actor() instanceof com.threerings.projectx.data.actor.Knight))
                throw new AssertionError("Town baseline is missing a native Knight actor");
            var emote = new Confirm();
            peer.place.projectXSceneService.b("Hello", null, emote);
            await(() -> emote.done);
            if (emote.reason != null) throw new AssertionError("Peer stock emote RPC rejected: " + emote.reason);
            await(() -> lan.chatCount > 0 && peer.chatCount > 0);
            System.out.println("PASS: two stock clients share Town Square actors and receive a peer emote");

            for (int id : List.of(2, 3, 445)) {
                lan.enter(id); lan.entered(); lan.awaitActor();
                if (!(lan.place instanceof com.threerings.projectx.town.data.TownSceneObject))
                    throw new AssertionError("Town scene " + id + " did not decode as TownSceneObject");
                System.out.println("PASS: stock town scene " + id + " loads and establishes a fresh actor baseline");
            }

            // Town Square -> Advanced Training Hall, using its archived sensor.
            travelByWalk(lan, maps, 1, 3922, 294, "training");
            // Same template for a second player must produce a separate live instance and baseline.
            peer.enter(294); peer.entered(); peer.awaitActor();
            if (peer.place.xL() == lan.place.xL()) throw new AssertionError("Two players shared a private training instance");
            if (!(peer.actor() instanceof Dungeoneer)) throw new AssertionError("Peer training actor is not a Dungeoneer");
            if (peer.party == null || peer.party.getClass() != LevelPartyObject.class)
                throw new AssertionError("Peer training party subscription did not decode LevelPartyObject");
            if (peer.party.xL() == lan.party.xL() || !peer.currentActors().keySet().equals(Set.of(peer.player.pawnId)) ||
                !lan.currentActors().keySet().equals(Set.of(lan.player.pawnId)))
                throw new AssertionError("Concurrent private training leaked party members or actor baselines");
            System.out.println("PASS: concurrent training entries use distinct scenes, parties, Dungeoneers and fresh baselines");

            // The stock Advanced Training UI calls PartyMarshaller.h(ConfirmMarshaller): method 3, no args.
            FORCED.clear(); var leave = new Confirm(); lan.party.partyService.h(leave);
            await(() -> leave.done && !FORCED.isEmpty());
            if (leave.reason != null) throw new AssertionError("Training party method 3 rejected: " + leave.reason);
            checkForce(1, 1);
            lan.enter(1); lan.entered(); lan.awaitActor();
            System.out.println("PASS: native LevelPartyObject.partyService.h emits method 3 and returns from training");
            travelByWalk(lan, maps, 294, 1526, 1, "town");

            travelByWalk(lan, maps, 445, 3770, 437, "level");
            travelByWalk(lan, maps, 437, 945, 445, "town");
            travelByWalk(lan, maps, 445, 3568, 449, "level");
            travelByWalk(lan, maps, 449, 2812, 445, "town");
            System.out.println("PASS: all six new interior links used their native walking sensors or stock party exit");
        }
    }

    private static void requireScene(SceneCatalog catalog, int id, String kind) throws Exception {
        var map = catalog.scene(id);
        if (!map.kind().equals(kind)) throw new AssertionError("Scene " + id + " has kind " + map.kind() + ", expected " + kind);
    }

    private static void travelByWalk(Endpoint endpoint, SceneCatalog catalog, int sourceId, int triggerId,
            int destinationId, String destinationKind) throws Exception {
        var source = catalog.scene(sourceId);
        var trigger = source.portals().triggers().stream().filter(p -> p.id() == triggerId).findFirst()
            .orElseThrow(() -> new AssertionError("Missing native trigger " + sourceId + "/" + triggerId));
        if (trigger.destination() != destinationId) throw new AssertionError("Wrong native link destination for " + sourceId + "/" + triggerId);
        endpoint.enter(sourceId); endpoint.entered(); endpoint.awaitActor();
        var actor = endpoint.actor();
        if (source.town() ? !(actor instanceof com.threerings.projectx.data.actor.Knight) : !(actor instanceof Dungeoneer))
            throw new AssertionError("Wrong actor type before link " + sourceId + "/" + triggerId + ": " + actor.getClass());
        FORCED.clear();
        float[][] path = route(source, trigger, actor.tz().x, actor.tz().y);
        endpoint.walk(path);
        await(() -> !FORCED.isEmpty()); checkForce(1, destinationId);
        endpoint.enter(destinationId); endpoint.entered(); endpoint.awaitActor();
        if (!endpoint.modelIs(destinationId)) throw new AssertionError("Scene callback loaded the wrong destination");
        if (!catalog.scene(destinationId).kind().equals(destinationKind)) throw new AssertionError("Unexpected destination scene kind");
        if (destinationKind.equals("town") && !(endpoint.place instanceof com.threerings.projectx.town.data.TownSceneObject))
            throw new AssertionError("Town destination did not decode as TownSceneObject");
        if (!destinationKind.equals("town") && endpoint.place.getClass() != ProjectXSceneObject.class)
            throw new AssertionError("Interior destination did not decode as generic ProjectXSceneObject");
        var expected = catalog.scene(destinationId).portals().entrances().get(trigger.entrance());
        var arrival = endpoint.actor();
        if (Math.abs(arrival.tz().x - expected.x()) > .002 || Math.abs(arrival.tz().y - expected.y()) > .002 || arrival.bR(2))
            throw new AssertionError("Wrong tagged arrival or active movement after link " + sourceId + "/" + triggerId + ": " + arrival);
        if (destinationKind.equals("level") || destinationKind.equals("training")) {
            if (!(arrival instanceof Dungeoneer) || endpoint.party == null || endpoint.party.getClass() != LevelPartyObject.class)
                throw new AssertionError("Interior entry did not subscribe native party and Dungeoneer actor");
            int expectedParty = 500 + endpoint.place.xL();
            if (endpoint.party.xL() != expectedParty) throw new AssertionError("Unexpected interior party oid " + endpoint.party.xL());
        }
        System.out.println("PASS: stock walking link " + sourceId + "/" + triggerId + " -> " + destinationId + "/" + trigger.entrance()
            + " with tagged arrival, actor type and fresh baseline");
    }

    private static void checkForce(int zone, int scene) {
        if (FORCED.size() != 1 || FORCED.getFirst()[0] != zone || FORCED.getFirst()[1] != scene)
            throw new AssertionError("Unexpected forced destination: " + FORCED.size());
    }

    private static float[][] route(SceneCatalog.MapScene map, ScenePortals.Trigger target, float x, float y) {
        record Cell(int x, int y) { float wx() { return x * .25f; } float wy() { return y * .25f; } }
        Cell start = new Cell(Math.round(x * 4), Math.round(y * 4));
        var previous = new HashMap<Cell, Cell>(); var pending = new ArrayDeque<Cell>();
        previous.put(start, null); pending.add(start); Cell end = null;
        int[][] directions = {{1,0},{-1,0},{0,1},{0,-1}};
        while (!pending.isEmpty()) {
            Cell here = pending.removeFirst();
            if (interior(target, here.wx(), here.wy())) { end = here; break; }
            for (var d : directions) {
                Cell next = new Cell(here.x() + d[0], here.y() + d[1]);
                if (Math.abs(next.x()) > 320 || Math.abs(next.y()) > 320 || previous.containsKey(next) || !clear(map, target, next.wx(), next.wy())) continue;
                previous.put(next, here); pending.add(next);
            }
        }
        if (end == null) throw new AssertionError("No stock-collision route to " + map.id() + "/" + target.id());
        var path = new ArrayList<Cell>(); for (Cell p = end; p != null; p = previous.get(p)) path.add(p); Collections.reverse(path);
        var points = new ArrayList<float[]>(); float px = x, py = y;
        for (int i = 1; i < path.size();) {
            int far = i;
            for (int j = i; j < path.size(); j++) { var p = path.get(j); if (Math.hypot(p.wx()-px,p.wy()-py) > 20 || !visible(map,target,px,py,p.wx(),p.wy())) break; far = j; }
            var p = path.get(far); points.add(new float[]{p.wx(),p.wy()}); px = p.wx(); py = p.wy(); i = far + 1;
        }
        if (points.isEmpty()) throw new AssertionError("Actor spawned inside portal sensor");
        return points.toArray(float[][]::new);
    }

    private static boolean clear(SceneCatalog.MapScene map, ScenePortals.Trigger target, float x, float y) {
        return map.collision().walkable(x,y) && map.portals().triggers().stream().noneMatch(t -> t != target && t.intersects(x,y));
    }
    private static boolean interior(ScenePortals.Trigger target, float x, float y) {
        // The Training Hall entry is only .75 units deep and the imported stock collision
        // becomes solid one quarter-unit step inside its sensor. Keep a .05-unit margin
        // beyond first actor-radius contact so quarter-grid walking can reach it reliably.
        return target.intersects(x-.05f,y) && target.intersects(x+.05f,y) &&
            target.intersects(x,y-.05f) && target.intersects(x,y+.05f);
    }
    private static boolean visible(SceneCatalog.MapScene map, ScenePortals.Trigger target, float ax, float ay, float bx, float by) {
        int steps = Math.max(1, (int)Math.ceil(Math.hypot(bx-ax,by-ay)/.05));
        for (int i=0;i<=steps;i++) { float f=(float)i/steps; if(!clear(map,target,ax+(bx-ax)*f,ay+(by-ay)*f))return false; }
        return true;
    }

    private static final class Confirm implements A.a {
        boolean done; String reason;
        public void xx() { done = true; }
        public void aT(String value) { reason = value; done = true; }
    }

    private static final class Endpoint implements AutoCloseable {
        final Client client; final PlayerObject player;
        ProjectXSceneObject place; LevelPartyObject party;
        com.threerings.presents.dobj.t<ProjectXSceneObject> placeSubscription;
        com.threerings.presents.dobj.t<LevelPartyObject> partySubscription;
        SceneModel model; final Map<Integer, Actor> actors = new HashMap<>();
        SceneDeltaEvent last; int chatCount; boolean observing = true;

        Endpoint(String host, int port, String key, String username, String knight) throws Exception {
            client = new Client(new ProjectXCredentials(new Name(username), "lan-test-password"), queue);
            client.b(host, new int[]{port}, new int[0]); client.dr(key); client.ds("interior-check"); client.dt("projectx"); client.xg();
            await(client::isLoggedOn); player = (PlayerObject)client.xc(); registerReceiver();
            player.a(new com.threerings.tudey.dobj.a() { public void c(SceneDeltaEvent event) { if (observing) receive(event); } });
            var selected = new Confirm(); ((aU)client.bU(aU.class)).b(new Name(knight), null, selected); await(() -> selected.done);
            if (selected.reason != null) throw new AssertionError("Select " + knight + ": " + selected.reason);
            await(() -> !FORCED.isEmpty());
        }

        private void registerReceiver() throws Exception {
            Class<?> receiver = Class.forName("com.threerings.whirled.zone.a.f");
            Object handler = java.lang.reflect.Proxy.newProxyInstance(receiver.getClassLoader(), new Class[]{receiver}, (proxy, method, values) -> {
                if (method.getName().equals("aF")) FORCED.add(new int[]{(Integer)values[0],(Integer)values[1]}); return null;
            });
            client.xd().a((com.threerings.presents.client.w)Class.forName("com.threerings.whirled.zone.a.a").getConstructor(receiver).newInstance(handler));
        }

        void enter(int sceneId) throws Exception {
            unsubscribe(); model = null;
            var move = new Move(); Class<?> service = Class.forName("com.threerings.whirled.zone.a.g");
            Class<?> listener = Class.forName("com.threerings.whirled.zone.a.g$a");
            Object callback = java.lang.reflect.Proxy.newProxyInstance(listener.getClassLoader(), new Class[]{listener}, (proxy, method, values) -> {
                if (method.getName().equals("aT")) throw new AssertionError(values[0]);
                if (method.getName().equals("a") && values.length == 4 && values[3] instanceof SceneModel found) {
                    move.oid = (Integer)values[0]; move.model = found; move.done = true;
                }
                return null;
            });
            service.getMethod("a", int.class, int.class, int.class, listener).invoke(client.bU(service), 1, sceneId, 0, callback);
            await(() -> move.done); model = move.model;
            if (model.sceneId != sceneId) throw new AssertionError("Zone callback returned wrong scene model");
            placeSubscription = new com.threerings.presents.dobj.t<>() {
                public void a(ProjectXSceneObject object) { place = object; listenChat(object); }
                public void a(int oid, ObjectAccessException error) { throw new AssertionError(error); }
            };
            client.xb().a(move.oid, placeSubscription); await(() -> place != null);
            if (sceneId == 294 && place.getClass() != ProjectXSceneObject.class) throw new AssertionError("Training scene is not generic ProjectXSceneObject");
            if ((sceneId == 437 || sceneId == 449) && place.getClass() != ProjectXSceneObject.class) throw new AssertionError("Level scene is not generic ProjectXSceneObject");
            party = null; partySubscription = null;
            if (sceneId == 294 || sceneId == 437 || sceneId == 449) {
                int partyOid = 500 + move.oid;
                await(() -> player.partyOid == partyOid);
                partySubscription = new com.threerings.presents.dobj.t<>() {
                    public void a(LevelPartyObject object) { party = object; }
                    public void a(int oid, ObjectAccessException error) { throw new AssertionError(error); }
                };
                client.xb().a(partyOid, partySubscription); await(() -> party != null);
            } else {
                await(() -> player.partyOid == 0);
            }
            actors.clear(); last = null;
        }

        void entered() { place.tudeySceneService.adB(); }
        void awaitActor() throws Exception {
            await(() -> last != null && last.aeh() == 0 && actors.containsKey(player.pawnId));
            Actor own = actor();
            if ((model.sceneId == 294 || model.sceneId == 437 || model.sceneId == 449) && !(own instanceof Dungeoneer))
                throw new AssertionError("Interior snapshot actor is not Dungeoneer: " + own.getClass());
        }
        Actor actor() { Actor value = actors.get(player.pawnId); if (value == null) throw new AssertionError("Missing controlled actor"); return value; }
        Map<Integer, Actor> currentActors() { return actors; }
        boolean modelIs(int id) { return model != null && model.sceneId == id; }
        void weaponControls() throws Exception {
            for(String config:List.of("Weapon/Sword/Calibur","Weapon/Handgun/Proto Gun","Weapon/Bomb/Blast Bomb")){
                long itemId=0;for(var item:player.items)if(item.Vx().getName().equals(config)){itemId=item.VA();break;}
                if(itemId==0)throw new AssertionError("Fixture must grant "+config);
                var reply=new Confirm();client.bU(com.threerings.projectx.item.client.L.class).a(itemId,6,reply);await(()->reply.done);
                if(reply.reason!=null)throw new AssertionError("Arsenal equip refused: "+reply.reason);
                await(()->{try{return ((com.threerings.projectx.item.data.Item)field(actor(),"_weapon")).Vx().getName().equals(config);}catch(Exception e){return false;}});
                int start=last.QY()+100;
                place.a(last.QY(),last.QY(),new InputFrame[]{new com.threerings.projectx.dungeon.data.DungeonInputFrame(start,0,0,4,start)});
                if(config.contains("/Bomb/")){
                    await(()->{try{return (int)field(actor(),"_activity")==283;}catch(Exception e){return false;}});
                    int stop=last.QY()+150;place.a(last.QY(),last.QY(),new InputFrame[]{new com.threerings.projectx.dungeon.data.DungeonInputFrame(stop,0,0,0,stop)});
                }
                await(()->{try{int activity=(int)field(actor(),"_activity");return activity>=289&&activity!=291;}catch(Exception e){return false;}});
                if(config.contains("/Handgun/")||config.contains("/Bomb/"))await(()->actors.values().stream().anyMatch(a->!(a instanceof Dungeoneer)));
                System.out.println("PASS: stock Arsenal equips "+config+" and native input produces its authored attack"+(config.contains("/Sword/")?"":" and projectile"));
                int stop=last.QY()+100;place.a(last.QY(),last.QY(),new InputFrame[]{new com.threerings.projectx.dungeon.data.DungeonInputFrame(stop,0,0,0,stop)});
                await(()->{try{return (int)field(actor(),"_activity")==0;}catch(Exception e){return false;}});
            }
        }
        private Object field(Object object,String name)throws Exception{
            for(Class<?> type=object.getClass();type!=null;type=type.getSuperclass())try{var field=type.getDeclaredField(name);field.setAccessible(true);return field.get(object);}catch(NoSuchFieldException ignored){}
            throw new NoSuchFieldException(name);
        }

        private void receive(SceneDeltaEvent event) {
            if (last != null && (event.QY() <= last.QY() || event.aeh() != last.QY())) throw new AssertionError("Delta does not reference previous scene baseline");
            if (event.aeh() == 0) actors.clear(); else if (last == null) throw new AssertionError("Scene delta arrived without a fresh baseline");
            if (event.aej() != null) for (var actor : event.aej()) actors.put(actor.vw(), actor);
            if (event.aek() != null) for (var delta : event.aek()) {
                Actor old = actors.get(delta.vw()); if (old == null) throw new AssertionError("Actor delta has no baseline: " + delta.vw());
                actors.put(delta.vw(), (Actor)delta.apply(old));
            }
            if (event.ael() != null) for (int id : event.ael()) actors.remove(id);
            last = event;
        }
        private void listenChat(ProjectXSceneObject object) {
            object.a(new com.threerings.presents.dobj.l() { public void a(MessageEvent event) {
                if (event.getName().equals("crowd.chat")) chatCount++;
            }});
        }
        void walk(float[][] path) throws Exception {
            for (var waypoint : path) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                while (FORCED.isEmpty()) {
                    Actor pawn = actor(); float dx = waypoint[0] - pawn.tz().x, dy = waypoint[1] - pawn.tz().y;
                    double distance = Math.hypot(dx,dy); if (distance < .025) break;
                    if (System.nanoTime() > deadline) throw new AssertionError("Walk stuck at " + pawn.tz() + " toward " + Arrays.toString(waypoint));
                    float heading = (float)Math.atan2(dy,dx); int time = last.QY();
                    int start = time + 100, duration = Math.max(1, Math.min(80, (int)Math.round(distance * 200)));
                    int stop = start + duration;
                    place.a(time,time,new InputFrame[]{input(start,heading,1), input(stop,heading,0)});
                    await(() -> !FORCED.isEmpty() || last.aef() >= stop && last.QY() >= stop);
                }
                if (!FORCED.isEmpty()) return;
            }
            try { await(() -> !FORCED.isEmpty()); }
            catch (AssertionError timeout) {
                Actor pawn = actor();
                throw new AssertionError("Walking reached its final route waypoint without a zone-force callback; scene=" +
                    model.sceneId + " actorPosition=" + pawn.tz() + " finalWaypoint=" + Arrays.toString(path[path.length-1]), timeout);
            }
        }
        private InputFrame input(int timestamp, float heading, int flags) {
            if (model.sceneId == 294 || model.sceneId == 437 || model.sceneId == 449)
                return new com.threerings.projectx.dungeon.data.DungeonInputFrame(timestamp, heading, heading, flags, 0);
            return new ProjectXInputFrame(timestamp, heading, heading, flags, 0);
        }
        private void unsubscribe() {
            if (place != null && placeSubscription != null) client.xb().b(place.xL(), placeSubscription);
            if (party != null && partySubscription != null) client.xb().b(party.xL(), partySubscription);
            place = null; party = null; placeSubscription = null; partySubscription = null;
        }
        public void close() { observing = false; unsubscribe(); if (client.isLoggedOn()) client.Q(false); }
    }

    private static final class Move { int oid; SceneModel model; boolean done; }
    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean() && System.nanoTime() < end) {
            Runnable task = TASKS.poll(100, TimeUnit.MILLISECONDS); if (task != null) task.run();
        }
        if (!condition.getAsBoolean()) throw new AssertionError("Interior stock fixture timed out");
    }
}
