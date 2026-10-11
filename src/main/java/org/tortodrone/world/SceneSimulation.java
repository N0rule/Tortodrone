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
        private final List<SceneEffect> effects;
        private final Map<Integer,ActorState> byId;
        private final Set<Integer> occupants;
        private final Map<Integer,Boolean> roles;
        private final Map<Integer,ScenePortals.Trigger> portals;
        private final Delta common, initial;
        private Snapshot(int stamp, List<ActorState> actors, List<SceneEffect> effects, Map<Integer,ScenePortals.Trigger> portals, Snapshot previous) {
            timestamp=stamp;this.actors=List.copyOf(actors);this.effects=List.copyOf(effects);
            this.portals=Map.copyOf(portals);
            var index=new LinkedHashMap<Integer,ActorState>();var permissions=new HashMap<Integer,Boolean>();
            for(var actor:actors){index.put(actor.id(),actor);if(actor.projectile()==null)permissions.put(actor.id(),actor.gameMaster());}
            byId=Collections.unmodifiableMap(index);occupants=Set.copyOf(permissions.keySet());roles=Map.copyOf(permissions);
            predecessor=previous==null?null:previous.identity;
            initial=new Delta(this.actors,List.of(),new int[0],List.of(),Map.of());
            common=previous==null?initial:calculate(previous);
        }
        public int timestamp(){return timestamp;}
        public List<ActorState> actors(){return actors;}
        public List<ActorState> players(){return actors.stream().filter(a->a.projectile()==null).toList();}
        public List<SceneEffect> effectsSince(Snapshot previous){long last=previous==null?Long.MAX_VALUE:previous.effects.stream().mapToLong(SceneEffect::sequence).max().orElse(0);return effects.stream().filter(e->e.sequence()>last).toList();}
        public Set<Integer> occupants(){return occupants;}
        public Map<Integer,Boolean> roles(){return roles;}
        public ScenePortals.Trigger portal(int actorId){return portals.get(actorId);}
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
                    if(actor.projectile()==null||!actor.equals(old))updated.add(actor);if(actor.projectile()==null&&old.gameMaster()!=actor.gameMaster())changed.add(actor);
                }
            }
            int[] removed=previous.byId.keySet().stream().filter(id->!byId.containsKey(id)).mapToInt(Integer::intValue).toArray();
            return new Delta(List.copyOf(added),List.copyOf(updated),removed,List.copyOf(changed),previous.byId);
        }
    }
    public final class Membership implements AutoCloseable {
        private ActorState appearance;
        private final TownMovement movement;
        private KnightCombat combat;
        private final Consumer<Snapshot> publish;
        private boolean entered, detached;
        private ScenePortals.Trigger portal;
        private Membership(ActorState actor,Consumer<Snapshot> publish) {
            appearance=actor;this.publish=publish;
            movement=new TownMovement(instance.map().collision(),actor.x(),actor.y(),actor.rotation(),instance.map().portals());
            movement.resetInput(actor.created());
        }
        public void enter(){synchronized(SceneSimulation.this){check();entered=true;start();}}
        public void pause(){synchronized(SceneSimulation.this){if(detached)return;entered=false;weapons.cancel(appearance.id());movement.resetInput(SceneClock.now());stopIfIdle();}}
        public void resetInput(){synchronized(SceneSimulation.this){check();weapons.cancelAttacks(appearance.id());movement.resetInput(SceneClock.now());}}
        public boolean input(int stamp,float facing,float heading,int flags,int sceneTime,long now) {
            return input(stamp,facing,heading,flags,stamp,sceneTime,now);
        }
        public boolean input(int stamp,float facing,float heading,int flags,int actionChanged,int sceneTime,long now) {
            synchronized(SceneSimulation.this){check();return entered&&portal==null&&movement.accept(stamp,facing,heading,flags,actionChanged,sceneTime,now);}
        }
        public void equipment(org.tortodrone.character.WorldProfile profile) {
            synchronized(SceneSimulation.this){check();if(combat!=null){var before=combat.state().weapon();combat.equipment(profile);
                if(!Objects.equals(before,combat.state().weapon()))weapons.cancelAttacks(appearance.id());}}
        }
        public void combat(WeaponCatalog catalog,org.tortodrone.character.WorldProfile profile) {
            combat(catalog,EquipmentCatalog.empty(),profile);
        }
        public void combat(WeaponCatalog catalog,EquipmentCatalog modifiers,org.tortodrone.character.WorldProfile profile) {
            synchronized(SceneSimulation.this){check();if(instance.map().town())throw new IllegalStateException("Town Knights are peaceful");
                combat=new KnightCombat(catalog,modifiers);combat.equipment(profile);combat.attacks(attack->weapons.attack(appearance.id(),attack));movement.combat(combat);}
        }
        public boolean sit(int sceneTime,long now) {
            synchronized(SceneSimulation.this){check();return entered&&portal==null&&movement.sit(sceneTime,now);}
        }
        public boolean emote(PlayerEmotes.Descriptor emote,int sceneTime,long now) {
            synchronized(SceneSimulation.this){check();return entered&&portal==null&&movement.emote(emote,sceneTime,now);}
        }
        public void appearance(ActorState actor) {
            synchronized(SceneSimulation.this){check();if(actor.id()!=appearance.id()||actor.clientOid()!=appearance.clientOid())throw new IllegalArgumentException("Actor ownership changed");appearance=actor;}
        }
        private ActorState actor() {
            var a=appearance;
            return new ActorState(a.id(),a.clientOid(),a.name(),a.color(),a.created(),movement.x(),movement.y(),
                movement.rotation(),movement.direction(),movement.flags(),movement.activity(),movement.activityStarted(),a.config(),a.gameMaster(),movement.combatState());
        }
        private void check(){if(detached)throw new IllegalStateException("Scene membership is closed");}
        @Override public void close(){synchronized(SceneSimulation.this){if(detached)return;detached=true;weapons.cancel(appearance.id());members.remove(appearance.id(),this);stopIfIdle();}}
    }
    private final SceneRegistry.Instance instance;
    private final Map<Integer,Membership> members=new LinkedHashMap<>();
    private final SceneWeapons weapons;
    public synchronized void fires(FireCatalog catalog){weapons.catalog(catalog);}
    private ScheduledExecutorService timer;
    private Snapshot previous;
    private long tickCount;
    public SceneSimulation(SceneRegistry.Instance instance){this.instance=Objects.requireNonNull(instance);weapons=new SceneWeapons(instance.map().collision());}
    public synchronized Membership join(ActorState actor,Consumer<Snapshot> publish) {
        if(members.containsKey(actor.id()))throw new IllegalStateException("Actor is already in this scene");
        var membership=new Membership(actor,Objects.requireNonNull(publish));members.put(actor.id(),membership);return membership;
    }
    /** Selection/subscription sees the same scene-owned entities without advancing movement. */
    public synchronized Snapshot snapshot(){return capture(SceneClock.now(),null);}
    public synchronized int playerCount(){return members.size();}
    public synchronized long tickCount(){return tickCount;}
    private Snapshot capture(int stamp,Snapshot baseline) {
        var portals=new HashMap<Integer,ScenePortals.Trigger>();
        for(var member:members.values())if(member.portal!=null)portals.put(member.appearance.id(),member.portal);
        var actors=new ArrayList<ActorState>();members.values().forEach(m->actors.add(m.actor()));actors.addAll(weapons.actors());
        return new Snapshot(stamp,actors,weapons.effects(),portals,baseline);
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
            for(var member:members.values())if(member.entered&&member.portal==null) {
                member.movement.advance(stamp,now);
                member.portal=member.movement.portal();
            }
            weapons.advance(stamp,members.values().stream().filter(m->m.entered).map(Membership::actor).toList());
            publication=capture(stamp,previous);previous=publication;tickCount++;
            recipients=members.values().stream().filter(m->m.entered).toList();
        }
        // No simulation lock is held across callbacks, including detach/travel races.
        for(var recipient:recipients)recipient.publish.accept(publication);
    }
}
