package org.tortodrone.world;
import org.tortodrone.core.world.SceneClock;

import java.util.*;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.Json;
import org.tortodrone.core.WireTypes;
import org.tortodrone.core.character.KnightObjective;
import org.tortodrone.core.character.KnightMemory;
import org.tortodrone.core.world.SceneConversation;
import org.tortodrone.core.world.SceneActions;
import org.tortodrone.core.world.FireCatalog;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.core.world.GearStats;
import org.tortodrone.core.world.InteractionState;
import org.tortodrone.core.world.KnightCombat;
import org.tortodrone.core.world.SceneCombatEvent;
import org.tortodrone.core.world.SceneEntityState;
import org.tortodrone.core.world.SceneLiftState;
import org.tortodrone.core.world.ScenePickupState;
import org.tortodrone.core.world.SceneStatus;
import org.tortodrone.core.world.PreparedActor;
import org.tortodrone.core.world.SceneShape;
import org.tortodrone.core.world.SceneContent;
import org.tortodrone.core.world.SceneCollision;
import org.tortodrone.core.world.SceneDamage;
import org.tortodrone.core.world.SceneScript;
import org.tortodrone.core.world.SceneTargeting;
import org.tortodrone.core.world.SceneWander;
import org.tortodrone.core.world.SceneFollow;
import org.tortodrone.core.world.SceneAttackMotion;
import org.tortodrone.core.world.ScenePartyPlatform;
import org.tortodrone.core.world.SceneMovementScript;
import org.tortodrone.core.world.SceneElevator;
import org.tortodrone.core.world.ScenePortals;
import org.tortodrone.core.world.MonsterCatalog;
import org.tortodrone.core.world.CollisionMap;
import org.tortodrone.core.world.MonsterType;
import org.tortodrone.core.world.MonsterArchetype;

/** Instance-owned native actors, pad regeneration, movement and damage targets. */
public final class SceneEntities implements SceneWeapons.Targets {
    /** Identity-owned ride; never a client token or an arbitrary destination. */
    public static final class ElevatorTrip {
        private final ActorState source;private final List<Integer> members;private final SceneElevator configuration;private final int started;
        ElevatorTrip(ActorState source,List<Integer> members,SceneElevator configuration,int started){this.source=source;this.members=List.copyOf(members);this.configuration=configuration;this.started=started;}
        public ActorState source(){return source;}public List<Integer> members(){return members;}public SceneElevator configuration(){return configuration;}public int started(){return started;}
    }
    /** Pure scene-lock callbacks: authorize/freeze, revalidate, offer owned work, release.
     * Socket and storage work must remain on the session thread. */
    public interface ElevatorControl {
        default boolean begin(ElevatorTrip trip,int stamp){return false;}
        default boolean valid(ElevatorTrip trip){return false;}
        default boolean advance(ElevatorTrip trip,int stamp){return false;}
        default void cancel(ElevatorTrip trip,int stamp) { }
    }
    private final ElevatorSystem elevatorSystem=new ElevatorSystem(this);
    private final SceneCollisions collisions=new SceneCollisions(this);
    private final WaveSystem waveSystem=new WaveSystem(this);
    private final PotSystem pots=new PotSystem(this);
    private final InteractionSystem interactions=new InteractionSystem(this);
    private final HeartDrops hearts=new HeartDrops(this);
    private final DamageSystem damageSystem=new DamageSystem(this);
    private final SceneLogic logic=new SceneLogic(this);
    private final LiftSystem lifts=new LiftSystem(this);
    private final SceneSpawns spawns=new SceneSpawns(this);
    private final SceneTick tick=new SceneTick(this);
    public void elevators(ElevatorControl control){elevatorSystem.control(control);}
    public record Pickup(int entityId,String item,boolean temporary,Integer equippedSlot,int heat) {
        public Pickup(int entityId,String item,boolean temporary,Integer equippedSlot){this(entityId,item,temporary,equippedSlot,0);}
    }
    private static final Set<String> SUPPORTED=Set.of("Actor","Agent","Stranger","Monster","Generator","Door","Switch","EntryState","Block","Block$Stateful","Pickup","Trap","Bomb","DungeonLift","Lift","TerritoryProp");
    static boolean liftKind(String kind){return kind.equals("DungeonLift")||kind.equals("Lift");}
    /** Damage wakes a monster even when it was hit outside every activation radius. */
    static final int DAMAGE_ENGAGE_MILLIS=5000;
    /** Reuse a clear line of sight briefly; damage or a moved target invalidates it. */
    static final int SIGHT_CACHE_MILLIS=150;
    static final float SIGHT_CACHE_DISTANCE=.5f;
    /** Server-side wave policy; enforced here, never trusted from a client. */
    static final int MAX_ALIVE_MONSTERS=64;
    static final int MAX_ENGAGED_MONSTERS=32;
    static final int MAX_WAVE_SPAWNS_PER_TICK=4;
    /** Administrative spawns use the same ring policy as a wave, never an unbounded radius. */
    static final float MAX_ADMIN_SPAWN_RADIUS=128;
    /** Validate one prepared item-use definition before a scene exposes it to play. */
    public static void validateItemUse(SceneContent.Spec spec)throws java.io.IOException{
        org.tortodrone.core.world.ItemUseRules.validate(spec);
    }
    /** Spawn the static prop authored by a native actor-item use. */
    public int spawnItemActor(ActorState player,SceneContent.Spec actor,int stamp){return spawns.itemActor(player,actor,stamp);}
    public int liftItem(ActorState player,SceneContent.Spec lift,int stamp){return spawns.liftItem(player,lift,stamp);}
    public void soloRewards(boolean value){spawns.soloRewards(value);}
    public boolean soloRewards(){return spawns.soloRewards();}
    /** Test seam: the authored signal dispatch now lives in {@link SceneLogic}. */
    void signal(String tag,String name,int stamp,String source){logic.signal(tag,name,stamp,source);}
    void generate(SceneEntity pad,int stamp){spawns.generate(pad,stamp);}
    public void discard(int id){var entity=entities.get(id);if(entity!=null)unlink(entity);}
    /** Native collision bit 1 << 13 ("lift" in the 2026 mask.properties registry): the served
     *  Lift Barriers state exactly this bit, and the 2026 lift base adds it to thrown and
     *  resting collision masks, so thrown lifts stop at closed barriers (see the archive's
     *  vendored {@code Lift} base and LiftBarrierProbe). Carriers are stopped by the pawn rule
     *  in {@link #penetration}, because the raw mask test alone never blocks them: the pawn mask
     *  lacks the bit and a carried lift's own collision drops to 0 in 2026. */
    static final int LIFT_COLLISION=8192;
    static final Object POINT=new FireCatalog.DataValue("com.threerings.tudey.shape.config.ShapeConfig$Point",Map.of());

