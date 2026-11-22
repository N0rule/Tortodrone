package org.tortodrone.world;
import org.tortodrone.core.world.SceneClock;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.tortodrone.core.character.Knight;
import org.tortodrone.core.character.WorldProfile;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.character.KnightProgress;
import org.tortodrone.core.character.KnightObjective;
import org.tortodrone.core.character.KnightMemory;
import org.tortodrone.core.character.AnimatedItems;
import org.tortodrone.core.world.SceneConversation;
import org.tortodrone.core.world.FireCatalog;
import org.tortodrone.core.town.TownState;
import org.tortodrone.core.town.SpriteRules;
import org.tortodrone.core.town.TownCatalog;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.core.world.EquipmentCatalog;
import org.tortodrone.core.world.GearStats;
import org.tortodrone.core.world.KnightCombat;
import org.tortodrone.core.world.PlayerEmotes;
import org.tortodrone.core.world.SceneCombatEvent;
import org.tortodrone.core.world.SceneEffect;
import org.tortodrone.core.world.SceneFrame;
import org.tortodrone.core.world.FloorAdvanceRequest;
import org.tortodrone.core.world.SceneHealth;
import org.tortodrone.core.world.SceneKnightState;
import org.tortodrone.core.world.ScenePetState;
import org.tortodrone.core.world.SceneStatus;
import org.tortodrone.core.world.SceneContent;
import org.tortodrone.core.world.SceneDamage;
import org.tortodrone.core.world.SceneElevator;
import org.tortodrone.core.world.ScenePortals;
import org.tortodrone.core.world.WeaponCatalog;
import org.tortodrone.core.world.InputBits;
import org.tortodrone.core.party.PartyView;

/** One authoritative clock, movement owner and immutable publication per scene instance.
 * Session callbacks only offer publications to bounded mailboxes; no socket or storage work
 * runs on the simulation thread. Terrain and future world systems belong to this instance. */
public final class SceneSimulation {
    /** Native Dungeoneer TIER_ENABLED publication flag (256): served content has no gated subtown
     * segment, so live captures show it set on every Character/PC/Level pawn in every dungeon. */
    public static final int TIER_ENABLED_FLAG = 256;
    /** Native Knight.HIDDEN_FOR_DIALOG (32): published while the Cloak status
     * condition is active, hiding the knight from other clients' scene views.
     * Same value as Monster.CAMO, but this is the Knight/KnightData namespace. */
    public static final int HIDDEN_FOR_DIALOG_FLAG = 32;
    @FunctionalInterface public interface RevivePayment<T>{T commit()throws java.io.IOException;}
    /** Run on the requesting session thread. No network I/O occurs under this lock.
     * Holding the scene through commit makes departure and competing revives mutually
     * exclusive with payment; the health/effect change is published only after commit. */
    public synchronized <T> T revive(SceneMembership payer,int targetWorld,RevivePayment<T> payment)throws java.io.IOException {
        var target=members.get(targetWorld);
        if(payer==null||members.get(payer.appearance.id())!=payer||!payer.entered||!payer.portalOpen()
                ||target==null||!target.entered||!target.portalOpen()||target.revivePending
                ||target!=payer&&!payer.health.alive()||!target.health.canSparkRevive())throw new java.io.IOException("m.no_revive");
        target.revivePending=true;
        try{
            T result=payment.commit();int stamp=SceneClock.now();
            target.health.revive(stamp);target.movement.resurrect(stamp);
            weapons.effect(new org.tortodrone.core.character.StarterCatalog.Model("Player/Spark Rez",Map.of()),target.actor(),stamp);
            entities.reviveImpulse(target.actor(),stamp);
            return result;
        }finally{target.revivePending=false;}
    }
    org.tortodrone.core.town.TownCatalog town=org.tortodrone.core.town.TownCatalog.empty();
    final String pickupEpoch=UUID.randomUUID().toString();
    public synchronized void town(org.tortodrone.core.town.TownCatalog rules){town=Objects.requireNonNull(rules);}
    /** A null definition is the native ClearDialog operation, delivered on the session thread. */
    public record ConversationRequest(SceneConversation definition,ActorState source,int timestamp) { }
    public record MemoryRequest(org.tortodrone.core.character.KnightMemory operation,ActorState source,int timestamp) { }
    public record ObjectiveRequest(org.tortodrone.core.character.KnightObjective operation,ActorState source,int timestamp) { }
    public record StoryRequests(List<MemoryRequest> memories,List<ObjectiveRequest> objectives) { }
    /** SceneMembership-owned native NextFloor intent; this is not a completion receipt or travel grant. */

