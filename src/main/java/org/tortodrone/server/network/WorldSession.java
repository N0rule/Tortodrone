package org.tortodrone.server.network;

import java.io.IOException;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import org.tortodrone.world.*;

/** A selected character's server-controlled town state. Each world ID has one controller. */
final class WorldSession implements AutoCloseable {
    private static final Map<Integer, WorldSession> ACTIVE = new HashMap<>();
    private final Socket socket;
    private final CharacterStore characters;
    private final SessionOutput output;
    private final SessionCodec codec;
    private final int oid;
    private final long authRevision;
    private final SessionLog log;
    private final SceneRegistry scenes;
    private SceneRegistry.Instance scene;
    private TownMovement movement;
    private Map<Integer, ActorState> lastSnapshot = Map.of();
    private Knight knight;
    private WorldProfile profile;
    private int acknowledge, created, reference, lastStamp, sceneGeneration;
    private short ping;
    private long lastPoll;
    private boolean subscribed, entered, closed;
    private Short forcedReceiver;
    private Set<Integer> occupants = Set.of();
    private ScheduledExecutorService ticks;

    WorldSession(Socket socket, CharacterStore characters, SessionOutput output,
            SessionCodec codec, int oid, long revision, SessionLog log) throws IOException {
        this(socket,characters,output,codec,oid,revision,log,SceneRegistry.system());
    }

    WorldSession(Socket socket, CharacterStore characters, SessionOutput output,
            SessionCodec codec, int oid, long revision, SessionLog log, SceneRegistry scenes) {
        this.socket = socket;
        this.characters = characters;
        this.output = output;
        this.codec = codec;
        this.oid = oid;
        authRevision = revision;
        this.log = log;
        this.scenes = scenes;
        lastPoll = monotonicTime();
    }

    synchronized boolean selected() { return knight != null; }
    synchronized boolean ownsScene(int oid) { return selected() && scene.oid()==oid; }

    static boolean active(int accountId, int knightId) {
        synchronized (ACTIVE) {
            return ACTIVE.values().stream().anyMatch(session ->
                session.characters.accountId() == accountId && session.profile.knightId() == knightId);
        }
    }

    synchronized void select(String name, Map<String, Short> receivers)
            throws IOException, CharacterStore.Refused {
        if(closed||socket.isClosed())throw new IOException("Session is closed");
        if (selected()) throw new IOException("A character is already selected; reconnect to change characters");
        // Picker edits/deletes use this same account lock around their online check and save.
        synchronized (characters) {
            var loaded = characters.worldProfile(name, authRevision);
            var choice = characters.roster().stream().filter(k -> k.id() == loaded.knightId())
                .findFirst().orElseThrow(() -> new IOException("No such knight"));
            synchronized (ACTIVE) {
                if (ACTIVE.containsKey(loaded.worldId())) throw new IOException("This knight is already online");
                profile = loaded;
                knight = choice;
                scene = scenes.destination(scenes.catalog().entry().id(),loaded.worldId());
                movement = new TownMovement(scene.map().collision(),scene.map().spawnX(),scene.map().spawnY());
                ACTIVE.put(loaded.worldId(), this);
            }
        }
        created = SceneClock.now();
        movement.resetInput(created);
        output.send(() -> codec.selected(oid, knight, profile));
        receiverChanged(receivers);
        log.info("Character selected; world ID=" + profile.worldId() + "; scene=" + scene.map().id() + "; instance="+scene.index());
    }

    /** Call outside the CharacterStore publication lock to preserve session/account lock order. */
    synchronized void refresh() throws IOException {
        if (selected()) {
            output.send(() -> codec.selected(oid, knight, profile));
            if (subscribed) output.send(() -> codec.sceneLocation(oid,scene));
        }
    }

    synchronized void receiverChanged(Map<String, Short> receivers) throws IOException {
        Short receiver = receivers.get(SceneRegistry.ZONE_RECEIVER);
        if (receiver == null) forcedReceiver = null;
        if (selected() && receiver != null && !receiver.equals(forcedReceiver)) {
            output.send(() -> codec.forceScene(oid, receiver,scene));
            forcedReceiver = receiver;
        }
    }

    synchronized boolean invocation(SessionDecoder.Invocation request) throws IOException {
        if(closed||socket.isClosed())throw new IOException("Session is closed");
        int service = request.service();
        var args = request.arguments();
        if ((service == 4 || service == 5) && request.method() == 1) {
            move(args, service == 5);
            return true;
        }
        if ((service == 10 && request.method() == 7) || (service == 11 && request.method() == 1)) {
            if (args.size() != 2 || !(args.get(1) instanceof SessionDecoder.Listener listener)
                    || !listener.type().endsWith("$ResultMarshaller")
                    || (service == 10 ? !(args.get(0) instanceof String) : !(args.get(0) instanceof Long))) {
                throw new SessionDecoder.Rejected("Invalid town feed request");
            }
            if (selected()) output.send(() -> codec.emptyListResult(oid, listener.requestId()));
            else output.send(() -> codec.invocationFailure(oid, listener.requestId(), "Select a knight first"));
            return true;
        }
        if (service == 9 && request.method() == 9) {
            equip(args);
            return true;
        }
        // Arsenal's equip action uses method 3, distinct from moveItem (method 9).
        if (service == 9 && request.method() == 3) {
            if (args.size() != 3 || !(args.get(0) instanceof Long)
                    || !(args.get(1) instanceof Integer) || !(args.get(2) instanceof SessionDecoder.Listener)) {
                throw new SessionDecoder.Rejected("Invalid equip item request");
            }
            equip(List.of(args.get(0), new SessionDecoder.EnumValue(
                "com.threerings.projectx.item.data.ItemCodes$Location", 1), args.get(1), args.get(2)));
            return true;
        }
        return false;
    }

