package org.tortodrone.world;

import java.util.List;
import java.util.Objects;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.core.world.SceneElevator;
import org.tortodrone.core.world.ScenePortals;

/** Authored elevator platforms: occupied-platform detection, the identity-owned ride
 *  offer, drop/ready gates and cancellation. This is pure scene-lock work; storage and
 *  socket publication stay on the session thread through the control callback. */
final class ElevatorSystem {
    private final SceneEntities scene;
    private SceneCallbacks.ElevatorControl control=new SceneCallbacks.ElevatorControl(){};

    ElevatorSystem(SceneEntities scene) { this.scene=scene; }

    void control(SceneCallbacks.ElevatorControl value) { control=Objects.requireNonNull(value); }

    /** A pawn body of radius r can reach a platform whose edge is up to 2r away, so
     *  movement treats that bridged footprint as supported across the authored gap. */
    boolean floor(float x,float y){
        for(var e:scene.entityValues())if(e.elevator!=null&&e.destroyed==Integer.MAX_VALUE
            &&e.spec.contains(e.x,e.y,e.rotation,x,y,ScenePortals.PAWN_RADIUS*2))return true;
        return false;
    }

    boolean entry(String key){
        return scene.entityValues().stream().anyMatch(e->e.elevator!=null&&e.binding.key().equals(key)&&e.destroyed==Integer.MAX_VALUE);
    }

    void advance(SceneEntity e,int stamp,List<ActorState> occupants){
        if(e.trip!=null){
            if(!control.valid(e.trip)){cancel(e.trip,stamp);return;}
            if(!e.elevatorOffered&&stamp-e.trip.started()>=SceneElevator.ACTIVATE_MILLIS){
                if(!control.advance(e.trip,stamp)){cancel(e.trip,stamp);return;}
                e.elevatorOffered=true;
            }
            return;
        }
        var living=occupants.stream().filter(p->scene.activePlayer(p.id())).toList();
        var on= living.stream().filter(p->e.spec.contains(e.x,e.y,e.rotation,p.x(),p.y(),ScenePortals.PAWN_RADIUS)).map(ActorState::id).toList();
        int state=on.isEmpty()?0:on.size()<living.size()?1:0;
        if(!on.isEmpty()&&on.size()==living.size()){
            var trip=new SceneEntities.ElevatorTrip(e.snapshot(),on,e.elevator,stamp);
            if(control.begin(trip,stamp)){e.trip=trip;e.elevatorOffered=false;state=2;}
        }
        state(e,state,stamp);
    }

    boolean cancel(SceneEntities.ElevatorTrip trip,int stamp){
        var e=trip==null?null:scene.entity(trip.source().id());if(e==null||e.trip!=trip)return false;
        e.trip=null;e.elevatorOffered=false;state(e,0,stamp);control.cancel(trip,stamp);return true;
    }

    /** The session calls this only after its floor report/preflight succeeds. */
    boolean drop(SceneEntities.ElevatorTrip trip,boolean returning,int stamp){
        var e=trip==null?null:scene.entity(trip.source().id());
        if(e==null||e.trip!=trip||!e.elevatorOffered||e.state!=2||!control.valid(trip))return false;
        e.elevatorDrop=stamp;state(e,returning?4:3,stamp);return true;
    }

    boolean ready(SceneEntities.ElevatorTrip trip,int stamp){
        var e=trip==null?null:scene.entity(trip.source().id());
        return e!=null&&e.trip==trip&&(e.state==3||e.state==4)&&stamp-e.elevatorDrop>=SceneElevator.DROP_MILLIS&&control.valid(trip);
    }

    private static void state(SceneEntity e,int state,int stamp){if(e.state!=state){e.state=state;e.stateEntered=stamp;}}
}
