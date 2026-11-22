package org.tortodrone.world;

import java.util.*;
import org.tortodrone.core.world.SceneCollision;
import org.tortodrone.core.world.ScenePortals;
import org.tortodrone.core.world.SceneShape;

/** Collision and contact queries over the shared live entity model. All calls run under the
 *  scene monitor; this system only reads entity positions, flags and shapes and never mutates
 *  the scene. */
final class SceneCollisions {
    private final SceneEntities scene;

    SceneCollisions(SceneEntities scene) {
        this.scene = scene;
    }

    boolean walkable(float x,float y,int ignore){
        for(var e:scene.entityValues()){
            if(e.id==ignore||e.destroyed!=Integer.MAX_VALUE)continue;
            if(e.carrier!=0)continue;
            if(e.kind.equals("Door")&&(e.flags&6)==2)continue;
            if((e.collisionFlags&scene.pawnCollisionMask())!=0&&e.containsPawn(x,y))return false;
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
    java.util.Set<Integer> disabledSources(){
        var result=new java.util.HashSet<Integer>();
        for(var e:scene.entityValues()){
            if(e.collisionFlags==0)continue;
            boolean disabled=e.destroyed!=Integer.MAX_VALUE
                ||e.kind.equals("Door")&&(e.flags&6)==2;
            if(!disabled)continue;
            try{result.add(Integer.parseInt(e.binding.key()));}catch(NumberFormatException ignored){}
        }
        return java.util.Set.copyOf(result);
    }
    SceneCollision.Penetration penetration(float x,float y,int ignore){
        boolean carrying=ignore!=0&&scene.entityValues().stream().anyMatch(e->SceneEntities.liftKind(e.kind)
            &&e.carrier==ignore&&e.destroyed==Integer.MAX_VALUE);
        var result=SceneCollision.Penetration.NONE;
        for(var e:scene.entityValues()){
            if(e.id==ignore||e.destroyed!=Integer.MAX_VALUE||e.carrier!=0||e.kind.equals("Door")&&(e.flags&6)==2)continue;
            if((e.collisionFlags&scene.pawnCollisionMask())==0&&!(carrying&&e.kind.equals("Door")
                    &&(e.collisionFlags&SceneEntities.LIFT_COLLISION)!=0))continue;
            if(!e.containsPawn(x,y))continue;
            result=result.deepest(e.penetration(x,y));
        }
        return result;
    }
    boolean contact(float x,float y,int owner){
        return contact(x,y,0,owner,null,-1);
    }
    int contactActor(float x,float y,float rotation,int owner,Object shape,int mask){
        for(var e:scene.entityValues())if(e.id!=owner&&e.carrier==0&&e.destroyed==Integer.MAX_VALUE
            &&(e.collisionFlags&mask)!=0&&!(e.kind.equals("Door")&&(e.flags&2)!=0)
            &&(shape==null||e.shape==null?e.spec.contains(e.x,e.y,e.rotation,x,y,0):SceneCollision.overlaps(shape,x,y,rotation,e.shape,e.x,e.y,e.rotation,0)))return e.id;
        return 0;
    }
    boolean contact(float x,float y,float rotation,int owner,Object shape,int mask){
        var source=scene.entity(owner);
        if(source!=null&&(source.kind.equals("Monster")||source.kind.equals("Trap"))&&(mask&4)!=0&&scene.occupants().stream().anyMatch(p->shape==null?
            Math.hypot(p.x()-x,p.y()-y)<=ScenePortals.PAWN_RADIUS:SceneShape.intersects(shape,
                (p.x()-x)*(float)Math.cos(rotation)+(p.y()-y)*(float)Math.sin(rotation),
                -(p.x()-x)*(float)Math.sin(rotation)+(p.y()-y)*(float)Math.cos(rotation),ScenePortals.PAWN_RADIUS)))return true;
        return contactActor(x,y,rotation,owner,shape,mask)!=0;
    }
}