    private final SceneContent content;private final CollisionMap terrain;
    private final SceneActions actions;
    private ActorState entranceOverride;
    public ActorState defaultEntrance(){return entranceOverride;}
    private final SceneCollision staticCollision;
    private final int pawnCollisionMask;
    private final Map<Integer,SceneEntity> entities=new LinkedHashMap<>();
    private final MovementSystem movementSystem=new MovementSystem(this);
    private final CombatSystem combatSystem=new CombatSystem(this);
    private final StatusSystem statusSystem=new StatusSystem(this);
    SceneEntity entity(int id){return entities.get(id);}
    org.tortodrone.core.world.CollisionMap terrain(){return terrain;}
    SceneCollision staticCollision(){return staticCollision;}
    int pawnCollisionMask(){return pawnCollisionMask;}
    SceneContent content(){return content;}
    List<ActorState> occupants(){return players;}
    ScenePathBudget pathBudget(){return pathBudget;}
    MonsterMetrics monsterMetrics(){return monsterMetrics;}
    java.util.random.RandomGenerator random(){return random;}
    List<SceneEntity> candidatesNear(float minX,float minY,float maxX,float maxY){return collisions.candidates(minX,minY,maxX,maxY);}
    void moved(SceneEntity entity){collisions.moved(entity);}
    boolean hasEntity(int id){return entities.containsKey(id);}
    boolean targetablePlayer(int id){return playerActive.test(id)&&playerTargetable.test(id);}
    int lastStamp(){return lastStamp;}
    boolean spatialQuery(){return spatialQuery;}
    List<SceneEntity> spatialNear(float minX,float minY,float maxX,float maxY){return collisions.near(minX,minY,maxX,maxY);}
    SceneActions actions(){return actions;}
    void fireAttack(ActorState actor,FireCatalog.Action action){attack.accept(actor,action);}
    void hurtForCombat(SceneEntity entity,float amount,int stamp,boolean react,boolean major){damageSystem.hurt(entity,amount,stamp,react,major);}
    void damagePlayer(PlayerHit hit){playerDamage.accept(hit);}
    boolean startConversationSink(SceneConversation conversation,ActorState source,int stamp,int target){return conversationSink.start(conversation,source,stamp,target);}
    boolean requestFloorAdvance(ActorState player,ActorState source,int stamp){return floorAdvanceSink.request(player,source,stamp);}
    boolean applyMemory(ActorState player,KnightMemory operation,ActorState source,int stamp){return memorySink.apply(player,operation,source,stamp);}
    void fireEffectSink(StarterCatalog.Model config,ActorState source,int stamp){effectSink.fire(config,source,stamp);}
    boolean applyPawnStatusSink(int actor,SceneActions.PawnStatus operation,int stamp){return pawnStatus.apply(actor,operation,stamp);}
    void impulsePlayer(int actor,KnightCombat.Impulse impulse){playerImpulse.accept(actor,impulse);}
    void combatEvent(SceneCombatEvent event){feedback.accept(event);}
    void fireDeathAction(SceneEntity entity,String graph){
        try{attack.accept(entity.snapshot(),FireCatalog.actionJson(graph));}
        catch(java.io.IOException invalid){throw new IllegalArgumentException("Invalid prepared monster death action",invalid);}
    }
    boolean lightsOil(Object damage){return pots.lightsOil(damage);}
    void lightOil(SceneEntity entity,int stamp){pots.lightOil(entity,stamp);}
    void dropHeart(SceneEntity entity,int stamp){hearts.drop(entity,stamp);}
    void linkLater(SceneEntity target,int stamp,boolean ghost){links.addLast(new Link(target,stamp,ghost));}
    float spatialReach(){return collisions.reach();}
    java.util.Collection<SceneEntity> entityValues(){return entities.values();}
    boolean activePlayer(int id){return playerActive.test(id);}
    boolean pickupEligible(int id,SceneContent.Spec spec){return pickupEligible.test(id,spec);}
    boolean healPlayer(int id,int amount){return playerHeal.test(id,amount);}
    /** Interactive props, doors and lifts are the only entities an interaction scan reads. */
    private final ArrayDeque<SceneEntity> pendingActors=new ArrayDeque<>();
    private record SpawnSource(SceneEntities scene,int id,int faction,String identity) { }
    private final Map<SceneEntity,List<SceneEntity>> arsenalProps=new LinkedHashMap<>();
    private int nextId=1_000_000_000,lastStamp;
    private long entityOrder;
    private final ScenePathBudget pathBudget=new ScenePathBudget();
    private final MonsterMetrics monsterMetrics=new MonsterMetrics();
    private final boolean spatialQuery;
    private final java.util.random.RandomGenerator random;
    /** Catalogue for wave spawns; an empty catalogue refuses every type id. */
    final MonsterCatalog monsters;
    private long generation=1;
    private record Link(SceneEntity entity,int stamp,boolean ghost) { }
    private final ArrayDeque<Link> links=new ArrayDeque<>();
    /** A knight hit; impulse is the region's DamageImpulse, applied only if the hit lands. */
    public record PlayerHit(int actor,Object damage,int timestamp,int source,float multiplier,KnightCombat.Impulse impulse) {
        public PlayerHit(int actor,Object damage,int timestamp,int source,float multiplier){this(actor,damage,timestamp,source,multiplier,null);}
        public PlayerHit(int actor,Object damage,int timestamp,int source){this(actor,damage,timestamp,source,1);}
    }
    private java.util.function.Consumer<PlayerHit> playerDamage=hit->{};
    public void playerDamage(java.util.function.BiConsumer<Integer,Object> damage){Objects.requireNonNull(damage);playerDamage=hit->damage.accept(hit.actor(),hit.damage());}
    public void playerHits(java.util.function.Consumer<PlayerHit> damage){playerDamage=Objects.requireNonNull(damage);}
    private java.util.function.Consumer<SceneCombatEvent> feedback=event->{};
    public void feedback(java.util.function.Consumer<SceneCombatEvent> value){feedback=Objects.requireNonNull(value);}
    private java.util.function.BiPredicate<Integer,Integer> playerHeal=(actor,amount)->false;
    public void playerHeal(java.util.function.BiPredicate<Integer,Integer> heal){playerHeal=Objects.requireNonNull(heal);}
    private java.util.function.IntPredicate playerActive=id->true;
    public void playerActive(java.util.function.IntPredicate predicate){playerActive=Objects.requireNonNull(predicate);}
    private java.util.function.BiPredicate<Integer,SceneContent.Spec> pickupEligible=(id,spec)->true;
    public void pickupEligible(java.util.function.BiPredicate<Integer,SceneContent.Spec> predicate){pickupEligible=Objects.requireNonNull(predicate);}
    private java.util.function.IntPredicate playerTargetable=id->true;
    public void playerTargetable(java.util.function.IntPredicate predicate){playerTargetable=Objects.requireNonNull(predicate);}
    private java.util.function.BiConsumer<Integer,KnightCombat.Impulse> playerImpulse=(id,impulse)->{};
    public void playerImpulse(java.util.function.BiConsumer<Integer,KnightCombat.Impulse> callback){playerImpulse=Objects.requireNonNull(callback);}
    private java.util.function.BiConsumer<ActorState,FireCatalog.Action> attack=(actor,action)->{};
    public void attacks(java.util.function.BiConsumer<ActorState,FireCatalog.Action> fire){attack=Objects.requireNonNull(fire);}
    @FunctionalInterface public interface PawnStatusSink { boolean apply(int actor,SceneActions.PawnStatus operation,int stamp); }
    private PawnStatusSink pawnStatus=(actor,operation,stamp)->false;
    public void pawnStatuses(PawnStatusSink sink){pawnStatus=Objects.requireNonNull(sink);}
    @FunctionalInterface public interface ConversationSink { boolean start(SceneConversation conversation,ActorState source,int stamp,int target); }
    private ConversationSink conversationSink=(conversation,source,stamp,target)->false;
    public void conversations(ConversationSink sink){conversationSink=Objects.requireNonNull(sink);}
    @FunctionalInterface public interface DialogClearSink {boolean clear(int target,int stamp);}
    private DialogClearSink dialogClearSink=(target,stamp)->false;
    public void dialogClears(DialogClearSink sink){dialogClearSink=Objects.requireNonNull(sink);}
    @FunctionalInterface public interface EffectSink { void fire(org.tortodrone.core.character.StarterCatalog.Model config,ActorState source,int stamp); }
    private EffectSink effectSink=(config,source,stamp)->{};
    public void effects(EffectSink sink){effectSink=Objects.requireNonNull(sink);}
    /** Simulation-only request sink. Never perform storage or network work in this callback. */
    @FunctionalInterface public interface FloorAdvanceSink { boolean request(ActorState player,ActorState source,int stamp); }
    private FloorAdvanceSink floorAdvanceSink=(player,source,stamp)->false;
    public void floorAdvances(FloorAdvanceSink sink){floorAdvanceSink=Objects.requireNonNull(sink);}
    @FunctionalInterface public interface MemorySink {boolean apply(ActorState player,org.tortodrone.core.character.KnightMemory operation,ActorState source,int stamp);}
    private MemorySink memorySink=(player,operation,source,stamp)->false;
    public void memories(MemorySink sink){memorySink=Objects.requireNonNull(sink);}
    @FunctionalInterface public interface ObjectiveSink {boolean apply(ActorState player,org.tortodrone.core.character.KnightObjective operation,ActorState source,int stamp);}
    private ObjectiveSink objectiveSink=(player,operation,source,stamp)->false;
    public void objectives(ObjectiveSink sink){objectiveSink=Objects.requireNonNull(sink);}
    private List<ActorState> players=List.of();
    public SceneEntities(SceneContent content,CollisionMap terrain){
        this(content,terrain,SceneCollision.empty());
    }
    public SceneEntities(SceneContent content,CollisionMap terrain,SceneCollision staticCollision){this(content,terrain,staticCollision,new Random());}
    public SceneEntities(SceneContent content,CollisionMap terrain,SceneCollision staticCollision,java.util.random.RandomGenerator random){
        this(content,terrain,staticCollision,random,true);
    }
    /** The linear path exists only for the differential harness and the one-release rollback. */
    SceneEntities(SceneContent content,CollisionMap terrain,SceneCollision staticCollision,java.util.random.RandomGenerator random,boolean spatialQuery){
        this(content,terrain,staticCollision,random,spatialQuery,SceneClock.now());
    }
    /** Explicit creation stamp so the differential harness can replay identical instances. */
    SceneEntities(SceneContent content,CollisionMap terrain,SceneCollision staticCollision,java.util.random.RandomGenerator random,boolean spatialQuery,int stamp){
        this(content,terrain,staticCollision,random,spatialQuery,stamp,MonsterCatalog.empty());
    }
    SceneEntities(SceneContent content,CollisionMap terrain,SceneCollision staticCollision,java.util.random.RandomGenerator random,boolean spatialQuery,int stamp,MonsterCatalog monsters){
        this.random=Objects.requireNonNull(random);this.spatialQuery=spatialQuery;this.monsters=Objects.requireNonNull(monsters);
        this.content=content;this.terrain=terrain;this.staticCollision=Objects.requireNonNull(staticCollision);pawnCollisionMask=Math.round(content.stats().getOrDefault("player.collision-mask",SceneContent.DEFAULT_PLAYER_COLLISION_MASK));
        var rules=new ArrayList<SceneActions.Rule>();
        for(var binding:content.entries()){
            if(binding.spec().text("kind","").equals("SceneAnchor")){
                logic.anchor(binding);
            }else if(binding.spec().text("kind","").equals("SceneTrigger")){
                try{rules.add(SceneActions.rule(binding));}catch(java.io.IOException invalid){throw new IllegalArgumentException("Invalid scene handler",invalid);}
            }else spawn(binding,binding.spec(),null,stamp);
        }
        actions=new SceneActions(rules,logic::signalSink,random,logic::destroyTagged,spawns::scripted,logic::applyPawnStatuses,logic::startConversation,logic::sourceEffect,logic::nextFloor,logic::setMemory);
        actions.nearestSignals(logic::nearestSignal);
        actions.targets(logic::variableTargets);
        actions.sourceSignals(logic::sourceSignal);
        actions.entrances((target,time,source,activator)->{
            var resolved=logic.variableTargets(target,time,source,activator);if(resolved.isEmpty())return false;
            var point=logic.scriptSource(resolved.getFirst(),time);if(point==null)return false;
            // Never accept a checkpoint outside the authoritative floor/collision.
            if(!terrain.walkable(point.x(),point.y())||!walkable(point.x(),point.y(),point.id())||staticCollision.penetration(pawnCollisionMask,point.x(),point.y(),ScenePortals.PAWN_RADIUS,disabledCollisionSources()).present())return false;
            entranceOverride=point;return true;
        });
        actions.sourceDestruction((time,source)->{
            if(!source.startsWith("actor:"))return false;
            int id;try{id=Integer.parseInt(source.substring(6));}catch(NumberFormatException invalid){return false;}
            var target=entities.get(id);if(target==null)return false;
            if(target.destroyed==Integer.MAX_VALUE){target.destroyed=time;target.scriptDestroyed=true;target.status.clear();target.path=List.of();target.impulse=null;}
            return true;
        });
        actions.variables(logic::setVariable,logic::variable);
        actions.dialogClears((only,time,source,activator)->{
            if(logic.scriptSource(source,time)==null)return false;
            int target=0;
            if(only){var player=players.stream().filter(p->activator.equals("actor:"+p.id())&&playerActive.test(p.id())).findFirst().orElse(null);if(player==null)return true;target=player.id();}
            return dialogClearSink.clear(target,time);
        });
        actions.gameplayEffects(logic::gameplayEffect);
        actions.impulses((operation,time,source)->{var origin=logic.scriptSource(source,time);if(origin==null)return false;impulse(operation.config(),origin.x(),origin.y(),origin.rotation(),time,0);return true;});
        actions.objectives((operation,time,source,activator)->{
            var player=players.stream().filter(p->activator.equals("actor:"+p.id())&&playerActive.test(p.id())).findFirst().orElse(null);var origin=logic.scriptSource(source,time);
            return player!=null&&origin!=null&&objectiveSink.apply(player,operation,origin,time);
        });
        boolean startup=false;
        for(var entry:content.entries())if(entry.spec().flag("scene-startup",false)){startup=true;actions.signal(entry.spec().text("watch-tag",""),"startup",stamp,"entry:"+entry.key());}
        if(startup)actions.advance(stamp);
        // A placed station's interaction region and animated switch share an entry key.
        for(var zone:entities.values())if(zone.spec.flag("arsenal-zone",false))
            arsenalProps.put(zone,entities.values().stream().filter(e->e!=zone&&e.kind.equals("Switch")&&e.binding.key().equals(zone.binding.key())).toList());
    }
    /** Insertion assigns the deterministic order the spatial query returns candidates in. */
    void insert(SceneEntity entity){
        entity.order=++entityOrder;entities.put(entity.id,entity);collisions.add(entity);
        interactions.add(entity);
        if(entity.kind.equals("Monster"))monsterMetrics.spawned();
    }
    void unlink(SceneEntity entity){
        if(entities.remove(entity.id)!=null&&entity.kind.equals("Monster"))monsterMetrics.despawned();
        interactions.remove(entity);
        collisions.remove(entity);
    }
    SceneEntity spawn(SceneContent.Entry binding,SceneContent.Spec spec,SceneEntity pad,int stamp){
        String nativeType=spec.text("native-type","");String kind=nativeType.substring(nativeType.lastIndexOf('.')+1);
        if(!SUPPORTED.contains(kind))throw new IllegalArgumentException("Unsupported prepared scene actor: "+kind);
        var entity=new SceneEntity(nextId++,stamp,binding,spec,pad,content);insert(entity);
        if(kind.equals("Monster")&&spec.integer("activate-time",0)>0)entity.activity=3;
        if(kind.equals("Generator")&&!spec.child().fields().isEmpty()){
            generate(entity,stamp);
        }
        return entity;
    }
    @Override public boolean spawnPickup(StarterCatalog.Model specification,float x,float y,float rotation,int stamp){return spawns.pickup(specification,x,y,rotation,stamp);}
    @Override public Object sceneActorSource(int source){return spawns.sceneActorSource(source);}
    @Override public boolean spawnSceneActor(String template,float x,float y,float rotation,int stamp,Object source){return spawns.sceneActor(template,x,y,rotation,stamp,source);}
    // Negative handlers describe placed entry interactions, not additional visible actors.
    public List<ActorState> actors(){return entities.values().stream().filter(e->!e.entrySource()).map(SceneEntity::snapshot).toList();}
    /** Authored native elevator platforms bridge their floor shaft. A pawn body of radius r can
     *  reach a platform whose edge is up to 2r away, so movement treats that bridged footprint as
     *  supported instead of rejecting the authored gap between the pit rim and the platform. */
    public boolean elevatorFloor(float x,float y){return elevatorSystem.floor(x,y);}
    public ActorState actor(int id){var e=entities.get(id);return e==null||e.destroyed!=Integer.MAX_VALUE?null:e.snapshot();}
    public SceneContent.Spec specification(int id){var e=entities.get(id);return e==null||e.destroyed!=Integer.MAX_VALUE?null:e.spec;}
    /** Test seam: the immutable parsed behaviour shared by every instance of this spec. */
    PreparedActor prepared(int id){var e=entities.get(id);return e==null?null:e.prepared;}
    /** Test seam: current activation band for a live entity. */
    SceneMonsterBand band(int id){var e=entities.get(id);return e==null?null:e.band;}
    /** Test seam: effective scheduling policy for a live entity. */
    MonsterArchetype archetype(int id){var e=entities.get(id);return e==null?null:e.archetype;}
    /** Test seam: effective activation radius for a live entity. */
    float activationRadius(int id){var e=entities.get(id);return e==null?0:e.activationRadius;}
    /** Test seam: effective leash radius for a live entity (zero means unleased). */
    float leashRadius(int id){var e=entities.get(id);return e==null?0:e.leashRadius;}
    /** Read-only counters for one scene; gauges are computed from live monsters. */
    MonsterMetrics.Snapshot metrics(){
        int total=0,awake=0,engaged=0;var byType=new TreeMap<String,Integer>();
        for(var e:entities.values())if(e.kind.equals("Monster")&&e.destroyed==Integer.MAX_VALUE){
            total++;
            if(e.band!=SceneMonsterBand.DORMANT)awake++;
            if(e.band==SceneMonsterBand.ENGAGED)engaged++;
            byType.merge(e.monsterType.isBlank()?"placed":e.monsterType,1,Integer::sum);
        }
        return monsterMetrics.snapshot(total,awake,engaged,byType,pathBudget.queued(),pathBudget.dropped());
    }
    void tickDuration(long nanos){monsterMetrics.tick(nanos);}
    /** Test seam: A* searches admitted in the most recent tick. */
    int pathSearches(){return pathBudget.granted();}
    int pathQueued(){return pathBudget.queued();}
    public ActorState station(String kind){return entities.values().stream().filter(e->e.spec.text("town-kind","").equals(kind)&&e.destroyed==Integer.MAX_VALUE).map(SceneEntity::snapshot).findFirst().orElse(null);}
    /** Called only after the scene validates the knight's interaction intent. */
    public void openArsenal(int id,int stamp){
        var props=arsenalProps.get(entities.get(id));
        if(props!=null)for(var prop:props)if(prop.state==0)setSwitch(prop,1,stamp);
    }
    public boolean carry(int id,ActorState player,int stamp){
        var e=entities.get(id);if(e==null||!liftKind(e.kind)||e.destroyed!=Integer.MAX_VALUE||(e.flags&6)!=0)return false;
        e.carrier=e.owner=player.id();e.ownerOid=player.clientOid();e.dropPoint=null;e.flags|=4;return true;
    }
    public int vial(ActorState player,SceneContent.Spec item,int stamp){
        var fields=new TreeMap<>(item.section("lift.").fields());fields.put("destroy-on-land","true");
        var binding=new SceneContent.Entry("item:"+nextId,player.x(),player.y(),player.rotation(),new SceneContent.Spec(fields));
        var e=spawn(binding,binding.spec(),null,stamp);e.vial=item;
        if(!carry(e.id,player,stamp)){unlink(e);return 0;}return e.id;
    }
    public boolean canThrow(int id){return lifts.canThrow(id);}
    public boolean prepareDrop(int id,ActorState player){return lifts.prepareDrop(id,player);}
    public void throwLift(int id,ActorState player,int stamp){lifts.throwLift(id,player,stamp);}
    public void dropLift(int id,ActorState player,int stamp){lifts.dropLift(id,player,stamp);}
    public void releaseLifts(int player,int stamp){lifts.releaseLifts(player,stamp);}
    public List<Pickup> takePickups(int player){return interactions.takePickups(player);}
    public List<Pickup> pendingPickups(int player){return interactions.pendingPickups(player);}
    public int heatClaim(int player,int actor){return interactions.heatClaim(player,actor);}
    public boolean activateHeat(int player,int actor){return interactions.activateHeat(player,actor);}
    public void reconsiderPickups(int player){interactions.reconsider(player);}
    public void completePickup(int player,int actor,boolean success){interactions.complete(player,actor,success);}
    public void cancelClaims(int player){interactions.cancel(player);}
    public int interactionDuration(int id){return interactions.duration(id);}
    public InteractionState interaction(float x,float y,float rotation){return interactions.interaction(x,y,rotation,0,0);}
    /** Carried keys can address only a compatible closed door in this scene. */
    public InteractionState interaction(float x,float y,float rotation,int owner,int lift){return interactions.interaction(x,y,rotation,owner,lift);}

