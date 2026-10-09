package org.tortodrone.world;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** One authoritative clock, movement owner and immutable publication per scene instance.
 * Session callbacks only offer publications to bounded mailboxes; no socket or storage work
 * runs on the simulation thread. Terrain and future world systems belong to this instance. */
public final class SceneSimulation {
    public record Delta(List<ActorState> added, List<ActorState> updated, int[] removed,
            List<ActorState> changedRoles, Map<Integer,ActorState> baseline) {
        public Delta { removed=removed.clone(); }
        @Override public int[] removed(){return removed.length==0?removed:removed.clone();}
    }
    public static final class Snapshot {
        private final int timestamp;
        private final Object identity=new Object(), predecessor;
        private final List<ActorState> actors;
        private final Map<Integer,ActorState> byId;
        private final Set<Integer> occupants;
        private final Map<Integer,Boolean> roles;
        private final Delta common, initial;
        private Snapshot(int stamp, List<ActorState> actors, Snapshot previous) {
            timestamp=stamp;this.actors=List.copyOf(actors);
            var index=new LinkedHashMap<Integer,ActorState>();var permissions=new HashMap<Integer,Boolean>();
            for(var actor:actors){index.put(actor.id(),actor);permissions.put(actor.id(),actor.gameMaster());}
            byId=Collections.unmodifiableMap(index);occupants=byId.keySet();roles=Map.copyOf(permissions);
            predecessor=previous==null?null:previous.identity;
            initial=new Delta(this.actors,List.of(),new int[0],List.of(),Map.of());
            common=previous==null?initial:calculate(previous);
        }
        public int timestamp(){return timestamp;}
        public List<ActorState> actors(){return actors;}
        public Set<Integer> occupants(){return occupants;}
        public Map<Integer,Boolean> roles(){return roles;}
        public Delta delta(Snapshot previous) {
            if(previous==null)return initial;
            return previous.identity==predecessor?common:calculate(previous);
        }
        private Delta calculate(Snapshot previous) {
            var added=new ArrayList<ActorState>();var updated=new ArrayList<ActorState>();
            var changed=new ArrayList<ActorState>();
            for(var actor:actors) {
                var old=previous.byId.get(actor.id());
                if(old==null)added.add(actor);else {
                    // Stock prediction mutates activity on its reference: transmit every actor.
                    updated.add(actor);if(old.gameMaster()!=actor.gameMaster())changed.add(actor);
                }
            }
            int[] removed=previous.byId.keySet().stream().filter(id->!byId.containsKey(id)).mapToInt(Integer::intValue).toArray();
            return new Delta(List.copyOf(added),List.copyOf(updated),removed,List.copyOf(changed),previous.byId);
        }
    }
    public final class Membership implements AutoCloseable {
        private ActorState appearance;
        private final TownMovement movement;
        private final Consumer<Snapshot> publish;
        private boolean entered, detached;
        private Membership(ActorState actor,Consumer<Snapshot> publish) {
            appearance=actor;this.publish=publish;
            movement=new TownMovement(instance.map().collision(),instance.map().spawnX(),instance.map().spawnY());
            movement.resetInput(actor.created());
        }
        public void enter(){synchronized(SceneSimulation.this){check();entered=true;start();}}
        public void pause(){synchronized(SceneSimulation.this){if(detached)return;entered=false;movement.resetInput(SceneClock.now());stopIfIdle();}}
        public void resetInput(){synchronized(SceneSimulation.this){check();movement.resetInput(SceneClock.now());}}
        public boolean input(int stamp,float facing,float heading,int flags,int sceneTime,long now) {
            synchronized(SceneSimulation.this){check();return entered&&movement.accept(stamp,facing,heading,flags,sceneTime,now);}
        }
        public void appearance(ActorState actor) {
            synchronized(SceneSimulation.this){check();if(actor.id()!=appearance.id()||actor.clientOid()!=appearance.clientOid())throw new IllegalArgumentException("Actor ownership changed");appearance=actor;}
        }
        private ActorState actor() {
            var a=appearance;
            return new ActorState(a.id(),a.clientOid(),a.name(),a.color(),a.created(),movement.x(),movement.y(),
                movement.rotation(),movement.direction(),movement.flags(),movement.activity(),movement.activityStarted(),a.config(),a.gameMaster());
        }
        private void check(){if(detached)throw new IllegalStateException("Scene membership is closed");}
        @Override public void close(){synchronized(SceneSimulation.this){if(detached)return;detached=true;members.remove(appearance.id(),this);stopIfIdle();}}
    }
    private final SceneRegistry.Instance instance;
    private final Map<Integer,Membership> members=new LinkedHashMap<>();
    private ScheduledExecutorService timer;
    private Snapshot previous;
    private long tickCount;
    public SceneSimulation(SceneRegistry.Instance instance){this.instance=Objects.requireNonNull(instance);}
    public synchronized Membership join(ActorState actor,Consumer<Snapshot> publish) {
        if(members.containsKey(actor.id()))throw new IllegalStateException("Actor is already in this scene");
        var membership=new Membership(actor,Objects.requireNonNull(publish));members.put(actor.id(),membership);return membership;
    }
    /** Selection/subscription sees the same scene-owned entities without advancing movement. */
    public synchronized Snapshot snapshot(){return capture(SceneClock.now(),null);}
    public synchronized int playerCount(){return members.size();}
    public synchronized long tickCount(){return tickCount;}
    private Snapshot capture(int stamp,Snapshot baseline) {
        return new Snapshot(stamp,members.values().stream().map(Membership::actor).toList(),baseline);
    }
    private void start() {
        if(timer!=null)return;
        timer=Executors.newSingleThreadScheduledExecutor(r->{var thread=new Thread(r,"Scene-"+instance.oid());thread.setDaemon(true);return thread;});
        // Never burst missed ticks. Integration uses actual elapsed scene time.
        timer.scheduleWithFixedDelay(this::tick,50,50,TimeUnit.MILLISECONDS);
    }
    private void stopIfIdle() {
        if(members.values().stream().anyMatch(m->m.entered))return;
        if(timer!=null){timer.shutdownNow();timer=null;}
        previous=null;
    }
    private void tick() {
        Snapshot publication;List<Membership> recipients;
        synchronized(this) {
            if(timer==null)return;
            int stamp=SceneClock.now();long now=System.nanoTime()/1_000_000L;
            if(previous!=null&&stamp-previous.timestamp()<=0)return;
            // All authoritative entities advance before capture. Future AI/combat/dynamic
            // collision stages run here once per instance, independent of observer count.
            for(var member:members.values())if(member.entered)member.movement.advance(stamp,now);
            publication=capture(stamp,previous);previous=publication;tickCount++;
            recipients=members.values().stream().filter(m->m.entered).toList();
        }
        // No simulation lock is held across callbacks, including detach/travel races.
        for(var recipient:recipients)recipient.publish.accept(publication);
    }
}
