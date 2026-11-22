package org.tortodrone.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.core.world.SceneCombatEvent;
import org.tortodrone.core.world.SceneEffect;
import org.tortodrone.core.world.SceneFrame;
import org.tortodrone.core.world.SceneHealth;
import org.tortodrone.core.world.ScenePortals;

/** One immutable per-tick scene state plus the per-recipient delta computed against a
 *  baseline. Snapshots are built by {@link SnapshotBuilder}; the shared encoding cache
 *  only avoids duplicate delta work when several recipients share one baseline. */
public final class SceneSnapshot {
    public record Delta(List<ActorState> added, List<ActorState> updated, int[] removed,
            List<ActorState> changedRoles, Map<Integer,ActorState> baseline) {
        public Delta { removed=removed.clone(); }
        @Override public int[] removed(){return removed.length==0?removed:removed.clone();}
    }
    /** A snapshot's delta inputs together with their shared encoding. */
    public record EncodedDelta(Delta delta,List<SceneEffect> effects,List<SceneCombatEvent> combat,SceneFrame frame) { }
    /** Encode the shared body once; every recipient materializes it with its own symbol codes. */
    @FunctionalInterface public interface DeltaEncoder {
        SceneFrame encode(Delta delta,List<SceneEffect> effects,List<SceneCombatEvent> combat,boolean interior)throws java.io.IOException;
    }
    private record Encoded(EncodedDelta delta,SceneSnapshot baseline,boolean interior) { }

    private final int timestamp;
    private final Object identity=new Object(), predecessor;
    private final List<ActorState> actors;
    private final List<ActorState> players;
    private final List<SceneEffect> effects;
    private final List<SceneCombatEvent> combatEvents;
    private final Map<Integer,ActorState> byId;
    private final Map<Integer,ActorState> byClientOid;
    private final Map<Integer,Integer> clientOids;
    private final Set<Integer> occupants;
    private final Map<Integer,Boolean> roles;
    private final Map<Integer,ScenePortals.Trigger> portals;
    private final Map<Integer,SceneHealth.State> health;
    private final long effectsLastSequence,combatLastSequence;
    private final Delta common, initial;
    /** The last shared encoding, keyed by the recipient baseline and scene kind. */
    private volatile Encoded encoded;

