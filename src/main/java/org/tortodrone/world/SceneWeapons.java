package org.tortodrone.world;

import java.util.*;

/** Scene-owned stock fire actions. No damage targets are synthesized in town interiors. */
public final class SceneWeapons {
    private static final int MAX_ACTORS=512, MAX_EFFECTS=1024, MAX_RUNS=256;
    private record Pose(float x,float y,float rotation,int ownerId,int ownerOid) { }
    private static final class Run {
        final KnightCombat.AttackStart start;final FireCatalog.AttackFire attack;final int[] loops;final boolean[] done;
        Run(KnightCombat.AttackStart start,FireCatalog.AttackFire attack){this.start=start;this.attack=attack;loops=new int[attack.fireRefs().size()];done=new boolean[loops.length];}
    }
    private static final class Shot {
        final int id;final FireCatalog.ActorSpec spec;final Pose source;final int created;final float originX,originY,rotation;
        float x,y;int destroyed=Integer.MAX_VALUE;boolean fizzle;
        Shot(int id,FireCatalog.ActorSpec spec,Pose pose,int created,float x,float y,float rotation){this.id=id;this.spec=spec;source=pose;this.created=created;originX=this.x=x;originY=this.y=y;this.rotation=rotation;}
        ActorState snapshot(){var p=new ProjectileState(spec.nativeType(),spec.config(),source.ownerOid,created,destroyed,originX,originY,spec.height(),spec.speed(),spec.maxStep(),source.ownerId,fizzle);
            return new ActorState(id,source.ownerOid,"",0,created,x,y,rotation,rotation,spec.flags()|(spec.nativeType().equals("BULLET")?2:0)|(fizzle?4:0),0,created,spec.config(),false,null,p);}
    }
    private final CollisionMap terrain;
    private final Map<Integer,List<Run>> runs=new HashMap<>();
    private final Map<Integer,Shot> shots=new LinkedHashMap<>();
    private final ArrayDeque<SceneEffect> effects=new ArrayDeque<>();
    private FireCatalog catalog=FireCatalog.empty();
    private int nextId=1_500_000_000,lastStamp;
    private long sequence;
    public SceneWeapons(CollisionMap terrain){this.terrain=Objects.requireNonNull(terrain);}
    public void catalog(FireCatalog catalog){this.catalog=Objects.requireNonNull(catalog);}
    public void attack(int owner,KnightCombat.AttackStart start){
        var attack=catalog.find(start.config(),start.activity());if(attack==null)return;
        var list=runs.computeIfAbsent(owner,k->new ArrayList<>());
        if(list.size()<MAX_RUNS)list.add(new Run(start,attack));
    }
    public void cancel(int owner){runs.remove(owner);shots.values().removeIf(s->s.source.ownerId==owner);}
    public List<ActorState> actors(){return shots.values().stream().map(Shot::snapshot).toList();}
    public List<SceneEffect> effects(){return List.copyOf(effects);}
    public void advance(int stamp,List<ActorState> players){
        var owners=new HashMap<Integer,ActorState>();for(var player:players)owners.put(player.id(),player);
        for(var iterator=runs.entrySet().iterator();iterator.hasNext();){var e=iterator.next();var actor=owners.get(e.getKey());if(actor==null){iterator.remove();continue;}
            var pose=new Pose(actor.x(),actor.y(),actor.rotation(),actor.id(),actor.clientOid());
            e.getValue().removeIf(run->{
                for(int i=0;i<run.loops.length;i++){
                    if(run.done[i])continue;var ref=run.attack.fireRefs().get(i);
                    int delay=(int)(ref.delay()/run.attack.fireSpeed()),refractory=(int)(ref.refractory()/run.attack.fireSpeed());
                    int elapsed=stamp-run.start.started()-run.start.land();
                    if(elapsed<=delay+refractory*run.loops[i])continue;
                    int fired=run.start.started()+run.start.land()+delay+refractory*run.loops[i];
                    execute(ref.action(),pose,ref.advance()?Math.max(fired,lastStamp):fired,0);
                    run.loops[i]++;
                    if(refractory<=0)run.done[i]=true;
                }
                boolean finished=true;for(boolean done:run.done)finished&=done;
                return finished||stamp-run.start.started()>=run.start.clear()||actor.activityStarted()>run.start.started();
            });if(e.getValue().isEmpty())iterator.remove();
        }
        int previous=lastStamp==0?stamp:lastStamp;
        for(var iterator=shots.values().iterator();iterator.hasNext();){var shot=iterator.next();
            if(shot.destroyed!=Integer.MAX_VALUE){if(stamp-shot.destroyed>=100)iterator.remove();continue;}
            int end=Math.min(stamp,shot.created+shot.spec.fuse());
            if(shot.spec.nativeType().equals("BULLET")){
                int from=Math.max(previous,shot.created);float distance=shot.spec.speed()*Math.max(0,end-from)/1000f;
                int steps=Math.max(1,(int)Math.ceil(Math.abs(distance)/Math.max(.05f,shot.spec.maxStep())));
                for(int n=0;n<steps;n++){float x=shot.x+(float)Math.cos(shot.rotation)*distance/steps,y=shot.y+(float)Math.sin(shot.rotation)*distance/steps;
                    if(!terrain.walkable(x,y)){destroy(shot,stamp,false);break;}shot.x=x;shot.y=y;}
            }
            if(shot.destroyed==Integer.MAX_VALUE&&stamp-shot.created>=shot.spec.fuse())destroy(shot,shot.created+shot.spec.fuse(),shot.spec.nativeType().equals("BULLET"));
        }
        effects.removeIf(effect->stamp-effect.timestamp()>5000);lastStamp=stamp;
    }
    private void destroy(Shot shot,int stamp,boolean fizzle){shot.destroyed=stamp;shot.fizzle=fizzle;
        if(!fizzle&&shot.spec.detonation()!=null)execute(shot.spec.detonation(),new Pose(shot.x,shot.y,shot.rotation,shot.source.ownerId,shot.source.ownerOid),stamp,0);}
    private boolean execute(FireCatalog.Action action,Pose source,int stamp,int depth){
        if(action==null||depth>32)return false;
        if(action.kind()==FireCatalog.Kind.COMPOUND){boolean result=true;for(var child:action.children()){boolean success=execute(child,source,stamp,depth+1);result&=success;if(!success&&action.stopOnFailure())break;}return result;}
        if(!sourceTarget(action.targetType()))return false;
        if(action.kind()==FireCatalog.Kind.FIRE_EFFECT){if(effects.size()>=MAX_EFFECTS)effects.removeFirst();effects.addLast(new SceneEffect(++sequence,action.config(),stamp,source.x,source.y,source.rotation,source.ownerOid));return true;}
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
            var spec=action.actor();float rc=(float)Math.cos(rotation),rs=(float)Math.sin(rotation);
            float ox=spec.offsetX()*rc-spec.offsetY()*rs,oy=spec.offsetX()*rs+spec.offsetY()*rc;
            if(nextId==Integer.MAX_VALUE)nextId=1_500_000_000;
            while(shots.containsKey(nextId))nextId++;
            var shot=new Shot(nextId++,spec,source,stamp,x,y,rotation);
            if(terrain.walkable(x+ox,y+oy)){shot.x+=ox;shot.y+=oy;}
            shots.put(shot.id,shot);result=true;
        }return result;
    }
    private static boolean nonTargetAction(String type){return type.endsWith("$DamageRegionFromPoint")||type.endsWith("$ApplyStatus")||type.endsWith("$ImpartImpulse");}
    private static boolean sourceTarget(String type){return type.isEmpty()||type.equals("Source")||type.equals("Activator");}
}
