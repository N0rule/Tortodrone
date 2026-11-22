package org.tortodrone.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.core.world.FireCatalog;
import org.tortodrone.core.world.KnightCombat;
import org.tortodrone.core.world.SceneClock;
import org.tortodrone.core.world.SceneCombatEvent;
import org.tortodrone.core.world.SceneCollision;
import org.tortodrone.core.world.SceneContent;
import org.tortodrone.core.world.SceneDamage;
import org.tortodrone.core.world.ScenePortals;
import org.tortodrone.core.world.SceneTargeting;

/** Monster targeting, attack scheduling and pursuit for one scene instance. Runs under
 *  the scene monitor and reads live entities through SceneEntities; it never publishes
 *  or persists, only invokes the scene's combat callbacks. */
final class CombatSystem {
    private final SceneEntities scene;
    private int engagedAtTickStart;

    CombatSystem(SceneEntities scene) { this.scene=scene; }

    void beginTick(int engaged){engagedAtTickStart=engaged;}

    List<ActorState> targetCandidates(SceneEntity e,List<ActorState> occupants){
        if(e.targeting==null||e.targeting.monsterMask()==0)return occupants;
        var candidates=new ArrayList<ActorState>();
        if(e.targeting.includePlayers()&&(Math.round(scene.content().stats().getOrDefault("player.damage-mask",SceneContent.DEFAULT_PLAYER_DAMAGE_MASK))&e.targeting.monsterMask())!=0)candidates.addAll(occupants);
        candidates.addAll(scene.entityValues().stream().filter(v->v!=e&&v.kind.equals("Monster")&&v.health>0&&v.destroyed==Integer.MAX_VALUE
            &&v.status.state().modifiers().canBeTargeted()&&(v.spec.integer("damage-mask",0)&e.targeting.monsterMask())!=0).map(SceneEntity::snapshot).toList());return List.copyOf(candidates);
    }
    boolean targetActive(ActorState actor){return actor.entity()!=null?scene.hasEntity(actor.id()):scene.activePlayer(actor.id())&&scene.targetablePlayer(actor.id());}
    private boolean acquireIntersects(SceneEntity e,ActorState target){
        var victim=target.entity()==null?null:scene.entity(target.id());
        if(victim!=null&&e.shape!=null&&victim.shape!=null)return SceneCollision.overlaps(e.shape,e.x,e.y,e.rotation,victim.shape,victim.x,victim.y,victim.rotation,e.targeting.expansion());
        return e.spec.contains(e.x,e.y,e.rotation,target.x(),target.y(),e.targeting.expansion()+(victim==null?ScenePortals.PAWN_RADIUS:victim.spec.number("radius",0)));
    }
    private float targetDistance(SceneEntity source,ActorState target){
        return targetDistance(source.x,source.y,target);
    }
    private float targetDistance(float x,float y,ActorState target){
        if(target.entity()==null)return Math.max(0,(float)Math.hypot(target.x()-x,target.y()-y)-ScenePortals.PAWN_RADIUS);
        var victim=scene.entity(target.id());if(victim==null)return Float.POSITIVE_INFINITY;
        var body=victim.pawnShape==null?SceneCollision.pawnShape(victim.spec,victim.shape):victim.pawnShape;
        return body.at(victim.x,victim.y,victim.rotation).distance(x,y);
    }
    /** Activation ladder: cheap distance decisions gate the full pursuit/attack tick. */
    void think(SceneEntity e,int stamp,float elapsed,List<ActorState> occupants){
        // Recent damage overrides the leash briefly: a hit monster answers before walking home.
        if(e.returning){if(SceneClock.before(stamp,e.engagedUntil))e.returning=false;else{scene.returnHome(e,stamp,elapsed);return;}}
        // An authored attack sequence, including chains, must finish even if the target leaves.
        if(e.activity>=4){e.band=SceneMonsterBand.ENGAGED;e.nextThink=stamp;move(e,stamp,elapsed,occupants);return;}
        float nearest=Float.POSITIVE_INFINITY;
        for(var player:occupants)if(scene.activePlayer(player.id())&&scene.targetablePlayer(player.id()))
            nearest=Math.min(nearest,(float)Math.hypot(player.x()-e.x,player.y()-e.y));
        float radius=e.activationRadius;
        boolean forced=SceneClock.before(stamp,e.engagedUntil);
        if(e.leashRadius>0&&!forced&&e.band!=SceneMonsterBand.DORMANT
                &&Math.hypot(e.x-e.homeX,e.y-e.homeY)>e.leashRadius){
            e.returning=true;e.activity=0;e.fired.clear();e.path=List.of();
            scene.returnHome(e,stamp,elapsed);return;
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
                    &&engagedAtTickStart>=SceneEntities.MAX_ENGAGED_MONSTERS)
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
        if(e.band==SceneMonsterBand.ALERT&&engagedAtTickStart>=SceneEntities.MAX_ENGAGED_MONSTERS)return;
        move(e,stamp,elapsed,occupants);
        if(e.target!=0){
            if(e.band!=SceneMonsterBand.ENGAGED&&!forced&&engagedAtTickStart>=SceneEntities.MAX_ENGAGED_MONSTERS){
                e.target=0;e.targetMode=null;e.flags&=~18;return;
            }
            if(e.band!=SceneMonsterBand.ENGAGED)engagedAtTickStart++;
            e.band=SceneMonsterBand.ENGAGED;e.nextThink=stamp;
        }
    }
    void move(SceneEntity e,int stamp,float elapsed,List<ActorState> occupants){
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
            for(var player:players)if(scene.activePlayer(player.id())&&scene.targetablePlayer(player.id())){float d=(float)Math.hypot(player.x()-e.x,player.y()-e.y);if(d<nearest){nearest=d;e.target=player.id();}}
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
                e.targetMode=e.targeting.choose(nearest,scene.random());e.targetModeUpdate=false;
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
                    e.impulse=SceneDamage.combine(impulse,e.impulse);e.impulseAdvanced=e.activityStarted+motion.delay();scene.advanceImpulse(e,stamp);
                }
                e.flags|=64;
            }
            for(String fire:e.spec.text(prefix+"fires","").split(",")){
                if(fire.isBlank()||e.fired.contains(fire))continue;
                String fp=prefix+"fire."+fire+".";
                int due=e.activityStarted+Math.round((e.spec.integer(prefix+"land",0)+e.spec.integer(fp+"delay",0)/e.spec.number(prefix+"fire-speed",1))/e.attackSpeed);
                if(SceneClock.reached(stamp,due)){
                    var sceneAction=e.sceneFireActions.get(fp+"scene-action");
                    if(sceneAction!=null)scene.actions().perform(sceneAction,stamp,"actor:"+e.id,"actor:"+e.id);
                    else try{scene.fireAttack(e.snapshot(),FireCatalog.actionJson(e.spec.text(fp+"action","")));}
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
        if(target==null&&e.follow!=null){scene.follow(e,stamp,elapsed,occupants);return;}
        if(target==null&&e.wander!=null){scene.wander(e,stamp,elapsed);return;}
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
        if(!scene.entitySegment(e,e.x,e.y,tx,ty)){
            if(e.path.isEmpty()&&SceneClock.before(stamp,e.nextPath))return;
            if(e.path.isEmpty()||Math.hypot(tx-e.pathX,ty-e.pathY)>=1
                    ||!scene.entitySegment(e,e.x,e.y,e.path.getFirst().x(),e.path.getFirst().y())){
                if(!scene.pathBudget().acquire(e.id))return;
                e.path=target.entity()!=null&&e.targeting!=null?
                    ScenePathing.region(e.x,e.y,(x,y)->Math.max(targetDistance(x,y,target)-attackRange,sight(e,x,y,target.x(),target.y())?0:.01f),(x,y,nx,ny)->scene.planSegment(e,x,y,nx,ny)):
                    ScenePathing.route(e.x,e.y,tx,ty,(x,y,nx,ny)->scene.planSegment(e,x,y,nx,ny));e.pathX=tx;e.pathY=ty;
                scene.monsterMetrics().searched(e.path.isEmpty());
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
        if(scene.moveEntity(e,x,y))e.flags|=2;
    }
    boolean beginAttack(SceneEntity e,int slot,int stamp){
        if(slot<0||slot>255||!e.status.state().modifiers().canAttack()||e.status.state().modifiers().attackSpeed()<=0)return false;
        float curse=e.status.used(0);if(curse>0){scene.hurtForCombat(e,curse,stamp,false,false);if(e.health<=0)return false;}
        // Activity changes reset fire receipts; a chained activity owns its own fire table and speed.
        e.attackSpeed=e.status.state().modifiers().attackSpeed();e.activity=4+slot;e.activityStarted=stamp;e.fired.clear();e.flags&=~64;return true;
    }
    /** Candidate entities whose indexed extent can touch the box, in insertion order.
     * The linear map is retained behind {@code spatialQuery=false} for the differential
     * harness and one-release rollback. */
    private List<SceneEntity> nearby(float minX,float minY,float maxX,float maxY){
        if(!scene.spatialQuery()||!Float.isFinite(minX)||!Float.isFinite(minY)||!Float.isFinite(maxX)||!Float.isFinite(maxY)
                ||Math.abs(minX)>1e8||Math.abs(minY)>1e8||Math.abs(maxX)>1e8||Math.abs(maxY)>1e8)
            return List.copyOf(scene.entityValues());
        return scene.spatialNear(minX,minY,maxX,maxY);
    }
    boolean sight(SceneEntity e,float tx,float ty){
        return sight(e,e.x,e.y,tx,ty);
    }
    private boolean sight(SceneEntity e,float x,float y,float tx,float ty){
        boolean current=x==e.x&&y==e.y;
        if(current&&e.sightStamp!=Integer.MIN_VALUE&&scene.lastStamp()>=e.sightStamp
                &&scene.lastStamp()-e.sightStamp<=SceneEntities.SIGHT_CACHE_MILLIS
                &&Math.hypot(tx-e.sightX,ty-e.sightY)<=SceneEntities.SIGHT_CACHE_DISTANCE)
            return e.sightResult;
        boolean result=true;
        if(!scene.staticCollision().clearSegment(129,SceneEntities.POINT,x,y,tx,ty,0,scene.disabledCollisionSources()))result=false;
        else {
            int steps=Math.max(1,(int)Math.ceil(Math.hypot(tx-x,ty-y)/.1));
            for(var obstacle:nearby(Math.min(x,tx),Math.min(y,ty),Math.max(x,tx),Math.max(y,ty)))
                if(obstacle!=e&&obstacle.destroyed==Integer.MAX_VALUE&&obstacle.carrier==0
                    &&(obstacle.collisionFlags&129)!=0&&!(obstacle.kind.equals("Door")&&(obstacle.flags&6)==2))
                for(int i=0;i<=steps;i++)if(obstacle.spec.contains(obstacle.x,obstacle.y,obstacle.rotation,x+(tx-x)*i/steps,y+(ty-y)*i/steps,0)){result=false;break;}
        }
        if(current){e.sightStamp=scene.lastStamp();e.sightX=tx;e.sightY=ty;e.sightResult=result;}
        return result;
    }
}