    void drainPending(){for(int count=pendingActors.size();count>0;count--){var entity=pendingActors.removeFirst();insert(entity);}}
    void tickPlayers(List<ActorState> players){this.players=players;}
    void beginCombatTick(int engaged){combatSystem.beginTick(engaged);}
    void advanceWaves(int stamp){waveSystem.advance(stamp);}
    /** Bounds a station's interaction prompt to the same reach the native prompt uses. */
    void tickStations(int stamp,List<ActorState> players){
        for(var station:arsenalProps.entrySet()){
            var zone=station.getKey();
            // Proximity retains an interacted station; it must not open a closed one.
            // Include the prompt's forward reach so opening from the edge cannot
            // immediately close it again on the next simulation tick.
            float reach=Math.max(ScenePortals.PAWN_RADIUS,zone.spec.number("interaction-radius",.4f)
                +zone.spec.number("interaction-expansion",0)+(float)Math.hypot(zone.spec.number("interaction-offset-x",.7f),zone.spec.number("interaction-offset-y",0)));
            if(players.stream().noneMatch(p->zone.spec.contains(zone.x,zone.y,zone.rotation,p.x(),p.y(),reach)))
                for(var prop:station.getValue())if(prop.state!=0)setSwitch(prop,0,stamp);
        }
    }
    void drainLinks(int stamp){while(!links.isEmpty()&&links.peekFirst().stamp()<=stamp){var link=links.removeFirst();damageSystem.clearLinks(link.entity(),stamp,link.ghost());}}
    float advanceClock(int stamp){float elapsed=lastStamp==0?0:Math.min(.25f,(stamp-lastStamp)/1000f);lastStamp=stamp;pathBudget.begin(stamp);return elapsed;}
    void shutdownEntity(SceneEntity e,int stamp){logic.shutdown(e,stamp);}
    void deactivateEntity(SceneEntity e){logic.deactivate(e);}
    void retainActionSources(){logic.retainSources(actions);}
    void advanceElevator(SceneEntity e,int stamp,List<ActorState> players){elevatorSystem.advance(e,stamp,players);}
    void collectPickup(SceneEntity e,float elapsed,List<ActorState> players){interactions.collect(e,elapsed,players);}
    void tickStatus(SceneEntity e,int stamp,float elapsed){statusSystem.tick(e,stamp,elapsed);}
    int engagedMonsters(){return (int)entities.values().stream().filter(e->e.kind.equals("Monster")&&e.destroyed==Integer.MAX_VALUE&&e.band==SceneMonsterBand.ENGAGED).count();}
    /** One simulation tick of the shared entity model; the tick body lives in SceneTick. */
    public void advance(int stamp,List<ActorState> occupants){tick.advance(stamp,occupants);}
    public int spawnMonsters(String reference,float x,float y,float rotation,int count,float radius,int stamp)throws java.io.IOException {
        return spawns.monsters(reference,x,y,rotation,count,radius,stamp);
    }
    /** Current scene generation. A wave created for an older generation is dropped, never replayed. */
    public long generation(){return generation;}
    /** Register a wave for this scene generation. Unknown or unsupported types fail before creation. */
    public void addWave(MonsterGroup group){waveSystem.add(group);}
    int waveSpawned(String id){return waveSystem.spawned(id);}
    int waveAlive(String id){return waveSystem.alive(id);}
    boolean waveCleared(String id){return waveSystem.cleared(id);}
    int liveMonsters(){return waveSystem.liveMonsters();}
    /** Immediate removal without death actions; status, paths, claims and source effects are cleared. */
    void despawn(SceneEntity entity){
        actions.cancelSource("actor:"+entity.id);
        entity.status.clear();entity.path=List.of();entity.impulse=null;entity.target=0;entity.targetMode=null;
        entity.targetModeUpdate=false;entity.flags=0;entity.claim=0;entity.claimTaken=false;
        entity.carrier=0;entity.owner=0;entity.ownerOid=0;
        unlink(entity);
        logic.deactivate(entity);
    }

