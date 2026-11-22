package org.tortodrone.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.core.world.SceneClock;
import org.tortodrone.core.world.SceneCollision;
import org.tortodrone.core.world.ScenePortals;

/** Actor motion for one scene instance: collision-checked translation, authored
 *  wander/follow/script movement, leash return and impulse integration. Every call runs
 *  under the scene monitor; pathfinding is budgeted here and never spawns work. */
final class MovementSystem {
    private final SceneEntities scene;

    MovementSystem(SceneEntities scene) { this.scene=scene; }

    /** Actor-specific masks and shapes, including subjective monster barriers. */
    boolean moveEntity(SceneEntity mover,float x,float y){
        int mask=mover.spec.integer("collision-mask",scene.pawnCollisionMask());
        int steps=Math.max(1,(int)Math.ceil(Math.hypot(x-mover.x,y-mover.y)/.1));
        float dx=(x-mover.x)/steps,dy=(y-mover.y)/steps;boolean moved=false;
        float pad=mover.shape==null?ScenePortals.PAWN_RADIUS:mover.spatialExtent+ScenePortals.PAWN_RADIUS;
        var obstacles=scene.candidatesNear(Math.min(mover.x,x)-pad,Math.min(mover.y,y)-pad,Math.max(mover.x,x)+pad,Math.max(mover.y,y)+pad);
        for(int i=0;i<steps;i++){
            float nx=mover.x+dx,ny=mover.y+dy;
            boolean clear=scene.staticCollision().isEmpty()||mover.shape==null?scene.terrain().walkableSegment(mover.x,mover.y,nx,ny):
                scene.staticCollision().floorSupported(nx,ny)&&scene.staticCollision().clearSegment(mask,mover.shape,mover.x,mover.y,nx,ny,mover.rotation,scene.disabledCollisionSources());
            if(!clear)break;
            for(var obstacle:obstacles){
                if(obstacle==mover||obstacle.destroyed!=Integer.MAX_VALUE||obstacle.carrier!=0
                        ||obstacle.kind.equals("Door")&&(obstacle.flags&6)==2||(obstacle.collisionFlags&mask)==0)continue;
                if(mover.shape!=null&&obstacle.shape!=null
                        ?SceneCollision.overlaps(mover.shape,nx,ny,mover.rotation,obstacle.shape,obstacle.x,obstacle.y,obstacle.rotation,0)
                        :obstacle.containsPawn(nx,ny)){clear=false;break;}
            }
            if(clear&&(mask&Math.round(scene.content().stats().getOrDefault("player.collision-flags",2f)))!=0){
                for(var player:scene.occupants())if(scene.activePlayer(player.id())){
                    float radius=mover.spec.number("radius",0)+ScenePortals.PAWN_RADIUS;
                    // Permit separation if an earlier pose already overlapped a knight.
                    if(Math.hypot(nx-player.x(),ny-player.y())<radius
                            &&Math.hypot(nx-player.x(),ny-player.y())<Math.hypot(mover.x-player.x(),mover.y-player.y())){clear=false;break;}
                }
            }
            if(!clear)break;mover.x=nx;mover.y=ny;moved=true;
        }
        if(moved)scene.moved(mover);
        return moved;
    }

    private boolean entitySegment(SceneEntity mover,float x,float y,float tx,float ty){return entitySegment(mover,x,y,tx,ty,.1f);}
    /** Traversal sampling for path planning only; direct movement checks stay at 0.1. */
    private boolean entitySegment(SceneEntity mover,float x,float y,float tx,float ty,float sampleStep){
        int mask=mover.spec.integer("collision-mask",scene.pawnCollisionMask());
        if(scene.staticCollision().isEmpty()||mover.shape==null){if(!scene.terrain().walkableSegment(x,y,tx,ty))return false;}
        else if(!scene.staticCollision().floorSupported(tx,ty)||!scene.staticCollision().clearSegment(mask,mover.shape,x,y,tx,ty,mover.rotation,scene.disabledCollisionSources(),sampleStep))return false;
        int steps=Math.max(1,(int)Math.ceil(Math.hypot(tx-x,ty-y)/sampleStep));
        float pad=mover.shape==null?ScenePortals.PAWN_RADIUS:mover.spatialExtent+ScenePortals.PAWN_RADIUS;
        for(var obstacle:scene.candidatesNear(Math.min(x,tx)-pad,Math.min(y,ty)-pad,Math.max(x,tx)+pad,Math.max(y,ty)+pad))
            if(obstacle!=mover&&obstacle.destroyed==Integer.MAX_VALUE&&obstacle.carrier==0
                &&(obstacle.collisionFlags&mask)!=0&&!(obstacle.kind.equals("Door")&&(obstacle.flags&6)==2))
            for(int i=1;i<=steps;i++){
                float px=x+(tx-x)*i/steps,py=y+(ty-y)*i/steps;
                if(mover.shape!=null&&obstacle.shape!=null?SceneCollision.overlaps(mover.shape,px,py,mover.rotation,obstacle.shape,obstacle.x,obstacle.y,obstacle.rotation,0):obstacle.containsPawn(px,py))return false;
            }
        return true;
    }

