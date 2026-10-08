package org.tortodrone.world;

import java.security.MessageDigest;
import java.util.HexFormat;
import static org.tortodrone.support.TestWire.check;

public final class TownMovementTest {
    public static void main(String[] args) throws Exception {
        byte[] scene = TownSquare.model();
        check(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(scene))
            .equals("045528ddd9bb3c3a940586b28a3c9c3c5a3b3d2db47345f86d47ea0cc3db874f"), "pinned original Town Square");
        scene[0] ^= 1;
        check(scene[0] != TownSquare.model()[0], "callers cannot modify the cached scene");
        check(TownSquare.clock() > 0, "scene clock starts positive");
        check(TownTerrain.walkable(0,-8), "verified spawn");
        check(!TownTerrain.walkable(Float.NaN,0) && !TownTerrain.walkable(10000,0)
            && !TownTerrain.walkable(0,Float.POSITIVE_INFINITY), "invalid positions blocked");
        var m = new TownMovement();
        m.resetInput(100);
        check(m.accept(100,1.5f,0,1,100,1000), "first input accepted");
        m.advance(100,1000);
        m.advance(150,1050);
        check(Math.abs(m.x()-.25f)<.00001 && m.y()==-8 && m.rotation()==1.5f, "movement independent of facing");
        check(!m.accept(100,0,0,1,100,1050) && !m.accept(99,0,0,1,100,1050), "replays rejected");
        check(!m.accept(101,Float.NaN,0,1,100,1050)
            && !m.accept(101,0,Float.POSITIVE_INFINITY,1,100,1050)
            && !m.accept(20000,0,0,1,100,1050), "invalid angles and clocks rejected");
        m.advance(151,1501);
        check(Math.abs(m.x()-.25f)<.00001 && m.flags()==0, "stale input stops movement");
        check(m.accept(101,7,0,2,101,1600), "facing update accepted");
        m.advance(152,1600);
        check(Math.abs(m.x()-.25f)<.00001 && m.flags()==0 && Math.abs(m.rotation())<=Math.PI, "inventory input does not cause movement and angle normalized");
        int stamp=153;
        for(int i=0;i<1000;i++) {
            check(m.accept(stamp,0,0,1,stamp,2000+i*50), "fresh frame");
            m.advance(stamp,2000+i*50); stamp+=50;
            check(TownTerrain.walkable(m.x(),m.y()), "movement stays on walkable terrain");
        }
        check(m.x()<35, "town geometry blocks an unlimited straight walk");
        var future = new TownMovement();
        future.resetInput(100);
        check(future.accept(200,1.5f,0,1,100,1000), "future input enqueued");
        future.advance(150,1050);
        check(future.x()==0 && future.flags()==0 && future.rotation()==0, "future input not applied early");
        future.advance(200,1100);
        check(future.x()==0 && future.flags()==2 && future.rotation()==1.5f, "input applies when due");
        future.advance(225,1125);
        check(Math.abs(future.x()-.125f)<.00001, "partial tick uses elapsed scene time");
        check(future.accept(250,0,0,0,225,1125), "future stop enqueued");
        future.advance(300,1200);
        check(Math.abs(future.x()-.25f)<.00001 && future.flags()==0, "batched stop splits simulation at timestamp");
        future.resetInput(300);
        check(future.accept(300,0,0,1,300,1200), "re-entry starts a fresh input stream");
        future.advance(300,1200);
        float before = future.x();
        future.advance(317,1217);
        future.advance(341,1241);
        future.advance(350,1250);
        check(Math.abs(future.x()-before-.25f)<.00001, "scheduler jitter does not alter movement speed");
        var bounded = new TownMovement();
        bounded.resetInput(100);
        for(int i=0;i<256;i++) check(bounded.accept(1000+i,0,0,1,100,1000), "bounded future input");
        check(!bounded.accept(1256,0,0,1,100,1000), "future queue cannot grow without limit");
        var inventory = new TownMovement();
        inventory.resetInput(100);
        check(inventory.accept(100,1,0,3,100,1000), "inventory and move input accepted");
        inventory.advance(150,1050);
        check(inventory.activity()==3 && inventory.activityStarted()==100
            && inventory.flags()==0 && inventory.x()==0, "inventory blocks walking");
        check(inventory.accept(200,2,0,3,150,1050), "continued inventory input accepted");
        inventory.advance(200,1100);
        check(inventory.activityStarted()==100 && inventory.rotation()==1, "inventory start and facing remain stable");
        check(inventory.accept(250,2,0,1,200,1100), "inventory close accepted");
        inventory.advance(250,1150);
        check(inventory.activity()==4 && inventory.activityStarted()==250, "closing activity starts once");
        for(int time=300;time<=1100;time+=50) {
            check(inventory.accept(time,2,0,1,time,900+time), "walking during inventory close accepted");
            inventory.advance(time,900+time);
            check(inventory.x()==0 && inventory.flags()==0 && inventory.activityStarted()==250,
                "closing animation is not restarted and prevents early movement");
        }
        inventory.advance(1189,2089);
        check(inventory.activity()==0 && inventory.activityStarted()==1139
            && inventory.flags()==2 && Math.abs(inventory.x()-.25f)<.00001,
            "walking resumes at the exact closing deadline within a tick");
        inventory.resetInput(1200);
        check(inventory.activity()==0 && inventory.activityStarted()==1200 && inventory.flags()==0,
            "re-entry clears inventory activity");
        var peaceful=new TownMovement();peaceful.resetInput(100);
        check(peaceful.accept(100,1,0,4|8|16|32,100,1000),"combat input remains bounded input");
        peaceful.advance(150,1050);
        check(peaceful.activity()==0&&peaceful.flags()==0&&peaceful.x()==0,"combat input cannot start town actions");
        check(peaceful.accept(150,1,0,1|4|8|16|32,150,1050),"move with combat bits accepted");
        peaceful.advance(200,1100);
        check(peaceful.activity()==0&&peaceful.flags()==2&&Math.abs(peaceful.x()-.25f)<.00001,
            "combat bits neither block walking nor become actor flags");
        System.out.println("PASS: archived scene, spawn, movement, facing, replay limits, timeout, timestamped batches, scheduler jitter and town collision");
    }
}
