package org.tortodrone.server.network;

import java.io.IOException;
import java.net.Socket;
import java.util.*;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import org.tortodrone.storage.Database;
import org.tortodrone.world.*;

/** Selected character protocol/controller; authoritative entities live in their scene instance. */
final class WorldSession implements AutoCloseable {
    private static final Map<Integer, WorldSession> ACTIVE = new HashMap<>();
    private static final SceneChat CHAT=new SceneChat();
    private final SceneChat.RateLimit chatRate=new SceneChat.RateLimit();
    private SceneChat.Membership chat;
    private final Socket socket;
    private final CharacterStore characters;
    private final SessionOutput output;
    private final SessionCodec codec;
    private final int oid;
    private final long authRevision;
    private final SessionLog log;
    private final SceneRegistry scenes;
    @FunctionalInterface interface RoleSource { boolean gameMaster()throws IOException; }
    private final RoleSource roles;
    private boolean gameMaster;
    private SceneRegistry.Instance scene;
    private SceneSimulation.Membership membership;
    private SceneSimulation.Snapshot lastSnapshot;
    private final Object publications=new Object();
    private record Publication(SceneRegistry.Instance scene,SceneSimulation.Snapshot snapshot) { }
    private Publication pending;
    private boolean deliveryStopped;
    private Thread delivery;
    private StarterCatalog.Model appearance;
    private Knight knight;
    private WorldProfile profile;
    private int acknowledge, created, reference, lastStamp, sceneGeneration;
    private short ping;
    private WorldStatePoller.Registration persistence;
    private boolean subscribed, entered, closed;
    private Short forcedReceiver;
    private Set<Integer> occupants = Set.of();
    private Map<Integer,Boolean> occupantRoles=Map.of();

    WorldSession(Socket socket, CharacterStore characters, SessionOutput output,
            SessionCodec codec, int oid, long revision, SessionLog log) throws IOException {
        this(socket,characters,output,codec,oid,revision,log,SceneRegistry.system());
    }

    WorldSession(Socket socket, CharacterStore characters, SessionOutput output,
            SessionCodec codec, int oid, long revision, SessionLog log, SceneRegistry scenes) {
        this(socket,characters,output,codec,oid,revision,log,scenes,()->false);
    }

    WorldSession(Socket socket, CharacterStore characters, SessionOutput output,
            SessionCodec codec, int oid, long revision, SessionLog log, SceneRegistry scenes,RoleSource roles) {
        this.socket = socket;
        this.characters = characters;
        this.output = output;
        this.codec = codec;
        this.oid = oid;
        authRevision = revision;
        this.log = log;
        this.scenes = scenes;
        this.roles=roles;
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
        if(scenes.catalog().scenes().isEmpty())throw new IOException("Maps are unavailable; install default maps and restart the server");
        gameMaster=roles.gameMaster();
        // Picker edits/deletes use this same account lock around their online check and save.
        synchronized (characters) {
            var loaded = characters.worldProfile(name, authRevision);
            for(var item:loaded.items())if(!item.starter()) {
                var entry=characters.catalog().require(item.config());
                if(entry.slot()!=item.slot())throw new IOException("Saved item category differs from the deployed catalogue");
            }
            var choice = characters.roster().stream().filter(k -> k.id() == loaded.knightId())
                .findFirst().orElseThrow(() -> new IOException("No such knight"));
            synchronized (ACTIVE) {
                if (ACTIVE.containsKey(loaded.worldId())) throw new IOException("This knight is already online");
                profile = loaded;
                knight = choice;
                scene = scenes.destination(scenes.catalog().entry().id(),loaded.worldId());
                ACTIVE.put(loaded.worldId(), this);
            }
        }
        created = SceneClock.now();
        attach();
        output.send(() -> codec.selected(oid, knight, profile));
        output.send(() -> codec.gameMaster(oid,gameMaster));
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
                leaveChat();
                subscribed=entered=false;sceneGeneration++;occupants=Set.of();occupantRoles=Map.of();lastSnapshot=null;
                reference=acknowledge=0;membership.close();scene=next;created=SceneClock.now();attach();
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
            var before=profile;
            profile = characters.equip(knight.id(), authRevision, item, location.ordinal() == 0 ? null : slot);
            updateAppearance();
            output.send(() -> codec.itemChanges(oid, knight, before,profile));
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
        var snapshot = place.simulation().snapshot();
        synchronized (this) {
            if(scene!=place||closed)throw new IOException("Scene changed during subscription");
            output.send(() -> codec.scene(message, scene, snapshot.actors()));
            leaveChat();
            lastSnapshot = null;
            reference = acknowledge = 0;
            occupants = snapshot.occupants();
            occupantRoles=snapshot.roles();
            subscribed = true;
            entered = false;
            sceneGeneration++;
            membership.pause();
        }
    }

