package org.tortodrone.world;

import java.nio.file.Path;
import static org.tortodrone.support.TestWire.check;

public final class ScenePortalsTest {
    public static void main(String[] args)throws Exception {
        var portals=ScenePortals.load(Path.of("defaults/maps/haven"),
            "26f15faf59dcf711e66bf0494d64fda7da6bd95ffffa5934a659a41a398d8c57",
            CollisionMap.read(Path.of("defaults/maps/haven/walkability.gz")));
        var trigger=portals.triggers().getFirst();
        check(trigger.intersects(-38.2f,0.4f),"pawn center inside portal rectangle intersects");
        check(trigger.intersects(-39.229614f-ScenePortals.PAWN_RADIUS,0.4f),
            "circle tangent to rectangle edge counts as intersection");
        check(trigger.intersects(-39.229614f-.2f,-2.078433f-.2f),
            "circle reaches a rectangle corner when corner distance is below radius");
        check(!trigger.intersects(-39.229614f-.25f,-2.078433f-.25f),
            "diagonal corner outside circle radius does not intersect");
        check(!trigger.intersects(Float.NaN,0)&&!trigger.intersects(0,Float.POSITIVE_INFINITY),
            "non-finite actor positions never trigger portals");
        var sensor=new ScenePortals.Trigger(1,2,"test",.8f,-8.1f,.9f,-7.9f);
        var movement=new TownMovement(CollisionMap.read(Path.of("defaults/maps/haven/walkability.gz")),0,-8,0,
            new ScenePortals(java.util.Map.of(),java.util.List.of(sensor)));
        movement.resetInput(1000);movement.accept(1000,0,0,1,1000,0);movement.advance(2000,0);
        check(movement.portal()==sensor&&movement.x()<.8f&&movement.flags()==0,
            "delayed movement freezes at the first circle intersection instead of tunnelling through a sensor");
        float frozen=movement.x();movement.resetInput(2000);movement.advance(3000,0);
        check(movement.x()==frozen&&!movement.accept(3000,0,0,1,3000,0),"departing movement stays frozen after input reset");
        System.out.println("PASS: portal sensor uses circle-versus-rectangle intersection including tangent and corner geometry");
    }
}
