package org.tortodrone.world;

import java.util.*;

/** Scene-owned stock fire actions, with contact and damage resolved by instance entities. */
public final class SceneWeapons {
    public interface Targets {
        boolean contact(float x,float y,int owner);
        default boolean contact(float x,float y,float rotation,int owner,Object shape,int mask){return contact(x,y,owner);}
        void damage(org.tortodrone.character.StarterCatalog.Model region,float x,float y,float rotation,int stamp,int owner);
        default void damage(org.tortodrone.character.StarterCatalog.Model region,float x,float y,float rotation,int stamp,int owner,Object sourceShape){damage(region,x,y,rotation,stamp,owner);}
        default void sweep(org.tortodrone.character.StarterCatalog.Model region,float x,float y,float rotation,int stamp,int owner,Set<Integer> hit){damage(region,x,y,rotation,stamp,owner);}
        default void impulse(org.tortodrone.character.StarterCatalog.Model region,float x,float y,float rotation,int stamp,int owner){ }
        default boolean monsters(Object region,float x,float y,float rotation){return false;}
    }
    public record PetAction(FireCatalog.Kind kind,org.tortodrone.character.StarterCatalog.Model config,int owner,int timestamp) { }
    private java.util.function.Consumer<PetAction> petActions=ignored->{};
    public void petActions(java.util.function.Consumer<PetAction> sink){petActions=Objects.requireNonNull(sink);}
    private Targets targets;
    public void targets(Targets value){targets=value;}
    private static final int MAX_ACTORS=512, MAX_EFFECTS=1024, MAX_RUNS=256;
    private record Pose(float x,float y,float rotation,int ownerId,int ownerOid,Object shape,int actorId) {
        Pose(float x,float y,float rotation,int ownerId,int ownerOid,Object shape){this(x,y,rotation,ownerId,ownerOid,shape,ownerId);}
        Pose(float x,float y,float rotation,int ownerId,int ownerOid){this(x,y,rotation,ownerId,ownerOid,null);}
    }
    private static final class Run {
        final KnightCombat.AttackStart start;final FireCatalog.AttackFire attack;final int[] loops;final boolean[] done;
        final Set<Integer> hit=new HashSet<>();
        Run(KnightCombat.AttackStart start,FireCatalog.AttackFire attack){this.start=start;this.attack=attack;loops=new int[attack.fireRefs().size()];done=new boolean[loops.length];}
    }
    private static final class Shot {
        final int id;final FireCatalog.ActorSpec spec;final Pose source;final int created;final float originX,originY,rotation;
        final int[] timers;float x,y,relativeX,relativeY;int advanced;int destroyed=Integer.MAX_VALUE,collided;boolean fizzle,returning;
        Shot(int id,FireCatalog.ActorSpec spec,Pose pose,int created,float x,float y,float rotation){this.id=id;this.spec=spec;source=pose;this.created=created;originX=this.x=x;originY=this.y=y;this.rotation=rotation;advanced=created;timers=new int[spec.timers().size()];}
        ActorState snapshot(){var orbit=spec.orbit()==null?null:new ProjectileState.Orbit(spec.orbit().radius(),spec.nativeType().equals("ORBITAL")?source.ownerId:0,relativeX,relativeY,collided,rotation,source.ownerId);
            // Native _z is the source's floor elevation, not the authored bullet
            // height. Knights have no explicit Z; their client resolves the floor.
            var p=new ProjectileState(spec.nativeType(),spec.config(),source.ownerOid,created,destroyed,originX,originY,ProjectileState.UNSPECIFIED_Z,spec.speed(),spec.maxStep(),source.ownerId,fizzle,orbit);
            int flags=spec.flags()|(spec.nativeType().equals("BULLET")?2:0)|(fizzle?(orbit==null?4:8):0)|(returning?4:0);
            return new ActorState(id,source.ownerOid,"",0,created,x,y,rotation,rotation,flags,0,created,spec.config(),false,null,p);}
    }
    private final CollisionMap terrain;
    private SceneCollision staticCollision=SceneCollision.empty();
    public void staticCollision(SceneCollision value){staticCollision=Objects.requireNonNull(value);}
    private boolean clear(FireCatalog.ActorSpec spec,float fromX,float fromY,float toX,float toY,float rotation){
        return staticCollision.isEmpty()||spec.collisionShape()==null?terrain.walkableSegment(fromX,fromY,toX,toY):staticCollision.clearSegment(spec.collisionMask(),spec.collisionShape(),fromX,fromY,toX,toY,rotation);
    }
    private final Map<Integer,List<Run>> runs=new HashMap<>();
    private final Map<Integer,Shot> shots=new LinkedHashMap<>();
    private final ArrayDeque<SceneEffect> effects=new ArrayDeque<>();
    private FireCatalog catalog=FireCatalog.empty();
    private int nextId=1_500_000_000,lastStamp;
    private Map<Integer,ActorState> currentOwners=Map.of();
    private final Map<Integer,IdentityHashMap<Object,Integer>> cooldowns=new HashMap<>();
    private long sequence;
    public SceneWeapons(CollisionMap terrain){this.terrain=Objects.requireNonNull(terrain);}
    public void catalog(FireCatalog catalog){this.catalog=Objects.requireNonNull(catalog);}
    public void attack(int owner,KnightCombat.AttackStart start){
        var attack=catalog.find(start.config(),start.activity());attack(owner,start,attack);
    }
    public void attack(int owner,KnightCombat.AttackStart start,FireCatalog.AttackFire attack){
        if(attack==null)return;
        var list=runs.computeIfAbsent(owner,k->new ArrayList<>());
        if(list.size()<MAX_RUNS)list.add(new Run(start,attack));
    }
    public void cancel(int owner){runs.remove(owner);cooldowns.remove(owner);shots.values().removeIf(s->s.source.ownerId==owner);}
    public void cancelAttacks(int owner){runs.remove(owner);}
    public boolean fire(FireCatalog.Action action,ActorState source,int stamp){return execute(action,new Pose(source.x(),source.y(),source.rotation(),source.id(),source.clientOid()),stamp,0);}
    public void effect(org.tortodrone.character.StarterCatalog.Model config,ActorState source,int stamp){
        if(config==null)return;if(effects.size()>=MAX_EFFECTS)effects.removeFirst();
        effects.addLast(new SceneEffect(++sequence,config,stamp,source.x(),source.y(),source.rotation(),source.clientOid()));
    }
    public List<ActorState> actors(){return shots.values().stream().map(Shot::snapshot).toList();}
    public List<SceneEffect> effects(){return List.copyOf(effects);}
    public void advance(int stamp,List<ActorState> players){
        var owners=new HashMap<Integer,ActorState>();for(var player:players)owners.put(player.id(),player);
        currentOwners=owners;
        for(var iterator=runs.entrySet().iterator();iterator.hasNext();){var e=iterator.next();var actor=owners.get(e.getKey());if(actor==null){iterator.remove();continue;}
            var pose=new Pose(actor.x(),actor.y(),actor.rotation(),actor.pet()==null?actor.id():actor.pet().knight(),actor.clientOid(),null,actor.id());
            e.getValue().removeIf(run->{
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
                        if(stamp<cutoff&&targets!=null)targets.sweep(ref.action().config(),pose.x+ref.action().x()*c-ref.action().y()*s,pose.y+ref.action().x()*s+ref.action().y()*c,pose.rotation,stamp,pose.ownerId,run.hit);
                    }else execute(ref.action(),pose,ref.advance()?Math.max(fired,lastStamp):fired,0,fired);
                    run.loops[i]++;
                    if(refractory<=0)run.done[i]=true;
                }
                boolean finished=true;for(boolean done:run.done)finished&=done;
                return finished||stamp-run.start.started()>=run.start.clear()||actor.activityStarted()>run.start.started();
            });if(e.getValue().isEmpty())iterator.remove();
        }
        // Each actor owns its clock: children born during another actor's
        // detonation catch up next tick without losing their first movement step.
        for(var shot:List.copyOf(shots.values())){
            if(shot.destroyed!=Integer.MAX_VALUE){if(stamp-shot.destroyed>=100)shots.remove(shot.id);continue;}
            int end=Math.min(stamp,shot.created+shot.spec.fuse());
            while(shot.destroyed==Integer.MAX_VALUE){
                int selected=-1,due=Integer.MAX_VALUE;
                for(int i=0;i<shot.timers.length;i++){
                    var timer=shot.spec.timers().get(i);int fired=shot.timers[i];
                    if(fired>timer.limit()||fired==timer.limit()&&timer.endAction()==null)continue;
                    int deadline=shot.created+timer.delay()+timer.interval()*fired;
                    if(deadline<=end&&deadline<due){selected=i;due=deadline;}
                }
                if(selected<0)break;
                advanceShot(shot,due,owners.get(shot.source.ownerId));
                if(shot.destroyed!=Integer.MAX_VALUE)break;
                var timer=shot.spec.timers().get(selected);int fired=shot.timers[selected]++;
                execute(fired<timer.limit()?timer.action():timer.endAction(),shotPose(shot),due,0);
            }
            if(shot.destroyed==Integer.MAX_VALUE)advanceShot(shot,end,owners.get(shot.source.ownerId));
            if(shot.destroyed==Integer.MAX_VALUE&&stamp-shot.created>=shot.spec.fuse())destroy(shot,shot.created+shot.spec.fuse(),!shot.spec.nativeType().equals("BOMB"));
        }
        effects.removeIf(effect->stamp-effect.timestamp()>5000);lastStamp=stamp;
    }
    private Pose shotPose(Shot shot){Object shape=shot.spec.nativeType().equals("LASER")?new FireCatalog.DataValue("com.threerings.tudey.shape.config.ShapeConfig$Segment",Map.of("length",shot.spec.speed())):shot.spec.collisionShape();return new Pose(shot.x,shot.y,shot.rotation,shot.source.ownerId,0,shape,shot.id);}
    private void advanceShot(Shot shot,int end,ActorState home){
        if(end<=shot.advanced)return;
        if(shot.spec.nativeType().equals("ORBITAL")){
            if(home==null){destroy(shot,end,true);return;}var orbit=shot.spec.orbit();float angle=orbit.startingRotation()+spin(end-shot.created,orbit.angularSpeed(),false);
            shot.relativeX=orbit.radius()*(float)Math.cos(angle);shot.relativeY=orbit.radius()*(float)Math.sin(angle);shot.x=home.x()+shot.relativeX;shot.y=home.y()+shot.relativeY;shot.advanced=end;return;
        }
        if(shot.spec.nativeType().equals("BULLET")){
            int from=shot.advanced;float distance=shot.spec.speed()*(end-from)/1000f;
            int steps=Math.max(1,(int)Math.ceil(Math.abs(distance)/Math.max(.05f,shot.spec.maxStep())));
            for(int n=0;n<steps;n++){
                int time=from+(int)((long)(end-from)*(n+1)/steps);
                float x=shot.x+(float)Math.cos(shot.rotation)*distance/steps,y=shot.y+(float)Math.sin(shot.rotation)*distance/steps;
                if(!clear(shot.spec,shot.x,shot.y,x,y,shot.rotation)){destroy(shot,time,false);break;}
                shot.x=x;shot.y=y;
                if(targets!=null&&targets.contact(x,y,shot.rotation,shot.source.ownerId,shot.spec.collisionShape(),shot.spec.collisionMask())){destroy(shot,time,false);break;}
            }
        }else if(shot.spec.orbit()!=null)advanceOrbit(shot,end,shot.advanced,home);
        shot.advanced=end;
    }
    // Transcribed from the archive's 2026-compatible OrbitalBulletAdvancer:
    // fly the centre out, home to the owner, and compute the orbit at 1/60 s steps.
    private void advanceOrbit(Shot shot,int end,int from,ActorState home){
        var orbit=shot.spec.orbit();
        float remaining=Math.max(0,end-from)/1000f;
        while(remaining>0&&shot.destroyed==Integer.MAX_VALUE){
            float rest=Math.max(remaining-.016666668f,0),elapsed=remaining-rest;
            int next=end-(int)(rest*1000);remaining=rest;
            float cx,cy;
            if(next-shot.created<=orbit.outDuration()){
                float distance=orbit.flightSpeed()*(next-shot.created)/1000f;
                cx=shot.originX+(float)Math.cos(shot.rotation)*distance;cy=shot.originY+(float)Math.sin(shot.rotation)*distance;
            }else{
                shot.returning=true;cx=shot.x-shot.relativeX;cy=shot.y-shot.relativeY;
                if(home!=null){float dx=home.x()-cx,dy=home.y()-cy,distance=(float)Math.hypot(dx,dy),step=orbit.flightSpeed()*elapsed;
                    if(distance<=step){cx=home.x();cy=home.y();}else if(distance>0){cx+=dx*step/distance;cy+=dy*step/distance;}}
            }
            float angle=orbit.startingRotation()+shot.rotation+spin(next-shot.created,orbit.angularSpeed(),orbit.alternateSpin()&&(shot.created&1)==1);
            float rx=orbit.radius()*(float)Math.cos(angle),ry=orbit.radius()*(float)Math.sin(angle);
            if(orbit.compound()){float second=orbit.secondStartingRotation()+shot.rotation+spin(next-shot.created,orbit.secondSpeed(),orbit.alternateSpin()&&(shot.created&1)==1);
                rx+=orbit.secondOrbitRadius()*(float)Math.cos(second);ry+=orbit.secondOrbitRadius()*(float)Math.sin(second);}
            float x=cx+rx,y=cy+ry;
            if((shot.spec.flags()&2)==0&&!clear(shot.spec,shot.x,shot.y,x,y,shot.rotation)){shot.collided=next;destroy(shot,next,false);break;}
            shot.relativeX=rx;shot.relativeY=ry;shot.x=x;shot.y=y;from=next;
            if(targets!=null&&targets.contact(x,y,shot.rotation,shot.source.ownerId,shot.spec.collisionShape(),shot.spec.collisionMask())){shot.collided=next;destroy(shot,next,false);}
        }
    }
    private static float spin(int elapsed,float speed,boolean reverse){int rate=speed==0?0:(int)(1000/speed);return rate==0?0:(reverse?-1:1)*(float)(2*Math.PI)*(elapsed%rate)/rate;}
    private void destroy(Shot shot,int stamp,boolean fizzle){if(shot.destroyed!=Integer.MAX_VALUE)return;shot.destroyed=stamp;shot.fizzle=fizzle;
        if(shot.spec.shutdown()!=null)execute(shot.spec.shutdown(),shotPose(shot),stamp,0);
        var detonation=catalog.detonation(shot.spec);
        // Stock BulletLogic executes the detonation even when its advancer marked
        // fuse expiry as FIZZLE. Tortofist's invisible carrier relies on this to
        // release missiles; the flag only chooses the client's destruction visual.
        if(detonation!=null)execute(detonation,shotPose(shot),stamp,0);}
    private boolean execute(FireCatalog.Action action,Pose source,int stamp,int depth){
        return execute(action,source,stamp,depth,stamp);
    }
    private boolean execute(FireCatalog.Action action,Pose source,int stamp,int depth,int seed){
        if(action==null||depth>96)return false;
        if(action.kind()==FireCatalog.Kind.CONDITIONAL){
            Boolean result=condition(action.config().arguments().get("condition"),source,stamp,0);
            return result!=null&&action.children().size()==2&&execute(action.children().get(result?0:1),source,stamp,depth+1,seed);
        }
        if(action.kind()==FireCatalog.Kind.RANDOM_EQUAL){return action.children().isEmpty()||execute(action.children().get(java.util.concurrent.ThreadLocalRandom.current().nextInt(action.children().size())),source,stamp,depth+1,seed);}
        if(action.kind()==FireCatalog.Kind.COMPOUND||action.kind()==FireCatalog.Kind.MULTI_PRESPAWNED){boolean result=true;int index=0;
            for(var child:action.children()){boolean success=execute(child,source,stamp,depth+1,seed+(action.kind()==FireCatalog.Kind.MULTI_PRESPAWNED?index++:0));result&=success;if(!success&&action.stopOnFailure())break;}return result;}
        if(action.kind()==FireCatalog.Kind.FAIL)return false;
        if(action.kind()==FireCatalog.Kind.APPLY_STATUS||action.kind()==FireCatalog.Kind.START_SPRITE_SHIELD||action.kind()==FireCatalog.Kind.END_SPRITE_SHIELD){petActions.accept(new PetAction(action.kind(),action.config(),source.ownerId,stamp));return true;}
        if(!sourceTarget(action.targetType()))return false;
        if(action.kind()==FireCatalog.Kind.DESTROY_SOURCE){var shot=shots.get(source.actorId);if(shot==null)return false;destroy(shot,stamp,false);return true;}
        if(action.kind()==FireCatalog.Kind.DAMAGE_REGION){
            if(targets!=null){float c=(float)Math.cos(source.rotation),s=(float)Math.sin(source.rotation);
                targets.damage(action.config(),source.x+action.x()*c-action.y()*s,source.y+action.x()*s+action.y()*c,source.rotation,stamp,source.ownerId,source.shape);}
            return true;
        }
        if(action.kind()==FireCatalog.Kind.IMPULSE_REGION){if(targets!=null)targets.impulse(action.config(),source.x,source.y,source.rotation,stamp,source.ownerId);return true;}
        if(action.kind()==FireCatalog.Kind.FIRE_EFFECT){if(action.config()==null)return true;if(effects.size()>=MAX_EFFECTS)effects.removeFirst();effects.addLast(new SceneEffect(++sequence,action.config(),stamp,source.x,source.y,source.rotation,source.ownerOid));return true;}
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
                var config=new org.tortodrone.character.StarterCatalog.Model(spec.config().name(),arguments);
                spec=new FireCatalog.ActorSpec(spec.nativeType(),config,spec.fuse(),spec.speed(),spec.maxStep(),spec.flags(),spec.offsetX(),spec.offsetY(),spec.height(),spec.detonation(),spec.orbit(),spec.collisionMask(),spec.collisionShape(),spec.timers(),spec.shutdown());
            }
            float rc=(float)Math.cos(rotation),rs=(float)Math.sin(rotation);
            float ox=spec.offsetX()*rc-spec.offsetY()*rs,oy=spec.offsetX()*rs+spec.offsetY()*rc;
            if(nextId==Integer.MAX_VALUE)nextId=1_500_000_000;
            while(shots.containsKey(nextId))nextId++;
            var shot=new Shot(nextId++,spec,source,stamp,x,y,rotation);
            shots.put(shot.id,shot);
            // A muzzle offset is movement too. Sweeping it prevents a bullet from
            // starting beyond a monster standing between the knight and the muzzle.
            int muzzleSteps=Math.max(1,(int)Math.ceil(Math.hypot(ox,oy)/.05f));
            for(int step=1;step<=muzzleSteps;step++){
                float px=x+ox*step/muzzleSteps,py=y+oy*step/muzzleSteps;
                if(!clear(spec,shot.x,shot.y,px,py,rotation)){destroy(shot,stamp,false);break;}
                shot.x=px;shot.y=py;
                if(spec.nativeType().equals("BULLET")&&spec.collisionMask()!=0&&targets!=null&&targets.contact(px,py,rotation,source.ownerId,spec.collisionShape(),spec.collisionMask())){destroy(shot,stamp,false);break;}
            }
            result=true;
        }return result;
    }
    private static boolean nonTargetAction(String type){return type.endsWith("$DamageRegionFromPoint")||type.endsWith("$ApplyStatus")||type.endsWith("$ImpartImpulse");}
    private Boolean condition(Object raw,Pose source,int stamp,int depth){
        if(depth>16||!(raw instanceof FireCatalog.DataValue value))return null;
        String type=value.type().substring(value.type().lastIndexOf('$')+1);
        if(type.equals("Always"))return true;if(type.equals("Never"))return false;
        if(type.equals("All")||type.equals("Any")){
            Object children=SceneDamage.field(raw,"conditions");if(!(children instanceof FireCatalog.ListValue list))return null;
            for(Object child:list.values()){Boolean result=condition(child,source,stamp,depth+1);if(result==null)return null;if(type.equals("All")&&!result)return false;if(type.equals("Any")&&result)return true;}
            return type.equals("All");
        }
        if(type.equals("Not")){Boolean result=condition(SceneDamage.field(raw,"condition"),source,stamp,depth+1);return result==null?null:!result;}
        if(type.equals("FlagSet")){
            var shot=shots.get(source.actorId);if(shot==null)return null;
            Object flag=SceneDamage.field(raw,"flagName");
            Boolean set="FIZZLE".equals(flag)?shot.fizzle:"RETURNING".equals(flag)?shot.returning:null;
            return set==null?null:set==!Boolean.FALSE.equals(SceneDamage.field(raw,"set"));
        }
        if(type.equals("Cooldown")){
            var deadlines=cooldowns.computeIfAbsent(source.ownerId,id->new IdentityHashMap<>());
            if(stamp<deadlines.getOrDefault(raw,Integer.MIN_VALUE))return false;
            if(deadlines.size()>=256&&!deadlines.containsKey(raw))return false;
            deadlines.put(raw,stamp+Math.max(0,(int)SceneDamage.number(raw,"time",0)));return true;
        }
        if(type.equals("ShieldRarityEquipped")){
            var owner=currentOwners.get(source.ownerId);var combat=owner==null?null:owner.combat();
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