    final SceneRegistry.Instance instance;
    final Map<Integer,SceneMembership> members=new LinkedHashMap<>();
    final PortalSystem portals=new PortalSystem();
    /** Battle-sprite actors use this fixed id base so their ids never collide with
     * client-visible pawn or entity oid ranges observed on the wire; server-chosen. */
    private static final int PET_ACTOR_ID_BASE = 1_700_000_000;
    /** Carried actors released by a drop land after the 400 ms tossed-flight window. */
    private static final int DROP_FLIGHT_MILLIS = 400;
    int nextPetId=PET_ACTOR_ID_BASE;
    FireCatalog petFires=FireCatalog.empty();
    public synchronized void petFires(FireCatalog catalog){petFires=Objects.requireNonNull(catalog);}
    final SceneWeapons weapons;
    final SceneEntities entities;
    /** Native `PartyObject.visited` reveal keys: packed tile coords, oldest first. */
    private final LinkedHashSet<Integer> visited=new LinkedHashSet<>();
    private static final int MAX_VISITED=8192;
    public synchronized java.util.Set<Integer> visited(){return java.util.Set.copyOf(visited);}
    void recordVisited(float x,float y){
        int packed=((int)Math.floor(x)<<16)|((int)Math.floor(y)&0xffff);
        if(visited.add(packed)&&visited.size()>MAX_VISITED){var oldest=visited.iterator();if(oldest.hasNext()){oldest.next();oldest.remove();}}
    }
    private final ArrayDeque<SceneCombatEvent> combatEvents=new ArrayDeque<>();
    private long combatSequence;
    void feedback(SceneCombatEvent event){
        if(combatEvents.size()>=1024)combatEvents.removeFirst();
        combatEvents.addLast(new SceneCombatEvent(++combatSequence,event.timestamp(),event.actor(),event.amount(),event.source(),event.x(),event.y(),event.rotation(),event.performance()));
    }
    public synchronized void fires(FireCatalog catalog){weapons.catalog(catalog);}
    /** Set while this instance owns a scheduler lane; never a second concurrent tick. */
    private volatile boolean scheduled;
    private volatile Consumer<SceneSimulation> failureListener;
    private Throwable tickFailure;
    private SceneSnapshot previous;
    private long tickCount;
    private int lastMetricLog;
    final List<SceneContent.Entry> arsenalZones;
    public synchronized boolean placement(float x,float y){
        var map=instance.map();
        return Float.isFinite(x)&&Float.isFinite(y)&&map.collision().walkable(x,y)
            &&(map.staticCollision().isEmpty()||map.staticCollision().floorSupported(x,y)
                &&!map.staticCollision().penetration(Math.round(map.content().stats().getOrDefault("player.collision-mask",SceneContent.DEFAULT_PLAYER_COLLISION_MASK)),x,y,ScenePortals.PAWN_RADIUS).present())
            &&!entities.penetration(x,y,0).present()
            &&map.portals().triggers().stream().noneMatch(t->t.intersects(x,y));
    }
    /** Authorized GM spawn; creation stays under the simulation lock and never does I/O. */
    public synchronized int spawnMonsters(String reference,float x,float y,float rotation,int count,float radius)throws java.io.IOException {
        return entities.spawnMonsters(reference,x,y,rotation,count,radius,SceneClock.now());
    }
    public SceneSimulation(SceneRegistry.Instance instance){this.instance=Objects.requireNonNull(instance);
        arsenalZones=instance.map().content().entries().stream().filter(e->e.spec().flag("arsenal-zone",false)).toList();
        entities=new SceneEntities(instance.map().content(),instance.map().collision(),instance.map().staticCollision(),new Random(),true,SceneClock.now(),instance.map().monsters());
        // Tutorial instances refuse every party change, so party rewards have one recipient.
        entities.soloRewards(instance.tutorial()!=null);
        weapons=new SceneWeapons(instance.map().collision());weapons.staticCollision(instance.map().staticCollision());weapons.targets(entities);
        var gearTags=GearStats.damageTags(instance.map().content().stats());
        weapons.damageModifiers((owner,attack)->{var member=members.get(owner);
            return member==null||member.loadout.combat==null||attack.activity()<KnightCombat.INCOMPLETE?GearStats.Damage.NONE:
                GearStats.damage(instance.map().content().stats(),member.loadout.combatProfile,member.loadout.combat.state().weapon(),town,gearTags);});
        entities.feedback(this::feedback);
        entities.playerActive(id->{var member=members.get(id);return member!=null&&member.entered&&member.health.alive()&&member.movement.activity()!=TownMovement.RESURRECT;});
        entities.pickupEligible((id,spec)->{var member=members.get(id);return member!=null&&member.canPickup(spec);});
        entities.playerTargetable(id->{var member=members.get(id);return member!=null&&member.health.status().modifiers().canBeTargeted();});
        entities.playerImpulse((id,impulse)->{var member=members.get(id);if(member!=null&&member.entered&&member.health.alive()&&member.loadout.combat!=null)member.loadout.combat.impulse(impulse);});
        entities.playerHits(hit->{var member=members.get(hit.actor());if(member!=null&&member.entered){
            var result=member.health.hit(hit.damage(),hit.timestamp(),hit.multiplier(),member.loadout.combat);
            // A held charge is never interruptable natively; post-hit invulnerability still applies.
            if(result.react()&&member.health.alive()&&!(member.loadout.combat!=null&&member.loadout.combat.chargeHit(hit.timestamp()))){
                weapons.cancelAttacks(hit.actor());
                if(member.lift!=0){entities.dropLift(member.lift,member.actor(),hit.timestamp());member.lift=member.dropAt=0;}
                member.movement.hurt(hit.timestamp());
            }
            // Invulnerable and fully absorbed hits leave the native summary empty: no push.
            if(result.amount()>0&&hit.impulse()!=null&&member.health.alive()&&member.loadout.combat!=null)member.loadout.combat.impulse(hit.impulse());
            member.updateLife(hit.timestamp());
            member.movement.status(member.health.status().modifiers());
            // The native knight post carries a literal zero source: no hit on a knight says who
            // dealt it, so the client's own-pawn delegate owns the whole display.
            if(result.amount()>0)feedback(new SceneCombatEvent(0,hit.timestamp(),hit.actor(),result.feedback(),0,member.movement.x(),member.movement.y(),member.movement.rotation(),"NORMAL"));
        }});
        entities.conversations((definition,source,stamp,target)->{
            for(var member:members.values())if(member.entered&&(target==0||target==member.appearance.id()))member.pendingConversation=new ConversationRequest(definition,source,stamp);
            return true;
        });
        entities.dialogClears((target,stamp)->{
            for(var member:members.values())if(member.entered&&(target==0||target==member.appearance.id()))member.pendingConversation=new ConversationRequest(null,null,stamp);
            return true;
        });
        entities.floorAdvances((player,source,stamp)->{
            var member=members.get(player.id());
            if(member==null||!member.entered||member.detached||!member.health.alive()||!member.portalOpen()||!instance.map().kind().equals("level"))return false;
            if(member.activeFloorAdvance==null){
                var request=new FloorAdvanceRequest(++member.floorSequence,source,stamp);
                member.activeFloorAdvance=member.pendingFloorAdvance=request;
            }
            return true;
        });
        entities.elevators(new SceneEntities.ElevatorControl(){
            private boolean allowed(SceneMembership member,SceneEntities.ElevatorTrip trip){
                return member!=null&&!member.detached&&member.entered&&member.health.alive()&&member.portalOpen()
                    &&member.movement.activity()!=TownMovement.RESURRECT&&(member.elevatorSettled&&member.elevatorTrip==trip||member.elevatorEntry.equals(trip.source().entity().key())&&member.elevatorMembers.equals(new HashSet<>(trip.members())));
            }
            @Override public boolean begin(SceneEntities.ElevatorTrip trip,int stamp){
                if(!instance.map().kind().equals("level"))return false;
                for(int id:trip.members()){
                    var member=members.get(id);if(!allowed(member,trip)||member.elevatorTrip!=null||member.movement.inTransit()||member.activeFloorAdvance!=null)return false;
                }
                for(int id:trip.members()){
                    var member=members.get(id);member.elevatorTrip=trip;member.elevatorSettled=member.elevatorPayoutSettled=false;
                    if(!member.movement.transit(true,stamp)){cancel(trip,stamp);return false;}
                    weapons.cancelAttacks(id);
                    if(member.lift!=0){entities.dropLift(member.lift,member.actor(),stamp);member.lift=member.dropAt=0;}
                }
                return true;
            }
            @Override public boolean valid(SceneEntities.ElevatorTrip trip){
                var living=members.values().stream().filter(m->!m.detached&&m.entered&&m.health.alive()&&m.movement.activity()!=TownMovement.RESURRECT).map(m->m.appearance.id()).collect(java.util.stream.Collectors.toSet());
                return living.equals(new HashSet<>(trip.members()))&&trip.members().stream().allMatch(id->{var member=members.get(id);return allowed(member,trip)&&member.elevatorTrip==trip&&member.movement.inTransit();});
            }
            @Override public boolean advance(SceneEntities.ElevatorTrip trip,int stamp){
                if(!valid(trip)||trip.members().stream().anyMatch(id->members.get(id).activeFloorAdvance!=null))return false;
                for(int id:trip.members()){
                    var member=members.get(id);var request=new FloorAdvanceRequest(++member.floorSequence,trip.source(),stamp);
                    member.activeFloorAdvance=member.pendingFloorAdvance=request;
                }
                return true;
            }
            @Override public void cancel(SceneEntities.ElevatorTrip trip,int stamp){
                for(int id:trip.members()){
                    var member=members.get(id);if(member==null||member.elevatorTrip!=trip)continue;
                    member.elevatorTrip=null;member.elevatorSettled=member.elevatorPayoutSettled=false;member.movement.transit(false,stamp);
                    if(member.activeFloorAdvance!=null&&member.activeFloorAdvance.source()==trip.source())member.activeFloorAdvance=member.pendingFloorAdvance=null;
                }
            }
        });
        entities.memories((player,operation,source,stamp)->{
            var member=members.get(player.id());
            if(member==null||!member.entered||member.detached||!member.health.alive()||!member.portalOpen()||member.pendingMemories.size()>=256)return false;
            member.pendingMemories.addLast(new MemoryRequest(operation,source,stamp));return true;
        });
        entities.objectives((player,operation,source,stamp)->{
            var member=members.get(player.id());
            if(member==null||!member.entered||member.detached||!member.health.alive()||!member.portalOpen()||member.pendingObjectives.size()>=256)return false;
            member.pendingObjectives.addLast(new ObjectiveRequest(operation,source,stamp));return true;
        });
        entities.effects(weapons::effect);
        entities.pawnStatuses((id,operation,stamp)->{
            var member=members.get(id);if(member==null||!member.entered)return false;
            if(operation.clear()){member.health.clearStatus(operation.clearMask());return true;}
            return member.health.applyCondition(operation.condition(),operation.data(),stamp);
        });
        entities.playerHeal((actor,amount)->{var member=members.get(actor);return member!=null&&member.entered&&member.heal(amount,SceneClock.now());});
        weapons.petActions(action->{var member=members.get(action.owner());if(member==null||!member.entered)return;
            switch(action.kind()){
                case HEAL->member.health.heal(((Number)action.config().arguments().getOrDefault("current",0)).intValue());
                case CLEAR_STATUS->member.health.clearStatus(((Number)action.config().arguments().getOrDefault("mask",0)).intValue());
                case APPLY_STATUS->{if(action.config()!=null&&action.config().arguments().get("reference") instanceof org.tortodrone.core.character.StarterCatalog.Model ref){
                    Object data=action.config().arguments().get("data");float radius=((Number)action.config().arguments().getOrDefault("radius",0)).floatValue();
                    for(var target:members.values())if(target.entered&&(target==member||radius>0&&Math.hypot(target.movement.x()-member.movement.x(),target.movement.y()-member.movement.y())<=radius)){
                        target.health.condition(ref,data,action.timestamp());if(data instanceof FireCatalog.DataValue value&&value.type().endsWith("$SpriteShield"))target.loadout.auraOwner=member.appearance.id();
                    }
                }}
                case START_SPRITE_SHIELD->{
                    member.pet.startShield(((Number)action.config().arguments().getOrDefault("health",0)).intValue(),(String)action.config().arguments().get("power"));
                    var defenses=new HashMap<String,String>();Object defense=action.config().arguments().get("defense");
                    for(String type:List.of("normal","piercing","elemental","shadow")){
                        Object scale=SceneDamage.field(defense,type);
                        if(scale instanceof org.tortodrone.core.character.StarterCatalog.Model model)defenses.put("defense."+type,Float.toString(instance.map().content().scales().getOrDefault(model.name(),0f)));
                        else if(scale instanceof Number n)defenses.put("defense."+type,n.toString());
                    }
                    member.pet.shieldDefense(new SceneContent.Spec(defenses));
                }
                case END_SPRITE_SHIELD->member.pet.endShield();
                default->{}
            }
        });
        entities.attacks((actor,action)->weapons.fire(action,actor,SceneClock.now()));}
    public record InteractionSource(ActorState actor,SceneContent.Spec specification) { }
    public synchronized InteractionSource interactionSource(int id){return new InteractionSource(entities.actor(id),entities.specification(id));}
    public synchronized ActorState station(String kind){return entities.station(kind);}
    public synchronized SceneMembership join(ActorState actor,Consumer<SceneSnapshot> publish) {
        return join(actor,publish,null);
    }
    public synchronized SceneMembership join(ActorState actor,Consumer<SceneSnapshot> publish,Runnable sceneFailure) {
        if(members.containsKey(actor.id()))throw new IllegalStateException("Actor is already in this scene");
        if(members.values().stream().anyMatch(m->m.appearance.clientOid()==actor.clientOid()))throw new IllegalStateException("Connection already owns a scene actor");
        var membership=new SceneMembership(this,actor,Objects.requireNonNull(publish));membership.onSceneFailure(sceneFailure);members.put(actor.id(),membership);return membership;
    }
    /** Selection/subscription sees the same scene-owned entities without advancing movement. */
    public synchronized SceneSnapshot snapshot(){return capture(SceneClock.now(),null);}
    /** Read one existing membership without ticking the scene or creating an instance. */
    public record Companion(int selectedSprite,ScenePetState deployed,int observedAt) { }
    public synchronized Companion companion(int knight) {
        var member=members.get(knight);
        if(member==null||member.detached)return null;
        var actor=member.pet.actor();
        return new Companion(member.loadout.sprite==null?0:member.loadout.sprite.id(),actor==null?null:actor.pet(),SceneClock.now());
    }
    public synchronized int playerCount(){return members.size();}
    public synchronized ActorState defaultEntrance(){return entities.defaultEntrance();}
    public synchronized long tickCount(){return tickCount;}
    /** Read-only monster/path counters for this scene instance. */
    public synchronized MonsterMetrics.Snapshot metrics(){return entities.metrics();}
    /** Scheduled executors otherwise hide exceptions in their cancelled future. */
    public synchronized Throwable failure(){return tickFailure;}
    private SceneSnapshot capture(int stamp,SceneSnapshot baseline) {
        return SnapshotBuilder.build(stamp,members.values(),entities,weapons,combatEvents,baseline);
    }
    /** Install the owner callback for tick failures; the registry recycles the instance. */
    public void onFailure(Consumer<SceneSimulation> listener){failureListener=listener;}
    void start() {
        if(scheduled)return;
        tickFailure=null;
        SceneScheduler.shared().start(instance.oid(),this::scheduledTick);
        scheduled=true;
    }
    /** One serial lane callback: a failure stops this scene without touching any other lane. */
    private void scheduledTick() {
        try{tick();}
        catch(RuntimeException|Error failure){
            synchronized(this){tickFailure=failure;scheduled=false;}
            SceneScheduler.shared().stop(instance.oid());
            System.getLogger(SceneSimulation.class.getName()).log(System.Logger.Level.ERROR,
                "Scene simulation stopped: template="+instance.map().id()+", instance="+instance.oid(),failure);
            var listener=failureListener;
            if(listener!=null)try{listener.accept(this);}catch(RuntimeException ignored){}
            List<Runnable> evictions;
            synchronized(this){evictions=members.values().stream().map(m->m.sceneFailure).filter(Objects::nonNull).toList();}
            for(var eviction:evictions)try{eviction.run();}catch(RuntimeException ignored){}
            throw failure;
        }
    }
    void stopIfIdle() {
        if(members.values().stream().anyMatch(m->m.entered))return;
        if(scheduled){scheduled=false;SceneScheduler.shared().stop(instance.oid());}
        previous=null;
    }
    /** Per-tick observations sampled without holding the scene monitor. */
    private long tickLockWaitNanos,tickWorkNanos,tickMaxWorkNanos,tickMaxLockWaitNanos,tickMaxPublicationNanos;
    private static final int MEASUREMENT_WINDOW=256;
    private final long[] lockWaitSamples=new long[MEASUREMENT_WINDOW],workSamples=new long[MEASUREMENT_WINDOW],
        publicationSamples=new long[MEASUREMENT_WINDOW],deliverySamples=new long[MEASUREMENT_WINDOW];
    private int sampleCount,sampleNext;
    private long maxDeliveryNanos;
    public record TickMetrics(long ticks,long lockWaitNanos,long workNanos,long maxWorkNanos,
            long lockWaitP50Nanos,long lockWaitP99Nanos,long workP50Nanos,long workP99Nanos,
            long publicationP50Nanos,long publicationP99Nanos,long deliveryP50Nanos,long deliveryP99Nanos,
            long maxLockWaitNanos,long maxPublicationNanos,long maxDeliveryNanos){ }
    public synchronized TickMetrics tickMetrics(){
        return new TickMetrics(tickCount,tickLockWaitNanos,tickWorkNanos,tickMaxWorkNanos,
            percentile(lockWaitSamples,.50),percentile(lockWaitSamples,.99),
            percentile(workSamples,.50),percentile(workSamples,.99),
            percentile(publicationSamples,.50),percentile(publicationSamples,.99),
            percentile(deliverySamples,.50),percentile(deliverySamples,.99),
            tickMaxLockWaitNanos,tickMaxPublicationNanos,maxDeliveryNanos);
    }
    private void sample(long lockWait,long work,long publication,long delivery) {
        lockWaitSamples[sampleNext]=lockWait;workSamples[sampleNext]=work;
        publicationSamples[sampleNext]=publication;deliverySamples[sampleNext]=delivery;
        sampleNext=(sampleNext+1)%MEASUREMENT_WINDOW;
        if(sampleCount<MEASUREMENT_WINDOW)sampleCount++;
        if(delivery>maxDeliveryNanos)maxDeliveryNanos=delivery;
    }
    private long percentile(long[] samples,double quantile) {
        if(sampleCount==0)return 0;
        long[] copy=java.util.Arrays.copyOf(samples,sampleCount);java.util.Arrays.sort(copy);
        return copy[Math.min(copy.length-1,(int)Math.ceil(copy.length*quantile)-1)];
    }
    private void tick() {
        long entered=System.nanoTime();
        SceneSnapshot publication;List<Consumer<SceneSnapshot>> recipients;
        long publicationNanos=0,lockWaitSample=0;
        synchronized(this) {
            long lockWait=System.nanoTime()-entered;
            lockWaitSample=lockWait;
            long tickStarted=System.nanoTime();
            if(!scheduled)return;
            int stamp=SceneClock.now();long now=System.nanoTime()/1_000_000L;
            if(previous!=null&&stamp-previous.timestamp()<=0)return;
            // All authoritative entities advance before capture. Future AI/combat/dynamic
            // collision stages run here once per instance, independent of observer count.
            for(var member:members.values())if(member.entered&&member.portalOpen()) {
                if(member.loadout.auraOwner!=0&&member.health.status().conditions().stream().noneMatch(c->c.name().contains("Angelic Aura")))member.loadout.auraOwner=0;
                member.health.advance(stamp,previous==null?0:stamp-previous.timestamp(),(member.movement.flags()&TownMovement.PUBLISHED_MOVING_FLAG)!=0);
                member.updateLife(stamp);
                member.movement.status(member.health.status().modifiers());
                if(member.health.status().modifiers().shocked()&&member.lift!=0){entities.dropLift(member.lift,member.actor(),stamp);member.lift=member.dropAt=0;}
                if(member.loadout.busy()){
                    if(member.movement.activity()!=TownMovement.USE_ITEM){member.loadout.cancelPending();}
                    else if(SceneClock.reached(stamp,member.loadout.itemLand())){
                        var itemSpec=member.loadout.itemSpec();
                        boolean used;
                        if(itemSpec.integer("heal",0)>0){
                            used=member.health.heal(itemSpec.integer("heal",0));
                            if(used)weapons.effect(itemSpec.model("use-effect"),member.actor(),member.loadout.itemLand());
                        }else try{used=weapons.fire(FireCatalog.actionJson(itemSpec.text("use-action","")),member.actor(),member.loadout.itemLand());}
                        catch(java.io.IOException invalid){throw new IllegalStateException("Invalid prepared item action",invalid);}
                        if(used)member.loadout.markUsed(member.loadout.pendingItem());
                        member.loadout.settlePending();
                    }
                }
                // Resolve the delayed action before natural animation completion on a late tick.
                member.movement.preparePublication(stamp);
                if(member.pendingAnimated!=null){
                    String config=member.pendingAnimated;
                    if(member.movement.activity()!=TownMovement.ANIMATED_ITEM)member.pendingAnimated=null;
                    else if(stamp-member.movement.activityStarted()>=org.tortodrone.core.character.AnimatedItems.land(config)){
                        for(var effect:org.tortodrone.core.character.AnimatedItems.effects(config))weapons.effect(effect,member.actor(),stamp);
                        member.pendingAnimated=null;
                    }
                }
                member.movement.advance(stamp,now);
                if(!instance.map().town()&&member.portalOpen())recordVisited(member.movement.x(),member.movement.y());
                if(member.dropAt>0&&SceneClock.reached(stamp,member.dropAt)){entities.dropLift(member.lift,member.actor(),stamp);member.lift=member.dropAt=0;}
                portals.update(member.appearance.id(),member.movement.portal());member.pet.advance(stamp);
            }
            var players=members.values().stream().filter(m->m.entered).map(SceneMembership::actor).toList();
            for(var member:members.values())if(member.entered)for(var pulse:member.health.takePulses())entities.statusPulse(member.appearance.id(),pulse,member.movement.x(),member.movement.y(),stamp);
            for(var member:members.values())if(member.entered)for(var action:member.health.takeActions())weapons.fire(action,member.actor(),stamp);
            entities.advance(stamp,players);var sources=new ArrayList<>(players);for(var member:members.values()){var pet=member.pet.actor();if(pet!=null)sources.add(pet);}weapons.advance(stamp,sources);
            combatEvents.removeIf(event->stamp-event.timestamp()>5000);
            long publicationStarted=System.nanoTime();
            publication=capture(stamp,previous);previous=publication;tickCount++;
            publicationNanos=System.nanoTime()-publicationStarted;
            tickWorkNanos=System.nanoTime()-tickStarted;
            tickLockWaitNanos=lockWait;
            if(tickWorkNanos>tickMaxWorkNanos)tickMaxWorkNanos=tickWorkNanos;
            if(lockWait>tickMaxLockWaitNanos)tickMaxLockWaitNanos=lockWait;
            if(publicationNanos>tickMaxPublicationNanos)tickMaxPublicationNanos=publicationNanos;
            entities.tickDuration(tickWorkNanos);
            if(stamp-lastMetricLog>=30_000){
                lastMetricLog=stamp;
                var m=entities.metrics();
                System.getLogger(SceneSimulation.class.getName()).log(System.Logger.Level.DEBUG,
                    "Scene metrics: template="+instance.map().id()+" instance="+instance.oid()
                    +" total="+m.total()+" awake="+m.awake()+" engaged="+m.engaged()
                    +" byType="+m.byType()+" paths="+m.pathSearches()+" queued="+m.pathQueued()
                    +" dropped="+m.pathDropped()+" failures="+m.pathFailures()
                    +" spawned="+m.spawned()+" despawned="+m.despawned()
                    +" tickP99="+String.format(java.util.Locale.ROOT,"%.2f",m.tickP99Millis())+"ms");
            }
            recipients=members.values().stream().filter(m->m.entered).map(m->m.publish).toList();
        }
        // No simulation lock is held across callbacks, including detach/travel races.
        long deliveryStarted=System.nanoTime();
        for(var recipient:recipients)recipient.accept(publication);
        long deliveryNanos=System.nanoTime()-deliveryStarted;
        synchronized(this){sample(lockWaitSample,tickWorkNanos,publicationNanos,deliveryNanos);}
    }
}