    /** Move to a later scene generation, removing every wave and wave actor that cannot belong to it. */
    public void generation(long next){
        if(next<=generation)throw new IllegalArgumentException("Scene generation must move forward");
        if(next==Long.MAX_VALUE)throw new IllegalArgumentException("Scene generation is exhausted");
        waveSystem.retain(next);
        generation=next;
    }
    /** Secondary burn/spasm damage retains its collision mask and excludes the affected source. */
    public void statusPulse(int source,SceneStatus.Pulse pulse,float x,float y,int stamp){statusSystem.pulse(source,pulse,x,y,stamp);}
    void liftTick(SceneEntity e,int stamp,float elapsed,List<ActorState> players){lifts.lift(e,stamp,elapsed,players);}
    void scriptedMovement(SceneEntity e,int stamp,float elapsed){movementSystem.scriptedMovement(e,stamp,elapsed);}
    /** Per attack slot, the damage circle radius its first fire action can reach. A
     *  monster that stops at the native targeting range can be unable to land a melee
     *  region whose circle is smaller than that range (the Jelly Cube stops at
     *  1.9997 edge while its resolved Medium circle reaches 1.5). Cap only that
     *  slot, so a projectile or beam on another slot keeps its full range. */
    /** Export resolution never applies an attack's parameter choices, so the Jelly
     * Roundhouse's fire reference keeps its blank base Shape (Circle 1.0) while every
     * Jelly Cube (Intro) resolves Damage Region=Medium, Circle 1.5. Medium is verified
     * against the installed client's own choice table and matches the archive's
     * identical stripped-RegionConfig reconstruction for the Ice Cube (WORKLOG
     * 2026-10-29 lane/w5-ai). Restore the resolved radius until re-preparation can
     * carry the applied choice in the native bindings. */
    static final String ROUNDHOUSE_MEDIUM_CONFIG="Character/NPC/Monster/Slime/Jelly Cube (Intro)";
    static final float ROUNDHOUSE_MEDIUM_RADIUS=1.5f;
    void scriptedProgram(SceneEntity e,int stamp,float elapsed){
        e.flags&=~2;if(e.scriptStep>=e.program.steps().size())return;
        var step=e.program.steps().get(e.scriptStep);boolean starting=e.scriptStarted==Integer.MIN_VALUE;
        if(starting){
            e.scriptStarted=stamp;e.scriptGoal=null;e.path=List.of();
            if(step instanceof SceneScript.Rotate rotate)e.scriptRotation=(float)Math.IEEEremainder(rotate.direction()+(rotate.relative()?e.rotation:0)+(rotate.variance()==0?0:(random.nextFloat()*2-1)*rotate.variance()),2*Math.PI);
            if(step instanceof SceneScript.Attack attack)beginAttack(e,attack.index(),stamp);
        }
        boolean complete=switch(step){
            case SceneScript.Wait wait->stamp-e.scriptStarted>wait.millis();
            case SceneScript.Condition condition->condition.name().isEmpty()||variable(condition.name(),new SceneActions.LogicTarget("source",""),stamp,"actor:"+e.id,"actor:"+e.id);
            case SceneScript.Move move->movementSystem.scriptMove(e,move.tag(),move.maxPath(),e.spec.number("turn-rate",0),stamp,elapsed);
            case SceneScript.Rotate ignored->MovementSystem.scriptRotate(e,e.scriptRotation,elapsed,e.spec.number("turn-rate",0));
            case SceneScript.Action action->{var prepared=e.scriptActions.computeIfAbsent(e.scriptStep,index->actions.prepare(action.action()));actions.perform(prepared,stamp,"actor:"+e.id,"actor:"+e.id);yield true;}
            case SceneScript.Attack ignored->{if(e.activity>=4)move(e,stamp,elapsed,players);yield e.activity==0;}
            case SceneScript.Goto ignored->true;
        };
        if(complete){e.scriptStep=step instanceof SceneScript.Goto jump?jump.step():e.scriptStep+1;e.scriptStarted=Integer.MIN_VALUE;e.scriptGoal=null;e.path=List.of();}
    }
    void wander(SceneEntity e,int stamp,float elapsed){movementSystem.wander(e,stamp,elapsed);}
    List<ActorState> targetCandidates(SceneEntity e,List<ActorState> occupants){return combatSystem.targetCandidates(e,occupants);}
    void follow(SceneEntity e,int stamp,float elapsed,List<ActorState> occupants){movementSystem.follow(e,stamp,elapsed,occupants);}
    boolean targetActive(ActorState actor){return combatSystem.targetActive(actor);}
    void returnHome(SceneEntity e,int stamp,float elapsed){movementSystem.returnHome(e,stamp,elapsed);}
    void think(SceneEntity e,int stamp,float elapsed,List<ActorState> occupants){combatSystem.think(e,stamp,elapsed,occupants);}
    void move(SceneEntity e,int stamp,float elapsed,List<ActorState> occupants){combatSystem.move(e,stamp,elapsed,occupants);}
    private boolean beginAttack(SceneEntity e,int slot,int stamp){return combatSystem.beginAttack(e,slot,stamp);}
    private boolean sight(SceneEntity e,float tx,float ty){return combatSystem.sight(e,tx,ty);}
    boolean entitySegment(SceneEntity mover,float x,float y,float tx,float ty){return movementSystem.entitySegmentFor(mover,x,y,tx,ty);}
    /** Coarse traversal used only to plan a route; movement revalidates at 0.1. */
    boolean planSegment(SceneEntity mover,float x,float y,float tx,float ty){return movementSystem.planSegmentFor(mover,x,y,tx,ty);}
    boolean moveEntity(SceneEntity mover,float x,float y){return movementSystem.moveEntity(mover,x,y);}
    void cycle(SceneEntity e,int stamp){logic.cycle(e,stamp);}
    void switches(SceneEntity e,int stamp,List<ActorState> players){logic.switches(e,stamp,players);}
    void entitySignal(SceneEntity target,String name,int stamp){logic.entitySignal(target,name,stamp);}
    public boolean elevatorEntry(String key){return elevatorSystem.entry(key);}
    public boolean cancelElevator(ElevatorTrip trip,int stamp){return elevatorSystem.cancel(trip,stamp);}
    /** The session calls this only after its floor report/preflight succeeds. */
    public boolean dropElevator(ElevatorTrip trip,boolean returning,int stamp){return elevatorSystem.drop(trip,returning,stamp);}
    public boolean elevatorReady(ElevatorTrip trip,int stamp){return elevatorSystem.ready(trip,stamp);}
    void setSwitch(SceneEntity e,int state,int stamp){logic.setSwitch(e,state,stamp);}
    ActorState scriptSource(String source,int stamp){return logic.scriptSource(source,stamp);}
    List<ActorState> taggedSources(String tag,int stamp){return logic.taggedSources(tag,stamp);}
    ActorState entrySource(SceneContent.Entry binding,int stamp){return logic.entrySource(binding,stamp);}
    boolean variable(String name,SceneActions.LogicTarget target,int stamp,String source,String activator){return logic.variable(name,target,stamp,source,activator);}
    int effectSourceCount(){return logic.effectSourceCount();}
    int nextEntityId(){return nextId;}
    void nextEntityId(int value){nextId=value;}
    int entityCount(){return entities.size();}
    int pendingCount(){return pendingActors.size();}
    void stagePending(SceneEntity entity){pendingActors.addLast(entity);}
    boolean conversationValid(SceneConversation definition,String node,int choice,ActorState source,int responder,int stamp){return logic.conversationValid(definition,node,choice,source,responder,stamp);}
    boolean conversationStart(SceneConversation definition,ActorState source,int responder,int stamp){return logic.conversationStart(definition,source,responder,stamp);}
    boolean conversationResponse(SceneConversation definition,String node,int choice,ActorState source,int responder,int stamp){return logic.conversationResponse(definition,node,choice,source,responder,stamp);}
    boolean scriptedSpawn(SceneActions.Spawn actor,int stamp,String source,String activator){return spawns.scripted(actor,stamp,source,activator);}
    public boolean walkable(float x,float y,int ignore){return collisions.walkable(x,y,ignore);}
    /** Source ids whose static collision must be ignored because the entity is open or destroyed. */
    public java.util.Set<Integer> disabledCollisionSources(){return collisions.disabledSources();}
    public SceneCollision.Penetration penetration(float x,float y,int ignore){return collisions.penetration(x,y,ignore);}
    @Override public boolean contact(float x,float y,int owner){return collisions.contact(x,y,owner);}
    @Override public boolean attachable(int id){return collisions.attachable(id);}
    @Override public int contactActor(float x,float y,float rotation,int owner,Object shape,int mask){return collisions.contactActor(x,y,rotation,owner,shape,mask);}
    @Override public boolean contact(float x,float y,float rotation,int owner,Object shape,int mask){return collisions.contact(x,y,rotation,owner,shape,mask);}
    /** Land one carried pot; pot behavior lives in PotSystem. */
    void potLand(SceneEntity entity,int stamp){pots.land(entity,stamp);}
    /** Pot land damage uses the same prepared-region path as every scripted attack. */
    void damageRegion(StarterCatalog.Model region,float x,float y,float rotation,int stamp,int owner){
        damage(region,x,y,rotation,stamp,owner,null);
    }
    /** Spawn a native Bomb hazard patch for a pot variant. */
    SceneEntity hazard(String config,Object hazard,float radius,int lifetime,Map<String,String> arguments,float x,float y,int stamp){
        var fields=new TreeMap<String,String>();
        fields.put("config",config);
        fields.put("native-type","com.threerings.projectx.dungeon.data.actor.Bomb");
        fields.put("shape","Circle");
        fields.put("radius",Float.toString(radius));
        fields.put("collision-flags",hazard==null?"16":"0");
        fields.put("hazard-lifetime",Integer.toString(lifetime));
        fields.putAll(arguments);
        var spec=new SceneContent.Spec(fields);
        var binding=new SceneContent.Entry("hazard:"+nextId,x,y,0,spec);
        var entity=new SceneEntity(nextId++,stamp,binding,spec,null,content);
        entity.hazardStatus=hazard;
        insert(entity);return entity;
    }
    @Override public void damage(StarterCatalog.Model region,float x,float y,float rotation,int stamp,int owner){
        damageSystem.damage(region,x,y,rotation,stamp,owner,null,null);
    }
    @Override public void damage(StarterCatalog.Model region,float x,float y,float rotation,int stamp,int owner,Object sourceShape){
        damageSystem.damage(region,x,y,rotation,stamp,owner,sourceShape,null);
    }
    @Override public void sweep(StarterCatalog.Model region,float x,float y,float rotation,int stamp,int owner,Set<Integer> hit){
        damageSystem.damage(region,x,y,rotation,stamp,owner,null,hit);
    }
    @Override public void sweep(StarterCatalog.Model region,float x,float y,float rotation,int stamp,int owner,Object shape,Set<Integer> hit){
        damageSystem.damage(region,x,y,rotation,stamp,owner,shape,hit);
    }
    public void reviveImpulse(ActorState player,int stamp){damageSystem.reviveImpulse(player,stamp);}
    @Override public void impulse(StarterCatalog.Model region,float x,float y,float rotation,int stamp,int owner){
        damageSystem.impulse(region,x,y,rotation,stamp,owner);
    }
    @Override public boolean monsters(Object region,float x,float y,float rotation){
        return damageSystem.monsters(region,x,y,rotation);
    }
    void advanceImpulse(SceneEntity e,int stamp){movementSystem.advanceImpulse(e,stamp);}
}