    /** The stock controller sends enteredPlace after attaching its scene-delta listener. */
    synchronized void entered() throws IOException {
        if (!subscribed || closed) throw new IOException("Subscribe to town before entering it");
        if (entered) return;
        entered = true;
        int generation=sceneGeneration,place=scene.oid();
        chat=CHAT.join(place,oid,knight.name(),chatRate,message->deliverChat(place,generation,message));
        lastStamp = SceneClock.now();
        membership.enter();
        persistence=WorldStatePoller.join(this,characters,profile,authRevision,knight.name());
        if(delivery==null)delivery=Thread.ofVirtual().name("SceneDelivery-"+oid).start(this::deliverPublications);
    }

    synchronized void unsubscribe() { leaveChat();subscribed = entered = false; sceneGeneration++;if(membership!=null)membership.pause(); }

    void speak(SessionDecoder.Invocation request)throws IOException {
        SceneChat.Membership sender;
        int place,generation;
        synchronized(this) {
            if(closed||socket.isClosed()||!subscribed||!entered||!ownsScene(request.oid()))throw new SessionDecoder.Rejected("Unauthorized scene chat");
            if(request.service()!=SceneChat.SERVICE||request.method()!=1||request.arguments().size()!=2
                    ||!(request.arguments().get(0) instanceof String)||!(request.arguments().get(1) instanceof Byte))
                throw new SessionDecoder.Rejected("Invalid scene chat request");
            try{characters.worldProfile(knight.name(),authRevision);}catch(CharacterStore.Refused e){throw new IOException("Knight changed; reconnect",e);}
            sender=chat;place=scene.oid();generation=sceneGeneration;
        }
        String text=(String)request.arguments().get(0);
        if(text.stripLeading().startsWith("!")&&roles.gameMaster()) {
            try {
                if(!chatRate.allow(System.nanoTime()))throw new IOException("Please slow down before sending another command.");
                String result=org.tortodrone.server.ItemCommands.chat(SceneChat.validate(text).substring(1),characters.catalog(),
                    (user,name,config,count)->characters.grantItems(user,name,config,count,authRevision));
                feedback(place,generation,result);
            }catch(IOException refused){feedback(place,generation,refused.getMessage());}
            return;
        }
        try {
            if(!CHAT.speak(sender,(String)request.arguments().get(0),(Byte)request.arguments().get(1)))
                feedback(place,generation,"Please slow down before sending another message.");
        }catch(IOException refused){feedback(place,generation,refused.getMessage());}
    }
    private synchronized void feedback(int place,int generation,String reason)throws IOException {
        if(!closed&&subscribed&&entered&&scene.oid()==place&&sceneGeneration==generation)output.send(()->codec.chatFeedback(place,reason));
    }
    /** Crowd ChatService method 2. ListenerMarshaller has failure only, no success response. */
    void broadcast(SessionDecoder.Invocation request)throws IOException {
        var args=request.arguments();
        if(request.oid()!=SessionCodec.INVOCATION_OID||request.service()!=3||request.method()!=2
                ||args.size()!=2||!(args.get(0) instanceof String text)
                ||!(args.get(1) instanceof SessionDecoder.Listener listener)
                ||!listener.type().equals("com.threerings.presents.data.InvocationMarshaller$ListenerMarshaller"))
            throw new SessionDecoder.Rejected("Invalid broadcast request");
        SceneChat.Membership sender;
        try {
            synchronized(this) {
                if(closed||socket.isClosed()||!subscribed||!entered)throw new IOException("Enter a scene before broadcasting");
                if(!roles.gameMaster())throw new IOException("Game Master permission required");
                try{characters.worldProfile(knight.name(),authRevision);}catch(CharacterStore.Refused e){throw new IOException("Knight changed; reconnect",e);}
                sender=chat;
            }
            if(!CHAT.broadcast(sender,text))throw new IOException("Please slow down before sending another message.");
        }catch(IOException refused){output.send(()->codec.invocationFailure(oid,listener.requestId(),refused.getMessage()));}
    }
    void tell(SessionDecoder.Invocation request)throws IOException {
        var args=request.arguments();
        if(request.oid()!=SessionCodec.INVOCATION_OID||request.service()!=3||request.method()!=3
                ||args.size()!=3||!(args.get(0) instanceof String target)||!(args.get(1) instanceof String text)
                ||!(args.get(2) instanceof SessionDecoder.Listener listener)
                ||!listener.type().equals("com.threerings.crowd.chat.data.ChatMarshaller$TellMarshaller"))
            throw new SessionDecoder.Rejected("Invalid tell request");
        SceneChat.Membership sender;
        try {
            synchronized(this) {
                if(closed||socket.isClosed()||!subscribed||!entered)throw new IOException("Enter a scene before sending a tell");
                try{characters.worldProfile(knight.name(),authRevision);}catch(CharacterStore.Refused e){throw new IOException("Knight changed; reconnect",e);}
                sender=chat;
            }
            CHAT.tell(sender,target,text);
            output.send(()->codec.tellSucceeded(oid,listener.requestId()));
        }catch(IOException refused){output.send(()->codec.invocationFailure(oid,listener.requestId(),refused.getMessage()));}
    }
    private synchronized boolean deliverChat(int place,int generation,SceneChat.Message message)throws IOException {
        if(closed||socket.isClosed()||!subscribed||!entered||scene.oid()!=place||sceneGeneration!=generation)return false;
        if(message.personal()) {
            try{characters.worldProfile(knight.name(),authRevision);}catch(CharacterStore.Refused refused){return false;}
        }
        output.send(()->codec.chat(message.personal()?oid:place,message));return true;
    }
    private void leaveChat(){
        if(chat!=null){chat.close();chat=null;}
        if(persistence!=null){persistence.close();persistence=null;}
    }

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
            if (membership.input(frame.timestamp(), frame.rotation(), frame.direction(),
                    frame.flags(), sceneTime, now)) acknowledge = frame.timestamp();
        }
    }

    /** One latest immutable publication, replacing obsolete frames before encoding. */
    private void offer(SceneRegistry.Instance place,SceneSimulation.Snapshot snapshot) {
        synchronized(publications){if(deliveryStopped)return;pending=new Publication(place,snapshot);publications.notifyAll();}
    }
    private void deliverPublications() {
        try {
            while(true) {
                Publication publication;
                synchronized(publications) {
                    while(pending==null&&!deliveryStopped)publications.wait();
                    if(deliveryStopped)return;
                    publication=pending;pending=null;
                }
                publish(publication);
            }
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        catch(IOException|RuntimeException failure) {
            log.debug("Town session closed; "+failure.getClass().getSimpleName());close();
            try{socket.close();}catch(IOException ignored){}
        }
    }
    private synchronized void publish(Publication publication)throws IOException {
        var current=publication.snapshot();int stamp=current.timestamp();
        if(closed||!subscribed||!entered||scene!=publication.scene()||stamp-lastStamp<=0)return;
        var delta=current.delta(lastSnapshot);
        // Subscription already announces occupants; the first reliable delta has no baseline.
        var changedRoles=lastSnapshot==null?current.actors().stream().filter(a->occupantRoles.containsKey(a.id())&&occupantRoles.get(a.id())!=a.gameMaster()).toList():delta.changedRoles();
        if(!changedRoles.isEmpty())output.send(()->codec.occupantRoles(scene.oid(),changedRoles));
        if(!current.occupants().equals(occupants)){output.send(()->codec.occupants(scene.oid(),current.actors()));occupants=current.occupants();}
        occupantRoles=current.roles();
        short elapsed=(short)Math.max(1,Math.min(Short.MAX_VALUE,stamp-lastStamp));
        output.send(()->codec.sceneDelta(oid,scene.oid(),acknowledge,ping,reference,stamp,elapsed,
            delta.added(),delta.updated(),delta.removed(),delta.baseline()));
        lastStamp=reference=stamp;lastSnapshot=current;
    }
    /** Batch metadata is validated against this exact subscription before publication.
     * Changed inventory loads without holding the session or simulation lock. */
    void refreshState(WorldStatePoller.Registration registration,Database.WorldState state)throws IOException {
        WorldProfile before;
        synchronized(this) {
            if(closed||!entered||persistence!=registration)return;
            if(state==null||state.accountId()!=registration.accountId||state.knightId()!=registration.knightId
                    ||state.authRevision()!=registration.authRevision||!state.name().equalsIgnoreCase(registration.name))
                throw new IOException("Knight or account changed; reconnect");
            before=profile;
        }
        WorldProfile fresh=state.revision()>before.revision()?loadProfile(before):before;
        synchronized(this) {
            if(closed||!entered||persistence!=registration)return;
            if(state.gameMaster()!=gameMaster){gameMaster=state.gameMaster();membership.appearance(actorAppearance());output.send(()->codec.gameMaster(oid,gameMaster));}
            // An equip may commit while the read is in flight: never roll back its publication.
            if(fresh.revision()>profile.revision()) {
                var previous=profile;profile=fresh;
                if(fresh.crowns()!=previous.crowns()||fresh.energy()!=previous.energy())output.send(()->codec.balances(oid,profile));
                if(!fresh.items().equals(previous.items())){updateAppearance();output.send(()->codec.itemChanges(oid,knight,previous,profile));}
            }
        }
    }
    private WorldProfile loadProfile(WorldProfile cached)throws IOException {
        try{return characters.worldProfile(knight.name(),authRevision,cached);}
        catch(CharacterStore.Refused refused){throw new IOException("Knight changed; reconnect",refused);}
    }
    synchronized void refreshFailed(WorldStatePoller.Registration registration,Exception failure) {
        if(persistence!=registration||closed)return;
        log.debug("Town session closed; "+failure.getClass().getSimpleName());close();
        try{socket.close();}catch(IOException ignored){}
    }
    private void attach() {
        rebuildAppearance();var place=scene;
        membership=place.simulation().join(actorAppearance(),snapshot->offer(place,snapshot));
    }
    private void rebuildAppearance() {
        var model=StarterCatalog.town(knight,profile,characters.catalog());
        appearance=new StarterCatalog.Model("Character/PC/Default",Map.of("Model",model,"Height",model.arguments().get("Height")));
    }
    private void updateAppearance(){rebuildAppearance();membership.appearance(actorAppearance());}
    private ActorState actorAppearance() {
        return new ActorState(profile.worldId(),oid,knight.name(),256|knight.color(),created,
            scene.map().spawnX(),scene.map().spawnY(),0,0,0,0,created,appearance,gameMaster);
    }

    private static long monotonicTime() { return System.nanoTime() / 1_000_000L; }
    @Override public synchronized void close() {
        leaveChat();
        closed = true;
        subscribed = false;
        if(membership!=null)membership.close();
        synchronized(publications){deliveryStopped=true;pending=null;publications.notifyAll();}
        if (profile != null) synchronized (ACTIVE) { ACTIVE.remove(profile.worldId(), this); }
    }
}
