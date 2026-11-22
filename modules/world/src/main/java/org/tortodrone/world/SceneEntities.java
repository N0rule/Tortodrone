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
    public void elevators(ElevatorControl control){elevatorSystem.control(control);}
    public record Pickup(int entityId,String item,boolean temporary,Integer equippedSlot,int heat) {
        public Pickup(int entityId,String item,boolean temporary,Integer equippedSlot){this(entityId,item,temporary,equippedSlot,0);}
    }
    private static final Set<String> SUPPORTED=Set.of("Actor","Agent","Stranger","Monster","Generator","Door","Switch","EntryState","Block","Block$Stateful","Pickup","Trap","Bomb","DungeonLift","Lift","TerritoryProp");
    private static boolean liftKind(String kind){return kind.equals("DungeonLift")||kind.equals("Lift");}
    /** Damage wakes a monster even when it was hit outside every activation radius. */
    static final int DAMAGE_ENGAGE_MILLIS=5000;
    /** Reuse a clear line of sight briefly; damage or a moved target invalidates it. */
    static final int SIGHT_CACHE_MILLIS=150;
    static final float SIGHT_CACHE_DISTANCE=.5f;
    /** Uniform broadphase cell for entity collision/perception queries. */
    static final float SPATIAL_CELL=2;
    /** Server-side wave policy; enforced here, never trusted from a client. */
    static final int MAX_ALIVE_MONSTERS=64;
    static final int MAX_ENGAGED_MONSTERS=32;
    static final int MAX_WAVE_SPAWNS_PER_TICK=4;
    /** Administrative spawns use the same ring policy as a wave, never an unbounded radius. */
    static final float MAX_ADMIN_SPAWN_RADIUS=128;
    private static final class SpatialIndex {
        private final Map<Long,List<SceneEntity>> cells=new HashMap<>();
        /** Largest inserted extent; queries widen by it instead of rescanning the scene. */
        private float reach;
        private static long key(int x,int y){return ((long)x<<32)^(y&0xffffffffL);}
        private static int cell(float value){return (int)Math.floor(value/SPATIAL_CELL);}
        void add(SceneEntity entity){
            remove(entity);
            int minX=cell(entity.x-entity.spatialExtent),maxX=cell(entity.x+entity.spatialExtent);
            int minY=cell(entity.y-entity.spatialExtent),maxY=cell(entity.y+entity.spatialExtent);
            var occupied=new long[(maxX-minX+1)*(maxY-minY+1)];int index=0;
            for(int x=minX;x<=maxX;x++)for(int y=minY;y<=maxY;y++){
                long key=key(x,y);occupied[index++]=key;cells.computeIfAbsent(key,ignored->new ArrayList<>()).add(entity);
            }
            entity.spatialCells=occupied;
            entity.spatialMinX=minX;entity.spatialMaxX=maxX;entity.spatialMinY=minY;entity.spatialMaxY=maxY;
            reach=Math.max(reach,entity.spatialExtent);
        }
        /** Re-bucket only when the occupied cell block changes; ordinary movement stays put. */
        void move(SceneEntity entity){
            int minX=cell(entity.x-entity.spatialExtent),maxX=cell(entity.x+entity.spatialExtent);
            int minY=cell(entity.y-entity.spatialExtent),maxY=cell(entity.y+entity.spatialExtent);
            if(entity.spatialCells!=null&&minX==entity.spatialMinX&&maxX==entity.spatialMaxX
                    &&minY==entity.spatialMinY&&maxY==entity.spatialMaxY)return;
            add(entity);
        }
        float reach(){return reach;}
        void remove(SceneEntity entity){
            if(entity.spatialCells==null)return;
            for(long key:entity.spatialCells){var bucket=cells.get(key);if(bucket==null)continue;bucket.remove(entity);if(bucket.isEmpty())cells.remove(key);}
            entity.spatialCells=null;
        }
        List<SceneEntity> near(float minX,float minY,float maxX,float maxY){
            var found=new TreeMap<Long,SceneEntity>();
            int left=cell(minX),right=cell(maxX),bottom=cell(minY),top=cell(maxY);
            for(int x=left;x<=right;x++)for(int y=bottom;y<=top;y++){
                var bucket=cells.get(key(x,y));if(bucket==null)continue;
                for(var entity:bucket)found.put(entity.order,entity);
            }
            return new ArrayList<>(found.values());
        }
        void clear(){cells.clear();reach=0;}
    }
    /** Validate one prepared item-use definition before a scene exposes it to play. */
    public static void validateItemUse(SceneContent.Spec spec)throws java.io.IOException{
        org.tortodrone.core.world.ItemUseRules.validate(spec);
    }
    /** Spawn the static prop authored by a native actor-item use. */
    public int spawnItemActor(ActorState player,SceneContent.Spec actor,int stamp){
        if(entities.size()+pendingActors.size()>=4096)return 0;
        var spec=new SceneContent.Spec(new TreeMap<>(actor.fields()));
        var binding=new SceneContent.Entry("item:"+nextId,player.x(),player.y(),player.rotation(),spec);
        var entity=spawn(binding,spec,null,stamp);entity.faction=0;return entity.id;
    }
    /** Create and carry the lift authored by a native lift-item use. */
    public int liftItem(ActorState player,SceneContent.Spec lift,int stamp){
        if(entities.size()+pendingActors.size()>=4096)return 0;
        var binding=new SceneContent.Entry("item:"+nextId,player.x(),player.y(),player.rotation(),lift);
        var entity=spawn(binding,lift,null,stamp);
        if(!carry(entity.id,player,stamp)){unlink(entity);return 0;}return entity.id;
    }
    /** Reclaim an item entity whose payment transaction was refused. */
    public void discard(int id){var entity=entities.get(id);if(entity!=null)unlink(entity);}
    /** Native collision bit 1 << 13 ("lift" in the 2026 mask.properties registry): the served
     *  Lift Barriers state exactly this bit, and the 2026 lift base adds it to thrown and
     *  resting collision masks, so thrown lifts stop at closed barriers (see the archive's
     *  vendored {@code Lift} base and LiftBarrierProbe). Carriers are stopped by the pawn rule
     *  in {@link #penetration}, because the raw mask test alone never blocks them: the pawn mask
     *  lacks the bit and a carried lift's own collision drops to 0 in 2026. */
    private static final int LIFT_COLLISION=8192;
    private static final Object POINT=new FireCatalog.DataValue("com.threerings.tudey.shape.config.ShapeConfig$Point",Map.of());

    /** Served {@code Dynamic/Lift Objects} pot arguments, condensed to what the land handler
     *  executes: {@code Pot (Base)} {@code land} runs DamageRegionFromPoint with damage
     *  [Status(Ailment), DepthScale PC/Damage/Base/Default (flags 2)] and region expansion
     *  = Blast Radius, then the per-variant Extra Action spawns ground hazards (Fire Pot one
     *  ScorcherTorch, Damage {flags 2, Fire 1.0/100, ULTRA/MODERATE}, toggled by its Lifetime
     *  argument's served choice to a 6 Second cleanup fuse while the streamed Lifetime argument
     *  renders the flame for its authored 4 seconds; Oil Pot nine Oil Slick patches).
     *  Damage stops at 4 seconds conservatively: stripped handler conditions do not establish
     *  the native damage cutoff. Cleanup must never extend the active damage region.
     *  Pots without an entry below stay inert. */
    private record Pot(float blast,StarterCatalog.Model ailment,Object hazardDamage,String hazardConfig,float hazardRadius,int hazardLifetime) { }
    /** The capture separates the pot smash ailment from the torch hazard. The direct
     *  torch carries Fire with Power 100; the smash keeps the previously authored 80. */
    private static StarterCatalog.Model fireStatus(int power){
        return new StarterCatalog.Model("Fire",Map.of(
            "Chance",1f,"Power",power,
            "Status Chance",new FireCatalog.EnumValue(org.tortodrone.core.WireTypes.STATUS_CHANCE,"ULTRA",5),
            "Status Power",new FireCatalog.EnumValue(org.tortodrone.core.WireTypes.STATUS_POWER,"MODERATE",2)));
    }
    private static final Map<String,Pot> POTS=Map.of(
        "Dynamic/Lift Objects/Clay Pot",new Pot(.5f,null,null,null,0,0),
        "Dynamic/Lift Objects/Fire Pot",new Pot(1f,fireStatus(80),
            new FireCatalog.DataValue(org.tortodrone.core.WireTypes.DAMAGE_STATUS,Map.of(
                "flags",2,"statusCondition",fireStatus(100))),
            "Bomb/Monster/ScorcherTorch",1f,6000),
        "Dynamic/Lift Objects/Oil Pot",new Pot(1f,null,null,"Bomb/Monster/Oil Slick",.45f,15000));

    private final SceneContent content;private final CollisionMap terrain;
    private final SceneActions actions;
    private final Map<String,List<SceneContent.Entry>> anchors=new LinkedHashMap<>();
    private final Map<String,ActorState> effectSources=new LinkedHashMap<>();
    private final Map<String,Map<String,Boolean>> logicVariables=new LinkedHashMap<>();
    private int variableCount;
    private ActorState entranceOverride;
    public ActorState defaultEntrance(){return entranceOverride;}
    private long nextEffectSource;
    private final SceneCollision staticCollision;
    private final int pawnCollisionMask;
    private final Map<Integer,SceneEntity> entities=new LinkedHashMap<>();
    private final MovementSystem movementSystem=new MovementSystem(this);
    SceneEntity entity(int id){return entities.get(id);}
    org.tortodrone.core.world.CollisionMap terrain(){return terrain;}
    SceneCollision staticCollision(){return staticCollision;}
    int pawnCollisionMask(){return pawnCollisionMask;}
    SceneContent content(){return content;}
    List<ActorState> occupants(){return players;}
    ScenePathBudget pathBudget(){return pathBudget;}
    MonsterMetrics monsterMetrics(){return monsterMetrics;}
    java.util.random.RandomGenerator random(){return random;}
    List<SceneEntity> candidatesNear(float minX,float minY,float maxX,float maxY){return candidates(minX,minY,maxX,maxY);}
    void moved(SceneEntity entity){spatial.move(entity);}
    java.util.Collection<SceneEntity> entityValues(){return entities.values();}
    boolean activePlayer(int id){return playerActive.test(id);}
    /** Interactive props, doors and lifts are the only entities an interaction scan reads. */
    private final Set<SceneEntity> interactionCandidates=new LinkedHashSet<>();
    private static boolean interactionCandidate(SceneEntity entity){return entity.interactive||liftKind(entity.kind)||entity.kind.equals("Door");}
    private final ArrayDeque<SceneEntity> pendingActors=new ArrayDeque<>();
    private int sceneSpawnStamp,sceneSpawnCount;
    private record SpawnSource(SceneEntities scene,int id,int faction,String identity) { }
    private final Map<SceneEntity,List<SceneEntity>> arsenalProps=new LinkedHashMap<>();
    private int nextId=1_000_000_000,lastStamp;
    private long entityOrder;
    private final ScenePathBudget pathBudget=new ScenePathBudget();
    private final MonsterMetrics monsterMetrics=new MonsterMetrics();
    private final SpatialIndex spatial=new SpatialIndex();
    private final boolean spatialQuery;
    private final java.util.random.RandomGenerator random;
    /** Catalogue for wave spawns; an empty catalogue refuses every type id. */
    private final MonsterCatalog monsters;
    private final Map<String,Wave> waves=new LinkedHashMap<>();
    private long generation=1;
    private int engagedAtTickStart;
    private static final class Wave {
        final MonsterGroup group;
        int spawned,previousAlive,nextSpawn=Integer.MIN_VALUE;
        boolean cleared;
        Wave(MonsterGroup group){this.group=group;}
    }
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
        var rules=new ArrayList<SceneActions.Rule>();var anchorKeys=new HashSet<String>();
        for(var binding:content.entries()){
            if(binding.spec().text("kind","").equals("SceneAnchor")){
                if(!binding.key().matches("[0-9]+")||Integer.parseInt(binding.key())<1||!anchorKeys.add(binding.key())||!Float.isFinite(binding.x())||!Float.isFinite(binding.y())||!Float.isFinite(binding.rotation()))throw new IllegalArgumentException("Invalid scene anchor identity/transform");
                var tags=binding.spec().text("source-tags","").split(",");if(tags.length>256)throw new IllegalArgumentException("Too many scene anchor tags");
                for(String tag:tags)if(!tag.isBlank()){
                    if(tag.length()>256||tag.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid scene anchor tag");
                    anchors.computeIfAbsent(tag,key->new ArrayList<>()).add(binding);
                }
            }else if(binding.spec().text("kind","").equals("SceneTrigger")){
                try{rules.add(SceneActions.rule(binding));}catch(java.io.IOException invalid){throw new IllegalArgumentException("Invalid scene handler",invalid);}
            }else spawn(binding,binding.spec(),null,stamp);
        }
        actions=new SceneActions(rules,(tag,name,time,source)->{signal(tag,name,time,source);return true;},random,this::destroyTagged,this::spawnScripted,this::pawnStatuses,this::startConversation,this::sourceEffect,this::nextFloor,this::setMemory);
        actions.nearestSignals(this::nearestSignal);
        actions.targets(this::variableTargets);
        actions.sourceSignals((name,time,source)->{
            var receiver=scriptSource(source,time);if(receiver==null)return false;
            actions.signalSource(source,name,time,source);
            if(source.startsWith("actor:")){var target=entities.get(receiver.id());if(target!=null)entitySignal(target,name,time);}
            return true;
        });
        actions.entrances((target,time,source,activator)->{
            var resolved=variableTargets(target,time,source,activator);if(resolved.isEmpty())return false;
            var point=scriptSource(resolved.getFirst(),time);if(point==null)return false;
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
        actions.variables(this::setVariable,this::variable);
        actions.dialogClears((only,time,source,activator)->{
            if(scriptSource(source,time)==null)return false;
            int target=0;
            if(only){var player=players.stream().filter(p->activator.equals("actor:"+p.id())&&playerActive.test(p.id())).findFirst().orElse(null);if(player==null)return true;target=player.id();}
            return dialogClearSink.clear(target,time);
        });
        actions.gameplayEffects(this::gameplayEffect);
        actions.impulses((operation,time,source)->{var origin=scriptSource(source,time);if(origin==null)return false;impulse(operation.config(),origin.x(),origin.y(),origin.rotation(),time,0);return true;});
        actions.objectives((operation,time,source,activator)->{
            var player=players.stream().filter(p->activator.equals("actor:"+p.id())&&playerActive.test(p.id())).findFirst().orElse(null);var origin=scriptSource(source,time);
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
    private void insert(SceneEntity entity){
        entity.order=++entityOrder;entities.put(entity.id,entity);spatial.add(entity);
        if(interactionCandidate(entity))interactionCandidates.add(entity);
        if(entity.kind.equals("Monster"))monsterMetrics.spawned();
    }
    private void unlink(SceneEntity entity){
        if(entities.remove(entity.id)!=null&&entity.kind.equals("Monster"))monsterMetrics.despawned();
        interactionCandidates.remove(entity);
        spatial.remove(entity);
    }
    private SceneEntity spawn(SceneContent.Entry binding,SceneContent.Spec spec,SceneEntity pad,int stamp){
        String nativeType=spec.text("native-type","");String kind=nativeType.substring(nativeType.lastIndexOf('.')+1);
        if(!SUPPORTED.contains(kind))throw new IllegalArgumentException("Unsupported prepared scene actor: "+kind);
        var entity=new SceneEntity(nextId++,stamp,binding,spec,pad,content);insert(entity);
        if(kind.equals("Monster")&&spec.integer("activate-time",0)>0)entity.activity=3;
        if(kind.equals("Generator")&&!spec.child().fields().isEmpty()){
            generate(entity,stamp);
        }
        return entity;
    }
    private boolean soloRewards;
    /** Set for instances that never admit a second member (the private tutorial). */
    public void soloRewards(boolean value){soloRewards=value;}
    @Override public boolean spawnPickup(StarterCatalog.Model specification,float x,float y,float rotation,int stamp){
        if(specification==null||!(specification.arguments().get("fields") instanceof String json)||entities.size()+pendingActors.size()>=4096)return false;
        try{
            if(!(org.tortodrone.core.Json.parse(json) instanceof Map<?,?> raw))return false;
            var fields=new HashMap<String,String>();for(var entry:raw.entrySet()){
                if(!(entry.getKey() instanceof String key)||!(entry.getValue() instanceof String value))return false;
                fields.put(key,value);
            }
            var spec=new SceneContent.Spec(fields);
            if(!spec.text("native-type","").equals("com.threerings.projectx.dungeon.data.actor.Pickup"))return false;
            boolean item=!spec.text("pickup-item","").isBlank(),heat=spec.integer("pickup-heat",0)>0;
            int heal=spec.integer("pickup-heal",0);
            if((item?1:0)+(heat?1:0)+(heal>0?1:0)!=1||heal<0||heal>1_000_000)return false;
            // Native party pickups pay every member. Heat claims already credit the whole
            // instance; an item/crown claim has one claimant, which is exact only when the
            // instance can never hold a second member.
            if(item&&spec.flag("pickup-party",false)&&!soloRewards)return false;
            spawn(new SceneContent.Entry("loot:"+nextId,x,y,rotation,spec),spec,null,stamp);return true;
        }catch(java.io.IOException invalid){throw new IllegalArgumentException("Invalid prepared loot actor",invalid);}
    }
    /** A lifecycle graph may request a prepared entity, never an arbitrary native class.
     * Stage the actor: death callbacks may run while the damage target set is being iterated. */
    @Override public Object sceneActorSource(int source){
        var parent=entities.get(source);return parent==null||!parent.kind.equals("Monster")?null:new SpawnSource(this,source,parent.faction,"actor:"+source);
    }
    @Override public boolean spawnSceneActor(String template,float x,float y,float rotation,int stamp,Object source){
        var spec=content.actorTemplates().get(template);
        if(spec==null||!(source instanceof SpawnSource parent)||parent.scene()!=this||!entities.containsKey(parent.id())||!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(rotation)
            ||entities.size()+pendingActors.size()>=4096||nextId==Integer.MAX_VALUE)return false;
        if(sceneSpawnStamp!=stamp){sceneSpawnStamp=stamp;sceneSpawnCount=0;}
        if(sceneSpawnCount>=128)return false;
        var binding=new SceneContent.Entry("spawn:"+nextId,x,y,rotation,spec);
        var entity=new SceneEntity(nextId,stamp,binding,spec,null,content);entity.faction=parent.faction();entity.scriptSource=parent.identity();
        if(spec.integer("activate-time",0)>0)entity.activity=3;
        pendingActors.addLast(entity);nextId++;sceneSpawnCount++;return true;
    }
    private void generate(SceneEntity pad,int stamp){
        var damage=pad.spec.model("generated-damage");
        boolean blocked=false;
        if(damage!=null&&damage.arguments().get("value") instanceof FireCatalog.DataValue){
            for(var player:players)if(pad.spec.contains(pad.x,pad.y,pad.rotation,player.x(),player.y(),ScenePortals.PAWN_RADIUS)){
                playerDamage.accept(new PlayerHit(player.id(),damage.arguments().get("value"),stamp,0));blocked=true;
            }
        }
        if(blocked){pad.nextGeneration=stamp+pad.spec.integer("delay",0);return;}
        spawn(pad.binding,pad.spec.child(),pad,stamp);pad.count++;pad.nextGeneration=0;pad.flags&=~2;
    }
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
    public boolean canThrow(int id){var e=entities.get(id);return e!=null&&e.spec.flag("can-throw",false);}
    private SceneEntity snapTarget(SceneEntity lift,ActorState player){
        String tag=lift.spec.text("snap-tag","");if(tag.isBlank())return null;
        float ox=content.stats().getOrDefault("player.interaction-offset-x",.7f),oy=content.stats().getOrDefault("player.interaction-offset-y",0f);
        float c=(float)Math.cos(player.rotation()),s=(float)Math.sin(player.rotation());
        float x=player.x()+ox*c-oy*s,y=player.y()+ox*s+oy*c;
        return entities.values().stream().filter(e->e.destroyed==Integer.MAX_VALUE&&Arrays.asList(e.spec.text("tags","").split(",")).contains(tag)
                &&e.spec.contains(e.x,e.y,e.rotation,x,y,content.stats().getOrDefault("player.interaction-radius",.4f)))
            .min(Comparator.comparingDouble(e->Math.hypot(e.x-x,e.y-y))).orElse(null);
    }
    public boolean prepareDrop(int id,ActorState player){
        var e=entities.get(id);if(e==null||e.carrier!=player.id())return false;
        var target=snapTarget(e,player);e.dropPoint=target==null?null:new SceneLiftState.Point(target.x,target.y);return target!=null;
    }
    public void throwLift(int id,ActorState player,int stamp){
        var e=entities.get(id);if(e==null||e.carrier!=player.id())return;
        e.carrier=0;e.owner=player.id();e.ownerOid=player.clientOid();e.flags=e.flags&~4|2;e.faction=player.id();e.excluded=new int[0];
        e.x=player.x();e.y=player.y();e.rotation=e.direction=player.rotation();e.thrownAt=stamp;spatial.move(e);
        e.z=content.stats().getOrDefault("player.lift-height",0f);
    }
    public void dropLift(int id,ActorState player,int stamp){
        var e=entities.get(id);if(e==null||e.carrier!=player.id())return;
        // Native Dungeoneer.drop invokes the current actionable with the dropped
        // lift as Activator. Test that owned prompt before releasing the carrier.
        var prompt=interaction(player.x(),player.y(),player.rotation(),player.id(),id);
        var unlock=e.spec.text("actionable-signal","").equals("gold_unlock")&&prompt!=null&&prompt.action().equals("UnlockGold")?entities.get(prompt.actorId()):null;
        e.carrier=0;e.flags&=~6;e.z=0;
        float distance=(ScenePortals.PAWN_RADIUS+e.spec.number("radius",0))*1.01f;
        var target=snapTarget(e,player);
        float x=target==null?player.x()+(float)Math.cos(player.rotation())*distance:target.x;
        float y=target==null?player.y()+(float)Math.sin(player.rotation())*distance:target.y;
        if(terrain.walkable(x,y)&&walkable(x,y,e.id)){e.x=x;e.y=y;}else {e.x=player.x();e.y=player.y();}spatial.move(e);
        e.dropPoint=new SceneLiftState.Point(e.x,e.y);
        e.rotation=e.direction=player.rotation();e.faction=player.id();e.excluded=new int[0];
        if(unlock!=null&&unlock.kind.equals("Door")&&unlock.spec.text("door-lock","").equals("gold")&&(unlock.flags&2)==0){
            entitySignal(unlock,"open",stamp);e.destroyed=stamp;e.scriptDestroyed=true;e.status.clear();return;
        }
        if(e.vial!=null)land(e,stamp);
    }
    public void releaseLifts(int player,int stamp){
        for(var e:List.copyOf(entities.values()))if(e.carrier==player){
            e.carrier=0;e.flags&=~6;if(e.vial!=null)e.destroyed=stamp;
        }
    }
    public List<Pickup> takePickups(int player){
        var result=new ArrayList<Pickup>();
        for(var e:entities.values())if(e.claim==player&&!e.claimTaken){e.claimTaken=true;result.add(pickup(e));}
        return List.copyOf(result);
    }
    public List<Pickup> pendingPickups(int player){
        return entities.values().stream().filter(e->e.claim==player).map(SceneEntities::pickup).toList();
    }
    private static Pickup pickup(SceneEntity e){return new Pickup(e.id,e.spec.text("pickup-item",""),e.spec.flag("pickup-transient",false),e.spec.fields().containsKey("pickup-equip-slot")?e.spec.integer("pickup-equip-slot",0):null,e.spec.integer("pickup-heat",0));}
    public int heatClaim(int player,int actor){var e=entities.get(actor);return e!=null&&e.destroyed==Integer.MAX_VALUE&&e.claim==player&&e.claimTaken?e.spec.integer("pickup-heat",0):0;}
    public boolean activateHeat(int player,int actor){
        var e=entities.get(actor);if(heatClaim(player,actor)<=0)return false;
        var target=players.stream().filter(p->p.id()==player&&p.clientOid()>0).findFirst().orElse(null);if(target==null)return false;
        e.pickupState=new ScenePickupState(target.clientOid(),lastStamp);
        float speed=e.spec.number("vacuum-speed",0);
        e.destroyed=lastStamp+(speed<=0?0:(int)(Math.hypot(target.x()-e.x,target.y()-e.y)*1000/speed));e.claim=0;e.claimTaken=false;return true;
    }
    public void reconsiderPickups(int player){for(var e:entities.values())e.refusedPickups.remove(player);}
    public void completePickup(int player,int actor,boolean success){
        var e=entities.get(actor);if(e==null||e.claim!=player)return;
        // Claims are produced by advance: settle on that scene tick, including under
        // a deterministic clock. Wall time can precede an explicitly advanced tick.
        e.claim=0;e.claimTaken=false;if(success)e.destroyed=lastStamp;else e.refusedPickups.add(player);
    }
    public void cancelClaims(int player){for(var e:entities.values()){if(e.claim==player&&!e.claimTaken)e.claim=0;e.refusedPickups.remove(player);}}
    public int interactionDuration(int id){var e=entities.get(id);return e==null?670:e.spec.integer("interaction-duration",Set.of("Talk","Shop").contains(interactionAction(e))?1667:670);}
    private String interactionAction(SceneEntity e){
        String station=e.spec.text("town-kind","");
        String fallback=liftKind(e.kind)?"Lift":
            content.conversations().containsKey(e.spec.text("dialogue",e.config.name()))?"Talk":
            Set.of("OpenShop","OpenDepot","OpenAuction").contains(station)?"Shop":"Interact";
        return e.spec.text("interaction-action",fallback);
    }
    public InteractionState interaction(float x,float y,float rotation){
        return interaction(x,y,rotation,0,0);
    }
    /** Carried keys can address only a compatible closed door in this scene. */
    public InteractionState interaction(float x,float y,float rotation,int owner,int lift){
        var carried=lift==0?null:entities.get(lift);
        boolean key=carried!=null&&carried.destroyed==Integer.MAX_VALUE&&carried.carrier==owner&&liftKind(carried.kind)&&carried.spec.text("actionable-signal","").equals("gold_unlock");
        SceneEntity nearest=null;float best=Float.POSITIVE_INFINITY;
        float c=(float)Math.cos(rotation),s=(float)Math.sin(rotation);
        for(var e:interactionCandidates)if((key?e.kind.equals("Door")&&e.spec.text("door-lock","").equals("gold")&&(e.flags&2)==0:e.interactive||liftKind(e.kind)&&(e.flags&6)==0)&&e.destroyed==Integer.MAX_VALUE){
            float ox=e.spec.number("interaction-offset-x",.7f),oy=e.spec.number("interaction-offset-y",0);
            float px=x+ox*c-oy*s,py=y+ox*s+oy*c;
            float distance=(float)Math.hypot(px-e.x,py-e.y);
            float radius=e.spec.number("interaction-radius",.4f)+e.spec.number("interaction-expansion",0);
            if(e.spec.contains(e.x,e.y,e.rotation,px,py,radius)&&distance<best){nearest=e;best=distance;}
        }
        if(nearest==null)return null;
        String description=nearest.spec.text("description","");
        String action=key?"UnlockGold":interactionAction(nearest);
        return new InteractionState(nearest.id,description.isEmpty()?null:new StarterCatalog.Model(description,Map.of()),action,
            nearest.entrySource()?nearest.binding.key():null);
    }
    public void advance(int stamp,List<ActorState> occupants){
        // Publish only the batch staged before this tick. Work queued by spawned
        // actors cannot recursively drain this batch; capacity also bounds later ticks.
        for(int count=pendingActors.size();count>0;count--){var entity=pendingActors.removeFirst();insert(entity);}
        var players=occupants.stream().filter(p->p.activity()!=TownMovement.DYING&&p.activity()!=TownMovement.DEAD&&p.activity()!=TownMovement.RESURRECT).toList();
        this.players=players;
        engagedAtTickStart=(int)entities.values().stream().filter(e->e.kind.equals("Monster")&&e.destroyed==Integer.MAX_VALUE&&e.band==SceneMonsterBand.ENGAGED).count();
        advanceWaves(stamp);
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
        while(!links.isEmpty()&&links.peekFirst().stamp()<=stamp){var link=links.removeFirst();clearLinks(link.entity(),stamp,link.ghost());}
        float elapsed=lastStamp==0?0:Math.min(.25f,(stamp-lastStamp)/1000f);lastStamp=stamp;pathBudget.begin(stamp);
        for(var e:List.copyOf(entities.values())){
            if(e.destroyed!=Integer.MAX_VALUE){
                if(e.trip!=null)cancelElevator(e.trip,stamp);
                // Monster destroyed time already includes the native death animation.
                // Retain one short publication grace, rather than counting it twice.
                if(e.scriptDestroyed?stamp-e.destroyed>0:stamp-e.destroyed>=100){
                    actions.cancelSource("actor:"+e.id);
                    if(e.shutdown!=null){
                        if(effectSources.size()>=4096||nextEffectSource==Long.MAX_VALUE)throw new IllegalStateException("Too many scene shutdown contexts");
                        String identity="effect:"+(++nextEffectSource);effectSources.put(identity,e.snapshot());
                        if(!actions.perform(e.shutdown,stamp,identity,"actor:"+e.id))effectSources.remove(identity);
                    }
                    unlink(e);
                    var removedVariables=logicVariables.remove("actor:"+e.id);if(removedVariables!=null)variableCount-=removedVariables.size();
                    actions.removed(new HashSet<>(Arrays.asList(e.spec.text("tags","").split(","))),
                        tag->entities.values().stream().anyMatch(other->Arrays.asList(other.spec.text("tags","").split(",")).contains(tag)),stamp,"actor:"+e.id);
                    if(e.pad!=null)e.pad.nextGeneration=e.destroyed+e.pad.spec.integer("delay",0);
                }
                continue;
            }
            if(e.kind.equals("Pickup")&&!e.claimTaken&&e.spec.integer("lifespan",0)>0&&stamp-e.created>=e.spec.integer("lifespan",0)){
                e.destroyed=stamp;e.claim=0;continue;
            }
            // Native PickupLogic flashes expiring pickups during their last five seconds.
            if(e.kind.equals("Pickup")&&e.spec.integer("lifespan",0)>0&&stamp-e.created>e.spec.integer("lifespan",0)-5000)e.flags|=2;
            if(e.kind.equals("Generator")&&e.nextGeneration>0){
                e.flags=stamp+e.spec.integer("warning",0)>=e.nextGeneration?2:0;
                if(SceneClock.reached(stamp,e.nextGeneration)){e.flags&=~2;generate(e,e.nextGeneration);}
            }
            if(e.kind.equals("Trap"))cycle(e,stamp);
            if(e.spec.fields().containsKey("hazard-lifetime")&&stamp-e.created>=e.spec.integer("hazard-lifetime",0)){
                e.destroyed=e.created+e.spec.integer("hazard-lifetime",0);
                continue;
            }
            if(e.hazardStatus!=null&&stamp-e.created<e.spec.integer("hazard-active-lifetime",e.spec.integer("hazard-lifetime",Integer.MAX_VALUE))&&stamp>=e.hazardNext){
                // Native Extra Action torch: applies its status damage region to everything
                // inside the patch on the Floor trap cadence (hazard-refractory, default 500).
                e.hazardNext=stamp+e.spec.integer("hazard-refractory",500);
                var shape=new FireCatalog.DataValue(org.tortodrone.core.WireTypes.SHAPE_CIRCLE,
                    List.of(new FireCatalog.FieldValue("radius","float",e.spec.number("radius",0))));
                var hitArgs=new LinkedHashMap<String,Object>(Map.of("damage",e.hazardStatus,
                    "region",new FireCatalog.DataValue(org.tortodrone.core.WireTypes.REGION_DEFAULT,Map.of(
                        "location",new FireCatalog.DataValue("com.threerings.tudey.config.TargetConfig$Source",List.of()),"expansion",0f))));
                hitArgs.put("shape",shape);
                damage(new StarterCatalog.Model("DamageRegionFromPoint",hitArgs),e.x,e.y,e.rotation,stamp,e.id,null);
            }
            if(e.detonateAt>0&&SceneClock.reached(stamp,e.detonateAt)){
                e.destroyed=e.detonateAt;
                String graph=e.spec.text("detonation-action","");if(!graph.isBlank())try{attack.accept(e.snapshot(),FireCatalog.actionJson(graph));}catch(java.io.IOException invalid){throw new IllegalStateException("Invalid block detonation",invalid);}
                continue;
            }
            if(e.kind.equals("Door")&&(e.flags&4)!=0&&stamp-e.stateEntered>e.spec.integer("door-speed",0))e.flags&=~4;
            if(e.kind.equals("Switch"))switches(e,stamp,players);
            if(e.elevator!=null)elevatorSystem.advance(e,stamp,players);
            if(e.spec.flag("heal-pad",false)&&SceneClock.reached(stamp,e.nextHeal)){
                e.nextHeal=stamp+Math.round(e.spec.number("heal-interval",0)*1000);
                for(var player:players)if(e.spec.contains(e.x,e.y,e.rotation,player.x(),player.y(),ScenePortals.PAWN_RADIUS)
                    &&playerHeal.test(player.id(),e.spec.integer("heal",0))){
                    String graph=e.spec.text("heal-action","");if(!graph.isBlank())try{attack.accept(e.snapshot(),FireCatalog.actionJson(graph));}
                    catch(java.io.IOException invalid){throw new IllegalStateException("Invalid prepared heal action",invalid);}
                }
            }
            if(e.kind.equals("Trap")&&e.state==2&&e.spec.fields().containsKey("sensor")){
                Object sensor=e.spec.model("sensor").arguments().get("value");float c=(float)Math.cos(e.rotation),s=(float)Math.sin(e.rotation);
                for(var player:players){float dx=player.x()-e.x,dy=player.y()-e.y;
                    if(SceneClock.reached(stamp,e.hazardHits.getOrDefault(player.id(),0))&&SceneShape.intersects(sensor,dx*c+dy*s,-dx*s+dy*c,ScenePortals.PAWN_RADIUS)){
                        try{attack.accept(e.snapshot(),FireCatalog.actionJson(e.spec.text("hazard-action","")));}
                        catch(java.io.IOException invalid){throw new IllegalStateException("Invalid prepared trap action",invalid);}
                        e.hazardHits.put(player.id(),stamp+e.spec.integer("hazard-refractory",0));
                    }
                }
                e.hazardHits.keySet().removeIf(id->players.stream().noneMatch(p->p.id()==id));
            }
            // PickupLogic's minimum activation time lets the spawn visual complete
            // before a knight standing on the pad can collect its replacement.
            if(e.kind.equals("Pickup")&&e.claim==0&&stamp-e.created>=1000)collect(e,elapsed,players);
            if(e.kind.equals("Monster")){
                e.status.advance(stamp,(int)(elapsed*1000),(e.flags&2)!=0,amount->{if(e.health>0)hurt(e,(float)amount,stamp,false,false);},amount->{if(e.health>0)e.health=Math.min(e.maximumHealth,e.health+(float)amount*e.status.state().modifiers().heal());});
                if(e.program!=null)scriptedProgram(e,stamp,elapsed);
                else if(e.script!=null)scriptedMovement(e,stamp,elapsed);
                else if(e.health==0)e.status.clear();else {
                    if(e.status.state().modifiers().canMove())advanceImpulse(e,stamp);else e.impulse=null;
                    // Idle wander and party follow are authored motion, not combat pursuit,
                    // and keep their every-tick integration without players.
                    if(e.wander!=null||e.follow!=null){e.band=SceneMonsterBand.ENGAGED;move(e,stamp,elapsed,players);}
                    else think(e,stamp,elapsed,players);
                }
            }
            if(liftKind(e.kind))lift(e,stamp,elapsed,players);
            if(e.activity==1&&stamp-e.activityStarted>=e.spec.integer("react-time",0)||e.activity==3&&stamp-e.activityStarted>=e.spec.integer("activate-time",0))e.activity=0;
        }
        for(var e:List.copyOf(entities.values()))for(var pulse:e.status.takePulses())statusPulse(e.id,pulse,e.x,e.y,stamp);
        for(var e:List.copyOf(entities.values()))for(var action:e.status.takeActions())attack.accept(e.snapshot(),action);
        for(var sensor:content.entries())if(sensor.spec().flag("scene-sensor",false)){
            int mask=sensor.spec().integer("sensor-mask",0),pawnFlags=Math.round(content.stats().getOrDefault("player.collision-flags",4f));
            if((mask&pawnFlags)==0)continue;
            for(var player:players)if(playerActive.test(player.id())&&sensor.spec().contains(sensor.x(),sensor.y(),sensor.rotation(),player.x(),player.y(),ScenePortals.PAWN_RADIUS))
                actions.signal(sensor.spec().text("watch-tag",""),sensor.spec().text("signal",""),stamp,"actor:"+player.id());
        }
        actions.advance(stamp);
        if(!effectSources.isEmpty())effectSources.keySet().retainAll(actions.workSources());
    }
    /** Register a wave for this scene generation. Unknown or unsupported types fail here,
     *  before any actor is created. */
    public void addWave(MonsterGroup group){
        Objects.requireNonNull(group);
        if(group.generation()!=generation)throw new IllegalArgumentException("Monster group belongs to a stale scene generation");
        if(waves.containsKey(group.id()))throw new IllegalArgumentException("Duplicate monster group: "+group.id());
        try{monsters.spawnable(group.typeId());}
        catch(java.io.IOException refused){throw new IllegalArgumentException("Monster group "+group.id()+": "+refused.getMessage(),refused);}
        for(var anchor:group.anchors()){
            if(!terrain.walkable(anchor.x(),anchor.y()))
                throw new IllegalArgumentException("Monster group anchor is outside walkable terrain: "+anchor);
            if(!staticCollision.isEmpty()&&(!staticCollision.floorSupported(anchor.x(),anchor.y())
                ||staticCollision.penetration(pawnCollisionMask,anchor.x(),anchor.y(),ScenePortals.PAWN_RADIUS).present()))
                throw new IllegalArgumentException("Monster group anchor is blocked: "+anchor);
        }
        waves.put(group.id(),new Wave(group));
    }
    /**
     * Spawn catalogue-backed monsters for an authorized administrator. The complete request is
     * validated before insertion, so an unknown/unsupported type, blocked position or room/cap
     * refusal never leaves a partial group behind. This is the same creation path as scene
     * placement and waves.
     */
    public int spawnMonsters(String reference,float x,float y,float rotation,int count,float radius,int stamp)throws java.io.IOException {
        if(count<1||count>MAX_ALIVE_MONSTERS)
            throw new java.io.IOException("Count must be between 1 and "+MAX_ALIVE_MONSTERS);
        if(!Float.isFinite(radius)||radius<0||radius>MAX_ADMIN_SPAWN_RADIUS)
            throw new java.io.IOException("Radius must be between 0 and "+MAX_ADMIN_SPAWN_RADIUS);
        if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(rotation))
            throw new java.io.IOException("Invalid monster spawn position");
        MonsterType type=monsters.resolveSpawnable(reference);
        int alive=liveMonsters();
        if(alive+count>MAX_ALIVE_MONSTERS)
            throw new java.io.IOException("The scene already has "+alive+" live monsters; the limit is "+MAX_ALIVE_MONSTERS);
        if((long)entities.size()+pendingActors.size()+count>=4096||(long)nextId+count>Integer.MAX_VALUE)
            throw new java.io.IOException("The scene has no room for new actors");
        var fields=monsters.fields(type);
        var points=new ArrayList<ScenePathing.Point>(count);
        for(int index=0;index<count;index++){
            float angle=(float)(Math.PI*2*index/count);
            float px=x+(count==1?0:radius*(float)Math.cos(angle));
            float py=y+(count==1?0:radius*(float)Math.sin(angle));
            if(!terrain.walkable(px,py))
                throw new java.io.IOException("Spawn position is outside walkable terrain");
            if(!staticCollision.isEmpty()&&(!staticCollision.floorSupported(px,py)
                    ||staticCollision.penetration(pawnCollisionMask,px,py,ScenePortals.PAWN_RADIUS).present()))
                throw new java.io.IOException("Spawn position is blocked");
            points.add(new ScenePathing.Point(px,py));
        }
        var created=new ArrayList<SceneEntity>(count);
        try{
            for(var point:points){
                var binding=new SceneContent.Entry("admin:"+nextId,point.x(),point.y(),rotation,new SceneContent.Spec(fields));
                var entity=spawn(binding,binding.spec(),null,stamp);entity.monsterType=type.id();
                created.add(entity);
            }
        }catch(RuntimeException failed){
            for(var entity:created)despawn(entity);
            throw failed;
        }
        return created.size();
    }
    /** Current scene generation. A wave created for an older generation is dropped, never replayed. */
    public long generation(){return generation;}
    /** Move to a later scene generation, removing every wave and wave actor that cannot belong to it. */
    public void generation(long next){
        if(next<=generation)throw new IllegalArgumentException("Scene generation must move forward");
        if(next==Long.MAX_VALUE)throw new IllegalArgumentException("Scene generation is exhausted");
        for(var wave:List.copyOf(waves.values()))if(wave.group.generation()!=next)dropWave(wave);
        generation=next;
    }
    int waveSpawned(String id){var wave=waves.get(id);return wave==null?0:wave.spawned;}
    int waveAlive(String id){
        int alive=0;
        for(var e:entities.values())if(id.equals(e.waveId)&&e.kind.equals("Monster")&&e.destroyed==Integer.MAX_VALUE)alive++;
        return alive;
    }
    boolean waveCleared(String id){var wave=waves.get(id);return wave!=null&&wave.cleared;}
    int liveMonsters(){return (int)entities.values().stream().filter(e->e.kind.equals("Monster")&&e.destroyed==Integer.MAX_VALUE).count();}
    private void advanceWaves(int stamp){
        int spawnedThisTick=0;
        for(var wave:List.copyOf(waves.values())){
            if(wave.group.generation()!=generation){dropWave(wave);continue;}
            int alive=waveAlive(wave.group.id());
            if(alive<wave.previousAlive)wave.nextSpawn=Math.max(wave.nextSpawn,stamp+wave.group.respawnDelayMs());
            wave.previousAlive=alive;
            while(wave.spawned<wave.group.total()&&alive<wave.group.maxAlive()
                    &&SceneClock.reached(stamp,wave.nextSpawn)&&spawnedThisTick<MAX_WAVE_SPAWNS_PER_TICK
                    &&liveMonsters()<MAX_ALIVE_MONSTERS){
                spawnWaveMonster(wave,stamp);wave.spawned++;alive++;spawnedThisTick++;
                wave.nextSpawn=stamp+wave.group.intervalMs();
            }
            if(!wave.cleared&&wave.spawned==wave.group.total()&&alive==0){
                wave.cleared=true;
                if(!wave.group.onCleared().isEmpty())actions.signal(wave.group.id(),wave.group.onCleared(),stamp,"wave:"+wave.group.id());
            }
        }
    }
    private void spawnWaveMonster(Wave wave,int stamp){
        MonsterType type;
        try{type=monsters.spawnable(wave.group.typeId());}
        catch(java.io.IOException refused){throw new IllegalStateException("Wave type became unavailable: "+wave.group.typeId(),refused);}
        var fields=monsters.fields(type);
        var anchor=wave.group.anchors().get(wave.spawned%wave.group.anchors().size());
        var binding=new SceneContent.Entry("wave:"+wave.group.id()+":"+(wave.spawned+1),anchor.x(),anchor.y(),0,new SceneContent.Spec(fields));
        var entity=spawn(binding,binding.spec(),null,stamp);
        entity.waveId=wave.group.id();entity.monsterType=type.id();
    }
    private void dropWave(Wave wave){
        for(var entity:List.copyOf(entities.values()))if(wave.group.id().equals(entity.waveId))despawn(entity);
        waves.remove(wave.group.id());
    }
    /** Immediate removal without death actions; status, paths, claims and source effects are cleared. */
    private void despawn(SceneEntity entity){
        actions.cancelSource("actor:"+entity.id);
        entity.status.clear();entity.path=List.of();entity.impulse=null;entity.target=0;entity.targetMode=null;
        entity.targetModeUpdate=false;entity.flags=0;entity.claim=0;entity.claimTaken=false;
        entity.carrier=0;entity.owner=0;entity.ownerOid=0;
        unlink(entity);
        var removedVariables=logicVariables.remove("actor:"+entity.id);if(removedVariables!=null)variableCount-=removedVariables.size();
    }
    /** Secondary burn/spasm damage retains its collision mask and excludes the affected source. */
    public void statusPulse(int source,SceneStatus.Pulse pulse,float x,float y,int stamp){
        if((pulse.mask()&1)!=0)for(var p:players)if(p.id()!=source&&playerActive.test(p.id())&&Math.hypot(p.x()-x,p.y()-y)<=pulse.radius()+2*ScenePortals.PAWN_RADIUS)
            playerDamage.accept(new PlayerHit(p.id(),pulse.damage(),stamp,source,1));
        if((pulse.mask()&128)!=0){
            float pad=pulse.radius()+ScenePortals.PAWN_RADIUS+spatial.reach();
            for(var target:candidates(x-pad,y-pad,x+pad,y+pad))
                if(target.id!=source&&target.kind.equals("Monster")&&target.health>0&&Math.hypot(target.x-x,target.y-y)<=pulse.radius()+target.spec.number("radius",0)+ScenePortals.PAWN_RADIUS){
                    Object accepted=SceneDamage.prune(pulse.damage(),target.spec.integer("damage-mask",0));float amount=SceneDamage.amount(accepted,content,target.spec);
                    amount+=target.status.damaged(amount,stamp);target.status.apply(accepted,stamp);if(amount>0)hurt(target,amount,stamp,SceneDamage.reacts(accepted),SceneDamage.major(accepted));
                }
        }
    }
    private void lift(SceneEntity e,int stamp,float elapsed,List<ActorState> players){
        if(e.carrier!=0){for(var player:players)if(player.id()==e.carrier){e.x=player.x();e.y=player.y();e.rotation=player.rotation();spatial.move(e);return;}return;}
        if((e.flags&2)==0)return;
        float distance=e.speed*elapsed;boolean collided=false;
        int steps=Math.max(1,(int)Math.ceil(Math.abs(distance)/.1f));
        // Thrown lifts also collide with the native lift bit, so closed Lift Barriers catch them.
        int thrownMask=e.spec.integer("thrown-collision-mask",-1);
        int flightMask=thrownMask>=0?thrownMask|LIFT_COLLISION:thrownMask;
        for(int step=0;step<steps;step++){
            float x=e.x+(float)Math.cos(e.direction)*distance/steps,y=e.y+(float)Math.sin(e.direction)*distance/steps;
            boolean clear=staticCollision.isEmpty()||e.shape==null?terrain.walkableSegment(e.x,e.y,x,y):
                staticCollision.clearSegment(flightMask,e.shape,e.x,e.y,x,y,e.rotation,disabledCollisionSources());
            if(!clear){collided=true;break;}
            e.x=x;e.y=y;spatial.move(e);
            // Execute the landing action at the colliding substep. Keeping the
            // previous clear pose left a thrown vial outside the victim's shape.
            if(contact(x,y,e.rotation,e.id,e.shape,flightMask)){collided=true;break;}
        }
        if(stamp-e.thrownAt>=e.spec.integer("throw-time",0))e.z=Math.max(0,e.z-e.spec.number("fall-speed",0)*elapsed);
        if(collided||e.z<=0||stamp-e.thrownAt>=e.spec.integer("fuse",0))land(e,stamp);
    }
    private void land(SceneEntity e,int stamp){
        e.flags&=~2;
        potLand(e,stamp);
        String graph=e.vial==null?e.spec.text("land-action",""):e.vial.text("use-action","");
        if(!graph.isBlank())try{attack.accept(e.snapshot(),FireCatalog.actionJson(graph));}catch(java.io.IOException invalid){throw new IllegalStateException("Invalid prepared lift action",invalid);}
        if(e.spec.flag("destroy-on-land",false))e.destroyed=stamp;
    }
    /** Native {@code Pot (Base)} land handler: smash region, then per-variant Extra Action. */
    private void potLand(SceneEntity e,int stamp){
        var pot=POTS.get(e.spec.config().name());if(pot==null)return;
        var damages=new ArrayList<Object>();
        if(pot.ailment()!=null)damages.add(new FireCatalog.DataValue(org.tortodrone.core.WireTypes.DAMAGE_STATUS,
            Map.of("flags",2,"statusCondition",pot.ailment())));
        damages.add(new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$DepthScale",Map.of(
            "depthScale",new StarterCatalog.Model("PC/Damage/Base/Default",Map.of()),
            "type",new FireCatalog.EnumValue("com.threerings.projectx.dungeon.util.DamageSummary$DamageType","NORMAL",0),"flags",2)));
        var args=new LinkedHashMap<String,Object>(Map.of("damage",new FireCatalog.DataValue(
                "com.threerings.projectx.dungeon.config.DamageConfig$Compound",Map.of("damages",
                    new FireCatalog.ListValue("com.threerings.projectx.dungeon.config.DamageConfig",damages))),
            "region",new FireCatalog.DataValue(org.tortodrone.core.WireTypes.REGION_DEFAULT,Map.of(
                "location",new FireCatalog.DataValue("com.threerings.tudey.config.TargetConfig$Source",List.of()),"expansion",pot.blast()))));
        var prepared=e.spec.model("source-shape");
        if(prepared!=null)args.put("shape",prepared.arguments().get("value"));
        damage(new StarterCatalog.Model("DamageRegionFromPoint",args),e.x,e.y,e.rotation,stamp,e.id,null);
        if(pot.hazardConfig()!=null)ignite(pot,e.x,e.y,stamp,e.owner,e.id);
    }
    /** Extra Action ground hazard: a native Bomb patch whose hazard applies native status
     *  damage on the Floor trap cadence (hazard-refractory 500), expiring at its Lifetime. */
    private void ignite(Pot pot,float x,float y,int stamp,int owner,int source){
        if(pot.hazardDamage()==null){
            for(int i=0;i<9;i++){
                float[] position=oilSpawn(x,y,pot.hazardRadius(),source);
                if(position!=null)hazard(pot.hazardConfig(),null,pot.hazardRadius(),pot.hazardLifetime(),Map.of(),position[0],position[1],stamp).owner=owner;
            }
            return;
        }
        // The Fire Pot spawns its torch with served arguments: the Lifetime argument selects
        // the "4 Seconds" choice, whose Creation Life renders the client flame for 4 seconds.
        hazard(pot.hazardConfig(),pot.hazardDamage(),pot.hazardRadius(),pot.hazardLifetime(),
            Map.of("config.argument.Lifetime","string|4 Seconds","hazard-active-lifetime","4000"),x,y,stamp).owner=owner;
    }
    /** Clyde's bounded perimeter search, using the slick diameter plus its .01 clearance.
     * Unlike an unadjusted SpawnActor, each patch must occupy a separate supported pose. */
    private float[] oilSpawn(float x,float y,float radius,int source){
        if(oilSpawnClear(x,y,radius,source))return new float[]{x,y};
        float step=radius*2+.01f;
        for(int distance=1;distance<=4;distance++){
            for(int dx=-distance;dx<=distance;dx++)if(oilSpawnClear(x+dx*step,y-distance*step,radius,source))return new float[]{x+dx*step,y-distance*step};
            for(int dy=1-distance;dy<distance;dy++)if(oilSpawnClear(x+distance*step,y+dy*step,radius,source))return new float[]{x+distance*step,y+dy*step};
            for(int dx=distance;dx>=-distance;dx--)if(oilSpawnClear(x+dx*step,y+distance*step,radius,source))return new float[]{x+dx*step,y+distance*step};
            for(int dy=distance-1;dy>-distance;dy--)if(oilSpawnClear(x-distance*step,y+dy*step,radius,source))return new float[]{x-distance*step,y+dy*step};
        }
        return null;
    }
    private boolean oilSpawnClear(float x,float y,float radius,int source){
        var circle=new FireCatalog.DataValue(org.tortodrone.core.WireTypes.SHAPE_CIRCLE,Map.of("radius",radius));
        if(staticCollision.isEmpty()?!terrain.walkable(x,y):!staticCollision.floorSupported(x,y)||!staticCollision.clear(3,circle,x,y,0,disabledCollisionSources()))return false;
        for(var e:entities.values())if(e.id!=source&&e.destroyed==Integer.MAX_VALUE&&e.carrier==0
                &&(e.config.name().equals("Bomb/Monster/Oil Slick")||(e.collisionFlags&3)!=0)
                &&e.spec.contains(e.x,e.y,e.rotation,x,y,radius))return false;
        return true;
    }
    private void lightOil(SceneEntity oil,int stamp){
        if(oil.ignited)return;
        oil.ignited=true;
        // Capture: oil-ignited torches use Fire (Scaled), flags 1, Chance 0.0 / Power 0.
        var fire=new StarterCatalog.Model("Fire (Scaled)",Map.of("Chance",0f,"Power",0));
        var damage=new FireCatalog.DataValue(org.tortodrone.core.WireTypes.DAMAGE_STATUS,Map.of("statusCondition",fire,"flags",1));
        hazard("Bomb/Monster/ScorcherTorch",damage,1f,4000,
            Map.of("config.argument.Lifetime","string|2 Seconds","config.argument.Nature","string|Neutral","hazard-active-lifetime","2000"),oil.x,oil.y,stamp).owner=oil.owner;
    }
    private boolean lightsOil(Object damage){
        if(SceneDamage.field(damage,"statusCondition") instanceof StarterCatalog.Model status){
            var spec=content.statuses().get(status.name());
            if(status.name().equals("Fire")||status.name().equals("Fire (Scaled)")||spec!=null&&spec.text("class","").equals("Fire"))return true;
        }
        if(SceneDamage.field(damage,"damages") instanceof FireCatalog.ListValue parts)for(var part:parts.values())if(lightsOil(part))return true;
        return false;
    }
    private SceneEntity hazard(String config,Object hazard,float radius,int lifetime,Map<String,String> arguments,float x,float y,int stamp){
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

    private void collect(SceneEntity e,float elapsed,List<ActorState> players){
        boolean heal=e.spec.integer("pickup-heal",0)>0;
        if(e.spec.text("pickup-item","").isEmpty()&&e.spec.integer("pickup-heat",0)==0&&!heal)return;
        for(var player:players){
            if(!playerActive.test(player.id())||!pickupEligible.test(player.id(),e.spec))continue;
            float dx=player.x()-e.x,dy=player.y()-e.y,distance=(float)Math.hypot(dx,dy);
            float reach=e.spec.number("radius",0)+ScenePortals.PAWN_RADIUS;
            if(distance>reach+e.spec.number("vacuum-expansion",0)){e.refusedPickups.remove(player.id());continue;}
            if(e.refusedPickups.contains(player.id()))continue;
            if(e.spec.integer("pickup-heat",0)>0){e.claim=player.id();return;}
            if(distance<=reach){
                // Native hearts heal on contact and stay put while the knight is full.
                if(heal){
                    if(playerHeal.test(player.id(),e.spec.integer("pickup-heal",0)))e.destroyed=lastStamp;
                    else e.refusedPickups.add(player.id());
                }else e.claim=player.id();
                return;
            }
            float step=Math.min(distance,e.spec.number("vacuum-speed",0)*elapsed);
            float x=e.x+dx*step/distance,y=e.y+dy*step/distance;
            if(terrain.walkableSegment(e.x,e.y,x,y)){e.x=x;e.y=y;spatial.move(e);}
            return;
        }
    }
    private void scriptedMovement(SceneEntity e,int stamp,float elapsed){movementSystem.scriptedMovement(e,stamp,elapsed);}
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
    private static final String ROUNDHOUSE_MEDIUM_CONFIG="Character/NPC/Monster/Slime/Jelly Cube (Intro)";
    private static final float ROUNDHOUSE_MEDIUM_RADIUS=1.5f;
    private void scriptedProgram(SceneEntity e,int stamp,float elapsed){
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
    private void wander(SceneEntity e,int stamp,float elapsed){movementSystem.wander(e,stamp,elapsed);}
    private List<ActorState> targetCandidates(SceneEntity e,List<ActorState> occupants){
        if(e.targeting==null||e.targeting.monsterMask()==0)return occupants;
        var candidates=new ArrayList<ActorState>();
        if(e.targeting.includePlayers()&&(Math.round(content.stats().getOrDefault("player.damage-mask",SceneContent.DEFAULT_PLAYER_DAMAGE_MASK))&e.targeting.monsterMask())!=0)candidates.addAll(occupants);
        candidates.addAll(entities.values().stream().filter(v->v!=e&&v.kind.equals("Monster")&&v.health>0&&v.destroyed==Integer.MAX_VALUE
            &&v.status.state().modifiers().canBeTargeted()&&(v.spec.integer("damage-mask",0)&e.targeting.monsterMask())!=0).map(SceneEntity::snapshot).toList());return List.copyOf(candidates);
    }
    private void follow(SceneEntity e,int stamp,float elapsed,List<ActorState> occupants){movementSystem.follow(e,stamp,elapsed,occupants);}
    private boolean targetActive(ActorState actor){return actor.entity()!=null?entities.containsKey(actor.id()):playerActive.test(actor.id())&&playerTargetable.test(actor.id());}
    private boolean acquireIntersects(SceneEntity e,ActorState target){
        var victim=target.entity()==null?null:entities.get(target.id());
        if(victim!=null&&e.shape!=null&&victim.shape!=null)return SceneCollision.overlaps(e.shape,e.x,e.y,e.rotation,victim.shape,victim.x,victim.y,victim.rotation,e.targeting.expansion());
        return e.spec.contains(e.x,e.y,e.rotation,target.x(),target.y(),e.targeting.expansion()+(victim==null?ScenePortals.PAWN_RADIUS:victim.spec.number("radius",0)));
    }
    private float targetDistance(SceneEntity source,ActorState target){
        return targetDistance(source.x,source.y,target);
    }
    private float targetDistance(float x,float y,ActorState target){
        if(target.entity()==null)return Math.max(0,(float)Math.hypot(target.x()-x,target.y()-y)-ScenePortals.PAWN_RADIUS);
        var victim=entities.get(target.id());if(victim==null)return Float.POSITIVE_INFINITY;
        var body=victim.pawnShape==null?SceneCollision.pawnShape(victim.spec,victim.shape):victim.pawnShape;
        return body.at(victim.x,victim.y,victim.rotation).distance(x,y);
    }
    private void returnHome(SceneEntity e,int stamp,float elapsed){movementSystem.returnHome(e,stamp,elapsed);}
    /** Activation ladder: cheap distance decisions gate the full pursuit/attack tick. */
    private void think(SceneEntity e,int stamp,float elapsed,List<ActorState> occupants){
        // Recent damage overrides the leash briefly: a hit monster answers before walking home.
        if(e.returning){if(SceneClock.before(stamp,e.engagedUntil))e.returning=false;else{returnHome(e,stamp,elapsed);return;}}
        // An authored attack sequence, including chains, must finish even if the target leaves.
        if(e.activity>=4){e.band=SceneMonsterBand.ENGAGED;e.nextThink=stamp;move(e,stamp,elapsed,occupants);return;}
        float nearest=Float.POSITIVE_INFINITY;
        for(var player:occupants)if(playerActive.test(player.id())&&playerTargetable.test(player.id()))
            nearest=Math.min(nearest,(float)Math.hypot(player.x()-e.x,player.y()-e.y));
        float radius=e.activationRadius;
        boolean forced=SceneClock.before(stamp,e.engagedUntil);
        if(e.leashRadius>0&&!forced&&e.band!=SceneMonsterBand.DORMANT
                &&Math.hypot(e.x-e.homeX,e.y-e.homeY)>e.leashRadius){
            e.returning=true;e.activity=0;e.fired.clear();e.path=List.of();
            returnHome(e,stamp,elapsed);return;
        }
        // A dormant monster still watches for an approaching player every tick; the
        // cadence below only decides when it reacquires and how often it re-checks.
        if(e.band==SceneMonsterBand.DORMANT&&(nearest<=radius||forced)){e.band=SceneMonsterBand.ALERT;e.nextThink=stamp;}
        boolean think=SceneClock.reached(stamp,e.nextThink);
        if(think){
            SceneMonsterBand next=nearest<=radius
                ?(e.target!=0||e.activity>=4?SceneMonsterBand.ENGAGED:SceneMonsterBand.ALERT)
                :forced?SceneMonsterBand.ENGAGED:SceneMonsterBand.DORMANT;
            // An explicit think override is a floor: an ambusher may stay alert, never asleep.
            if(next.ordinal()<e.minimumBand.ordinal())next=e.minimumBand;
            // The engaged cap is a safety valve for badly authored maps. Already-engaged
            // monsters keep running; a new voluntary transition waits in ALERT. Damage
            // remains authoritative and still wakes the target.
            if(next==SceneMonsterBand.ENGAGED&&e.band!=SceneMonsterBand.ENGAGED&&!forced
                    &&engagedAtTickStart>=MAX_ENGAGED_MONSTERS)
                next=SceneMonsterBand.ALERT;
            if(next==SceneMonsterBand.DORMANT&&e.band!=SceneMonsterBand.DORMANT){
                e.target=0;e.targetMode=null;e.targetModeUpdate=false;e.path=List.of();e.flags&=~18;
            }
            if(next==SceneMonsterBand.ENGAGED&&e.band!=SceneMonsterBand.ENGAGED)engagedAtTickStart++;
            e.band=next;e.nextThink=stamp+next.thinkInterval(e.id,e.archetype);
        }
        if(e.band==SceneMonsterBand.DORMANT)return;
        // Alert only reacquires on its think cadence; an owned attack or an engaged
        // target keeps the unchanged every-tick integration.
        if(e.band==SceneMonsterBand.ALERT&&!think)return;
        if(e.band==SceneMonsterBand.ALERT&&engagedAtTickStart>=MAX_ENGAGED_MONSTERS)return;
        move(e,stamp,elapsed,occupants);
        if(e.target!=0){
            if(e.band!=SceneMonsterBand.ENGAGED&&!forced&&engagedAtTickStart>=MAX_ENGAGED_MONSTERS){
                e.target=0;e.targetMode=null;e.flags&=~18;return;
            }
            if(e.band!=SceneMonsterBand.ENGAGED)engagedAtTickStart++;
            e.band=SceneMonsterBand.ENGAGED;e.nextThink=stamp;
        }
    }
    private void move(SceneEntity e,int stamp,float elapsed,List<ActorState> occupants){
        var players=targetCandidates(e,occupants);
        int previousTarget=e.target;
        float distance=e.spec.number("acquire-distance",0);if(distance<=0&&e.targeting==null&&e.wander==null&&e.activity<4)return;
        if(e.targeting!=null&&e.activity==3)return;
        if(e.targeting!=null){
            e.victims.keySet().removeIf(id->players.stream().noneMatch(p->p.id()==id&&targetActive(p)));
            if(SceneClock.reached(stamp,e.nextAcquire)){
                for(var player:players)if(targetActive(player)
                        &&acquireIntersects(e,player)&&e.victims.size()<256)e.victims.putIfAbsent(player.id(),new SceneEntity.Threat(0,0));
                double best=Double.NEGATIVE_INFINITY;e.target=0;
                for(var player:players){var threat=e.victims.get(player.id());if(threat==null)continue;
                    double rank=10.0*threat.hits()+10.0*threat.damage()-targetDistance(e,player);
                    if(rank>best){best=rank;e.target=player.id();}
                }
                e.nextAcquire=stamp+e.targeting.evaluation();e.targetModeUpdate=true;
            }
        }else if(SceneClock.reached(stamp,e.nextAcquire)){
            float nearest=distance;e.target=0;
            for(var player:players)if(playerActive.test(player.id())&&playerTargetable.test(player.id())){float d=(float)Math.hypot(player.x()-e.x,player.y()-e.y);if(d<nearest){nearest=d;e.target=player.id();}}
            e.nextAcquire=stamp+e.spec.integer("acquisition-interval",0);
        }
        ActorState target=players.stream().filter(p->p.id()==e.target&&targetActive(p)).findFirst().orElse(null);
        if(e.follow!=null&&previousTarget!=e.target){e.path=List.of();e.nextFollow=stamp;}
        float nearest=target==null?Float.POSITIVE_INFINITY:(float)Math.hypot(target.x()-e.x,target.y()-e.y);
        e.flags&=~18;if(target!=null)e.flags|=16;
        e.speed=target==null?e.spec.number("speed",0):e.spec.number("targeted-speed",e.spec.number("speed",0));
        if(e.targeting!=null){
            if(target==null){e.targetMode=null;e.targetModeUpdate=false;}
            else if(e.targetModeUpdate&&e.activity<4&&SceneClock.reached(stamp,e.nextAttack)){
                e.targetMode=e.targeting.choose(nearest,random);e.targetModeUpdate=false;
                e.targetRotation=(float)Math.atan2(target.y()-e.y,target.x()-e.x);
            }
            if(target!=null&&e.targetMode!=null&&targetDistance(e,target)<=e.targetMode.range())
                e.targetRotation=(float)Math.atan2(target.y()-e.y,target.x()-e.x);
            if(target!=null&&e.targetMode!=null&&e.status.state().modifiers().canRotate()&&(e.activity<4||e.spec.flag("attack."+(e.activity-4)+".blended",false))){
                float delta=(float)Math.IEEEremainder(e.targetRotation-e.rotation,2*Math.PI),step=e.spec.number("turn-rate",0)*elapsed;
                e.rotation=e.direction=(float)Math.IEEEremainder(e.rotation+Math.copySign(Math.min(Math.abs(delta),step),delta),2*Math.PI);
            }
        }
        int slot=e.activity>=4?e.activity-4:(e.targeting==null?e.spec.integer("attack",-1):e.targetMode==null?-1:e.targetMode.attack());String prefix="attack."+slot+".";
        var modifiers=e.status.state().modifiers();
        if(!modifiers.canAttack()||modifiers.shocked()){
            if(e.activity>=4){e.activity=0;e.fired.clear();}
            e.flags&=~2;return;
        }
        if(e.targeting!=null&&target==null&&e.activity==0&&e.targeting.idleAttack()>=0&&!e.idleAttackDone){
            if(beginAttack(e,e.targeting.idleAttack(),stamp))e.idleAttackDone=true;return;
        }
        if(target!=null)e.idleAttackDone=false;
        if(e.activity>=4){
            var motion=e.attackMotion.get(slot);
            if(motion!=null&&SceneClock.reached(stamp,e.activityStarted+motion.delay())&&e.fired.add("@impulse")){
                if(modifiers.canMove()&&(motion.x()!=0||motion.y()!=0)){
                    float c=(float)Math.cos(e.rotation),s=(float)Math.sin(e.rotation);
                    var impulse=new KnightCombat.Impulse(e.activityStarted+motion.delay(),motion.duration(),motion.x()*c-motion.y()*s,motion.x()*s+motion.y()*c);
                    e.impulse=SceneDamage.combine(impulse,e.impulse);e.impulseAdvanced=e.activityStarted+motion.delay();advanceImpulse(e,stamp);
                }
                e.flags|=64;
            }
            for(String fire:e.spec.text(prefix+"fires","").split(",")){
                if(fire.isBlank()||e.fired.contains(fire))continue;
                String fp=prefix+"fire."+fire+".";
                int due=e.activityStarted+Math.round((e.spec.integer(prefix+"land",0)+e.spec.integer(fp+"delay",0)/e.spec.number(prefix+"fire-speed",1))/e.attackSpeed);
                if(SceneClock.reached(stamp,due)){
                    var sceneAction=e.sceneFireActions.get(fp+"scene-action");
                    if(sceneAction!=null)actions.perform(sceneAction,stamp,"actor:"+e.id,"actor:"+e.id);
                    else try{attack.accept(e.snapshot(),FireCatalog.actionJson(e.spec.text(fp+"action","")));}
                    catch(java.io.IOException invalid){throw new IllegalStateException("Invalid prepared monster attack",invalid);}
                    e.fired.add(fire);
                }
            }
            int chain=e.spec.integer(prefix+"chain-next",-1);
            if(chain>=0&&stamp-e.activityStarted>e.spec.integer(prefix+"chain-rearm",Integer.MAX_VALUE)){
                beginAttack(e,chain,stamp);return;
            }
            if(stamp-e.activityStarted>=Math.round(e.spec.integer(prefix+"clear",0)/e.attackSpeed))e.activity=0;
            return;
        }
        if(target==null&&e.follow!=null){follow(e,stamp,elapsed,occupants);return;}
        if(target==null&&e.wander!=null){wander(e,stamp,elapsed);return;}
        if(target!=null)e.wanderPhase=0;
        float attackDistance=e.targeting==null?nearest:target==null?Float.POSITIVE_INFINITY:targetDistance(e,target);
        float attackRange=e.targeting==null?e.spec.number("attack-distance",0):e.targetMode==null?0:Math.min(e.targetMode.range(),e.meleeRangeCaps.getOrDefault(e.targetMode.attack(),Float.MAX_VALUE));
        if(target!=null&&e.activity==0&&slot>=0&&(e.targeting==null?SceneClock.reached(stamp,e.nextAttack):stamp-e.nextAttack>0)&&attackDistance<=attackRange&&modifiers.attackSpeed()>0
                &&sight(e,target.x(),target.y())){
            if(e.targeting!=null&&e.targetMode.faces()&&modifiers.canRotate()){
                float angle=(float)Math.atan2(target.y()-e.y,target.x()-e.x),delta=(float)Math.IEEEremainder(angle-e.rotation,2*Math.PI);
                if(Math.abs(delta)>e.targetMode.turnSnap()+.001f)return;
            }
            if(beginAttack(e,slot,stamp)){
                if(modifiers.canRotate()&&(e.targeting==null||e.targetMode.faces()))e.rotation=e.direction=(float)Math.atan2(target.y()-e.y,target.x()-e.x);
                e.nextAttack=stamp+Math.round((e.targeting==null?e.spec.integer("attack-interval",0):e.targetMode.interval()>=0?e.targetMode.interval():e.targeting.interval())/e.attackSpeed);
            }
            return;
        }
        if(e.targeting!=null&&(e.targetMode==null||attackDistance<=attackRange&&sight(e,target.x(),target.y())))return;
        if(target==null||!e.status.state().modifiers().canMove()||e.activity!=0||nearest<=e.spec.number("radius",0)+ScenePortals.PAWN_RADIUS)return;
        // Turning/attacking above still applies to stationary sentries. Pursuit
        // cannot translate them and must not search or publish MOVING for a zero step.
        if(e.speed*modifiers.speed()<=0)return;
        float tx=target.x(),ty=target.y();
        if(!entitySegment(e,e.x,e.y,tx,ty)){
            if(e.path.isEmpty()&&SceneClock.before(stamp,e.nextPath))return;
            if(e.path.isEmpty()||Math.hypot(tx-e.pathX,ty-e.pathY)>=1
                    ||!entitySegment(e,e.x,e.y,e.path.getFirst().x(),e.path.getFirst().y())){
                if(!pathBudget.acquire(e.id))return;
                e.path=target.entity()!=null&&e.targeting!=null?
                    ScenePathing.region(e.x,e.y,(x,y)->Math.max(targetDistance(x,y,target)-attackRange,sight(e,x,y,target.x(),target.y())?0:.01f),(x,y,nx,ny)->entitySegment(e,x,y,nx,ny)):
                    ScenePathing.route(e.x,e.y,tx,ty,(x,y,nx,ny)->entitySegment(e,x,y,nx,ny));e.pathX=tx;e.pathY=ty;
                monsterMetrics.searched(e.path.isEmpty());
                e.nextPath=stamp+Math.max(50,Math.max(e.spec.integer("acquisition-interval",0),e.archetype.repathMillis()));
            }
            while(!e.path.isEmpty()&&Math.hypot(e.path.getFirst().x()-e.x,e.path.getFirst().y()-e.y)<.05)e.path=e.path.subList(1,e.path.size());
            if(e.path.isEmpty())return;
            tx=e.path.getFirst().x();ty=e.path.getFirst().y();
        }else e.path=List.of();
        e.rotation=e.direction=(float)Math.atan2(ty-e.y,tx-e.x);
        nearest=(float)Math.hypot(tx-e.x,ty-e.y);
        float step=Math.min(nearest,e.speed*e.status.state().modifiers().speed()*elapsed);
        float x=e.x+(float)Math.cos(e.direction)*step,y=e.y+(float)Math.sin(e.direction)*step;
        if(moveEntity(e,x,y))e.flags|=2;
    }
    private boolean beginAttack(SceneEntity e,int slot,int stamp){
        if(slot<0||slot>255||!e.status.state().modifiers().canAttack()||e.status.state().modifiers().attackSpeed()<=0)return false;
        float curse=e.status.used(0);if(curse>0){hurt(e,curse,stamp,false,false);if(e.health<=0)return false;}
        // Activity changes reset fire receipts; a chained activity owns its own fire table and speed.
        e.attackSpeed=e.status.state().modifiers().attackSpeed();e.activity=4+slot;e.activityStarted=stamp;e.fired.clear();e.flags&=~64;return true;
    }
    /** Candidate entities whose indexed extent can touch the box, in insertion order.
     * The linear map is retained behind {@code spatialQuery=false} for the differential
     * harness and one-release rollback. */
    private List<SceneEntity> candidates(float minX,float minY,float maxX,float maxY){
        if(!spatialQuery||!Float.isFinite(minX)||!Float.isFinite(minY)||!Float.isFinite(maxX)||!Float.isFinite(maxY)
                ||Math.abs(minX)>1e8||Math.abs(minY)>1e8||Math.abs(maxX)>1e8||Math.abs(maxY)>1e8)
            return List.copyOf(entities.values());
        return spatial.near(minX,minY,maxX,maxY);
    }
    private boolean sight(SceneEntity e,float tx,float ty){
        return sight(e,e.x,e.y,tx,ty);
    }
    private boolean sight(SceneEntity e,float x,float y,float tx,float ty){
        boolean current=x==e.x&&y==e.y;
        if(current&&e.sightStamp!=Integer.MIN_VALUE&&lastStamp>=e.sightStamp
                &&lastStamp-e.sightStamp<=SIGHT_CACHE_MILLIS
                &&Math.hypot(tx-e.sightX,ty-e.sightY)<=SIGHT_CACHE_DISTANCE)
            return e.sightResult;
        boolean result=true;
        if(!staticCollision.clearSegment(129,POINT,x,y,tx,ty,0,disabledCollisionSources()))result=false;
        else {
            int steps=Math.max(1,(int)Math.ceil(Math.hypot(tx-x,ty-y)/.1));
            for(var obstacle:candidates(Math.min(x,tx),Math.min(y,ty),Math.max(x,tx),Math.max(y,ty)))
                if(obstacle!=e&&obstacle.destroyed==Integer.MAX_VALUE&&obstacle.carrier==0
                    &&(obstacle.collisionFlags&129)!=0&&!(obstacle.kind.equals("Door")&&(obstacle.flags&6)==2))
                for(int i=0;i<=steps;i++)if(obstacle.spec.contains(obstacle.x,obstacle.y,obstacle.rotation,x+(tx-x)*i/steps,y+(ty-y)*i/steps,0)){result=false;break;}
        }
        if(current){e.sightStamp=lastStamp;e.sightX=tx;e.sightY=ty;e.sightResult=result;}
        return result;
    }
    private boolean entitySegment(SceneEntity mover,float x,float y,float tx,float ty){return movementSystem.entitySegmentFor(mover,x,y,tx,ty);}
    private boolean moveEntity(SceneEntity mover,float x,float y){return movementSystem.moveEntity(mover,x,y);}
    private void cycle(SceneEntity e,int stamp){
        var cycle=content.cycles().getOrDefault(e.cycle,e.spec);
        float down=cycle.number("down",e.spec.number("down-time",0)),warn=cycle.number("warn",e.spec.number("warn-time",0)),up=cycle.number("up",e.spec.number("up-time",0));
        int period=Math.round((down+warn+up)*1000);
        int offset=period<=0?0:Math.floorMod(stamp-Math.round(cycle.number("offset",0)*1000),period);
        int state=e.cycle.equals("UP")?2:e.cycle.equals("DOWN")?0:offset<down*1000?0:offset<(down+warn)*1000?1:2;
        if(e.state!=state){e.state=state;e.stateEntered=stamp;}
        e.flags=state==2?e.flags|2:e.flags&~2;
    }
    private void switches(SceneEntity e,int stamp,List<ActorState> players){
        String mode=e.spec.text("switch-mode","");
        if(e.platform!=null){
            var living=players.stream().filter(p->playerActive.test(p.id())).toList();
            int count=(int)living.stream().filter(p->e.spec.contains(e.x,e.y,e.rotation,p.x(),p.y(),ScenePortals.PAWN_RADIUS)).count();
            if(count!=e.count){
                e.count=count;
                if(count>0&&count==living.size()){
                    if(e.partyIntersection==null)e.partyIntersection=actions.prepare(e.platform.intersection());
                    actions.perform(e.partyIntersection,stamp,"actor:"+e.id,"actor:"+e.id);
                }
            }
        }else if(mode.equals("pressure")){
            boolean pressed=players.stream().anyMatch(p->e.spec.contains(e.x,e.y,e.rotation,p.x(),p.y(),ScenePortals.PAWN_RADIUS))
                ||entities.values().stream().anyMatch(other->liftKind(other.kind)&&Arrays.asList(other.spec.text("tags","").split(",")).contains(e.spec.text("pressure-tag",""))&&other.carrier==0&&other.destroyed==Integer.MAX_VALUE
                    &&e.spec.contains(e.x,e.y,e.rotation,other.x,other.y,other.spec.number("radius",0)));
            int state=pressed?2:0;
            if(state!=e.state)setSwitch(e,state,stamp);
        }else if(mode.equals("button-once")){
            if(e.state==0&&players.stream().anyMatch(p->e.spec.contains(e.x,e.y,e.rotation,p.x(),p.y(),ScenePortals.PAWN_RADIUS)))setSwitch(e,1,stamp);
        }else if(mode.equals("button")||mode.equals("damage-timed")){
            if(e.state!=0&&SceneClock.reached(stamp,e.nextSwitch))setSwitch(e,0,stamp);
            if(mode.equals("button")&&e.state==0&&SceneClock.reached(stamp,e.nextSwitch)&&players.stream().anyMatch(p->e.spec.contains(e.x,e.y,e.rotation,p.x(),p.y(),ScenePortals.PAWN_RADIUS))){
                setSwitch(e,1,stamp);e.nextSwitch=stamp+e.spec.integer("switch-delay",0);
            }
        }
    }
    public boolean elevatorEntry(String key){return elevatorSystem.entry(key);}
    public boolean cancelElevator(ElevatorTrip trip,int stamp){return elevatorSystem.cancel(trip,stamp);}
    /** The session calls this only after its floor report/preflight succeeds. */
    public boolean dropElevator(ElevatorTrip trip,boolean returning,int stamp){return elevatorSystem.drop(trip,returning,stamp);}
    public boolean elevatorReady(ElevatorTrip trip,int stamp){return elevatorSystem.ready(trip,stamp);}
    private void setSwitch(SceneEntity e,int state,int stamp){
        e.state=state;e.stateEntered=stamp;
        e.flags=state==0?e.flags|2:e.flags&~2; // Native Switch.setState: START set exactly in state 0.
        if(e.platform!=null&&state==1)actions.perform(e.platform.pressed(),stamp,"actor:"+e.id,"actor:"+e.id);
        String effect=e.spec.text("state."+state+".effect","");if(!effect.isBlank())fireEffect(e,new StarterCatalog.Model(effect,Map.of()),stamp);
        var condition=e.spec.model("state."+state+".status");
        if(condition!=null){
            String tag=e.spec.text("state."+state+".status-tag","");float radius=e.spec.number("state."+state+".status-radius",0);
            var damage=new FireCatalog.DataValue(org.tortodrone.core.WireTypes.DAMAGE_STATUS,Map.of("statusCondition",condition));
            for(var target:entities.values())if(target.kind.equals("Monster")&&target.destroyed==Integer.MAX_VALUE&&(tag.isBlank()||Arrays.asList(target.spec.text("tags","").split(",")).contains(tag))&&Math.hypot(target.x-e.x,target.y-e.y)<=radius+target.spec.number("radius",0))target.status.apply(damage,stamp);
        }
        signal(e.spec.text("state."+state+".tag",""),e.spec.text("state."+state+".signal",""),stamp,"actor:"+e.id);
    }
    private void fireEffect(SceneEntity e,StarterCatalog.Model effect,int stamp){
        attack.accept(e.snapshot(),new FireCatalog.Action(FireCatalog.Kind.FIRE_EFFECT,effect,null,"Source","","",0,0,0,0,false,0,0,0,0,false,List.of(),""));
    }
    private ActorState scriptSource(String source,int stamp){
        if(source.startsWith("effect:"))return effectSources.get(source);
        if(source.startsWith("actor:"))try{return actor(Integer.parseInt(source.substring(6)));}catch(NumberFormatException invalid){return null;}
        if(!source.startsWith("entry:"))return null;
        String key=source.substring(6);var binding=content.entries().stream().filter(entry->entry.key().equals(key)).findFirst().orElse(null);
        if(binding==null||!key.matches("[0-9]+"))return null;
        var state=new SceneEntityState("Actor",Integer.MAX_VALUE,0,0,-1,0,stamp,0,0,key,null,SceneStatus.State.NORMAL,0,0,true);
        // A native entry identity for presentation only; never inserted as an actor or occupant.
        return new ActorState(0,0,"",0,stamp,binding.x(),binding.y(),binding.rotation(),0,0,0,stamp,binding.spec().config(),false,null,null,state);
    }
    private boolean startConversation(SceneActions.Conversation operation,int stamp,String source,String activator){
        var definition=content.conversations().get(operation.id());var origin=scriptSource(source,stamp);
        if(definition==null||origin==null)return false;
        int target=0;
        if(operation.activatorOnly()){
            var player=players.stream().filter(p->("actor:"+p.id()).equals(activator)).findFirst().orElse(null);
            if(player==null)return true;target=player.id();
        }
        return conversationSink.start(definition,origin,stamp,target);
    }
    private boolean nextFloor(int stamp,String source,String activator){
        var player=players.stream().filter(p->activator.equals("actor:"+p.id())).findFirst().orElse(null);
        if(player==null)return true; // Native PartyBase succeeds with no resolved knight/party.
        var origin=scriptSource(source,stamp);if(origin==null)return false;
        return floorAdvanceSink.request(player,origin,stamp);
    }
    private boolean setMemory(org.tortodrone.core.character.KnightMemory operation,int stamp,String source,String activator){
        var player=players.stream().filter(p->activator.equals("actor:"+p.id())&&playerActive.test(p.id())).findFirst().orElse(null);
        var origin=scriptSource(source,stamp);
        return player!=null&&origin!=null&&memorySink.apply(player,operation,origin,stamp);
    }
    private boolean sourceEffect(SceneActions.SourceEffect operation,int stamp,String source){
        if(operation.locationTag().isEmpty()){
            var origin=scriptSource(source,stamp);if(origin==null)return false;
            effectSink.fire(operation.config(),origin,stamp);
        }else{
            var origins=taggedSources(operation.locationTag(),stamp);if(origins.size()>256)return false;
            for(var origin:origins)effectSink.fire(operation.config(),origin,stamp);
        }
        return true; // Native FireEffect succeeds even when its Tagged selection is empty.
    }
    private boolean gameplayEffect(SceneActions.GameplayEffect operation,int stamp,String source){
        List<ActorState> origins;
        if(operation.locationTag().isEmpty()){var origin=scriptSource(source,stamp);if(origin==null)return false;origins=List.of(origin);}
        else origins=taggedSources(operation.locationTag(),stamp);
        if(origins.size()>256||effectSources.size()+origins.size()>4096||nextEffectSource>Long.MAX_VALUE-origins.size())return false;
        for(var origin:origins){
            String identity="effect:"+(++nextEffectSource);effectSources.put(identity,origin);
            if(!actions.perform(operation.action(),stamp,identity,logicIdentity(origin))){effectSources.remove(identity);return false;}
            effectSink.fire(operation.config(),origin,stamp);
        }return true;
    }
    List<ActorState> taggedSources(String tag,int stamp){
        var result=new ArrayList<ActorState>();
        for(var binding:anchors.getOrDefault(tag,List.of()))result.add(entrySource(binding,stamp));
        // Native Tagged maps retain a destroyed actor until its actual removal.
        for(var entity:entities.values())if(Arrays.asList(entity.spec.text("tags","").split(",")).contains(tag))result.add(entity.snapshot());
        return result;
    }
    private List<String> variableTargets(SceneActions.LogicTarget target,int stamp,String source,String activator){
        return switch(target.mode()){
            case "source"->scriptSource(source,stamp)==null?List.of():List.of(source);
            case "activator"->scriptSource(activator,stamp)!=null||players.stream().anyMatch(p->activator.equals("actor:"+p.id()))?List.of(activator):List.of();
            case "tagged"->taggedSources(target.tag(),stamp).stream().map(SceneEntities::logicIdentity).distinct().toList();
            default->throw new IllegalArgumentException("Invalid variable target");
        };
    }
    private boolean setVariable(SceneActions.SetVariable operation,int stamp,String source,String activator){
        if(scriptSource(source,stamp)==null)return false;
        var targets=variableTargets(operation.target(),stamp,source,activator);
        int additions=0;
        for(String target:targets){var values=logicVariables.get(target);if(values==null||!values.containsKey(operation.name())){if(values!=null&&values.size()>=256)return false;additions++;}}
        if(variableCount+additions>4096)return false;
        for(String target:targets)logicVariables.computeIfAbsent(target,key->new LinkedHashMap<>()).put(operation.name(),operation.value());
        variableCount+=additions;return true;
    }
    private boolean variable(String name,SceneActions.LogicTarget target,int stamp,String source,String activator){
        for(String identity:variableTargets(target,stamp,source,activator)){
            var values=logicVariables.get(identity);if(values!=null&&values.containsKey(name))return values.get(name);
        }return false;
    }
    private ActorState entrySource(SceneContent.Entry binding,int stamp){
        var state=new SceneEntityState("Actor",Integer.MAX_VALUE,0,0,-1,0,stamp,0,0,binding.key(),null,SceneStatus.State.NORMAL,0,0,true);
        return new ActorState(0,0,"",0,stamp,binding.x(),binding.y(),binding.rotation(),0,0,0,stamp,binding.spec().config(),false,null,null,state);
    }
    private String conversationSource(SceneConversation definition,ActorState source,int responder,int stamp){
        if(content.conversations().get(definition.actor())!=definition||source==null)return null;
        if(players.stream().noneMatch(p->p.id()==responder)||!playerActive.test(responder))return null;
        String identity;
        if(source.entity()!=null&&source.entity().entrySource())identity="entry:"+source.entity().key();else identity="actor:"+source.id();
        return scriptSource(identity,stamp)==null?null:identity;
    }
    boolean conversationValid(SceneConversation definition,String node,int choice,ActorState source,int responder,int stamp){
        var current=definition.nodes().get(node);return current!=null&&choice>=0&&choice<current.responses().size()&&conversationSource(definition,source,responder,stamp)!=null;
    }
    boolean conversationStart(SceneConversation definition,ActorState source,int responder,int stamp){
        String identity=conversationSource(definition,source,responder,stamp);if(identity==null)return false;
        if(definition.initialAction()==null)return true;
        return actions.perform(definition.initialAction(),stamp,identity,"actor:"+responder);
    }
    boolean conversationResponse(SceneConversation definition,String node,int choice,ActorState source,int responder,int stamp){
        if(!conversationValid(definition,node,choice,source,responder,stamp))return false;
        var current=definition.nodes().get(node);String identity=source.entity()!=null&&source.entity().entrySource()?"entry:"+source.entity().key():"actor:"+source.id();
        var action=current.responses().get(choice).action();return action==null||actions.perform(action,stamp,identity,"actor:"+responder);
    }
    private boolean pawnStatuses(SceneActions.PawnStatus operation,int stamp,String source){
        boolean applied=false;
        for(var player:players)applied=pawnStatus.apply(player.id(),operation,stamp)||applied;
        return operation.clear()||applied; // Native ClearStatus succeeds even with no targets.
    }
    private boolean spawnScripted(SceneActions.Spawn actor,int stamp,String source,String activator){
        List<ActorState> origins;
        if(actor.activatorLocation()){
            var origin=players.stream().filter(player->activator.equals("actor:"+player.id())).findFirst().orElse(null);
            if(origin==null)origin=scriptSource(activator,stamp);
            if(origin==null)return false;origins=List.of(origin);
        }else if(actor.locationTag().isEmpty()){
            if(source.startsWith("entry:")){
                String key=source.substring(6);var entry=content.entries().stream().filter(e->e.key().equals(key)).findFirst().orElse(null);
                if(entry==null)return false;origins=List.of(entrySource(entry,stamp));
            }else{var origin=scriptSource(source,stamp);if(origin==null)return false;origins=List.of(origin);}
        }else origins=taggedSources(actor.locationTag(),stamp);
        if(origins.isEmpty()||origins.size()>256||entities.size()+pendingActors.size()+origins.size()>4096)return false;
        var spec=new SceneContent.Spec(actor.fields());
        var staged=new ArrayList<SceneEntity>();int next=nextId;
        for(var origin:origins){
            var binding=new SceneContent.Entry("spawn:"+next,origin.x(),origin.y(),origin.rotation(),spec);
            var entity=new SceneEntity(next++,stamp,binding,spec,null,content);entity.scriptSource=source;
            if(actor.activate()&&spec.integer("activate-time",0)>0)entity.activity=3;
            staged.add(entity);
        }
        // Validate the whole selection before publication; a failed batch changes no actor IDs.
        for(var entity:staged)insert(entity);nextId=next;return true;
    }
    private boolean destroyTagged(String tag,int stamp,String source){
        boolean found=false;
        for(var e:entities.values())if(Arrays.asList(e.spec.text("tags","").split(",")).contains(tag)){
            found=true;
            if(e.destroyed!=Integer.MAX_VALUE)continue;
            // Destruction is not damage: no hit, death animation, loot or combat reward.
            e.destroyed=stamp;e.scriptDestroyed=true;e.status.clear();e.path=List.of();e.impulse=null;
        }
        return found;
    }
    private void signal(String tag,String name,int stamp,String source){
        if(tag.isEmpty()||name.isEmpty())return;
        actions.signal(tag,name,stamp,source);
        for(var target:entities.values())if(Arrays.asList(target.spec.text("tags","").split(",")).contains(tag))entitySignal(target,name,stamp);
    }
    private static String logicIdentity(ActorState actor){return actor.entity()!=null&&actor.entity().entrySource()?"entry:"+actor.entity().key():"actor:"+actor.id();}
    private boolean nearestSignal(SceneActions.NearestSignal operation,int stamp,String source){
        var origin=scriptSource(source,stamp);if(origin==null)return false;
        var targets=new LinkedHashMap<String,ActorState>();
        for(var target:taggedSources(operation.tag(),stamp)){
            String identity=logicIdentity(target);if(!operation.excludeSource()||!identity.equals(source))targets.putIfAbsent(identity,target);
        }
        if(targets.size()>2048)return false;
        // Clyde DistanceSubset sorts float distances from Source. Excluding removes
        // source identity, not its position. Equal distances retain server content
        // order; original tie ordering has no capture witness.
        var selected=targets.values().stream().sorted(Comparator.comparingDouble(a->{float dx=a.x()-origin.x(),dy=a.y()-origin.y();return (float)Math.sqrt(dx*dx+dy*dy);})).limit(operation.count()).toList();
        for(var target:selected){
            actions.signalTo(operation.tag(),operation.name(),stamp,source,logicIdentity(target));
            if(!target.entity().entrySource()){var entity=entities.get(target.id());if(entity!=null)entitySignal(entity,operation.name(),stamp);}
        }
        return true; // Native Signal succeeds for an empty resolved target set.
    }
    private void entitySignal(SceneEntity target,String name,int stamp){
        if(target.kind.equals("Door")&&name.equals("increment")){
            if(target.doorIncrements==0||--target.doorIncrements!=0)return;
            name="open";
        }
        if(target.script!=null&&name.equals(target.spec.text("script.activate-signal","")))target.scriptActivated=true;
        if(target.program!=null&&name.equals(target.spec.text("script.activate-signal","")))setVariable(new SceneActions.SetVariable("activate",true,new SceneActions.LogicTarget("source","")),stamp,"actor:"+target.id,"actor:"+target.id);
        if(target.platform!=null&&name.equals("next")&&target.state==0)setSwitch(target,1,stamp);
        if(target.kind.equals("Switch")&&target.platform==null&&target.spec.integer("switch-states",0)>0){
            // Native DumbSwitchLogic.signal: "next"/"prev" step with wrap, numeric names
            // target a state, other names are swallowed; only prepared dumb switches carry
            // their native state count, so authored smart-switch modes stay untouched.
            Integer next=null;
            if(name.equals("next"))next=target.state+1;
            else if(name.equals("prev"))next=target.state-1;
            else try{next=Integer.parseInt(name);}catch(NumberFormatException ignored){}
            if(next!=null){
                int states=target.spec.integer("switch-states",0);
                if(next<0)next=states-1;else if(next>=states)next=0;
                if(next!=target.state)setSwitch(target,next,stamp);
            }
        }
        if(target.kind.equals("Door")&&Set.of("open","close","toggle").contains(name)){
            boolean open=name.equals("open")||name.equals("toggle")&&(target.flags&2)==0;target.flags=(open?2:0)|4;target.stateEntered=stamp;
            for(var mover:entities.values())if(mover.kind.equals("Monster")){mover.path=List.of();mover.nextPath=0;}
        }else if(target.kind.equals("Trap")){
            if(name.equals("RESET")){target.cycle=target.spec.text("cycle","");int split=target.cycle.lastIndexOf('|');if(split>=0)target.cycle=target.cycle.substring(split+1);}
            else if(name.equals("TOGGLE")){var cycle=content.cycles().get(target.cycle);if(cycle!=null)target.cycle=cycle.text("inverse",target.cycle);}
            else if(content.cycles().containsKey(name))target.cycle=name;
            cycle(target,stamp);
        }
    }
    public boolean walkable(float x,float y,int ignore){
        for(var e:entities.values()){
            if(e.id==ignore||e.destroyed!=Integer.MAX_VALUE)continue;
            if(e.carrier!=0)continue;
            if(e.kind.equals("Door")&&(e.flags&6)==2)continue;
            if((e.collisionFlags&pawnCollisionMask)!=0&&e.containsPawn(x,y))return false;
        }return true;
    }
    /** Same mask and live actor exclusions as pawn collision, retaining the separating direction.
     *  Closed Lift Barriers additionally block a pawn that is carrying a lift. The served evidence
     *  never states that rule on the raw mask test (the pawn mask is 43, the barrier's lift bit
     *  8192), yet the gym's Lift Barrier doors are the served
     *  {@code DungeonActorSpriteConfig$SubjectiveDoor}: the client judges each door per viewer,
     *  open exactly when the viewer's target actor cannot collide with it, and a knight carrying
     *  a statue reads every closed Lift Barrier as closed ("when you pick up a statue, they go
     *  purple, and you are not supposed to go through them, only if you put down the statue").
     *  With the old raw-mask pass the server kept moving the pawn through a gate the client
     *  closed, which was the reported gradual penetration; matching the client server-side ends
     *  it. A lift at rest releases the pawn again, and an open barrier (open flag set) blocks
     *  nobody. */
    /** Source ids whose static collision must be ignored because the entity is open or destroyed. */
    public java.util.Set<Integer> disabledCollisionSources(){
        var result=new java.util.HashSet<Integer>();
        for(var e:entities.values()){
            if(e.collisionFlags==0)continue;
            boolean disabled=e.destroyed!=Integer.MAX_VALUE
                ||e.kind.equals("Door")&&(e.flags&6)==2;
            if(!disabled)continue;
            try{result.add(Integer.parseInt(e.binding.key()));}catch(NumberFormatException ignored){}
        }
        return java.util.Set.copyOf(result);
    }
    public SceneCollision.Penetration penetration(float x,float y,int ignore){
        boolean carrying=ignore!=0&&entities.values().stream().anyMatch(e->liftKind(e.kind)
            &&e.carrier==ignore&&e.destroyed==Integer.MAX_VALUE);
        var result=SceneCollision.Penetration.NONE;
        for(var e:entities.values()){
            if(e.id==ignore||e.destroyed!=Integer.MAX_VALUE||e.carrier!=0||e.kind.equals("Door")&&(e.flags&6)==2)continue;
            if((e.collisionFlags&pawnCollisionMask)==0&&!(carrying&&e.kind.equals("Door")
                    &&(e.collisionFlags&LIFT_COLLISION)!=0))continue;
            if(!e.containsPawn(x,y))continue;
            result=result.deepest(e.penetration(x,y));
        }
        return result;
    }
    @Override public boolean contact(float x,float y,int owner){
        return contact(x,y,0,owner,null,-1);
    }
    @Override public boolean attachable(int id){var e=entities.get(id);return e!=null&&e.kind.equals("Monster")&&e.health>0&&e.destroyed==Integer.MAX_VALUE;}
    @Override public int contactActor(float x,float y,float rotation,int owner,Object shape,int mask){
        for(var e:entities.values())if(e.id!=owner&&e.carrier==0&&e.destroyed==Integer.MAX_VALUE
            &&(e.collisionFlags&mask)!=0&&!(e.kind.equals("Door")&&(e.flags&2)!=0)
            &&(shape==null||e.shape==null?e.spec.contains(e.x,e.y,e.rotation,x,y,0):SceneCollision.overlaps(shape,x,y,rotation,e.shape,e.x,e.y,e.rotation,0)))return e.id;
        return 0;
    }
    @Override public boolean contact(float x,float y,float rotation,int owner,Object shape,int mask){
        var source=entities.get(owner);
        if(source!=null&&(source.kind.equals("Monster")||source.kind.equals("Trap"))&&(mask&4)!=0&&players.stream().anyMatch(p->shape==null?
            Math.hypot(p.x()-x,p.y()-y)<=ScenePortals.PAWN_RADIUS:SceneShape.intersects(shape,
                (p.x()-x)*(float)Math.cos(rotation)+(p.y()-y)*(float)Math.sin(rotation),
                -(p.x()-x)*(float)Math.sin(rotation)+(p.y()-y)*(float)Math.cos(rotation),ScenePortals.PAWN_RADIUS)))return true;
        return contactActor(x,y,rotation,owner,shape,mask)!=0;
    }
    @Override public void damage(StarterCatalog.Model region,float x,float y,float rotation,int stamp,int owner){
        damage(region,x,y,rotation,stamp,owner,null);
    }
    @Override public void damage(StarterCatalog.Model region,float x,float y,float rotation,int stamp,int owner,Object sourceShape){
        damage(region,x,y,rotation,stamp,owner,sourceShape,null);
    }
    @Override public void sweep(StarterCatalog.Model region,float x,float y,float rotation,int stamp,int owner,Set<Integer> hit){
        damage(region,x,y,rotation,stamp,owner,null,hit);
    }
    @Override public void sweep(StarterCatalog.Model region,float x,float y,float rotation,int stamp,int owner,Object shape,Set<Integer> hit){
        damage(region,x,y,rotation,stamp,owner,shape,hit);
    }
    private void damage(StarterCatalog.Model region,float x,float y,float rotation,int stamp,int owner,Object sourceShape,Set<Integer> hitOnce){
        Object damage=region.arguments().get("damage"),shape=region.arguments().get("region");
        var source=entities.get(owner);float expansion=SceneDamage.number(shape,"expansion",0);
        float multiplier=source==null?players.stream().filter(p->p.id()==owner&&p.sceneKnight()!=null).map(p->p.sceneKnight().status().modifiers().attackDamage()).findFirst().orElse(1f):source.status.state().modifiers().attackDamage();
        float relative=source==null?players.stream().filter(p->p.id()==owner&&p.sceneKnight()!=null).map(p->p.sceneKnight().status().modifiers().relativeDamage()).findFirst().orElse(1f):source.status.state().modifiers().relativeDamage();
        Object defined=region.arguments().getOrDefault("shape",SceneDamage.field(shape,"shape"));
        if(!(defined instanceof FireCatalog.DataValue)&&shape instanceof FireCatalog.DataValue value&&value.type().endsWith("$Default")){
            var model=source==null?null:source.spec.model("source-shape");
            if(sourceShape!=null)defined=sourceShape;
            else if(model!=null)defined=model.arguments().get("value");
            else expansion+=source==null?ScenePortals.PAWN_RADIUS:source.spec.number("radius",0);
        }
        // See ROUNDHOUSE_MEDIUM_CONFIG: the blank base circle expands to the resolved Medium radius.
        if(source!=null&&ROUNDHOUSE_MEDIUM_CONFIG.equals(source.config.name())&&defined instanceof FireCatalog.DataValue blank
                &&blank.type().endsWith("$TransformedShape")
                &&SceneDamage.field(defined,"shape") instanceof FireCatalog.DataValue inner&&inner.type().endsWith("$Circle")
                &&SceneDamage.number(inner,"radius",0)==1f)expansion+=ROUNDHOUSE_MEDIUM_RADIUS-1f;
        // The official explosive detonation (every Block/Explosive* variant detonates through
        // the one "Block/Explosive Block - Explosion" action) carries a Default region with
        // expansion 1.0; the 2026 client export strips regions to that constructor's default
        // 0, leaving the blast at the block's own 1x1 footprint, which a knight hugging the
        // solid block can never enter. The live capture brackets the official blast - a knight
        // 1.48 off-centre (0.98 outside the footprint) is hit at the detonation timestamp,
        // and knights 2.4 away are not - and the native Polygon corner expansion measures
        // 2.41 x 2.41 on the block's own rectangle.
        // SceneEntity.kind is the native-type's simple name ("Block$Stateful" here), so match the
        // family by prefix like the other block paths do.
        if(source!=null&&source.kind.startsWith("Block")&&source.config.name().startsWith("Block/Explosive")
                &&shape instanceof FireCatalog.DataValue blast&&blast.type().endsWith("$Default"))
            expansion=Math.max(expansion,1f);
        {
            float c=(float)Math.cos(rotation),s=(float)Math.sin(rotation);
            for(var player:players){float dx=player.x()-x,dy=player.y()-y;
                boolean hit=defined instanceof FireCatalog.DataValue?SceneShape.intersects(defined,dx*c+dy*s,-dx*s+dy*c,ScenePortals.PAWN_RADIUS+expansion):Math.hypot(dx,dy)<=ScenePortals.PAWN_RADIUS+expansion;
                int sourceId=source==null?owner:source.kind.equals("Monster")?owner:(liftKind(source.kind)||source.kind.equals("Bomb"))?source.owner:0;
                if(hit&&damageClear(region,x,y,player.x(),player.y(),owner,player.id())&&(hitOnce==null||hitOnce.add(player.id()))){
                    // One trap action can cover several occupants. Record each
                    // hit here so the next sensor cannot damage them again on
                    // the same tick or before the authored refractory deadline.
                    if(source!=null&&source.kind.equals("Trap")){
                        if(SceneClock.before(stamp,source.hazardHits.getOrDefault(player.id(),0)))continue;
                        source.hazardHits.put(player.id(),stamp+source.spec.integer("hazard-refractory",0));
                    }
                    // Native DamageRegionFromPoint pushes every actor the region damaged.
                    var impulse=createImpulse(region,x,y,rotation,player.x(),player.y(),Math.round(content.stats().getOrDefault("player.impulse-level",1f)),stamp);
                    playerDamage.accept(new PlayerHit(player.id(),damage,stamp,sourceId,multiplier,impulse));
                }
            }
        }
        for(var e:List.copyOf(entities.values())){
            if(e.destroyed!=Integer.MAX_VALUE)continue;
            // Native BlockLogic.wasDamaged returns false without a damageAction. A
            // global music block has no physical body or damage reaction to evaluate.
            if(e.kind.equals("Block")&&e.collisionFlags==0&&e.spec.text("shape","").equals("Global")&&e.spec.text("damage-action","").isEmpty())continue;
            float dx=e.x-x,dy=e.y-y,c=(float)Math.cos(rotation),s=(float)Math.sin(rotation);
            boolean hit;
            if(defined instanceof FireCatalog.DataValue geometry){
                float tx=dx*c+dy*s,ty=-dx*s+dy*c,r=e.spec.number("radius",0);
                hit=e.shape==null||geometry.type().endsWith("$Segment")?SceneShape.intersects(geometry,tx,ty,r+Math.max(0,expansion)):
                    SceneCollision.overlaps(geometry,x,y,rotation,e.shape,e.x,e.y,e.rotation,Math.max(0,expansion));
            }else hit=e.spec.contains(e.x,e.y,e.rotation,x,y,Math.max(0,expansion));
            if(!hit||!damageClear(region,x,y,e.x,e.y,owner,e.id))continue;
            if(hitOnce!=null&&!hitOnce.add(e.id))continue;
            if(e.config.name().equals("Bomb/Monster/Oil Slick")&&lightsOil(damage)){lightOil(e,stamp);continue;}
            if(e.kind.equals("Switch")){
                String mode=e.spec.text("switch-mode","");
                Object switchDamage=SceneDamage.prune(damage,e.spec.integer("switch-damage-mask",255));
                if(mode.startsWith("damage-")&&(!mode.equals("damage-once")||e.state==0)&&SceneClock.reached(stamp,e.nextSwitch)&&SceneDamage.amount(switchDamage,content,e.spec,multiplier)>0){
                    setSwitch(e,e.state==0?1:0,stamp);e.nextSwitch=stamp+e.spec.integer(mode.equals("damage-timed")?"switch-delay":"switch-cooldown",0);
                }
                continue;
            }
            if(e.spec.integer("damage-mask",0)==0)continue;
            Object accepted=SceneDamage.prune(damage,e.spec.integer("damage-mask",0));
            var defense=new HashMap<>(e.spec.fields());
            defense.replaceAll((key,value)->key.startsWith("defense.")?Float.toString(Float.parseFloat(value)*e.status.state().modifiers().defense()):value);
            var gear=region.arguments().get("equipment-damage") instanceof GearStats.Damage bonus?bonus:GearStats.Damage.NONE;
            float amount=SceneDamage.amount(accepted,content,new SceneContent.Spec(defense),multiplier,gear,relative);
            if(e.kind.equals("Monster")){
                amount+=e.status.damaged(amount,stamp);e.status.apply(accepted,stamp);if(amount>0){
                    int dealt=(int)Math.ceil(amount);hurt(e,dealt,stamp,SceneDamage.reacts(accepted),SceneDamage.major(accepted));
                    // Being hit is authoritative: a dormant monster wakes even with no player nearby.
                    e.band=SceneMonsterBand.ENGAGED;e.nextThink=stamp;e.engagedUntil=stamp+DAMAGE_ENGAGE_MILLIS;e.sightStamp=Integer.MIN_VALUE;e.returning=false;
                    if(e.targeting!=null&&targetCandidates(e,players).stream().anyMatch(p->p.id()==owner&&targetActive(p))&&(e.victims.containsKey(owner)||e.victims.size()<256)){
                        var previous=e.victims.getOrDefault(owner,new SceneEntity.Threat(0,0));
                        e.victims.put(owner,new SceneEntity.Threat(Math.min(1_000_000,previous.hits()+1),Math.min(1_000_000_000f,previous.damage()+dealt)));e.nextAcquire=stamp;
                    }
                    applyImpulse(e,region,x,y,rotation,stamp);
                    if(e.health==0)e.status.clear();
                    int sourceId=source!=null&&(liftKind(source.kind)||source.kind.equals("Bomb"))?source.owner:owner;
                    // Native MonsterLogic promotes the damage summary's performance (weak against
                    // the target's defenses, strong into them) by the dealt fraction of its health.
                    String tier=SceneDamage.performance(accepted,content,new SceneContent.Spec(defense),multiplier,gear,relative);
                    float fraction=dealt/e.spec.number("health",1);
                    feedback.accept(new SceneCombatEvent(0,stamp,e.id,dealt,sourceId,e.x,e.y,e.rotation,tier+(fraction>.4f?"_LARGE":fraction>.2f?"_MEDIUM":"_SMALL")));
                }
            }else if(amount>0&&(e.kind.startsWith("Block")||e.spec.text("damage-action","").equals("destroy")))blockHit(e,stamp);
        }
    }
    private boolean damageClear(StarterCatalog.Model region,float x,float y,float tx,float ty,int owner,int target){
        int mask=region.arguments().get("collision-mask") instanceof Number n?n.intValue():0;
        if(mask==0)return true;
        if(!staticCollision.clearSegment(mask,POINT,x,y,tx,ty,0,disabledCollisionSources()))return false;
        int steps=Math.max(1,(int)Math.ceil(Math.hypot(tx-x,ty-y)/.1));
        for(var obstacle:entities.values()){
            if(obstacle.id==owner||obstacle.id==target||obstacle.destroyed!=Integer.MAX_VALUE||obstacle.carrier!=0
                    ||(obstacle.collisionFlags&mask)==0||obstacle.kind.equals("Door")&&(obstacle.flags&6)==2)continue;
            for(int i=0;i<=steps;i++)if(obstacle.spec.contains(obstacle.x,obstacle.y,obstacle.rotation,x+(tx-x)*i/steps,y+(ty-y)*i/steps,0))return false;
        }
        return true;
    }
    /** Retail Bomb/Revive/Revive: radius 2, level 13, five-unit radial impulse over 400 ms. */
    public void reviveImpulse(ActorState player,int stamp){
        var region=new FireCatalog.DataValue(org.tortodrone.core.WireTypes.REGION_DEFAULT,Map.of("shape",
            new FireCatalog.DataValue(org.tortodrone.core.WireTypes.SHAPE_CIRCLE,Map.of("radius",2f))));
        var impulse=new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.ImpulseConfig$DamageImpulse",Map.of("level",13,"duration",400,
            "translation",new FireCatalog.DataValue(org.tortodrone.core.WireTypes.VECTOR2F,Map.of("x",5f,"y",0f))));
        impulse(new StarterCatalog.Model("ImpartImpulse",Map.of("region",region,"impulse",impulse,"flags",2)),player.x(),player.y(),0,stamp,player.id());
    }
    @Override public void impulse(StarterCatalog.Model region,float x,float y,float rotation,int stamp,int owner){
        Object raw=region.arguments().get("region"),shape=region.arguments().getOrDefault("shape",SceneDamage.field(raw,"shape"));
        float expansion=SceneDamage.number(raw,"expansion",0),c=(float)Math.cos(rotation),s=(float)Math.sin(rotation);
        int flags=region.arguments().get("flags") instanceof Number n?n.intValue():1;
        for(var e:entities.values())if(e.id!=owner&&e.kind.equals("Monster")&&e.destroyed==Integer.MAX_VALUE&&(e.spec.integer("damage-mask",0)&flags)!=0){
            float dx=e.x-x,dy=e.y-y;
            if(shape instanceof FireCatalog.DataValue?SceneShape.intersects(shape,dx*c+dy*s,-dx*s+dy*c,e.spec.number("radius",0)+expansion):Math.hypot(dx,dy)<=ScenePortals.PAWN_RADIUS+e.spec.number("radius",0)+expansion)
                applyImpulse(e,region,x,y,rotation,stamp);
        }
        if((flags&Math.round(content.stats().getOrDefault("player.damage-mask",SceneContent.DEFAULT_PLAYER_DAMAGE_MASK)))!=0)for(var player:players)if(player.id()!=owner&&playerActive.test(player.id())){
            float dx=player.x()-x,dy=player.y()-y;
            if(shape instanceof FireCatalog.DataValue?SceneShape.intersects(shape,dx*c+dy*s,-dx*s+dy*c,ScenePortals.PAWN_RADIUS+expansion):Math.hypot(dx,dy)<=2*ScenePortals.PAWN_RADIUS+expansion){
                var impulse=createImpulse(region,x,y,rotation,player.x(),player.y(),Math.round(content.stats().getOrDefault("player.impulse-level",1f)),stamp);
                if(impulse!=null)playerImpulse.accept(player.id(),impulse);
            }
        }
    }
    @Override public boolean monsters(Object region,float x,float y,float rotation){
        Object shape=SceneDamage.field(region,"shape");float expansion=SceneDamage.number(region,"expansion",0),c=(float)Math.cos(rotation),s=(float)Math.sin(rotation);
        for(var e:entities.values())if(e.kind.equals("Monster")&&e.destroyed==Integer.MAX_VALUE){
            float dx=e.x-x,dy=e.y-y;if(shape instanceof FireCatalog.DataValue?SceneShape.intersects(shape,dx*c+dy*s,-dx*s+dy*c,e.spec.number("radius",0)+expansion):Math.hypot(dx,dy)<=ScenePortals.PAWN_RADIUS+e.spec.number("radius",0)+expansion)return true;
        }return false;
    }
    private KnightCombat.Impulse createImpulse(StarterCatalog.Model region,float x,float y,float rotation,float tx,float ty,int level,int stamp){
        Object origin=region.arguments().get("impulse-origin");float ox=SceneDamage.number(origin,"x",0),oy=SceneDamage.number(origin,"y",0),c=(float)Math.cos(rotation),s=(float)Math.sin(rotation);
        return SceneDamage.impulse(region.arguments().get("impulse"),x+ox*c-oy*s,y+ox*s+oy*c,tx,ty,level,stamp);
    }
    private void applyImpulse(SceneEntity entity,StarterCatalog.Model region,float x,float y,float rotation,int stamp){
        // The shared combat table stores stats as floats and actor() injects them unrounded
        // ("1.0"); impulse levels are whole tiers, so read and round like the player side.
        var impulse=createImpulse(region,x,y,rotation,entity.x,entity.y,Math.round(entity.spec.number("impulse-level",1)),stamp);
        if(impulse!=null){entity.impulse=SceneDamage.combine(impulse,entity.impulse);entity.impulseAdvanced=stamp;}
    }
    private void advanceImpulse(SceneEntity e,int stamp){movementSystem.advanceImpulse(e,stamp);}
    private void blockHit(SceneEntity e,int stamp){
        e.hitTime=stamp;
        switch(e.spec.text("kind","")){
            case "TimedBlock" -> {if(e.state==0){e.state=1;e.stateEntered=stamp;e.detonateAt=stamp+e.spec.integer("block-fuse",0);}}
            case "StarlinkBlock" -> clearLinks(e,stamp,false);
            case "GhostlinkBlock" -> clearLinks(e,stamp,true);
            case "StoneBlock" -> {if(e.state<e.spec.integer("block-hits",1)-1){e.state++;e.stateEntered=stamp;}else e.destroyed=stamp;}
            default -> {
                if(e.spec.text("damage-action","").equals("destroy"))e.destroyed=stamp;
                else if(e.config.name().startsWith("Block/Treasure, Health")){
                    // Native treasure blocks run a TableSpawnPerMember of hearts and
                    // then DestroyActor. The heart table is not expressible in content,
                    // so drop the tutorial heart and destroy the block like stock.
                    e.destroyed=stamp;
                    dropHeart(e,stamp);
                }
            }
        }
    }
    /** Native Treasure Health block: run the block's official heart table (Treasure/Block/
     *  Treasure, Health (Tutorial) → Treasure/Health/Treasure Health, Hearts → Parts/Monster
     *  Health → Treasure/Health/Value/Hearts) exactly like the TableSpawnPerMember its
     *  destruction action carries: one value draw from the tutorial's depth entries (the
     *  native ValueDepthListEntry rule - bounds interpolated between bands, drawn gaussian
     *  at the spread's mean 0.43/dev 0.25, clamped, then the table's -1 value offset), spent
     *  largest heart tier first, each tier composing its actors from its own depth band.
     *  The tutorial floor runs at depth 0, where the bounds are both 2 and the draw is
     *  deterministically one tier-1 Heart. */
    private void dropHeart(SceneEntity e,int stamp){
        int value=heartValue(0);
        for(int tier=HEART_TIERS;tier>=1;tier--)while(value>=tier){value-=tier;for(String config:heartTierActors(tier,0))spawnHeart(e,stamp,config);}
    }
    /** The tutorial table's ValueDepthEntry bands {depth, min, max}. */
    private static final int[][] HEART_DEPTH={{0,2,2},{8,2,5},{9,3,4},{18,3,6},{19,3,7},{30,4,9}};
    private static final int HEART_TIERS=3;
    /** ValueDepthListEntry.b: pick the band at the scene depth, interpolate its bounds toward
     *  the next band, draw a gaussian at the spread (mean 0.43, dev 0.25 of max-min), clamp,
     *  then apply the table's -1 value offset. */
    private int heartValue(int depth){
        float min=0,max=0;
        for(int i=HEART_DEPTH.length-1;i>=0;i--){
            if(HEART_DEPTH[i][0]>depth)continue;
            min=HEART_DEPTH[i][1];max=HEART_DEPTH[i][2];
            if(i+1>=HEART_DEPTH.length||depth==HEART_DEPTH[i][0])break;
            float portion=(float)(depth-HEART_DEPTH[i][0])/(HEART_DEPTH[i+1][0]-HEART_DEPTH[i][0]);
            min+=portion*(HEART_DEPTH[i+1][1]-min);max+=portion*(HEART_DEPTH[i+1][2]-max);
            break;
        }
        float spread=max-min;
        int value=Math.round((float)random.nextGaussian(spread*0.43f+min,spread*0.25f));
        value=Math.max((int)min,Math.min((int)max,value));
        return value-1;
    }
    /** The tier spend's actor composition per depth band, from Treasure/Health/Value/Heart 1-3:
     *  tier 2 and 3 tables compose plain Hearts below depth 9, the larger Heart actor from
     *  9 to 18, and at depth 19+ tier 3 is one Heart 3 while tier 2 returns to two Hearts. */
    private static String[] heartTierActors(int tier,int depth){
        return switch(tier){
            case 1->new String[]{"Item/Consumable/Hearts/Heart"};
            case 2->depth<9||depth>=19?new String[]{"Item/Consumable/Hearts/Heart","Item/Consumable/Hearts/Heart"}
                :new String[]{"Item/Consumable/Hearts/Heart 2"};
            default->depth<9?new String[]{"Item/Consumable/Hearts/Heart","Item/Consumable/Hearts/Heart","Item/Consumable/Hearts/Heart"}
                :depth<19?new String[]{"Item/Consumable/Hearts/Heart 2","Item/Consumable/Hearts/Heart"}
                :new String[]{"Item/Consumable/Hearts/Heart 3"};
        };
    }
    /** The native heart pickups: Item/Consumable/Hearts/Heart tiers heal 40/80/120 on contact. */
    private void spawnHeart(SceneEntity e,int stamp,String config){
        int heal=config.endsWith("Heart 2")?80:config.endsWith("Heart 3")?120:40;
        var fields=new TreeMap<String,String>();
        fields.put("config",config);
        fields.put("native-type","com.threerings.projectx.dungeon.data.actor.Pickup");
        fields.put("shape","Circle");
        fields.put("radius","0.4");
        fields.put("pickup-heal",Integer.toString(heal));
        fields.put("vacuum-expansion","0.6");
        fields.put("vacuum-speed","8");
        fields.put("lifespan","120000");
        try{
            var json=org.tortodrone.core.Json.stringify(fields);
            spawnPickup(new StarterCatalog.Model("Pickup",Map.of("fields",json)),e.x,e.y,e.rotation,stamp);
        }catch(java.io.IOException invalid){throw new IllegalStateException("Invalid prepared heart pickup",invalid);}
    }
    private void clearLinks(SceneEntity source,int stamp,boolean ghost){
        if(source.destroyed!=Integer.MAX_VALUE)return;source.destroyed=stamp;if(ghost)source.flags|=2;
        float c=(float)Math.cos(source.rotation),s=(float)Math.sin(source.rotation);
        for(var target:entities.values())if(target!=source&&target.destroyed==Integer.MAX_VALUE&&target.kind.startsWith("Block")
            &&(ghost||target.spec.text("kind","").equals("StarlinkBlock")&&target.spec.integer("block-color",0)==source.spec.integer("block-color",0))){
            if(target.spec.contains(target.x,target.y,target.rotation,source.x+c,source.y+s,0)
                ||target.spec.contains(target.x,target.y,target.rotation,source.x-c,source.y-s,0)
                ||target.spec.contains(target.x,target.y,target.rotation,source.x-s,source.y+c,0)
                ||target.spec.contains(target.x,target.y,target.rotation,source.x+s,source.y-c,0))links.addLast(new Link(target,stamp+50,ghost));
        }
    }
    /** Native MonsterLogic.hurt: only reacting damage marks the hit effect, and the
     * hurt activity follows shouldReact rather than any single hit's size. */
    private void hurt(SceneEntity e,float amount,int stamp,boolean react,boolean major){
        if(e.health<=0)return;
        e.health=Math.max(0,e.health-amount);e.hitTime=stamp;
        e.reactDamage.addLast(new int[]{(int)Math.ceil(amount),stamp+REACT_WINDOW_MILLIS});
        while(e.reactDamage.size()>64)e.reactDamage.removeFirst();
        if(react){e.affectedTime=stamp;e.affectedType=0;}
        if(e.health==0){
            e.activity=2;e.activityStarted=stamp;e.destroyed=stamp+e.spec.integer("death-time",100);e.flags=0;
            String death=e.spec.text("death-action","");
            if(!death.isBlank())try{attack.accept(e.snapshot(),FireCatalog.actionJson(death));}
            catch(java.io.IOException invalid){throw new IllegalArgumentException("Invalid prepared monster death action",invalid);}
        }
        else if(shouldReact(e,react,major,stamp)){e.activity=1;e.activityStarted=stamp;}
    }
    /** Native window over which MonsterLogic sums damage for a major reaction. */
    static final int REACT_WINDOW_MILLIS=1000;
    /** Native MonsterLogic.shouldReact: an interruptable attack yields to any reacting hit;
     * otherwise only major damage whose one-second sum strictly exceeds min-react-percent. */
    private static boolean shouldReact(SceneEntity e,boolean react,boolean major,int stamp){
        if(react&&e.activity>=4&&e.activity<16&&e.spec.flag("attack."+(e.activity-4)+".interruptable",false))return true;
        long sum=0;for(var it=e.reactDamage.iterator();it.hasNext();){int[] rec=it.next();if(rec[1]<stamp)it.remove();else sum+=rec[0];}
        float maximum=e.maximumHealth>0?e.maximumHealth:e.spec.number("health",0);
        if(major&&maximum>0&&sum/maximum>e.spec.number("min-react-percent",0)){e.reactDamage.clear();return true;}
        return false;
    }
}