    private void move(List<Object> args, boolean zone) throws IOException {
        int count = zone ? 4 : 3;
        if (args.size() != count || !(args.get(count - 1) instanceof SessionDecoder.Listener listener)
                || !listener.type().endsWith(zone ? "$ZoneMoveMarshaller" : "$SceneMoveMarshaller")) {
            throw new SessionDecoder.Rejected("Invalid scene move listener");
        }
        String failure = null;
        if (!selected()) failure = "Select a knight first";
        else if (!(args.get(0) instanceof Integer target) || !(args.get(1) instanceof Integer)) {
            failure = "Invalid scene move request";
        }
        if (failure != null) {
            String reason = failure;
            output.send(() -> codec.invocationFailure(oid, listener.requestId(), reason));
            return;
        }
        try {
            int target=zone?(Integer)args.get(1):(Integer)args.get(0);
            var next=scenes.destination(target,profile.worldId());
            if(zone && (!(args.get(2) instanceof Integer) || !args.get(0).equals(next.map().zone())))
                throw new IOException("Scene does not belong to that zone");
            if(next!=scene) {
                subscribed=entered=false;sceneGeneration++;occupants=Set.of();lastSnapshot=Map.of();
                reference=acknowledge=0;scene=next;created=SceneClock.now();
                movement=new TownMovement(scene.map().collision(),scene.map().spawnX(),scene.map().spawnY());
                movement.resetInput(created);
            }
            output.send(() -> codec.sceneMove(oid, listener.requestId(), zone,scene,scenes.catalog()));
            output.send(() -> codec.sceneLocation(oid,scene));
        } catch(IOException error) {
            output.send(() -> codec.invocationFailure(oid,listener.requestId(),error.getMessage()));
        }
    }

    private void equip(List<Object> args) throws IOException {
        if (args.size() != 4 || !(args.get(0) instanceof Long item)
                || !(args.get(1) instanceof SessionDecoder.EnumValue location)
                || !location.type().equals("com.threerings.projectx.item.data.ItemCodes$Location")
                || !(args.get(2) instanceof Integer slot)
                || !(args.get(3) instanceof SessionDecoder.Listener listener)
                || !listener.type().endsWith("$ConfirmMarshaller")) {
            throw new SessionDecoder.Rejected("Invalid equipment request");
        }
        try {
            if (!selected()) throw new IOException("Select a knight first");
            if (location.ordinal() != 0 && location.ordinal() != 1) {
                throw new IOException("Only inventory and equipment moves are available");
            }
            profile = characters.equip(knight.id(), authRevision, item, location.ordinal() == 0 ? null : slot);
            output.send(() -> codec.equipped(oid, knight, profile));
            output.send(() -> codec.invocationConfirmed(oid, listener.requestId()));
        } catch (IOException failure) {
            String reason = failure.getMessage();
            output.send(() -> codec.invocationFailure(oid, listener.requestId(), reason));
        }
    }

    void subscribe(short message) throws IOException {
        if (!selected()) throw new IOException("Select a knight before subscribing to a scene");
        SceneRegistry.Instance place;
        synchronized(this){place=scene;}
        var snapshot = actors(place);
        synchronized (this) {
            if(scene!=place||closed)throw new IOException("Scene changed during subscription");
            output.send(() -> codec.scene(message, scene, snapshot));
            lastSnapshot = Map.of();
            reference = acknowledge = 0;
            occupants = snapshot.stream().map(ActorState::id).collect(Collectors.toUnmodifiableSet());
            subscribed = true;
            entered = false;
            sceneGeneration++;
            movement.resetInput(SceneClock.now());
        }
    }

    /** The stock controller sends enteredPlace after attaching its scene-delta listener. */
    synchronized void entered() throws IOException {
        if (!subscribed || closed) throw new IOException("Subscribe to town before entering it");
        if (entered) return;
        entered = true;
        lastStamp = SceneClock.now();
        if (ticks == null) {
            ticks = Executors.newSingleThreadScheduledExecutor(r -> {
                var thread = new Thread(r, "Town-" + oid);
                thread.setDaemon(true);
                return thread;
            });
            ticks.scheduleAtFixedRate(this::tick, 50, 50, TimeUnit.MILLISECONDS);
        }
    }

