package org.tortodrone.world;
import org.tortodrone.core.world.SceneClock;

import java.util.*;
import org.tortodrone.core.WireTypes;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.world.FireCatalog;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.core.world.GearStats;
import org.tortodrone.core.world.KnightCombat;
import org.tortodrone.core.world.SceneEffect;
import org.tortodrone.core.world.SceneCollision;
import org.tortodrone.core.world.SceneDamage;
import org.tortodrone.core.world.CollisionMap;
import org.tortodrone.world.DiskgunPolicy.PiercePolicy;

/** Scene-owned stock fire actions, with contact and damage resolved by instance entities. */
public final class SceneWeapons {
    public interface Targets {
        boolean contact(float x,float y,int owner);
        default int contactActor(float x,float y,float rotation,int owner,Object shape,int mask){return 0;}
        default ActorState actor(int id){return null;}
        default boolean attachable(int id){return false;}
        default boolean contact(float x,float y,float rotation,int owner,Object shape,int mask){return contact(x,y,owner);}
        void damage(org.tortodrone.core.character.StarterCatalog.Model region,float x,float y,float rotation,int stamp,int owner);
        default void damage(org.tortodrone.core.character.StarterCatalog.Model region,float x,float y,float rotation,int stamp,int owner,Object sourceShape){damage(region,x,y,rotation,stamp,owner);}
        default void sweep(org.tortodrone.core.character.StarterCatalog.Model region,float x,float y,float rotation,int stamp,int owner,Set<Integer> hit){damage(region,x,y,rotation,stamp,owner);}
        default void sweep(org.tortodrone.core.character.StarterCatalog.Model region,float x,float y,float rotation,int stamp,int owner,Object shape,Set<Integer> hit){sweep(region,x,y,rotation,stamp,owner,hit);}
        default void impulse(org.tortodrone.core.character.StarterCatalog.Model region,float x,float y,float rotation,int stamp,int owner){ }
        default boolean monsters(Object region,float x,float y,float rotation){return false;}
        default boolean spawnPickup(org.tortodrone.core.character.StarterCatalog.Model specification,float x,float y,float rotation,int stamp){return false;}
        default Object sceneActorSource(int source){return null;}
        default boolean spawnSceneActor(String template,float x,float y,float rotation,int stamp,Object source){return false;}
    }
    public record PetAction(FireCatalog.Kind kind,org.tortodrone.core.character.StarterCatalog.Model config,int owner,int timestamp) { }
    private java.util.function.Consumer<PetAction> petActions=ignored->{};
    public void petActions(java.util.function.Consumer<PetAction> sink){petActions=Objects.requireNonNull(sink);}
    private Targets targets;
    public void targets(Targets value){targets=value;shots.targets(value);}
    private static final int MAX_ACTORS=512, MAX_EFFECTS=1024, MAX_RUNS=256;
    private static final class Run {
        final KnightCombat.AttackStart start;final FireCatalog.AttackFire attack;final int[] loops;final boolean[] done;final GearStats.Damage damage;final PiercePolicy pierce;
        final Set<Integer> hit=new HashSet<>();
        Run(KnightCombat.AttackStart start,FireCatalog.AttackFire attack,GearStats.Damage damage,PiercePolicy pierce){this.start=start;this.attack=attack;this.damage=damage;this.pierce=pierce;loops=new int[attack.fireRefs().size()];done=new boolean[loops.length];}
    }
    private final CollisionMap terrain;
    private SceneCollision staticCollision=SceneCollision.empty();
    private java.util.Set<Integer> disabledSources=java.util.Set.of();
    public void staticCollision(SceneCollision value){staticCollision=Objects.requireNonNull(value);}
    public void disabledSources(java.util.Set<Integer> value){disabledSources=java.util.Set.copyOf(value);}
    private boolean clear(FireCatalog.ActorSpec spec,float fromX,float fromY,float toX,float toY,float rotation){
        return staticCollision.isEmpty()||spec.collisionShape()==null?terrain.walkableSegment(fromX,fromY,toX,toY):staticCollision.clearSegment(spec.collisionMask(),spec.collisionShape(),fromX,fromY,toX,toY,rotation,disabledSources);
    }
    private final Map<Integer,List<Run>> runs=new HashMap<>();
    private final SceneShots shots;
    private final ArrayDeque<SceneEffect> effects=new ArrayDeque<>();
    private FireCatalog catalog=FireCatalog.empty();
    private int lastStamp;
    private final Map<Integer,IdentityHashMap<Object,Integer>> cooldowns=new HashMap<>();
    private long sequence;
    private record Delayed(int stamp,FireCatalog.Action action,Pose source,int seed,int depth) { }
    private final PriorityQueue<Delayed> delayed=new PriorityQueue<>(Comparator.comparingInt(Delayed::stamp));
    public SceneWeapons(CollisionMap terrain){
        this.terrain=Objects.requireNonNull(terrain);
        shots=new SceneShots(new SceneShots.Host(){
            @Override public boolean execute(FireCatalog.Action action,Pose source,int stamp,int depth){return SceneWeapons.this.execute(action,source,stamp,depth);}
            @Override public boolean clear(FireCatalog.ActorSpec spec,float fromX,float fromY,float toX,float toY,float rotation){return SceneWeapons.this.clear(spec,fromX,fromY,toX,toY,rotation);}
        });
    }
    public void catalog(FireCatalog catalog){this.catalog=Objects.requireNonNull(catalog);shots.catalog(this.catalog);}
    private java.util.function.BiFunction<Integer,KnightCombat.AttackStart,GearStats.Damage> damageModifiers=(owner,attack)->GearStats.Damage.NONE;
    public void damageModifiers(java.util.function.BiFunction<Integer,KnightCombat.AttackStart,GearStats.Damage> source){damageModifiers=Objects.requireNonNull(source);}
    public void attack(int owner,KnightCombat.AttackStart start){
        var attack=catalog.find(DiskgunPolicy.resolvedFire(start.config()),start.activity());
        if(attack==null)attack=catalog.find(start.config(),start.activity());
        attack(owner,start,attack,damageModifiers.apply(owner,start),DiskgunPolicy.policy(start.config(),start.activity()));
    }
    public void attack(int owner,KnightCombat.AttackStart start,FireCatalog.AttackFire attack){
        attack(owner,start,attack,GearStats.Damage.NONE,PiercePolicy.NONE);
    }
    private void attack(int owner,KnightCombat.AttackStart start,FireCatalog.AttackFire attack,GearStats.Damage damage,PiercePolicy pierce){
        if(attack==null)return;
        var list=runs.computeIfAbsent(owner,k->new ArrayList<>());
        if(list.size()<MAX_RUNS)list.add(new Run(start,attack,damage,pierce));
    }
    public void cancel(int owner){runs.remove(owner);cooldowns.remove(owner);shots.removeIfOwner(owner);delayed.removeIf(d->d.source().ownerId==owner);}
    public void cancelAttacks(int owner){runs.remove(owner);}
    public boolean fire(FireCatalog.Action action,ActorState source,int stamp){return execute(action,new Pose(source.x(),source.y(),source.rotation(),source.id(),0,null,source.id(),GearStats.Damage.NONE,targets==null?null:targets.sceneActorSource(source.id())),stamp,0);}
    /** Refuse an unusable consumable before animation or consumption, including nested actions. */
    public static boolean usable(FireCatalog.Action action){
        return org.tortodrone.core.world.ItemUseRules.usable(action);
    }
    /** Ceiling on purely visual effect retention before garbage collection. */
    private static final int EFFECT_TTL_MILLIS = 5000;
    public void effect(org.tortodrone.core.character.StarterCatalog.Model config,ActorState source,int stamp){
        if(config==null)return;
        // These server-fired effects must reach the controlling client too;
        // _clientOid denotes a pre-executed effect that client already showed.
        Integer entry=source.entity()!=null&&source.entity().entrySource()?Integer.valueOf(source.entity().key()):null;
        var effect=new SceneEffect(sequence+1,config,stamp,source.x(),source.y(),source.rotation(),0,entry==null?source.id():0,entry);
        if(effects.size()>=MAX_EFFECTS)effects.removeFirst();sequence++;effects.addLast(effect);
    }
    public List<ActorState> actors(){return shots.actors();}
    public List<SceneEffect> effects(){return List.copyOf(effects);}
    public void advance(int stamp,List<ActorState> players){
        var owners=new HashMap<Integer,ActorState>();for(var player:players)owners.put(player.id(),player);
        shots.owners(owners);
        int delayedBudget=MAX_EFFECTS;
        while(!delayed.isEmpty()&&delayed.peek().stamp()<=stamp&&delayedBudget-->0){
            var next=delayed.remove();var shot=shots.get(next.source().actorId);
            // Delayed actor actions disappear with their source, as in ActionLogic.removed.
            if(shot!=null&&shot.destroyed==Integer.MAX_VALUE)execute(next.action(),shots.pose(shot),next.stamp(),next.depth(),next.seed());
            else if(next.source().actorId==next.source().ownerId&&owners.containsKey(next.source().ownerId))execute(next.action(),next.source(),next.stamp(),next.depth(),next.seed());
        }
        for(var iterator=runs.entrySet().iterator();iterator.hasNext();){var e=iterator.next();var actor=owners.get(e.getKey());if(actor==null){iterator.remove();continue;}
            e.getValue().removeIf(run->{
                // Shield actions execute on the server; the client does not prefire
                // their guarded effects. A prediction owner would hide the bump VFX.
                var pose=new Pose(actor.x(),actor.y(),actor.rotation(),actor.pet()==null?actor.id():actor.pet().knight(),
                    run.start.activity()==KnightCombat.DEFENSE?0:actor.clientOid(),null,actor.id(),run.damage,null,run.pierce);
                int cutoff=run.start.started()+run.start.clear();
                if(actor.activityStarted()>run.start.started())cutoff=Math.min(cutoff,actor.activityStarted());
                for(int i=0;i<run.loops.length;i++){
                    if(run.done[i])continue;var ref=run.attack.fireRefs().get(i);
                    // Stock attack animations and server fire actions use the same
                    // equipment multiplier, captured when the attack begins.
                    float fireSpeed=run.attack.fireSpeed()*run.start.attackSpeed();
                    int delay=(int)(ref.delay()/fireSpeed),refractory=(int)(ref.refractory()/fireSpeed);
                    int elapsed=stamp-run.start.started()-run.start.land();
                    if(elapsed<=delay+refractory*run.loops[i])continue;
                    int fired=run.start.started()+run.start.land()+delay+refractory*run.loops[i];
                    // A later activity can cancel an attack before its land time. Events
                    // already due before that transition still execute on this scene tick.
                    if(fired>=cutoff){run.done[i]=true;continue;}
                    if(ref.action().kind()==FireCatalog.Kind.SWEEP_DAMAGE){
                        float c=(float)Math.cos(pose.rotation),s=(float)Math.sin(pose.rotation);
                        if(SceneClock.before(stamp,cutoff)&&targets!=null)targets.sweep(pose.region(ref.action()),pose.x+ref.action().x()*c-ref.action().y()*s,pose.y+ref.action().x()*s+ref.action().y()*c,pose.rotation,stamp,pose.ownerId,run.hit);
                    }else execute(ref.action(),pose,ref.advance()?Math.max(fired,lastStamp):fired,0,fired);
                    run.loops[i]++;
                    if(refractory<=0)run.done[i]=true;
                }
                boolean finished=true;for(boolean done:run.done)finished&=done;
                return finished||stamp-run.start.started()>=run.start.clear()||actor.activityStarted()>run.start.started();
            });if(e.getValue().isEmpty())iterator.remove();
        }
        shots.advance(stamp);
        effects.removeIf(effect->stamp-effect.timestamp()>EFFECT_TTL_MILLIS);lastStamp=stamp;
    }
    private boolean execute(FireCatalog.Action action,Pose source,int stamp,int depth){
        return execute(action,source,stamp,depth,stamp);
    }
    private boolean execute(FireCatalog.Action action,Pose source,int stamp,int depth,int seed){
        if(action==null||depth>96)return false;
        if(action.kind()==FireCatalog.Kind.DELAYED){
            int delay=((Number)action.config().arguments().get("delay")).intValue(),variance=((Number)action.config().arguments().get("variance")).intValue();
            if(delay<0||variance<0||delay>600000||variance>600000||action.children().size()!=1)return false;
            if(variance>0)delay=Math.max(0,delay+java.util.concurrent.ThreadLocalRandom.current().nextInt(variance*2)-variance);
            if(delay==0)return execute(action.children().getFirst(),source,stamp,depth+1,seed);
            if(delayed.size()>=MAX_EFFECTS)return false;
            delayed.add(new Delayed(stamp+delay,action.children().getFirst(),source,seed,depth+1));return true;
        }
        if(action.kind()==FireCatalog.Kind.CONDITIONAL){
            Boolean result=condition(action.config().arguments().get("condition"),source,stamp,0);
            return result!=null&&action.children().size()==2&&execute(action.children().get(result?0:1),source,stamp,depth+1,seed);
        }
        if(action.kind()==FireCatalog.Kind.RANDOM_EQUAL){return action.children().isEmpty()||execute(action.children().get(java.util.concurrent.ThreadLocalRandom.current().nextInt(action.children().size())),source,stamp,depth+1,seed);}
        if(action.kind()==FireCatalog.Kind.RANDOM_WEIGHTED){
            Object weights=action.config()==null?null:action.config().arguments().get("weights");
            if(!(weights instanceof FireCatalog.ListValue list)||list.values().size()!=action.children().size())return false;
            double total=0;for(Object raw:list.values()){
                if(!(raw instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()<0)return false;total+=n.doubleValue();
            }
            if(total<=0||!Double.isFinite(total))return false;
            double pick=java.util.concurrent.ThreadLocalRandom.current().nextDouble(total);
            for(int i=0;i<list.values().size();i++){pick-=((Number)list.values().get(i)).doubleValue();if(pick<0)return execute(action.children().get(i),source,stamp,depth+1,seed);}
            return false;
        }
        if(action.kind()==FireCatalog.Kind.MULTI_PRESPAWNED&&source.pierce.singleProjectile())
            return action.children().isEmpty()||execute(action.children().getFirst(),source,stamp,depth+1,seed);
        if(action.kind()==FireCatalog.Kind.COMPOUND||action.kind()==FireCatalog.Kind.MULTI_PRESPAWNED){boolean result=true;int index=0;
            for(var child:action.children()){boolean success=execute(child,source,stamp,depth+1,seed+(action.kind()==FireCatalog.Kind.MULTI_PRESPAWNED?index++:0));result&=success;if(!success&&action.stopOnFailure())break;}return result;}
        if(action.kind()==FireCatalog.Kind.FAIL)return false;
        if(action.kind()==FireCatalog.Kind.HEAL||action.kind()==FireCatalog.Kind.CLEAR_STATUS||action.kind()==FireCatalog.Kind.APPLY_STATUS||action.kind()==FireCatalog.Kind.START_SPRITE_SHIELD||action.kind()==FireCatalog.Kind.END_SPRITE_SHIELD){
            if(!sourceTarget(action.targetType()))return false;
            petActions.accept(new PetAction(action.kind(),action.config(),source.ownerId,stamp));return true;}
        if(!sourceTarget(action.targetType()))return false;
        if(action.kind()==FireCatalog.Kind.SPAWN_SCENE_ACTOR)
            return targets!=null&&targets.spawnSceneActor((String)action.config().arguments().get("template"),source.x,source.y,source.rotation,stamp,source.sceneSource);
        if(action.kind()==FireCatalog.Kind.SPAWN_PICKUP){
            float c=(float)Math.cos(source.rotation),s=(float)Math.sin(source.rotation);
            float rotation=(action.relative()?source.rotation:0)+action.rotation();
            if(action.rotationVariance()!=0)rotation+=(float)(Math.random()-.5)*action.rotationVariance();
            return targets!=null&&targets.spawnPickup(action.config(),source.x+action.x()*c-action.y()*s,source.y+action.x()*s+action.y()*c,rotation,stamp);
        }
        if(action.kind()==FireCatalog.Kind.DESTROY_SOURCE)return shots.destroySource(source.actorId,stamp);
        if(action.kind()==FireCatalog.Kind.DAMAGE_REGION){
            if(targets!=null){float c=(float)Math.cos(source.rotation),s=(float)Math.sin(source.rotation);
                targets.damage(source.region(action),source.x+action.x()*c-action.y()*s,source.y+action.x()*s+action.y()*c,source.rotation,stamp,source.ownerId,source.shape);}
            return true;
        }
        if(action.kind()==FireCatalog.Kind.SWEEP_DAMAGE){
            var shot=shots.get(source.actorId);if(shot==null||targets==null)return false;
            float c=(float)Math.cos(source.rotation),s=(float)Math.sin(source.rotation);
            targets.sweep(source.region(action),source.x+action.x()*c-action.y()*s,source.y+action.x()*s+action.y()*c,source.rotation,stamp,source.ownerId,source.shape,shots.hits(shot,action));
            return true;
        }
        if(action.kind()==FireCatalog.Kind.IMPULSE_REGION){if(targets!=null)targets.impulse(action.config(),source.x,source.y,source.rotation,stamp,source.ownerId);return true;}
        if(action.kind()==FireCatalog.Kind.FIRE_EFFECT){if(action.config()==null)return true;if(effects.size()>=MAX_EFFECTS)effects.removeFirst();effects.addLast(new SceneEffect(++sequence,action.config(),stamp,source.x,source.y,source.rotation,source.ownerOid,source.actorId));return true;}
        if(action.kind()==FireCatalog.Kind.UNSUPPORTED&&nonTargetAction(action.unsupportedType()))return true;
        if(action.kind()!=FireCatalog.Kind.EMIT_PROJECTILE)return false;
        if(!action.facingType().isEmpty())return false; // Targeted shots require actual resolved targets.
        int count=action.count()>0?action.count():1;boolean result=false;var locations=new HashSet<Long>();
        for(int n=0;n<count&&shots.size()<MAX_ACTORS;n++){
            float rotation=(action.relative()?source.rotation:0)+action.rotation();
            if(action.rotationVariance()!=0)rotation+=(float)(Math.random()-.5)*action.rotationVariance();
            float c=(float)Math.cos(source.rotation),s=(float)Math.sin(source.rotation);
            float x=source.x+action.x()*c-action.y()*s,y=source.y+action.x()*s+action.y()*c;
            if(action.count()>0){boolean placed=false;for(int attempt=0;attempt<3;attempt++){
                int dx=java.util.concurrent.ThreadLocalRandom.current().nextInt(-action.steps(),action.steps()+1),dy=java.util.concurrent.ThreadLocalRandom.current().nextInt(-action.steps(),action.steps()+1);
                long key=((long)dx<<32)^(dy&0xffffffffL);float px=x+dx*action.stepSize(),py=y+dy*action.stepSize();
                if(locations.add(key)&&(action.collisionMask()==0||terrain.walkable(px,py))){x=px;y=py;placed=true;break;}}
                if(!placed)continue;}
            var spec=action.actor();
            // Stock preExecute uses the authored fire timestamp (incremented for
            // MultiSpawnPrespawned), also selecting Pollinator's pollen colour.
            if(!action.parameter().isEmpty()&&!action.values().isEmpty()){
                var arguments=new HashMap<>(spec.config().arguments());arguments.put(action.parameter(),action.values().get(Math.floorMod(seed,action.values().size())));
                var config=new org.tortodrone.core.character.StarterCatalog.Model(spec.config().name(),arguments);
                spec=new FireCatalog.ActorSpec(spec.nativeType(),config,spec.fuse(),spec.speed(),spec.maxStep(),spec.flags(),spec.offsetX(),spec.offsetY(),spec.height(),spec.detonation(),spec.orbit(),spec.collisionMask(),spec.collisionShape(),spec.timers(),spec.shutdown(),spec.startup(),spec.attachment(),spec.activation(),spec.activatesAttachments());
            }
            if(source.pierce.forwardOnly()&&spec.orbit()!=null)spec=DiskgunPolicy.forwardOnly(spec);
            float rc=(float)Math.cos(rotation),rs=(float)Math.sin(rotation);
            float ox=spec.offsetX()*rc-spec.offsetY()*rs,oy=spec.offsetX()*rs+spec.offsetY()*rc;
            var controller=action.config()!=null&&action.config().name().equals("ServerSpawned")?new Pose(source.x,source.y,source.rotation,source.ownerId,0,source.shape,source.actorId,source.damage,source.sceneSource,source.pierce):source;
            var shot=shots.create(spec,controller,stamp,x,y,rotation,source.pierce);
            shots.add(shot);
            if(spec.startup()!=null)execute(spec.startup(),shots.pose(shot),stamp,depth+1,seed);
            // Actor-born projectiles use the native offset directly. Sweeping
            // their muzzle from the parent impact would collide with the same
            // target and exhaust every ricochet before a shard could travel.
            boolean actorBorn=shots.contains(source.actorId);
            // A muzzle offset is movement too. Sweeping it prevents a bullet from
            // starting beyond a monster standing between the knight and the muzzle.
            int muzzleSteps=Math.max(1,(int)Math.ceil(Math.hypot(ox,oy)/.05f));
            for(int step=1;step<=muzzleSteps;step++){
                float px=x+ox*step/muzzleSteps,py=y+oy*step/muzzleSteps;
                if(!clear(spec,shot.x,shot.y,px,py,rotation)){shots.destroy(shot,stamp,false);break;}
                if(!actorBorn&&spec.nativeType().equals("BULLET")&&spec.collisionMask()!=0&&targets!=null&&targets.contact(px,py,rotation,source.ownerId,spec.collisionShape(),spec.collisionMask())){shots.impact(shot,px,py,stamp);break;}
                shot.x=px;shot.y=py;
            }
            result=true;
        }return result;
    }
    private static boolean nonTargetAction(String type){return type.endsWith("$DamageRegionFromPoint")||type.endsWith("$ApplyStatus")||type.endsWith("$ImpartImpulse");}
    private Boolean condition(Object raw,Pose source,int stamp,int depth){
        if(depth>16||!(raw instanceof FireCatalog.DataValue value))return null;
        String type=value.type().substring(value.type().lastIndexOf('$')+1);
        if(type.equals("Always"))return true;if(type.equals("Never"))return false;
        if(type.equals("Random"))return Math.random()<SceneDamage.number(raw,"probability",1);
        if(type.equals("All")||type.equals("Any")){
            Object children=SceneDamage.field(raw,"conditions");if(!(children instanceof FireCatalog.ListValue list))return null;
            for(Object child:list.values()){Boolean result=condition(child,source,stamp,depth+1);if(result==null)return null;if(type.equals("All")&&!result)return false;if(type.equals("Any")&&result)return true;}
            return type.equals("All");
        }
        if(type.equals("Not")){Boolean result=condition(SceneDamage.field(raw,"condition"),source,stamp,depth+1);return result==null?null:!result;}
        if(type.equals("FlagSet")){
            Boolean set=shots.flag(source.actorId,(String)SceneDamage.field(raw,"flagName"));
            return set==null?null:set==!Boolean.FALSE.equals(SceneDamage.field(raw,"set"));
        }
        if(type.equals("Cooldown")){
            var deadlines=cooldowns.computeIfAbsent(source.ownerId,id->new IdentityHashMap<>());
            if(stamp<deadlines.getOrDefault(raw,Integer.MIN_VALUE))return false;
            if(deadlines.size()>=256&&!deadlines.containsKey(raw))return false;
            deadlines.put(raw,stamp+Math.max(0,(int)SceneDamage.number(raw,"time",0)));return true;
        }
        if(type.equals("ShieldRarityEquipped")){
            var owner=shots.owner(source.ownerId);var combat=owner==null?null:owner.combat();
            var defense=combat==null||combat.shield()==null?null:catalog.find(combat.shield(),KnightCombat.DEFENSE);
            Object rarity=defense==null?null:defense.attack().arguments().get("rarity");
            return rarity instanceof Number n&&n.intValue()>=SceneDamage.number(raw,"rarity",0);
        }
        if(type.equals("Intersecting")){
            Object second=SceneDamage.field(raw,"second"),location=SceneDamage.field(second,"location");
            if(!Objects.equals(SceneDamage.field(location,"logicClass"),"com.threerings.projectx.dungeon.server.logic.MonsterLogic"))return null;
            return targets!=null&&targets.monsters(SceneDamage.field(raw,"first"),source.x,source.y,source.rotation);
        }
        return null;
    }
    private static boolean sourceTarget(String type){return type.isEmpty()||type.equals("Source")||type.equals("Activator");}
}