    SceneSnapshot(int stamp, List<ActorState> actors, List<SceneEffect> effects,List<SceneCombatEvent> combatEvents, Map<Integer,ScenePortals.Trigger> portals, Map<Integer,SceneHealth.State> health,SceneSnapshot previous) {
        timestamp=stamp;this.actors=List.copyOf(actors);this.effects=List.copyOf(effects);
        players=this.actors.stream().filter(ActorState::player).toList();
        this.portals=Map.copyOf(portals);
        this.health=Map.copyOf(health);
        this.combatEvents=List.copyOf(combatEvents);
        var index=new LinkedHashMap<Integer,ActorState>();var permissions=new HashMap<Integer,Boolean>();
        var clients=new HashMap<Integer,ActorState>();var clientIds=new HashMap<Integer,Integer>();
        for(var actor:this.actors){index.put(actor.id(),actor);if(actor.player()){
            permissions.put(actor.id(),actor.gameMaster());
            clients.putIfAbsent(actor.clientOid(),actor);
            clientIds.put(actor.id(),actor.clientOid());
        }}
        byId=Collections.unmodifiableMap(index);byClientOid=Map.copyOf(clients);clientOids=Map.copyOf(clientIds);
        occupants=Set.copyOf(permissions.keySet());roles=Map.copyOf(permissions);
        effectsLastSequence=this.effects.stream().mapToLong(SceneEffect::sequence).max().orElse(0);
        combatLastSequence=this.combatEvents.stream().mapToLong(SceneCombatEvent::sequence).max().orElse(0);
        predecessor=previous==null?null:previous.identity;
        initial=new Delta(this.actors,List.of(),new int[0],List.of(),Map.of());
        common=previous==null?initial:calculate(previous);
    }
    public int timestamp(){return timestamp;}
    public List<ActorState> actors(){return actors;}
    public List<ActorState> players(){return players;}
    /** Direct lookups keep per-recipient publication O(1) instead of scanning every player. */
    public ActorState actor(int id){return byId.get(id);}
    public ActorState player(int clientOid){return byClientOid.get(clientOid);}
    public Map<Integer,Integer> clientOids(){return clientOids;}
    public SceneHealth.State health(int actor){return health.get(actor);}
    public List<SceneEffect> effectsSince(SceneSnapshot previous){long last=previous==null?Long.MAX_VALUE:previous.effectsLastSequence;return effects.stream().filter(e->e.sequence()>last).toList();}
    public List<SceneCombatEvent> combatEventsSince(SceneSnapshot previous){long last=previous==null?Long.MAX_VALUE:previous.combatLastSequence;return combatEvents.stream().filter(e->e.sequence()>last).toList();}
    /** Encode the delta once per baseline. Clients that received different baselines (coalesced
     * activity frames) still get an exact delta; the cache only avoids duplicate work. */
    public EncodedDelta encoded(SceneSnapshot baseline,boolean interior,DeltaEncoder encoder)throws java.io.IOException{
        var cached=encoded;
        if(cached!=null&&cached.baseline==baseline&&cached.interior==interior)return cached.delta;
        var delta=delta(baseline);
        var effects=effectsSince(baseline);
        var combat=combatEventsSince(baseline);
        var result=new EncodedDelta(delta,effects,combat,encoder.encode(delta,effects,combat,interior));
        encoded=new Encoded(result,baseline,interior);
        return result;
    }
    public Set<Integer> occupants(){return occupants;}
    public Map<Integer,Boolean> roles(){return roles;}
    public ScenePortals.Trigger portal(int actorId){return portals.get(actorId);}
    /** Preserve these records when a recipient coalesces ordinary movement snapshots. */
    public boolean startsDataActivity(SceneSnapshot previous){
        if(previous==null)return false;
        for(var actor:players){
            var data=actor.sceneKnight()==null?null:actor.sceneKnight().activityData();
            var old=previous.byId.get(actor.id());
            if(data!=null&&old!=null&&(actor.activity()!=old.activity()||actor.activityStarted()!=old.activityStarted()
                    ||!Objects.equals(data,old.sceneKnight()==null?null:old.sceneKnight().activityData())))return true;
        }
        // A dying monster's first record is data-bearing the same way: the stock
        // client's delayed-time ActorHistory needs the activity-2 transition and
        // the destroyed stamp to run the death animation and fire the config's
        // destructionTransient before the removal record arrives. Native deltas
        // every tick, so coalescing must not eat the dying window.
        for(var actor:actors){
            if(actor.player())continue;
            var entity=actor.entity();
            var old=previous.byId.get(actor.id());
            if(entity==null||old==null||old.entity()==null)continue;
            if(actor.activity()==2&&old.activity()!=2)return true;
            if(entity.destroyed()!=Integer.MAX_VALUE&&old.entity().destroyed()==Integer.MAX_VALUE)return true;
        }
        return false;
    }
    public Delta delta(SceneSnapshot previous) {
        if(previous==null)return initial;
        return previous.identity==predecessor?common:calculate(previous);
    }
    private Delta calculate(SceneSnapshot previous) {
        var added=new ArrayList<ActorState>();var updated=new ArrayList<ActorState>();
        var changed=new ArrayList<ActorState>();
        for(var actor:actors) {
            var old=previous.byId.get(actor.id());
            if(old==null||actor.player()&&(old.clientOid()!=actor.clientOid()||old.created()!=actor.created()))added.add(actor);else {
                // Stock prediction mutates activity on its reference: transmit every actor.
                if(actor.player()||!actor.equals(old))updated.add(actor);if(actor.player()&&(!old.name().equals(actor.name())||old.gameMaster()!=actor.gameMaster()||!Objects.equals(old.sceneKnight()==null?null:old.sceneKnight().progress(),actor.sceneKnight()==null?null:actor.sceneKnight().progress())))changed.add(actor);
            }
        }
        int[] removed=previous.byId.keySet().stream().filter(id->!byId.containsKey(id)).mapToInt(Integer::intValue).toArray();
        return new Delta(List.copyOf(added),List.copyOf(updated),removed,List.copyOf(changed),previous.byId);
    }
}