    /** Native ScriptLogic.Move: the script walks unlimited total distance through bounded
     * partial paths, replanning whenever each path segment ends. maxPathLength bounds one
     * pathfinding request, never the distance to the goal. */
    private boolean scriptMovePath(SceneEntity e,String goal,float maxPath,float turnRate,int stamp,float elapsed){
        if(e.scriptGoal==null){
            if(goal.isEmpty())return true;
            e.scriptGoal=scene.taggedSources(goal,stamp).stream().min(Comparator.comparingDouble(a->Math.hypot(a.x()-e.x,a.y()-e.y))).orElse(null);
            if(e.scriptGoal==null)return true;
        }
        if(!e.status.state().modifiers().canMove())return false;
        float tx=e.scriptGoal.x(),ty=e.scriptGoal.y(),speed=e.speed*e.status.state().modifiers().speed();
        if(Math.hypot(tx-e.x,ty-e.y)<=speed*elapsed){
            moveEntity(e,tx,ty);
            if(Math.hypot(tx-e.x,ty-e.y)<.0001){e.path=List.of();return true;}
        }
        if(e.path.isEmpty()&&SceneClock.reached(stamp,e.nextPath)){
            if(!scene.pathBudget().acquire(e.id))return false;
            var path=ScenePathing.route(e.x,e.y,tx,ty,(x,y,nx,ny)->entitySegment(e,x,y,nx,ny,.5f));
            double length=0;float px=e.x,py=e.y;for(var point:path){length+=Math.hypot(point.x()-px,point.y()-py);px=point.x();py=point.y();}
            if(length<=maxPath)e.path=path;
            else{
                var prefix=new ArrayList<ScenePathing.Point>();float remaining=maxPath;px=e.x;py=e.y;
                for(var point:path){float distance=(float)Math.hypot(point.x()-px,point.y()-py);if(distance<=remaining){prefix.add(point);remaining-=distance;px=point.x();py=point.y();}
                    else{if(remaining>0)prefix.add(new ScenePathing.Point(px+(point.x()-px)*remaining/distance,py+(point.y()-py)*remaining/distance));break;}}
                e.path=List.copyOf(prefix);
            }e.nextPath=stamp+250;
        }
        while(!e.path.isEmpty()&&Math.hypot(e.path.getFirst().x()-e.x,e.path.getFirst().y()-e.y)<.01)e.path=e.path.subList(1,e.path.size());
        if(e.path.isEmpty())return false;var point=e.path.getFirst();float angle=(float)Math.atan2(point.y()-e.y,point.x()-e.x);
        if(!scriptRotate(e,angle,elapsed,turnRate))return false;
        float distance=(float)Math.hypot(point.x()-e.x,point.y()-e.y),step=Math.min(distance,speed*elapsed);
        if(moveEntity(e,e.x+(float)Math.cos(angle)*step,e.y+(float)Math.sin(angle)*step))e.flags|=2;
        else {e.path=List.of();e.nextPath=stamp+250;}return false;
    }
    static boolean scriptRotate(SceneEntity e,float target,float elapsed,float turnRate){
        if(!e.status.state().modifiers().canRotate())return false;
        float delta=(float)Math.IEEEremainder(target-e.rotation,2*Math.PI),step=turnRate*elapsed;
        if(Math.abs(delta)<.0001f){e.rotation=e.direction=target;return true;}
        e.rotation=e.direction=(float)Math.IEEEremainder(e.rotation+Math.copySign(Math.min(Math.abs(delta),step),delta),2*Math.PI);return false;
    }

