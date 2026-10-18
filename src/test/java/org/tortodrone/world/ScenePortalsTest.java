package org.tortodrone.world;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import static org.tortodrone.support.TestWire.check;

public final class ScenePortalsTest {
    public static void main(String[] args)throws Exception {
        var portals=ScenePortals.load(org.tortodrone.support.ContentFixture.path("maps/haven"),
            "83115fa639bb1e55c93827772d2c46b929282c3641854c1ea5422e8cb0949ac2",
            CollisionMap.read(org.tortodrone.support.ContentFixture.path("maps/haven/walkability.gz")));
        var trigger=portals.triggers().stream().filter(t->t.id()==6071).findFirst().orElseThrow();
        check(trigger.intersects(-38.2f,0.4f),"pawn center inside portal rectangle intersects");
        check(trigger.intersects(-39.229614f-ScenePortals.PAWN_RADIUS,0.4f),
            "circle tangent to rectangle edge counts as intersection");
        check(trigger.intersects(-39.229614f-.2f,-2.078433f-.2f),
            "circle reaches a rectangle corner when corner distance is below radius");
        check(!trigger.intersects(-39.229614f-.25f,-2.078433f-.25f),
            "diagonal corner outside circle radius does not intersect");
        check(!trigger.intersects(Float.NaN,0)&&!trigger.intersects(0,Float.POSITIVE_INFINITY),
            "non-finite actor positions never trigger portals");
        var polygon=new ScenePortals.Trigger(2,3,"poly",0,0,4,2,
            List.of(new ScenePortals.Vertex(0,0),new ScenePortals.Vertex(4,0),
                new ScenePortals.Vertex(3,2),new ScenePortals.Vertex(1,2)));
        check(polygon.intersects(2,1),"convex polygon contains interior pawn center");
        check(!polygon.intersects(.1f,1),"polygon rejects pawn near AABB corner beyond radius from polygon");
        check(polygon.intersects(-ScenePortals.PAWN_RADIUS,0),"polygon vertex tangency is inclusive");
        var clockwise=new ScenePortals.Trigger(3,3,"poly",0,0,4,2,
            List.of(new ScenePortals.Vertex(1,2),new ScenePortals.Vertex(3,2),
                new ScenePortals.Vertex(4,0),new ScenePortals.Vertex(0,0)));
        check(clockwise.intersects(2,1),"clockwise and counterclockwise polygon winding both work");
        var mutable=new ArrayList<>(List.of(new ScenePortals.Vertex(0,0),new ScenePortals.Vertex(4,0),new ScenePortals.Vertex(3,2)));
        var immutable=new ScenePortals.Trigger(4,3,"poly",0,0,4,2,mutable);mutable.clear();
        check(immutable.vertices().size()==3,"polygon vertices are defensively copied");
        checkFormat2Polygon("0,0;4,0;3,2;1,2",true,"valid format-2 polygon metadata loads");
        checkFormat2Polygon("0,0;3,3;0,3;2,0",false,"self-crossing or nonconvex polygon metadata is rejected");
        checkFormat2Polygon("0,3;-1.8,-2.4;2.9,.9;-2.9,.9;1.8,-2.4",false,
            "self-intersecting star with consistent local turns is rejected");
        checkFormat2Polygon("0,0;4,0;2,1;0,4",false,"concave polygon metadata is rejected");
        checkFormat2Polygon("NaN,0;1,0;1,1",false,"non-finite polygon metadata is rejected");
        checkFormat2Polygon("0,0;1,0;2,0",false,"collinear polygon metadata is rejected");
        checkFormat2Polygon("0,0;1,0;1,1;0,1;0,0",false,"duplicate polygon vertices are rejected");
        checkFormat2Polygon("0,0;1001,0;1001,1",false,"oversized polygon metadata is rejected");
        Path legacy=Files.createTempDirectory("legacy-portals-test-");
        try {
            Files.writeString(legacy.resolve("portals.properties"),"format=1\nlayout-sha256=test\ntriggers=1\n"+
                "trigger.1.scene=2\ntrigger.1.entrance=arrival\ntrigger.1.min-x=0\ntrigger.1.min-y=0\n"+
                "trigger.1.max-x=1\ntrigger.1.max-y=1\n");
            var old=ScenePortals.load(legacy,"test",CollisionMap.read(org.tortodrone.support.ContentFixture.path("maps/haven/walkability.gz"))).triggers().getFirst();
            check(old.vertices().isEmpty()&&old.intersects(.5f,.5f),"format-1 rectangle packs remain supported");
        }finally {Files.deleteIfExists(legacy.resolve("portals.properties"));Files.delete(legacy);}
        var sensor=new ScenePortals.Trigger(1,2,"test",.8f,-8.1f,.9f,-7.9f);
        var movement=new TownMovement(CollisionMap.read(org.tortodrone.support.ContentFixture.path("maps/haven/walkability.gz")),0,-8,0,
            new ScenePortals(java.util.Map.of(),java.util.List.of(sensor)));
        movement.resetInput(1000);movement.accept(1000,0,0,1,1000,0);movement.advance(2000,0);
        check(movement.portal()==sensor&&movement.x()<.8f&&movement.flags()==0,
            "delayed movement freezes at the first circle intersection instead of tunnelling through a sensor");
        float frozen=movement.x();movement.resetInput(2000);movement.advance(3000,0);
        check(movement.x()==frozen&&!movement.accept(3000,0,0,1,3000,0),"departing movement stays frozen after input reset");
        System.out.println("PASS: rectangle and convex polygon portal sensors, metadata validation, and movement freezing");
    }
    private static void checkFormat2Polygon(String vertices,boolean valid,String message)throws Exception {
        Path dir=Files.createTempDirectory("scene-portals-test-");
        try {
            Files.writeString(dir.resolve("portals.properties"),"format=2\nlayout-sha256=test\n"+
                "triggers=17\ntrigger.17.scene=2\ntrigger.17.entrance=arrival\n"+
                "trigger.17.shape=polygon\ntrigger.17.vertices="+vertices+"\n");
            boolean loaded=true;
            try { ScenePortals.load(dir,"test",CollisionMap.read(org.tortodrone.support.ContentFixture.path("maps/haven/walkability.gz"))); }
            catch(java.io.IOException e) { loaded=false; }
            check(loaded==valid,message);
        } finally { Files.deleteIfExists(dir.resolve("portals.properties"));Files.deleteIfExists(dir); }
    }
}
