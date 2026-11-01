package org.tortodrone.world;

import java.util.*;
import org.tortodrone.character.StarterCatalog;

/** Instance-owned native actors, pad regeneration, movement and damage targets. */
public final class SceneEntities implements SceneWeapons.Targets {
    public record Pickup(int entityId,String item,boolean temporary,Integer equippedSlot,int heat) {
        public Pickup(int entityId,String item,boolean temporary,Integer equippedSlot){this(entityId,item,temporary,equippedSlot,0);}
    }
    private static final Set<String> SUPPORTED=Set.of("Actor","Agent","Stranger","Monster","Generator","Door","Switch","Block","Block$Stateful","Pickup","Trap","Bomb","DungeonLift");
    private static final class Entity {
        final int id,created;final SceneContent.Spec spec;final SceneContent.Entry binding;
        final String kind;final StarterCatalog.Model config;final Entity pad;
        float x,y,rotation,direction,health,speed,attackSpeed=1;int flags,activity,activityStarted,state,stateEntered,count,hitTime,destroyed=Integer.MAX_VALUE,nextGeneration,claim;
        boolean claimTaken,ignited,scriptDestroyed;
        String scriptSource="";
        final SceneMovementScript script;
        final SceneTargeting targeting;
        SceneTargeting.Mode targetMode;boolean targetModeUpdate,idleAttackDone;float targetRotation;
        final SceneWander wander;
        final SceneFollow follow;
        int nextFollow,followTarget;
        ScenePickupState pickupState;
        final Map<Integer,SceneAttackMotion> attackMotion;
        int wanderPhase,wanderNext;float wanderRotation;
        final Map<Integer,Threat> victims;
        private record Threat(int hits,float damage) { }
        int scriptStep,scriptStarted=Integer.MIN_VALUE;ActorState scriptGoal;
        int target,nextAcquire,nextAttack,nextSwitch,nextHeal,carrier,owner,ownerOid,thrownAt,faction,detonateAt,affectedType,affectedTime;float z;int[] excluded;
        List<ScenePathing.Point> path=List.of();float pathX,pathY;int nextPath;
        SceneLiftState.Point dropPoint;
        SceneContent.Spec vial;String cycle;Set<String> fired=new HashSet<>();
        Object hazardStatus;int hazardNext;
        final SceneStatus status;
        final Object shape;
        final int collisionFlags;
        final boolean circularPawnBounds,compoundPawnBounds,interactive;
        final float maximumHealth;
        final float pawnRadius,pawnMinX,pawnMinY,pawnMaxX,pawnMaxY;
        final SceneCollision.PawnShape pawnShape;
        SceneCollision.PawnShape posedPawnShape;
        float pawnX,pawnY,pawnRotation;
        KnightCombat.Impulse impulse;int impulseAdvanced;
        final Map<Integer,Integer> hazardHits=new HashMap<>();
        final Set<Integer> refusedPickups=new HashSet<>();
        Entity(int id,int stamp,SceneContent.Entry binding,SceneContent.Spec spec,Entity pad,SceneContent content){
            this.id=id;created=stamp;this.binding=binding;this.spec=spec;this.pad=pad;
            script=SceneMovementScript.read(spec);targeting=SceneTargeting.read(spec);wander=SceneWander.read(spec);follow=SceneFollow.read(spec);attackMotion=SceneAttackMotion.readAll(spec);
            if(follow!=null&&(targeting==null||targeting.monsterMask()==0||script!=null||wander!=null))throw new IllegalArgumentException("Conflicting native follow routine");
            if(script!=null&&(targeting!=null||wander!=null))throw new IllegalArgumentException("Conflicting scene actor behaviors");
            victims=targeting==null?Map.of():new LinkedHashMap<>();
            config=spec.config();String type=spec.text("native-type","");kind=type.substring(type.lastIndexOf('.')+1);
            int heat=spec.integer("pickup-heat",0);
            if(heat<0||heat>1_000_000||heat>0&&(!kind.equals("Pickup")||!spec.text("pickup-item","").isEmpty()))throw new IllegalArgumentException("Invalid native heat pickup");
            if((targeting!=null||wander!=null||!attackMotion.isEmpty())&&!kind.equals("Monster"))throw new IllegalArgumentException("Native combat behavior requires a monster actor");
            interactive=content.conversations().containsKey(spec.text("dialogue",config.name()))||spec.fields().containsKey("town-station")||spec.fields().containsKey("town-kind");
            x=binding.x();y=binding.y();rotation=binding.rotation();health=spec.number("health",0);
            maximumHealth=health;
            activityStarted=stateEntered=stamp;
            status=new SceneStatus(content,true);
            var preparedShape=spec.model("source-shape");shape=preparedShape==null?null:preparedShape.arguments().get("value");
            collisionFlags=spec.integer("collision-flags",0);
            circularPawnBounds=spec.fields().containsKey("radius");compoundPawnBounds=spec.text("shape","").equals("Compound")&&shape!=null;
            pawnRadius=spec.number("radius",0);pawnMinX=spec.number("bounds-min-x",-spec.number("width",0)/2);pawnMaxX=spec.number("bounds-max-x",spec.number("width",0)/2);
            pawnMinY=spec.number("bounds-min-y",-spec.number("height",0)/2);pawnMaxY=spec.number("bounds-max-y",spec.number("height",0)/2);
            pawnShape=collisionFlags==0?null:SceneCollision.pawnShape(spec,shape);
            speed=spec.number("speed",0);
            faction=spec.integer("faction",0);
            if(kind.equals("Switch"))flags|=2; // Native Switch constructor's START flag, including help bubbles.
            if(spec.flag("start-open",false))flags|=2;
            cycle=spec.text("cycle","");int split=cycle.lastIndexOf('|');if(split>=0)cycle=cycle.substring(split+1);
        }
        boolean containsPawn(float px,float py){
            if(circularPawnBounds)return Math.hypot(x-px,y-py)<=pawnRadius+ScenePortals.PAWN_RADIUS;
            float dx=px-x,dy=py-y,c=(float)Math.cos(rotation),s=(float)Math.sin(rotation),tx=dx*c+dy*s,ty=-dx*s+dy*c;
            if(compoundPawnBounds)return SceneShape.intersects(shape,tx,ty,ScenePortals.PAWN_RADIUS);
            float gapX=Math.max(pawnMinX-tx,Math.max(0,tx-pawnMaxX)),gapY=Math.max(pawnMinY-ty,Math.max(0,ty-pawnMaxY));
            return gapX*gapX+gapY*gapY<=ScenePortals.PAWN_RADIUS*ScenePortals.PAWN_RADIUS;
        }
        SceneCollision.Penetration penetration(float px,float py){
            if(posedPawnShape==null||pawnX!=x||pawnY!=y||pawnRotation!=rotation){posedPawnShape=pawnShape.at(x,y,rotation);pawnX=x;pawnY=y;pawnRotation=rotation;}
            return posedPawnShape.penetration(px,py,ScenePortals.PAWN_RADIUS);
        }
        boolean entrySource(){return interactive&&spec.integer("handler-index",0)<0&&binding.key().matches("[0-9]+");}
        ActorState snapshot(){return new ActorState(id,0,"",0,created,x,y,rotation,direction,flags,activity,activityStarted,config,false,null,null,
            new SceneEntityState(kind,destroyed,speed,pawnRadius,
                maximumHealth>0?health/maximumHealth:-1,state,stateEntered,count,hitTime,binding.key(),
                kind.equals("DungeonLift")?new SceneLiftState(z,faction,excluded,dropPoint):null,status.state(),affectedType,affectedTime,entrySource(),pickupState));}
    }
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
     *  ScorcherTorch, Damage {flags 2, Fire 1.0/80, ULTRA/MODERATE}, toggled by its Lifetime
     *  argument's served choice to a 6 Second cleanup fuse while the streamed Lifetime argument
     *  renders the flame for its authored 4 seconds; Oil Pot nine Oil Slick patches).
     *  Damage stops at 4 seconds conservatively: stripped handler conditions do not establish
     *  the native damage cutoff. Cleanup must never extend the active damage region.
     *  Pots without an entry below stay inert. */
    private record Pot(float blast,StarterCatalog.Model ailment,Object hazardDamage,String hazardConfig,float hazardRadius,int hazardLifetime) { }
    private static final Map<String,Pot> POTS=Map.of(
        "Dynamic/Lift Objects/Clay Pot",new Pot(.5f,null,null,null,0,0),
        "Dynamic/Lift Objects/Fire Pot",new Pot(1f,new StarterCatalog.Model("Fire",Map.of(
                "Chance",1f,"Power",80,
                "Status Chance",new FireCatalog.EnumValue("com.threerings.projectx.dungeon.data.StatusCodes$StatusChance","ULTRA",5),
                "Status Power",new FireCatalog.EnumValue("com.threerings.projectx.dungeon.data.StatusCodes$StatusPower","MODERATE",2))),
            new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$Status",Map.of(
                "flags",2,"statusCondition",new StarterCatalog.Model("Fire",Map.of(
                    "Chance",1f,"Power",80,
                    "Status Chance",new FireCatalog.EnumValue("com.threerings.projectx.dungeon.data.StatusCodes$StatusChance","ULTRA",5),
                    "Status Power",new FireCatalog.EnumValue("com.threerings.projectx.dungeon.data.StatusCodes$StatusPower","MODERATE",2))))),
            "Bomb/Monster/ScorcherTorch",1f,6000),
        "Dynamic/Lift Objects/Oil Pot",new Pot(1f,null,null,"Bomb/Monster/Oil Slick",.45f,15000));

    private final SceneContent content;private final CollisionMap terrain;
    private final SceneActions actions;
    private final Map<String,List<SceneContent.Entry>> anchors=new LinkedHashMap<>();
    private final SceneCollision staticCollision;
    private final int pawnCollisionMask;
    private final Map<Integer,Entity> entities=new LinkedHashMap<>();
    private final Map<Entity,List<Entity>> arsenalProps=new LinkedHashMap<>();
    private int nextId=1_000_000_000,lastStamp;
    private final java.util.random.RandomGenerator random;
    private record Link(Entity entity,int stamp,boolean ghost) { }
    private final ArrayDeque<Link> links=new ArrayDeque<>();
    public record PlayerHit(int actor,Object damage,int timestamp,int source,float multiplier) {
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
    @FunctionalInterface public interface EffectSink { void fire(org.tortodrone.character.StarterCatalog.Model config,ActorState source,int stamp); }
    private EffectSink effectSink=(config,source,stamp)->{};
    public void effects(EffectSink sink){effectSink=Objects.requireNonNull(sink);}
    /** Simulation-only request sink. Never perform storage or network work in this callback. */
    @FunctionalInterface public interface FloorAdvanceSink { boolean request(ActorState player,ActorState source,int stamp); }
    private FloorAdvanceSink floorAdvanceSink=(player,source,stamp)->false;
    public void floorAdvances(FloorAdvanceSink sink){floorAdvanceSink=Objects.requireNonNull(sink);}
    @FunctionalInterface public interface MemorySink {boolean apply(ActorState player,org.tortodrone.character.KnightMemory operation,ActorState source,int stamp);}
    private MemorySink memorySink=(player,operation,source,stamp)->false;
    public void memories(MemorySink sink){memorySink=Objects.requireNonNull(sink);}
    private List<ActorState> players=List.of();
    public SceneEntities(SceneContent content,CollisionMap terrain){
        this(content,terrain,SceneCollision.empty());
    }
    public SceneEntities(SceneContent content,CollisionMap terrain,SceneCollision staticCollision){this(content,terrain,staticCollision,new Random());}
    public SceneEntities(SceneContent content,CollisionMap terrain,SceneCollision staticCollision,java.util.random.RandomGenerator random){
        this.random=Objects.requireNonNull(random);
        this.content=content;this.terrain=terrain;this.staticCollision=Objects.requireNonNull(staticCollision);pawnCollisionMask=Math.round(content.stats().getOrDefault("player.collision-mask",43f));int stamp=SceneClock.now();
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
        boolean startup=false;
        for(var entry:content.entries())if(entry.spec().flag("scene-startup",false)){startup=true;actions.signal(entry.spec().text("watch-tag",""),"startup",stamp,"entry:"+entry.key());}
        if(startup)actions.advance(stamp);
        // A placed station's interaction region and animated switch share an entry key.
        for(var zone:entities.values())if(zone.spec.flag("arsenal-zone",false))
            arsenalProps.put(zone,entities.values().stream().filter(e->e!=zone&&e.kind.equals("Switch")&&e.binding.key().equals(zone.binding.key())).toList());
    }
    private Entity spawn(SceneContent.Entry binding,SceneContent.Spec spec,Entity pad,int stamp){
        String nativeType=spec.text("native-type","");String kind=nativeType.substring(nativeType.lastIndexOf('.')+1);
        if(!SUPPORTED.contains(kind))throw new IllegalArgumentException("Unsupported prepared scene actor: "+kind);
        var entity=new Entity(nextId++,stamp,binding,spec,pad,content);entities.put(entity.id,entity);
        if(kind.equals("Monster")&&spec.integer("activate-time",0)>0)entity.activity=3;
        if(kind.equals("Generator")&&!spec.child().fields().isEmpty()){
            generate(entity,stamp);
        }
        return entity;
    }
    @Override public boolean spawnPickup(StarterCatalog.Model specification,float x,float y,float rotation,int stamp){
        if(specification==null||!(specification.arguments().get("fields") instanceof String json)||entities.size()>=4096)return false;
        try{
            if(!(org.tortodrone.resources.Json.parse(json) instanceof Map<?,?> raw))return false;
            var fields=new HashMap<String,String>();for(var entry:raw.entrySet()){
                if(!(entry.getKey() instanceof String key)||!(entry.getValue() instanceof String value))return false;
                fields.put(key,value);
            }
            var spec=new SceneContent.Spec(fields);
            if(!spec.text("native-type","").equals("com.threerings.projectx.dungeon.data.actor.Pickup")||spec.text("pickup-item","").isBlank())return false;
            spawn(new SceneContent.Entry("loot:"+nextId,x,y,rotation,spec),spec,null,stamp);return true;
        }catch(java.io.IOException invalid){throw new IllegalArgumentException("Invalid prepared loot actor",invalid);}
    }
    private void generate(Entity pad,int stamp){
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
    public List<ActorState> actors(){return entities.values().stream().filter(e->!e.entrySource()).map(Entity::snapshot).toList();}
    public ActorState actor(int id){var e=entities.get(id);return e==null||e.destroyed!=Integer.MAX_VALUE?null:e.snapshot();}
    public SceneContent.Spec specification(int id){var e=entities.get(id);return e==null||e.destroyed!=Integer.MAX_VALUE?null:e.spec;}
    public ActorState station(String kind){return entities.values().stream().filter(e->e.spec.text("town-kind","").equals(kind)&&e.destroyed==Integer.MAX_VALUE).map(Entity::snapshot).findFirst().orElse(null);}
    /** Called only after the scene validates the knight's interaction intent. */
    public void openArsenal(int id,int stamp){
        var props=arsenalProps.get(entities.get(id));
        if(props!=null)for(var prop:props)if(prop.state==0)setSwitch(prop,1,stamp);
    }
    public boolean carry(int id,ActorState player,int stamp){
        var e=entities.get(id);if(e==null||!e.kind.equals("DungeonLift")||e.destroyed!=Integer.MAX_VALUE||(e.flags&6)!=0)return false;
        e.carrier=e.owner=player.id();e.ownerOid=player.clientOid();e.dropPoint=null;e.flags|=4;return true;
    }
    public int vial(ActorState player,SceneContent.Spec item,int stamp){
        var fields=new TreeMap<>(item.section("lift.").fields());fields.put("destroy-on-land","true");
        var binding=new SceneContent.Entry("item:"+nextId,player.x(),player.y(),player.rotation(),new SceneContent.Spec(fields));
        var e=spawn(binding,binding.spec(),null,stamp);e.vial=item;
        if(!carry(e.id,player,stamp)){entities.remove(e.id);return 0;}return e.id;
    }
    public boolean canThrow(int id){var e=entities.get(id);return e!=null&&e.spec.flag("can-throw",false);}
    private Entity snapTarget(Entity lift,ActorState player){
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
        e.x=player.x();e.y=player.y();e.rotation=e.direction=player.rotation();e.thrownAt=stamp;
        e.z=content.stats().getOrDefault("player.lift-height",0f);
    }
    public void dropLift(int id,ActorState player,int stamp){
        var e=entities.get(id);if(e==null||e.carrier!=player.id())return;
        e.carrier=0;e.flags&=~6;e.z=0;
        float distance=(ScenePortals.PAWN_RADIUS+e.spec.number("radius",0))*1.01f;
        var target=snapTarget(e,player);
        float x=target==null?player.x()+(float)Math.cos(player.rotation())*distance:target.x;
        float y=target==null?player.y()+(float)Math.sin(player.rotation())*distance:target.y;
        if(terrain.walkable(x,y)&&walkable(x,y,e.id)){e.x=x;e.y=y;}else {e.x=player.x();e.y=player.y();}
        e.dropPoint=new SceneLiftState.Point(e.x,e.y);
        e.rotation=e.direction=player.rotation();e.faction=player.id();e.excluded=new int[0];
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
    private static Pickup pickup(Entity e){return new Pickup(e.id,e.spec.text("pickup-item",""),e.spec.flag("pickup-transient",false),e.spec.fields().containsKey("pickup-equip-slot")?e.spec.integer("pickup-equip-slot",0):null,e.spec.integer("pickup-heat",0));}
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
    private String interactionAction(Entity e){
        String station=e.spec.text("town-kind","");
        String fallback=e.kind.equals("DungeonLift")?"Lift":
            content.conversations().containsKey(e.spec.text("dialogue",e.config.name()))?"Talk":
            Set.of("OpenShop","OpenDepot","OpenAuction").contains(station)?"Shop":"Interact";
        return e.spec.text("interaction-action",fallback);
    }
    public InteractionState interaction(float x,float y,float rotation){
        Entity nearest=null;float best=Float.POSITIVE_INFINITY;
        for(var e:entities.values())if((e.interactive||e.kind.equals("DungeonLift")&&(e.flags&6)==0)&&e.destroyed==Integer.MAX_VALUE){
            float ox=e.spec.number("interaction-offset-x",.7f),oy=e.spec.number("interaction-offset-y",0);
            float c=(float)Math.cos(rotation),s=(float)Math.sin(rotation);
            float distance=(float)Math.hypot(x+ox*c-oy*s-e.x,y+ox*s+oy*c-e.y);
            float radius=e.spec.number("interaction-radius",.4f)+e.spec.number("interaction-expansion",0);
            if(e.spec.contains(e.x,e.y,e.rotation,x+ox*c-oy*s,y+ox*s+oy*c,radius)&&distance<best){nearest=e;best=distance;}
        }
        if(nearest==null)return null;
        String description=nearest.spec.text("description","");
        String action=interactionAction(nearest);
        return new InteractionState(nearest.id,description.isEmpty()?null:new StarterCatalog.Model(description,Map.of()),action,
            nearest.entrySource()?nearest.binding.key():null);
    }
    public void advance(int stamp,List<ActorState> occupants){
        var players=occupants.stream().filter(p->p.activity()!=TownMovement.DYING&&p.activity()!=TownMovement.DEAD&&p.activity()!=TownMovement.RESURRECT).toList();
        this.players=players;
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
        float elapsed=lastStamp==0?0:Math.min(.25f,(stamp-lastStamp)/1000f);lastStamp=stamp;
        for(var e:List.copyOf(entities.values())){
            if(e.destroyed!=Integer.MAX_VALUE){
                // Monster destroyed time already includes the native death animation.
                // Retain one short publication grace, rather than counting it twice.
                if(e.scriptDestroyed?stamp>e.destroyed:stamp-e.destroyed>=100){
                    entities.remove(e.id);
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
                if(stamp>=e.nextGeneration){e.flags&=~2;generate(e,e.nextGeneration);}
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
                var shape=new FireCatalog.DataValue("com.threerings.tudey.shape.config.ShapeConfig$Circle",
                    List.of(new FireCatalog.FieldValue("radius","float",e.spec.number("radius",0))));
                var hitArgs=new LinkedHashMap<String,Object>(Map.of("damage",e.hazardStatus,
                    "region",new FireCatalog.DataValue("com.threerings.tudey.config.RegionConfig$Default",Map.of(
                        "location",new FireCatalog.DataValue("com.threerings.tudey.config.TargetConfig$Source",List.of()),"expansion",0f))));
                hitArgs.put("shape",shape);
                damage(new StarterCatalog.Model("DamageRegionFromPoint",hitArgs),e.x,e.y,e.rotation,stamp,e.id,null);
            }
            if(e.detonateAt>0&&stamp>=e.detonateAt){
                e.destroyed=e.detonateAt;
                String graph=e.spec.text("detonation-action","");if(!graph.isBlank())try{attack.accept(e.snapshot(),FireCatalog.actionJson(graph));}catch(java.io.IOException invalid){throw new IllegalStateException("Invalid block detonation",invalid);}
                continue;
            }
            if(e.kind.equals("Door")&&(e.flags&4)!=0&&stamp-e.stateEntered>e.spec.integer("door-speed",0))e.flags&=~4;
            if(e.kind.equals("Switch"))switches(e,stamp,players);
            if(e.spec.flag("heal-pad",false)&&stamp>=e.nextHeal){
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
                    if(stamp>=e.hazardHits.getOrDefault(player.id(),0)&&SceneShape.intersects(sensor,dx*c+dy*s,-dx*s+dy*c,ScenePortals.PAWN_RADIUS)){
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
                e.status.advance(stamp,(int)(elapsed*1000),(e.flags&2)!=0,amount->{if(e.health>0)hurt(e,(float)amount,stamp);},amount->{if(e.health>0)e.health=Math.min(e.maximumHealth,e.health+(float)amount*e.status.state().modifiers().heal());});
                if(e.script!=null)scriptedMovement(e,stamp,elapsed);
                else if(e.health==0)e.status.clear();else {if(e.status.state().modifiers().canMove())advanceImpulse(e,stamp);else e.impulse=null;move(e,stamp,elapsed,players);}
            }
            if(e.kind.equals("DungeonLift"))lift(e,stamp,elapsed,players);
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
    }
    /** Secondary burn/spasm damage retains its collision mask and excludes the affected source. */
    public void statusPulse(int source,SceneStatus.Pulse pulse,float x,float y,int stamp){
        if((pulse.mask()&1)!=0)for(var p:players)if(p.id()!=source&&playerActive.test(p.id())&&Math.hypot(p.x()-x,p.y()-y)<=pulse.radius()+2*ScenePortals.PAWN_RADIUS)
            playerDamage.accept(new PlayerHit(p.id(),pulse.damage(),stamp,source,1));
        if((pulse.mask()&128)!=0)for(var target:List.copyOf(entities.values()))if(target.id!=source&&target.kind.equals("Monster")&&target.health>0&&Math.hypot(target.x-x,target.y-y)<=pulse.radius()+target.spec.number("radius",0)+ScenePortals.PAWN_RADIUS){
            Object accepted=SceneDamage.prune(pulse.damage(),target.spec.integer("damage-mask",0));float amount=SceneDamage.amount(accepted,content,target.spec);
            amount+=target.status.damaged(amount,stamp);target.status.apply(accepted,stamp);if(amount>0)hurt(target,amount,stamp);
        }
    }
    private void lift(Entity e,int stamp,float elapsed,List<ActorState> players){
        if(e.carrier!=0){for(var player:players)if(player.id()==e.carrier){e.x=player.x();e.y=player.y();e.rotation=player.rotation();return;}return;}
        if((e.flags&2)==0)return;
        float distance=e.speed*elapsed;boolean collided=false;
        int steps=Math.max(1,(int)Math.ceil(Math.abs(distance)/.1f));
        // Thrown lifts also collide with the native lift bit, so closed Lift Barriers catch them.
        int thrownMask=e.spec.integer("thrown-collision-mask",-1);
        int flightMask=thrownMask>=0?thrownMask|LIFT_COLLISION:thrownMask;
        for(int step=0;step<steps;step++){
            float x=e.x+(float)Math.cos(e.direction)*distance/steps,y=e.y+(float)Math.sin(e.direction)*distance/steps;
            boolean clear=staticCollision.isEmpty()||e.shape==null?terrain.walkableSegment(e.x,e.y,x,y):
                staticCollision.clearSegment(flightMask,e.shape,e.x,e.y,x,y,e.rotation);
            if(!clear){collided=true;break;}
            e.x=x;e.y=y;
            // Execute the landing action at the colliding substep. Keeping the
            // previous clear pose left a thrown vial outside the victim's shape.
            if(contact(x,y,e.rotation,e.id,e.shape,flightMask)){collided=true;break;}
        }
        if(stamp-e.thrownAt>=e.spec.integer("throw-time",0))e.z=Math.max(0,e.z-e.spec.number("fall-speed",0)*elapsed);
        if(collided||e.z<=0||stamp-e.thrownAt>=e.spec.integer("fuse",0))land(e,stamp);
    }
    private void land(Entity e,int stamp){
        e.flags&=~2;
        potLand(e,stamp);
        String graph=e.vial==null?e.spec.text("land-action",""):e.vial.text("use-action","");
        if(!graph.isBlank())try{attack.accept(e.snapshot(),FireCatalog.actionJson(graph));}catch(java.io.IOException invalid){throw new IllegalStateException("Invalid prepared lift action",invalid);}
        if(e.spec.flag("destroy-on-land",false))e.destroyed=stamp;
    }
    /** Native {@code Pot (Base)} land handler: smash region, then per-variant Extra Action. */
    private void potLand(Entity e,int stamp){
        var pot=POTS.get(e.spec.config().name());if(pot==null)return;
        var damages=new ArrayList<Object>();
        if(pot.ailment()!=null)damages.add(new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$Status",
            Map.of("flags",2,"statusCondition",pot.ailment())));
        damages.add(new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$DepthScale",Map.of(
            "depthScale",new StarterCatalog.Model("PC/Damage/Base/Default",Map.of()),
            "type",new FireCatalog.EnumValue("com.threerings.projectx.dungeon.util.DamageSummary$DamageType","NORMAL",0),"flags",2)));
        var args=new LinkedHashMap<String,Object>(Map.of("damage",new FireCatalog.DataValue(
                "com.threerings.projectx.dungeon.config.DamageConfig$Compound",Map.of("damages",
                    new FireCatalog.ListValue("com.threerings.projectx.dungeon.config.DamageConfig",damages))),
            "region",new FireCatalog.DataValue("com.threerings.tudey.config.RegionConfig$Default",Map.of(
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
        var circle=new FireCatalog.DataValue("com.threerings.tudey.shape.config.ShapeConfig$Circle",Map.of("radius",radius));
        if(staticCollision.isEmpty()?!terrain.walkable(x,y):!staticCollision.floorSupported(x,y)||!staticCollision.clear(3,circle,x,y,0))return false;
        for(var e:entities.values())if(e.id!=source&&e.destroyed==Integer.MAX_VALUE&&e.carrier==0
                &&(e.config.name().equals("Bomb/Monster/Oil Slick")||(e.collisionFlags&3)!=0)
                &&e.spec.contains(e.x,e.y,e.rotation,x,y,radius))return false;
        return true;
    }
    private void lightOil(Entity oil,int stamp){
        if(oil.ignited)return;
        oil.ignited=true;
        var fire=new StarterCatalog.Model("Fire (Scaled)",Map.of(
            "Status Chance",new FireCatalog.EnumValue("com.threerings.projectx.dungeon.data.StatusCodes$StatusChance","ULTRA",5),
            "Status Power",new FireCatalog.EnumValue("com.threerings.projectx.dungeon.data.StatusCodes$StatusPower","MODERATE",2)));
        var damage=new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$Status",Map.of("statusCondition",fire,"flags",7));
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
    private Entity hazard(String config,Object hazard,float radius,int lifetime,Map<String,String> arguments,float x,float y,int stamp){
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
        var entity=new Entity(nextId++,stamp,binding,spec,null,content);
        entity.hazardStatus=hazard;
        entities.put(entity.id,entity);return entity;
    }

    private void collect(Entity e,float elapsed,List<ActorState> players){
        if(e.spec.text("pickup-item","").isEmpty()&&e.spec.integer("pickup-heat",0)==0)return;
        for(var player:players){
            if(!playerActive.test(player.id())||!pickupEligible.test(player.id(),e.spec))continue;
            float dx=player.x()-e.x,dy=player.y()-e.y,distance=(float)Math.hypot(dx,dy);
            float reach=e.spec.number("radius",0)+ScenePortals.PAWN_RADIUS;
            if(distance>reach+e.spec.number("vacuum-expansion",0)){e.refusedPickups.remove(player.id());continue;}
            if(e.refusedPickups.contains(player.id()))continue;
            if(e.spec.integer("pickup-heat",0)>0){e.claim=player.id();return;}
            if(distance<=reach){e.claim=player.id();return;}
            float step=Math.min(distance,e.spec.number("vacuum-speed",0)*elapsed);
            float x=e.x+dx*step/distance,y=e.y+dy*step/distance;
            if(terrain.walkableSegment(e.x,e.y,x,y)){e.x=x;e.y=y;}
            return;
        }
    }
    private void scriptedMovement(Entity e,int stamp,float elapsed){
        e.flags&=~2;if(e.scriptStep>=4)return;
        if(e.scriptStep==0){e.scriptStep++;return;}
        if(e.scriptStarted==Integer.MIN_VALUE)e.scriptStarted=stamp;
        if(e.scriptStep==1){if(stamp-e.scriptStarted>e.script.waitMillis()){e.scriptStep++;e.scriptStarted=Integer.MIN_VALUE;}return;}
        if(e.scriptStep==2){
            if(e.scriptGoal==null){
                if(e.script.goal().isEmpty()){e.scriptStep++;e.scriptStarted=Integer.MIN_VALUE;return;}
                e.scriptGoal=taggedSources(e.script.goal(),stamp).stream().min(Comparator.comparingDouble(a->Math.hypot(a.x()-e.x,a.y()-e.y))).orElse(null);
                if(e.scriptGoal==null){e.scriptStep++;e.scriptStarted=Integer.MIN_VALUE;return;}
            }
            if(!e.status.state().modifiers().canMove())return;
            float tx=e.scriptGoal.x(),ty=e.scriptGoal.y(),speed=e.speed*e.status.state().modifiers().speed();
            if(Math.hypot(tx-e.x,ty-e.y)>e.script.maxPath())return;
            if(Math.hypot(tx-e.x,ty-e.y)<=speed*elapsed){
                moveEntity(e,tx,ty);
                if(Math.hypot(tx-e.x,ty-e.y)<.0001){e.scriptStep++;e.scriptStarted=Integer.MIN_VALUE;e.path=List.of();return;}
            }
            if(e.path.isEmpty()&&stamp>=e.nextPath){
                var path=ScenePathing.route(e.x,e.y,tx,ty,(x,y,nx,ny)->entitySegment(e,x,y,nx,ny));
                double length=0;float px=e.x,py=e.y;for(var point:path){length+=Math.hypot(point.x()-px,point.y()-py);px=point.x();py=point.y();}
                if(length<=e.script.maxPath())e.path=path;e.nextPath=stamp+250;
            }
            while(!e.path.isEmpty()&&Math.hypot(e.path.getFirst().x()-e.x,e.path.getFirst().y()-e.y)<.01)e.path=e.path.subList(1,e.path.size());
            if(e.path.isEmpty())return;var point=e.path.getFirst();float angle=(float)Math.atan2(point.y()-e.y,point.x()-e.x);
            if(!scriptRotate(e,angle,elapsed))return;
            float distance=(float)Math.hypot(point.x()-e.x,point.y()-e.y),step=Math.min(distance,speed*elapsed);
            if(moveEntity(e,e.x+(float)Math.cos(angle)*step,e.y+(float)Math.sin(angle)*step))e.flags|=2;
            else {e.path=List.of();e.nextPath=stamp+250;}return;
        }
        if(scriptRotate(e,e.script.rotation(),elapsed))e.scriptStep++;
    }
    private static boolean scriptRotate(Entity e,float target,float elapsed){
        if(!e.status.state().modifiers().canRotate())return false;
        float delta=(float)Math.IEEEremainder(target-e.rotation,2*Math.PI),step=e.script.turnRate()*elapsed;
        if(Math.abs(delta)<.0001f){e.rotation=e.direction=target;return true;}
        e.rotation=e.direction=(float)Math.IEEEremainder(e.rotation+Math.copySign(Math.min(Math.abs(delta),step),delta),2*Math.PI);return false;
    }
    private void wander(Entity e,int stamp,float elapsed){
        if(e.activity!=0||!e.status.state().modifiers().canMove())return;
        boolean outside=Math.hypot(e.x-e.binding.x(),e.y-e.binding.y())>e.wander.radius();
        float inward=(float)Math.atan2(e.binding.y()-e.y,e.binding.x()-e.x);
        boolean away=Math.abs(Math.IEEEremainder(inward-e.rotation,2*Math.PI))>Math.PI/2;
        if(e.wanderPhase==0||e.wanderPhase==2&&(stamp>=e.wanderNext||outside&&away)){
            // Wander/Base uses uniform [-pi,pi] direction changes and no pauses.
            // A full-circle uniform turn has the same law around any reflection base.
            e.wanderRotation=(float)(random.nextDouble()*2*Math.PI-Math.PI);e.wanderPhase=1;e.flags&=~2;
        }
        if(e.wanderPhase==1){
            if(!e.status.state().modifiers().canRotate())return;
            float delta=(float)Math.IEEEremainder(e.wanderRotation-e.rotation,2*Math.PI),step=e.wander.turnRate()*elapsed;
            e.rotation=e.direction=(float)Math.IEEEremainder(e.rotation+Math.copySign(Math.min(Math.abs(delta),step),delta),2*Math.PI);
            if(Math.abs(delta)>step+.0001f)return;
            e.wanderPhase=2;e.wanderNext=stamp+e.wander.evaluation();
        }
        float step=e.speed*e.status.state().modifiers().speed()*elapsed;
        if(moveEntity(e,e.x+(float)Math.cos(e.direction)*step,e.y+(float)Math.sin(e.direction)*step))e.flags|=2;
        else {e.flags&=~2;e.wanderPhase=0;}
    }
    private List<ActorState> targetCandidates(Entity e,List<ActorState> occupants){
        if(e.targeting==null||e.targeting.monsterMask()==0)return occupants;
        return entities.values().stream().filter(v->v!=e&&v.kind.equals("Monster")&&v.health>0&&v.destroyed==Integer.MAX_VALUE
            &&v.status.state().modifiers().canBeTargeted()&&(v.spec.integer("damage-mask",0)&e.targeting.monsterMask())!=0).map(Entity::snapshot).toList();
    }
    private void follow(Entity e,int stamp,float elapsed,List<ActorState> occupants){
        if(e.activity!=0)return;
        if(stamp>=e.nextFollow){
            var target=occupants.stream().filter(p->playerActive.test(p.id())).min(Comparator.comparingDouble(p->Math.hypot(p.x()-e.x,p.y()-e.y))).orElse(null);
            e.followTarget=target==null?0:target.id();e.path=List.of();e.nextFollow=stamp+e.follow.evaluation();
            if(target!=null){
                float distance=(float)Math.hypot(target.x()-e.x,target.y()-e.y);
                if(distance<e.follow.minimum()||distance>e.follow.maximum()){
                    var path=ScenePathing.route(e.x,e.y,target.x(),target.y(),(x,y,nx,ny)->entitySegment(e,x,y,nx,ny));
                    double length=0;float px=e.x,py=e.y;for(var point:path){length+=Math.hypot(point.x()-px,point.y()-py);px=point.x();py=point.y();}
                    if(length<=8)e.path=path;
                }
            }
        }
        var target=occupants.stream().filter(p->p.id()==e.followTarget&&playerActive.test(p.id())).findFirst().orElse(null);if(target==null){e.path=List.of();return;}
        while(!e.path.isEmpty()&&Math.hypot(e.path.getFirst().x()-e.x,e.path.getFirst().y()-e.y)<.05)e.path=e.path.subList(1,e.path.size());
        float tx=e.path.isEmpty()?target.x():e.path.getFirst().x(),ty=e.path.isEmpty()?target.y():e.path.getFirst().y();
        float angle=(float)Math.atan2(ty-e.y,tx-e.x),delta=(float)Math.IEEEremainder(angle-e.rotation,2*Math.PI),step=e.spec.number("turn-rate",0)*elapsed;
        if(e.status.state().modifiers().canRotate())e.rotation=e.direction=(float)Math.IEEEremainder(e.rotation+Math.copySign(Math.min(Math.abs(delta),step),delta),2*Math.PI);
        if(e.path.isEmpty()||!e.status.state().modifiers().canMove()||Math.abs(delta)>step+.0001f)return;
        float walk=Math.min((float)Math.hypot(tx-e.x,ty-e.y),e.speed*e.status.state().modifiers().speed()*elapsed);
        if(moveEntity(e,e.x+(float)Math.cos(angle)*walk,e.y+(float)Math.sin(angle)*walk))e.flags|=2;else{e.path=List.of();}
    }
    private boolean targetActive(ActorState actor){return actor.entity()!=null?entities.containsKey(actor.id()):playerActive.test(actor.id())&&playerTargetable.test(actor.id());}
    private boolean acquireIntersects(Entity e,ActorState target){
        var victim=target.entity()==null?null:entities.get(target.id());
        if(victim!=null&&e.shape!=null&&victim.shape!=null)return SceneCollision.overlaps(e.shape,e.x,e.y,e.rotation,victim.shape,victim.x,victim.y,victim.rotation,e.targeting.expansion());
        return e.spec.contains(e.x,e.y,e.rotation,target.x(),target.y(),e.targeting.expansion()+(victim==null?ScenePortals.PAWN_RADIUS:victim.spec.number("radius",0)));
    }
    private float targetDistance(Entity source,ActorState target){
        return targetDistance(source.x,source.y,target);
    }
    private float targetDistance(float x,float y,ActorState target){
        if(target.entity()==null)return Math.max(0,(float)Math.hypot(target.x()-x,target.y()-y)-ScenePortals.PAWN_RADIUS);
        var victim=entities.get(target.id());if(victim==null)return Float.POSITIVE_INFINITY;
        var body=victim.pawnShape==null?SceneCollision.pawnShape(victim.spec,victim.shape):victim.pawnShape;
        return body.at(victim.x,victim.y,victim.rotation).distance(x,y);
    }
    private void move(Entity e,int stamp,float elapsed,List<ActorState> occupants){
        var players=targetCandidates(e,occupants);
        int previousTarget=e.target;
        float distance=e.spec.number("acquire-distance",0);if(distance<=0&&e.targeting==null&&e.wander==null&&e.activity<4)return;
        if(e.targeting!=null&&e.activity==3)return;
        if(e.targeting!=null){
            e.victims.keySet().removeIf(id->players.stream().noneMatch(p->p.id()==id&&targetActive(p)));
            if(stamp>=e.nextAcquire){
                for(var player:players)if(targetActive(player)
                        &&acquireIntersects(e,player)&&e.victims.size()<256)e.victims.putIfAbsent(player.id(),new Entity.Threat(0,0));
                double best=Double.NEGATIVE_INFINITY;e.target=0;
                for(var player:players){var threat=e.victims.get(player.id());if(threat==null)continue;
                    double rank=10.0*threat.hits()+10.0*threat.damage()-targetDistance(e,player);
                    if(rank>best){best=rank;e.target=player.id();}
                }
                e.nextAcquire=stamp+e.targeting.evaluation();e.targetModeUpdate=true;
            }
        }else if(stamp>=e.nextAcquire){
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
            else if(e.targetModeUpdate&&e.activity<4&&stamp>=e.nextAttack){
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
            if(motion!=null&&stamp>=e.activityStarted+motion.delay()&&e.fired.add("@impulse")){
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
                if(stamp>=due){
                    try{attack.accept(e.snapshot(),FireCatalog.actionJson(e.spec.text(fp+"action","")));}
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
        float attackRange=e.targeting==null?e.spec.number("attack-distance",0):e.targetMode==null?0:e.targetMode.range();
        if(target!=null&&e.activity==0&&slot>=0&&(e.targeting==null?stamp>=e.nextAttack:stamp>e.nextAttack)&&attackDistance<=attackRange&&modifiers.attackSpeed()>0
                &&sight(e,target.x(),target.y())){
            if(e.targeting!=null&&e.targetMode.faces()&&modifiers.canRotate()){
                float angle=(float)Math.atan2(target.y()-e.y,target.x()-e.x),delta=(float)Math.IEEEremainder(angle-e.rotation,2*Math.PI);
                if(Math.abs(delta)>e.targetMode.turnSnap()+.001f)return;
            }
            if(beginAttack(e,slot,stamp)){
                if(modifiers.canRotate()&&(e.targeting==null||e.targetMode.faces()))e.rotation=e.direction=(float)Math.atan2(target.y()-e.y,target.x()-e.x);
                e.nextAttack=stamp+Math.round((e.targeting==null?e.spec.integer("attack-interval",0):e.targeting.interval())/e.attackSpeed);
            }
            return;
        }
        if(e.targeting!=null&&(e.targetMode==null||attackDistance<=attackRange&&sight(e,target.x(),target.y())))return;
        if(target==null||!e.status.state().modifiers().canMove()||e.activity!=0||nearest<=e.spec.number("radius",0)+ScenePortals.PAWN_RADIUS)return;
        float tx=target.x(),ty=target.y();
        if(!entitySegment(e,e.x,e.y,tx,ty)){
            if(e.path.isEmpty()&&stamp<e.nextPath)return;
            if(e.path.isEmpty()||Math.hypot(tx-e.pathX,ty-e.pathY)>=1
                    ||!entitySegment(e,e.x,e.y,e.path.getFirst().x(),e.path.getFirst().y())){
                e.path=target.entity()!=null&&e.targeting!=null?
                    ScenePathing.region(e.x,e.y,(x,y)->Math.max(targetDistance(x,y,target)-attackRange,sight(e,x,y,target.x(),target.y())?0:.01f),(x,y,nx,ny)->entitySegment(e,x,y,nx,ny)):
                    ScenePathing.route(e.x,e.y,tx,ty,(x,y,nx,ny)->entitySegment(e,x,y,nx,ny));e.pathX=tx;e.pathY=ty;
                e.nextPath=stamp+Math.max(50,e.spec.integer("acquisition-interval",0));
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
    private boolean beginAttack(Entity e,int slot,int stamp){
        if(slot<0||slot>255||!e.status.state().modifiers().canAttack()||e.status.state().modifiers().attackSpeed()<=0)return false;
        float curse=e.status.used(0);if(curse>0){hurt(e,curse,stamp);if(e.health<=0)return false;}
        // Activity changes reset fire receipts; a chained activity owns its own fire table and speed.
        e.attackSpeed=e.status.state().modifiers().attackSpeed();e.activity=4+slot;e.activityStarted=stamp;e.fired.clear();e.flags&=~64;return true;
    }
    private boolean sight(Entity e,float tx,float ty){
        return sight(e,e.x,e.y,tx,ty);
    }
    private boolean sight(Entity e,float x,float y,float tx,float ty){
        if(!staticCollision.clearSegment(129,POINT,x,y,tx,ty,0))return false;
        int steps=Math.max(1,(int)Math.ceil(Math.hypot(tx-x,ty-y)/.1));
        for(var obstacle:entities.values())if(obstacle!=e&&obstacle.destroyed==Integer.MAX_VALUE&&obstacle.carrier==0
                &&(obstacle.collisionFlags&129)!=0&&!(obstacle.kind.equals("Door")&&(obstacle.flags&6)==2))
            for(int i=0;i<=steps;i++)if(obstacle.spec.contains(obstacle.x,obstacle.y,obstacle.rotation,x+(tx-x)*i/steps,y+(ty-y)*i/steps,0))return false;
        return true;
    }
    private boolean entitySegment(Entity mover,float x,float y,float tx,float ty){
        int mask=mover.spec.integer("collision-mask",pawnCollisionMask);
        if(staticCollision.isEmpty()||mover.shape==null){if(!terrain.walkableSegment(x,y,tx,ty))return false;}
        else if(!staticCollision.floorSupported(tx,ty)||!staticCollision.clearSegment(mask,mover.shape,x,y,tx,ty,mover.rotation))return false;
        int steps=Math.max(1,(int)Math.ceil(Math.hypot(tx-x,ty-y)/.1));
        for(var obstacle:entities.values())if(obstacle!=mover&&obstacle.destroyed==Integer.MAX_VALUE&&obstacle.carrier==0
                &&(obstacle.collisionFlags&mask)!=0&&!(obstacle.kind.equals("Door")&&(obstacle.flags&6)==2))
            for(int i=1;i<=steps;i++){
                float px=x+(tx-x)*i/steps,py=y+(ty-y)*i/steps;
                if(mover.shape!=null&&obstacle.shape!=null?SceneCollision.overlaps(mover.shape,px,py,mover.rotation,obstacle.shape,obstacle.x,obstacle.y,obstacle.rotation,0):obstacle.containsPawn(px,py))return false;
            }
        return true;
    }
    /** Actor-specific masks and shapes, including subjective monster barriers. */
    private boolean moveEntity(Entity mover,float x,float y){
        int mask=mover.spec.integer("collision-mask",pawnCollisionMask);
        int steps=Math.max(1,(int)Math.ceil(Math.hypot(x-mover.x,y-mover.y)/.1));
        float dx=(x-mover.x)/steps,dy=(y-mover.y)/steps;boolean moved=false;
        for(int i=0;i<steps;i++){
            float nx=mover.x+dx,ny=mover.y+dy;
            boolean clear=staticCollision.isEmpty()||mover.shape==null?terrain.walkableSegment(mover.x,mover.y,nx,ny):
                staticCollision.floorSupported(nx,ny)&&staticCollision.clearSegment(mask,mover.shape,mover.x,mover.y,nx,ny,mover.rotation);
            if(!clear)break;
            for(var obstacle:entities.values()){
                if(obstacle==mover||obstacle.destroyed!=Integer.MAX_VALUE||obstacle.carrier!=0
                        ||obstacle.kind.equals("Door")&&(obstacle.flags&6)==2||(obstacle.collisionFlags&mask)==0)continue;
                if(mover.shape!=null&&obstacle.shape!=null
                        ?SceneCollision.overlaps(mover.shape,nx,ny,mover.rotation,obstacle.shape,obstacle.x,obstacle.y,obstacle.rotation,0)
                        :obstacle.containsPawn(nx,ny)){clear=false;break;}
            }
            if(clear&&(mask&Math.round(content.stats().getOrDefault("player.collision-flags",2f)))!=0){
                for(var player:players)if(playerActive.test(player.id())){
                    float radius=mover.spec.number("radius",0)+ScenePortals.PAWN_RADIUS;
                    // Permit separation if an earlier pose already overlapped a knight.
                    if(Math.hypot(nx-player.x(),ny-player.y())<radius
                            &&Math.hypot(nx-player.x(),ny-player.y())<Math.hypot(mover.x-player.x(),mover.y-player.y())){clear=false;break;}
                }
            }
            if(!clear)break;mover.x=nx;mover.y=ny;moved=true;
        }
        return moved;
    }
    private void cycle(Entity e,int stamp){
        var cycle=content.cycles().getOrDefault(e.cycle,e.spec);
        float down=cycle.number("down",e.spec.number("down-time",0)),warn=cycle.number("warn",e.spec.number("warn-time",0)),up=cycle.number("up",e.spec.number("up-time",0));
        int period=Math.round((down+warn+up)*1000);
        int offset=period<=0?0:Math.floorMod(stamp-Math.round(cycle.number("offset",0)*1000),period);
        int state=e.cycle.equals("UP")?2:e.cycle.equals("DOWN")?0:offset<down*1000?0:offset<(down+warn)*1000?1:2;
        if(e.state!=state){e.state=state;e.stateEntered=stamp;}
        e.flags=state==2?e.flags|2:e.flags&~2;
    }
    private void switches(Entity e,int stamp,List<ActorState> players){
        String mode=e.spec.text("switch-mode","");
        if(mode.equals("pressure")){
            boolean pressed=players.stream().anyMatch(p->e.spec.contains(e.x,e.y,e.rotation,p.x(),p.y(),ScenePortals.PAWN_RADIUS))
                ||entities.values().stream().anyMatch(other->other.kind.equals("DungeonLift")&&Arrays.asList(other.spec.text("tags","").split(",")).contains(e.spec.text("pressure-tag",""))&&other.carrier==0&&other.destroyed==Integer.MAX_VALUE
                    &&e.spec.contains(e.x,e.y,e.rotation,other.x,other.y,other.spec.number("radius",0)));
            int state=pressed?2:0;
            if(state!=e.state)setSwitch(e,state,stamp);
        }else if(mode.equals("button-once")){
            if(e.state==0&&players.stream().anyMatch(p->e.spec.contains(e.x,e.y,e.rotation,p.x(),p.y(),ScenePortals.PAWN_RADIUS)))setSwitch(e,1,stamp);
        }else if(mode.equals("button")||mode.equals("damage-timed")){
            if(e.state!=0&&stamp>=e.nextSwitch)setSwitch(e,0,stamp);
            if(mode.equals("button")&&e.state==0&&stamp>=e.nextSwitch&&players.stream().anyMatch(p->e.spec.contains(e.x,e.y,e.rotation,p.x(),p.y(),ScenePortals.PAWN_RADIUS))){
                setSwitch(e,1,stamp);e.nextSwitch=stamp+e.spec.integer("switch-delay",0);
            }
        }
    }
    private void setSwitch(Entity e,int state,int stamp){
        e.state=state;e.stateEntered=stamp;
        String effect=e.spec.text("state."+state+".effect","");if(!effect.isBlank())fireEffect(e,new StarterCatalog.Model(effect,Map.of()),stamp);
        var condition=e.spec.model("state."+state+".status");
        if(condition!=null){
            String tag=e.spec.text("state."+state+".status-tag","");float radius=e.spec.number("state."+state+".status-radius",0);
            var damage=new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$Status",Map.of("statusCondition",condition));
            for(var target:entities.values())if(target.kind.equals("Monster")&&target.destroyed==Integer.MAX_VALUE&&(tag.isBlank()||Arrays.asList(target.spec.text("tags","").split(",")).contains(tag))&&Math.hypot(target.x-e.x,target.y-e.y)<=radius+target.spec.number("radius",0))target.status.apply(damage,stamp);
        }
        signal(e.spec.text("state."+state+".tag",""),e.spec.text("state."+state+".signal",""),stamp,"actor:"+e.id);
    }
    private void fireEffect(Entity e,StarterCatalog.Model effect,int stamp){
        attack.accept(e.snapshot(),new FireCatalog.Action(FireCatalog.Kind.FIRE_EFFECT,effect,null,"Source","","",0,0,0,0,false,0,0,0,0,false,List.of(),""));
    }
    private ActorState scriptSource(String source,int stamp){
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
    private boolean setMemory(org.tortodrone.character.KnightMemory operation,int stamp,String source,String activator){
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
    private List<ActorState> taggedSources(String tag,int stamp){
        var result=new ArrayList<ActorState>();
        for(var binding:anchors.getOrDefault(tag,List.of()))result.add(entrySource(binding,stamp));
        // Native Tagged maps retain a destroyed actor until its actual removal.
        for(var entity:entities.values())if(Arrays.asList(entity.spec.text("tags","").split(",")).contains(tag))result.add(entity.snapshot());
        return result;
    }
    private ActorState entrySource(SceneContent.Entry binding,int stamp){
        var state=new SceneEntityState("Actor",Integer.MAX_VALUE,0,0,-1,0,stamp,0,0,binding.key(),null,SceneStatus.State.NORMAL,0,0,true);
        return new ActorState(0,0,"",0,stamp,binding.x(),binding.y(),binding.rotation(),0,0,0,stamp,binding.spec().config(),false,null,null,state);
    }
    boolean conversationResponse(SceneConversation definition,String node,int choice,ActorState source,int responder,int stamp){
        if(content.conversations().get(definition.actor())!=definition||source==null)return false;
        var current=definition.nodes().get(node);if(current==null||choice<0||choice>=current.responses().size())return false;
        String identity;
        if(source.entity()!=null&&source.entity().entrySource())identity="entry:"+source.entity().key();else identity="actor:"+source.id();
        if(scriptSource(identity,stamp)==null)return false;
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
        if(origins.isEmpty()||origins.size()>256||entities.size()+origins.size()>4096)return false;
        var spec=new SceneContent.Spec(actor.fields());
        var staged=new ArrayList<Entity>();int next=nextId;
        for(var origin:origins){
            var binding=new SceneContent.Entry("spawn:"+next,origin.x(),origin.y(),origin.rotation(),spec);
            var entity=new Entity(next++,stamp,binding,spec,null,content);entity.scriptSource=source;
            if(actor.activate()&&spec.integer("activate-time",0)>0)entity.activity=3;
            staged.add(entity);
        }
        // Validate the whole selection before publication; a failed batch changes no actor IDs.
        for(var entity:staged)entities.put(entity.id,entity);nextId=next;return true;
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
        for(var target:entities.values())if(Arrays.asList(target.spec.text("tags","").split(",")).contains(tag)){
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
    public SceneCollision.Penetration penetration(float x,float y,int ignore){
        boolean carrying=ignore!=0&&entities.values().stream().anyMatch(e->e.kind.equals("DungeonLift")
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
        {
            float c=(float)Math.cos(rotation),s=(float)Math.sin(rotation);
            for(var player:players){float dx=player.x()-x,dy=player.y()-y;
                boolean hit=defined instanceof FireCatalog.DataValue?SceneShape.intersects(defined,dx*c+dy*s,-dx*s+dy*c,ScenePortals.PAWN_RADIUS+expansion):Math.hypot(dx,dy)<=ScenePortals.PAWN_RADIUS+expansion;
                int sourceId=source==null?owner:source.kind.equals("Monster")?owner:(source.kind.equals("DungeonLift")||source.kind.equals("Bomb"))?source.owner:0;
                if(hit&&damageClear(region,x,y,player.x(),player.y(),owner,player.id())&&(hitOnce==null||hitOnce.add(player.id()))){
                    // One trap action can cover several occupants. Record each
                    // hit here so the next sensor cannot damage them again on
                    // the same tick or before the authored refractory deadline.
                    if(source!=null&&source.kind.equals("Trap")){
                        if(stamp<source.hazardHits.getOrDefault(player.id(),0))continue;
                        source.hazardHits.put(player.id(),stamp+source.spec.integer("hazard-refractory",0));
                    }
                    playerDamage.accept(new PlayerHit(player.id(),damage,stamp,sourceId,multiplier));
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
                if(mode.startsWith("damage-")&&stamp>=e.nextSwitch&&SceneDamage.amount(switchDamage,content,e.spec,multiplier)>0){
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
                    int dealt=(int)Math.ceil(amount);hurt(e,dealt,stamp);
                    if(e.targeting!=null&&targetCandidates(e,players).stream().anyMatch(p->p.id()==owner&&targetActive(p))&&(e.victims.containsKey(owner)||e.victims.size()<256)){
                        var previous=e.victims.getOrDefault(owner,new Entity.Threat(0,0));
                        e.victims.put(owner,new Entity.Threat(Math.min(1_000_000,previous.hits()+1),Math.min(1_000_000_000f,previous.damage()+dealt)));e.nextAcquire=stamp;
                    }
                    applyImpulse(e,region,x,y,rotation,stamp);
                    if(e.health==0)e.status.clear();
                    int sourceId=source!=null&&(source.kind.equals("DungeonLift")||source.kind.equals("Bomb"))?source.owner:owner;
                    float fraction=dealt/e.spec.number("health",1);
                    feedback.accept(new SceneCombatEvent(0,stamp,e.id,dealt,sourceId,fraction>.4f?"NORMAL_LARGE":fraction>.2f?"NORMAL_MEDIUM":"NORMAL_SMALL"));
                }
            }else if(amount>0&&(e.kind.startsWith("Block")||e.spec.text("damage-action","").equals("destroy")))blockHit(e,stamp);
        }
    }
    private boolean damageClear(StarterCatalog.Model region,float x,float y,float tx,float ty,int owner,int target){
        int mask=region.arguments().get("collision-mask") instanceof Number n?n.intValue():0;
        if(mask==0)return true;
        if(!staticCollision.clearSegment(mask,POINT,x,y,tx,ty,0))return false;
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
        var region=new FireCatalog.DataValue("com.threerings.tudey.config.RegionConfig$Default",Map.of("shape",
            new FireCatalog.DataValue("com.threerings.tudey.shape.config.ShapeConfig$Circle",Map.of("radius",2f))));
        var impulse=new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.ImpulseConfig$DamageImpulse",Map.of("level",13,"duration",400,
            "translation",new FireCatalog.DataValue("com.threerings.math.Vector2f",Map.of("x",5f,"y",0f))));
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
        if((flags&Math.round(content.stats().getOrDefault("player.damage-mask",13f)))!=0)for(var player:players)if(player.id()!=owner&&playerActive.test(player.id())){
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
    private void applyImpulse(Entity entity,StarterCatalog.Model region,float x,float y,float rotation,int stamp){
        var impulse=createImpulse(region,x,y,rotation,entity.x,entity.y,entity.spec.integer("impulse-level",1),stamp);
        if(impulse!=null){entity.impulse=SceneDamage.combine(impulse,entity.impulse);entity.impulseAdvanced=stamp;}
    }
    private void advanceImpulse(Entity e,int stamp){
        if(e.impulse==null)return;float portion=e.impulse.fraction(stamp)-e.impulse.fraction(e.impulseAdvanced);e.impulseAdvanced=stamp;
        float dx=e.impulse.x()*portion,dy=e.impulse.y()*portion;int steps=Math.max(1,(int)Math.ceil(Math.hypot(dx,dy)/.1));
        for(int i=0;i<steps;i++){if(!moveEntity(e,e.x+dx/steps,e.y+dy/steps))break;}
        if(stamp>=e.impulse.started()+e.impulse.duration())e.impulse=null;
    }
    private void blockHit(Entity e,int stamp){
        e.hitTime=stamp;
        switch(e.spec.text("kind","")){
            case "TimedBlock" -> {if(e.state==0){e.state=1;e.stateEntered=stamp;e.detonateAt=stamp+e.spec.integer("block-fuse",0);}}
            case "StarlinkBlock" -> clearLinks(e,stamp,false);
            case "GhostlinkBlock" -> clearLinks(e,stamp,true);
            case "StoneBlock" -> {if(e.state<e.spec.integer("block-hits",1)-1){e.state++;e.stateEntered=stamp;}else e.destroyed=stamp;}
            default -> {if(e.spec.text("damage-action","").equals("destroy"))e.destroyed=stamp;}
        }
    }
    private void clearLinks(Entity source,int stamp,boolean ghost){
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
    private void hurt(Entity e,float amount,int stamp){
        if(e.health<=0)return;
        e.health=Math.max(0,e.health-amount);e.hitTime=e.affectedTime=stamp;e.affectedType=0;
        if(e.health==0){
            e.activity=2;e.activityStarted=stamp;e.destroyed=stamp+e.spec.integer("death-time",100);e.flags=0;
            String death=e.spec.text("death-action","");
            if(!death.isBlank())try{attack.accept(e.snapshot(),FireCatalog.actionJson(death));}
            catch(java.io.IOException invalid){throw new IllegalArgumentException("Invalid prepared monster death action",invalid);}
        }
        else if(amount>=e.spec.number("min-react-percent",0)*e.spec.number("health",0)){e.activity=1;e.activityStarted=stamp;}
    }
}
