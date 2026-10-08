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
        check(Math.abs(m.x()-.25f)<.00001 && m.flags()==0 && Math.abs(m.rotation())<=Math.PI, "shield input does not cause movement and angle normalized");
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
        System.out.println("PASS: archived scene, spawn, movement, facing, replay limits, timeout, timestamped batches, scheduler jitter and town collision");
    }
}
