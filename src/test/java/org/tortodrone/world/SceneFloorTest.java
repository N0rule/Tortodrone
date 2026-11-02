package org.tortodrone.world;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.tortodrone.support.TestWire.check;

/** Floor-placeable geometry supplies ground only inside its authored footprint. */
public final class SceneFloorTest {
    public static void main(String[] args)throws Exception {
        Path dir=Files.createTempDirectory("tortodrone-floor-shapes-");
        try{
            var p=new Properties();p.setProperty("format","1");p.setProperty("layout-sha256","fixture");p.setProperty("tile-count","0");p.setProperty("shape-count","0");
            p.setProperty("floor-count","1");p.setProperty("floor.0.x","3");p.setProperty("floor.0.y","-1");
            p.setProperty("floor-shape-count","1");p.setProperty("floor-shape.0.geometry","P;0;0;2;0;2;0.5;0;0.5");
            write(dir,p);var floor=SceneCollision.load(dir,"fixture");
            check(floor.floorSupported(.5f,.25f)&&floor.floorSupported(3.5f,-.5f),"authored platform and ordinary terrain both support the pawn");
            check(!floor.floorSupported(.5f,.75f)&&!floor.floorSupported(-.01f,.25f)&&!floor.floorSupported(Float.NaN,0),"a thin platform cannot invent the rest of its grid cell or out-of-bounds ground");
            check(floor.clear(43,new FireCatalog.DataValue("Shape$Circle",Map.of("radius",.3f)),.5f,.25f,0),"a floor surface does not become an obstacle");
            p.remove("floor-shape-count");p.remove("floor-shape.0.geometry");write(dir,p);
            var legacy=SceneCollision.load(dir,"fixture");check(!legacy.floorSupported(.5f,.25f)&&legacy.floorSupported(3.5f,-.5f),"existing tile-only exports retain their exact interpretation");
            p.setProperty("floor-shape-count","100001");write(dir,p);refused(()->SceneCollision.load(dir,"fixture"));
            p.setProperty("floor-shape-count","1");p.setProperty("floor-shape.0.geometry","P;NaN;0;1;0;0;1");write(dir,p);refused(()->SceneCollision.load(dir,"fixture"));
            System.out.println("PASS: bounded native platform floors, exact footprint, terrain compatibility, no fabricated obstacle and invalid geometry refusal");
        }finally{try(var paths=Files.walk(dir)){for(var f:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(f);}}
    }
    private static void write(Path dir,Properties p)throws IOException{try(var out=Files.newOutputStream(dir.resolve("static-collision.properties"))){p.store(out,"");}}
    private interface Operation{void run()throws Exception;}
    private static void refused(Operation op)throws Exception{try{op.run();throw new AssertionError("Invalid floor accepted");}catch(IOException expected){}}
}
