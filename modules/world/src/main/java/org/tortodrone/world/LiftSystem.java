package org.tortodrone.world;

import java.util.*;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.core.world.SceneContent;
import org.tortodrone.core.world.FireCatalog;
import org.tortodrone.core.world.SceneLiftState;
import org.tortodrone.core.world.ScenePortals;

/** Carried, thrown and vial lifts: ownership, native snap targeting, flight collision and
 *  landing actions for one scene instance. Entity insertion stays in the entity model. */
final class LiftSystem {
    private final SceneEntities scene;

    LiftSystem(SceneEntities scene) {
        this.scene = scene;
    }

    boolean canThrow(int id){var e=scene.entity(id);return e!=null&&e.spec.flag("can-throw",false);}
    private SceneEntity snapTarget(SceneEntity lift,ActorState player){
        String tag=lift.spec.text("snap-tag","");if(tag.isBlank())return null;
        float ox=scene.content().stats().getOrDefault("player.interaction-offset-x",.7f),oy=scene.content().stats().getOrDefault("player.interaction-offset-y",0f);
        float c=(float)Math.cos(player.rotation()),s=(float)Math.sin(player.rotation());
        float x=player.x()+ox*c-oy*s,y=player.y()+ox*s+oy*c;
        return scene.entityValues().stream().filter(e->e.destroyed==Integer.MAX_VALUE&&Arrays.asList(e.spec.text("tags","").split(",")).contains(tag)
                &&e.spec.contains(e.x,e.y,e.rotation,x,y,scene.content().stats().getOrDefault("player.interaction-radius",.4f)))
            .min(Comparator.comparingDouble(e->Math.hypot(e.x-x,e.y-y))).orElse(null);
    }
    boolean prepareDrop(int id,ActorState player){
        var e=scene.entity(id);if(e==null||e.carrier!=player.id())return false;
        var target=snapTarget(e,player);e.dropPoint=target==null?null:new SceneLiftState.Point(target.x,target.y);return target!=null;
    }
    void throwLift(int id,ActorState player,int stamp){
        var e=scene.entity(id);if(e==null||e.carrier!=player.id())return;
        e.carrier=0;e.owner=player.id();e.ownerOid=player.clientOid();e.flags=e.flags&~4|2;e.faction=player.id();e.excluded=new int[0];
        e.x=player.x();e.y=player.y();e.rotation=e.direction=player.rotation();e.thrownAt=stamp;scene.moved(e);
        e.z=scene.content().stats().getOrDefault("player.lift-height",0f);
    }
    void dropLift(int id,ActorState player,int stamp){
        var e=scene.entity(id);if(e==null||e.carrier!=player.id())return;
        // Native Dungeoneer.drop invokes the current actionable with the dropped
        // lift as Activator. Test that owned prompt before releasing the carrier.
        var prompt=scene.interaction(player.x(),player.y(),player.rotation(),player.id(),id);
        var unlock=e.spec.text("actionable-signal","").equals("gold_unlock")&&prompt!=null&&prompt.action().equals("UnlockGold")?scene.entity(prompt.actorId()):null;
        e.carrier=0;e.flags&=~6;e.z=0;
        float distance=(ScenePortals.PAWN_RADIUS+e.spec.number("radius",0))*1.01f;
        var target=snapTarget(e,player);
        float x=target==null?player.x()+(float)Math.cos(player.rotation())*distance:target.x;
        float y=target==null?player.y()+(float)Math.sin(player.rotation())*distance:target.y;
        if(scene.terrain().walkable(x,y)&&scene.walkable(x,y,e.id)){e.x=x;e.y=y;}else {e.x=player.x();e.y=player.y();}scene.moved(e);
        e.dropPoint=new SceneLiftState.Point(e.x,e.y);
        e.rotation=e.direction=player.rotation();e.faction=player.id();e.excluded=new int[0];
        if(unlock!=null&&unlock.kind.equals("Door")&&unlock.spec.text("door-lock","").equals("gold")&&(unlock.flags&2)==0){
            scene.entitySignal(unlock,"open",stamp);e.destroyed=stamp;e.scriptDestroyed=true;e.status.clear();return;
        }
        if(e.vial!=null)land(e,stamp);
    }
    void releaseLifts(int player,int stamp){
        for(var e:List.copyOf(scene.entityValues()))if(e.carrier==player){
            e.carrier=0;e.flags&=~6;if(e.vial!=null)e.destroyed=stamp;
        }
    }
    void lift(SceneEntity e,int stamp,float elapsed,List<ActorState> players){
        if(e.carrier!=0){for(var player:players)if(player.id()==e.carrier){e.x=player.x();e.y=player.y();e.rotation=player.rotation();scene.moved(e);return;}return;}
        if((e.flags&2)==0)return;
        float distance=e.speed*elapsed;boolean collided=false;
        int steps=Math.max(1,(int)Math.ceil(Math.abs(distance)/.1f));
        // Thrown lifts also collide with the native lift bit, so closed Lift Barriers catch them.
        int thrownMask=e.spec.integer("thrown-collision-mask",-1);
        int flightMask=thrownMask>=0?thrownMask|SceneEntities.LIFT_COLLISION:thrownMask;
        for(int step=0;step<steps;step++){
            float x=e.x+(float)Math.cos(e.direction)*distance/steps,y=e.y+(float)Math.sin(e.direction)*distance/steps;
            boolean clear=scene.staticCollision().isEmpty()||e.shape==null?scene.terrain().walkableSegment(e.x,e.y,x,y):
                scene.staticCollision().clearSegment(flightMask,e.shape,e.x,e.y,x,y,e.rotation,scene.disabledCollisionSources());
            if(!clear){collided=true;break;}
            e.x=x;e.y=y;scene.moved(e);
            // Execute the landing action at the colliding substep. Keeping the
            // previous clear pose left a thrown vial outside the victim's shape.
            if(scene.contact(x,y,e.rotation,e.id,e.shape,flightMask)){collided=true;break;}
        }
        if(stamp-e.thrownAt>=e.spec.integer("throw-time",0))e.z=Math.max(0,e.z-e.spec.number("fall-speed",0)*elapsed);
        if(collided||e.z<=0||stamp-e.thrownAt>=e.spec.integer("fuse",0))land(e,stamp);
    }
    private void land(SceneEntity e,int stamp){
        e.flags&=~2;
        scene.potLand(e,stamp);
        String graph=e.vial==null?e.spec.text("land-action",""):e.vial.text("use-action","");
        if(!graph.isBlank())try{scene.fireAttack(e.snapshot(),FireCatalog.actionJson(graph));}catch(java.io.IOException invalid){throw new IllegalStateException("Invalid prepared lift action",invalid);}
        if(e.spec.flag("destroy-on-land",false))e.destroyed=stamp;
    }
}