    synchronized void unsubscribe() { subscribed = entered = false; sceneGeneration++; }

    synchronized void input(SessionDecoder.Input input) throws IOException {
        if (closed || socket.isClosed() || !subscribed || !entered || input.oid() != scene.oid()) {
            throw new SessionDecoder.Rejected("Unauthorized movement input");
        }
        // TCP delivers every queued snapshot in order, even while scene loading delays input.
        // Client acknowledgements are for unreliable transport; they do not choose our baseline.
        long now = monotonicTime();
        int sceneTime = SceneClock.now();
        ping = (short) Math.max(0, Math.min(Short.MAX_VALUE, (long) sceneTime - input.smoothedTime()));
        for (var frame : input.frames()) {
            if (movement.accept(frame.timestamp(), frame.rotation(), frame.direction(),
                    frame.flags(), sceneTime, now)) acknowledge = frame.timestamp();
        }
    }

    private void tick() {
        try {
            int stamp, generation;
            SceneRegistry.Instance place;
            synchronized (this) {
                if (closed || !subscribed || !entered) return;
                stamp = SceneClock.now();
                // Fixed-rate catch-up can run twice in the same millisecond. The stock reader
                // discards repeated timestamps, so never reference such a discarded snapshot.
                if (stamp - lastStamp <= 0) return;
                generation = sceneGeneration;
                place = scene;
                long now = monotonicTime();
                movement.advance(stamp, now);
                if (now - lastPoll >= 250) {
                    lastPoll = now;
                    var fresh = characters.worldProfile(knight.name(), authRevision);
                    if (fresh.revision() != profile.revision()) {
                        var previous = profile;
                        profile = fresh;
                        if (fresh.crowns() != previous.crowns() || fresh.energy() != previous.energy()) {
                            output.send(() -> codec.balances(oid, profile));
                        }
                        if (!fresh.items().equals(previous.items())) {
                            output.send(() -> codec.equipped(oid, knight, profile));
                        }
                    }
                }
            }
            // Never take another session's lock while holding this session or the ACTIVE lock.
            List<ActorState> current = actors(place);
            synchronized (this) {
                if (closed || !subscribed || !entered || generation != sceneGeneration) return;
                Map<Integer, ActorState> baseline = lastSnapshot;
                List<ActorState> added = new ArrayList<>(), updated = new ArrayList<>();
                var next = new HashSet<Integer>();
                for (var actor : current) {
                    next.add(actor.id());
                    if (baseline.containsKey(actor.id())) updated.add(actor);
                    else added.add(actor);
                }
                int[] removed = baseline.keySet().stream().filter(id -> !next.contains(id)).mapToInt(Integer::intValue).toArray();
                if (!next.equals(occupants)) {
                    output.send(() -> codec.occupants(scene.oid(),current));
                    occupants = Set.copyOf(next);
                }
                Map<Integer, ActorState> referenceActors = baseline;
                short elapsed = (short) Math.max(1, Math.min(Short.MAX_VALUE, stamp - lastStamp));
                output.send(() -> codec.sceneDelta(oid, scene.oid(),acknowledge, ping, reference, stamp, elapsed,
                    added, updated, removed, referenceActors));
                lastStamp = stamp;
                // Advance only after enqueue succeeds. SessionOutput closes on any failure,
                // so a later delta cannot reference a snapshot that was never queued.
                reference = stamp;
                lastSnapshot = current.stream().collect(Collectors.toUnmodifiableMap(ActorState::id, a -> a));
            }
        } catch (IOException | CharacterStore.Refused | RuntimeException failure) {
            log.debug("Town session closed; " + failure.getClass().getSimpleName());
            close();
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private static long monotonicTime() { return System.nanoTime() / 1_000_000L; }

    private static List<ActorState> actors(SceneRegistry.Instance place) {
        List<WorldSession> sessions;
        synchronized (ACTIVE) { sessions = List.copyOf(ACTIVE.values()); }
        List<ActorState> states = new ArrayList<>();
        for (var session : sessions) {
            var state = session.actor(place);
            if (state != null) states.add(state);
        }
        return List.copyOf(states);
    }

    private synchronized ActorState actor(SceneRegistry.Instance place) {
        if (closed || !selected() || scene!=place) return null;
        var model = StarterCatalog.equipped(knight, profile);
        var config = new StarterCatalog.Model("Character/PC/Default",
            Map.of("Model", model, "Height", model.arguments().get("Height")));
        return new ActorState(profile.worldId(), oid, knight.name(), 256 | knight.color(), created,
            movement.x(), movement.y(), movement.rotation(), movement.direction(), movement.flags(),
            movement.activity(), movement.activityStarted(), config);
    }

    @Override public synchronized void close() {
        closed = true;
        subscribed = false;
        if (ticks != null) ticks.shutdownNow();
        if (profile != null) synchronized (ACTIVE) { ACTIVE.remove(profile.worldId(), this); }
    }
}
