package org.tortodrone.world;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import static org.tortodrone.support.TestWire.check;

public final class CollisionMapTest {
    public static void main(String[] args)throws Exception {
        Path file=Files.createTempFile("tortodrone-collision-segment-",".gz");
        try {
            var bits=new BitSet(25);bits.set(0,25);bits.clear(12);
            write(file,5,5,-2,-2,1,bits);
            var map=CollisionMap.read(file);
            for(float[] ends:List.of(new float[]{-2,0,2,0},new float[]{0,-2,0,2},
                    new float[]{-2,-2,2,2},new float[]{-2,2,2,-2})){
                check(!map.walkableSegment(ends[0],ends[1],ends[2],ends[3]),"blocked cell between clear endpoints");
                check(!map.walkableSegment(ends[2],ends[3],ends[0],ends[1]),"reverse traversal sees the same wall");
            }
            check(map.walkableSegment(-2,-2,2,-2)&&map.walkableSegment(2,-2,-2,-2),"clear horizontal boundary path in both directions");
            check(map.walkableSegment(-2,2,-2,-2)&&map.walkableSegment(-2,-2,-2,2),"clear vertical boundary path in both directions");
            check(map.walkableSegment(-2,-1,1,2)&&map.walkableSegment(1,2,-2,-1),"clear diagonal path");
            check(map.walkableSegment(-1,0,-.51f,0),"segment stops before blocked cell boundary");
            check(!map.walkableSegment(-1,0,-.5f,0),"endpoint rounding agrees with point collision");
            check(map.walkableSegment(-1.5f,-2,-2,-2),"negative travel from exact clear cell boundary");
            check(map.walkableSegment(1,1,1,1)&&!map.walkableSegment(0,0,0,0),"stationary clear and blocked points");
            check(!map.walkableSegment(Float.NaN,0,1,1)&&!map.walkableSegment(1,1,Float.POSITIVE_INFINITY,0)
                &&!map.walkableSegment(-3,0,2,0)&&!map.walkableSegment(1,1,3,0),"invalid or out-of-map segment refused");
            // This briefly enters a blocked cell that fixed-distance sampling can miss.
            bits.set(12);bits.clear(1);write(file,5,5,0,0,1,bits);map=CollisionMap.read(file);
            check(!map.walkableSegment(0,0,2,1.99f)&&!map.walkableSegment(2,1.99f,0,0),"short diagonal crossing cannot skip a blocked cell");
            bits.set(1);bits.clear(6);write(file,5,5,0,0,1,bits);map=CollisionMap.read(file);
            check(!map.walkable(.5f,.5f)&&!map.walkableSegment(0,1,1,0)&&!map.walkableSegment(1,0,0,1),
                "mixed-direction exact corner retains point rounding in both directions");
            System.out.println("PASS: sampled collision segment traversal, walls, diagonals, boundaries and invalid coordinates");
        } finally {Files.delete(file);}
    }
    private static void write(Path file,int width,int height,float x,float y,float step,BitSet bits)throws IOException {
        try(var out=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))){
            out.writeInt(0x54445731);out.writeInt(width);out.writeInt(height);
            out.writeFloat(x);out.writeFloat(y);out.writeFloat(step);
            out.write(Arrays.copyOf(bits.toByteArray(),(width*height+7)/8));
        }
    }
}