    void scriptedMovement(SceneEntity e,int stamp,float elapsed){
        e.flags&=~2;if(e.scriptStep>=4)return;
        if(e.scriptStep==0){if(!e.spec.text("script.activate-signal","").isEmpty()&&!e.scriptActivated)return;e.scriptStep++;return;}
        if(e.scriptStarted==Integer.MIN_VALUE)e.scriptStarted=stamp;
        if(e.scriptStep==1){if(stamp-e.scriptStarted>e.script.waitMillis()){e.scriptStep++;e.scriptStarted=Integer.MIN_VALUE;}return;}
        if(e.scriptStep==2){if(scriptMovePath(e,e.script.goal(),e.script.maxPath(),e.script.turnRate(),stamp,elapsed)){e.scriptStep++;e.scriptStarted=Integer.MIN_VALUE;e.scriptGoal=null;}return;}
        if(scriptRotate(e,e.script.rotation(),elapsed,e.script.turnRate()))e.scriptStep++;
    }

    void wander(SceneEntity e,int stamp,float elapsed){
        if(e.activity!=0||!e.status.state().modifiers().canMove())return;
        boolean outside=Math.hypot(e.x-e.binding.x(),e.y-e.binding.y())>e.wander.radius();
        float inward=(float)Math.atan2(e.binding.y()-e.y,e.binding.x()-e.x);
        boolean away=Math.abs(Math.IEEEremainder(inward-e.rotation,2*Math.PI))>Math.PI/2;
        if(e.wanderPhase==0||e.wanderPhase==2&&(SceneClock.reached(stamp,e.wanderNext)||outside&&away)){
            // Wander/Base uses uniform [-pi,pi] direction changes and no pauses.
            // A full-circle uniform turn has the same law around any reflection base.
            e.wanderRotation=(float)(scene.random().nextDouble()*2*Math.PI-Math.PI);e.wanderPhase=1;e.flags&=~2;
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

    void follow(SceneEntity e,int stamp,float elapsed,List<ActorState> occupants){
        if(e.activity!=0)return;
        if(SceneClock.reached(stamp,e.nextFollow)){
            var target=occupants.stream().filter(p->scene.activePlayer(p.id())).min(Comparator.comparingDouble(p->Math.hypot(p.x()-e.x,p.y()-e.y))).orElse(null);
            e.followTarget=target==null?0:target.id();e.path=List.of();e.nextFollow=stamp+e.follow.evaluation();
            if(target!=null){
                float distance=(float)Math.hypot(target.x()-e.x,target.y()-e.y);
                if(distance<e.follow.minimum()||distance>e.follow.maximum()){
                    if(!scene.pathBudget().acquire(e.id))return;
                    var path=ScenePathing.route(e.x,e.y,target.x(),target.y(),(x,y,nx,ny)->entitySegment(e,x,y,nx,ny,.5f));
                    double length=0;float px=e.x,py=e.y;for(var point:path){length+=Math.hypot(point.x()-px,point.y()-py);px=point.x();py=point.y();}
                    if(length<=8)e.path=path;
                }
            }
        }
        var target=occupants.stream().filter(p->p.id()==e.followTarget&&scene.activePlayer(p.id())).findFirst().orElse(null);if(target==null){e.path=List.of();return;}
        while(!e.path.isEmpty()&&Math.hypot(e.path.getFirst().x()-e.x,e.path.getFirst().y()-e.y)<.05)e.path=e.path.subList(1,e.path.size());
        float tx=e.path.isEmpty()?target.x():e.path.getFirst().x(),ty=e.path.isEmpty()?target.y():e.path.getFirst().y();
        float angle=(float)Math.atan2(ty-e.y,tx-e.x),delta=(float)Math.IEEEremainder(angle-e.rotation,2*Math.PI),step=e.spec.number("turn-rate",0)*elapsed;
        if(e.status.state().modifiers().canRotate())e.rotation=e.direction=(float)Math.IEEEremainder(e.rotation+Math.copySign(Math.min(Math.abs(delta),step),delta),2*Math.PI);
        if(e.path.isEmpty()||!e.status.state().modifiers().canMove()||Math.abs(delta)>step+.0001f)return;
        float walk=Math.min((float)Math.hypot(tx-e.x,ty-e.y),e.speed*e.status.state().modifiers().speed()*elapsed);
        if(moveEntity(e,e.x+(float)Math.cos(angle)*walk,e.y+(float)Math.sin(angle)*walk))e.flags|=2;else{e.path=List.of();}
    }

    /** Leash return: drop combat intent, walk back to the placement anchor and heal on the way. */
    void returnHome(SceneEntity e,int stamp,float elapsed){
        e.band=SceneMonsterBand.ALERT;e.target=0;e.targetMode=null;e.targetModeUpdate=false;e.flags&=~18;
        float dx=e.homeX-e.x,dy=e.homeY-e.y,distance=(float)Math.hypot(dx,dy);
        if(distance<=.25f){e.returning=false;e.band=SceneMonsterBand.DORMANT;e.nextThink=stamp;e.path=List.of();e.flags&=~2;return;}
        if(e.leashHeal>0&&e.health>0&&e.health<e.maximumHealth)
            e.health=Math.min(e.maximumHealth,e.health+e.leashHeal*elapsed);
        if(!e.status.state().modifiers().canMove())return;
        float tx=e.homeX,ty=e.homeY;
        if(!entitySegment(e,e.x,e.y,tx,ty)){
            if(e.path.isEmpty()&&SceneClock.before(stamp,e.nextPath))return;
            if(e.path.isEmpty()||!entitySegment(e,e.x,e.y,e.path.getFirst().x(),e.path.getFirst().y())){
                if(!scene.pathBudget().acquire(e.id))return;
                e.path=ScenePathing.route(e.x,e.y,tx,ty,(x,y,nx,ny)->entitySegment(e,x,y,nx,ny,.5f));
                scene.monsterMetrics().searched(e.path.isEmpty());
                e.pathX=tx;e.pathY=ty;e.nextPath=stamp+Math.max(50,Math.max(e.spec.integer("acquisition-interval",0),e.archetype.repathMillis()));
            }
            while(!e.path.isEmpty()&&Math.hypot(e.path.getFirst().x()-e.x,e.path.getFirst().y()-e.y)<.05)e.path=e.path.subList(1,e.path.size());
            if(e.path.isEmpty())return;
            tx=e.path.getFirst().x();ty=e.path.getFirst().y();
        }else e.path=List.of();
        e.rotation=e.direction=(float)Math.atan2(ty-e.y,tx-e.x);
        float step=Math.min((float)Math.hypot(tx-e.x,ty-e.y),e.speed*e.status.state().modifiers().speed()*elapsed);
        if(moveEntity(e,e.x+(float)Math.cos(e.direction)*step,e.y+(float)Math.sin(e.direction)*step))e.flags|=2;
        else e.flags&=~2;
    }

    void advanceImpulse(SceneEntity e,int stamp){
        if(e.impulse==null)return;float portion=e.impulse.fraction(stamp)-e.impulse.fraction(e.impulseAdvanced);e.impulseAdvanced=stamp;
        float dx=e.impulse.x()*portion,dy=e.impulse.y()*portion;int steps=Math.max(1,(int)Math.ceil(Math.hypot(dx,dy)/.1));
        for(int i=0;i<steps;i++){if(!moveEntity(e,e.x+dx/steps,e.y+dy/steps))break;}
        if(SceneClock.reached(stamp,e.impulse.started()+e.impulse.duration()))e.impulse=null;
    }

    /** Scene-level chase/pursuit movement is combat-owned; it stays here so all
     * translation still shares one collision check. */
    boolean entitySegmentFor(SceneEntity e,float x,float y,float tx,float ty){return entitySegment(e,x,y,tx,ty);}
    boolean planSegmentFor(SceneEntity e,float x,float y,float tx,float ty){return entitySegment(e,x,y,tx,ty,.5f);}
    boolean scriptMove(SceneEntity e,String goal,float maxPath,float turnRate,int stamp,float elapsed){return scriptMovePath(e,goal,maxPath,turnRate,stamp,elapsed);}
}
