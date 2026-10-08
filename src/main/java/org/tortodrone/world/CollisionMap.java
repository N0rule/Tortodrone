package org.tortodrone.world;

import java.io.*;
import java.nio.file.*;
import java.util.BitSet;
import java.util.zip.GZIPInputStream;

/** Immutable, bounded static collision samples for one prepared scene template. */
public final class CollisionMap {
    private final int width, height;
    private final float minX, minY, step;
    private final BitSet walkable;
    private CollisionMap(int width,int height,float minX,float minY,float step,BitSet bits) {
        this.width=width;this.height=height;this.minX=minX;this.minY=minY;this.step=step;walkable=bits;
    }
    public static CollisionMap read(Path file) throws IOException {
        if (Files.size(file)>1024*1024) throw new IOException("Collision file is too large");
        try(var in=new DataInputStream(new GZIPInputStream(Files.newInputStream(file)))) {
            if(in.readInt()!=0x54445731)throw new IOException("Invalid collision header");
            int w=in.readInt(),h=in.readInt();float x=in.readFloat(),y=in.readFloat(),step=in.readFloat();
            long cells=(long)w*h;
            if(w<=0||h<=0||cells>4_000_000||!Float.isFinite(x)||!Float.isFinite(y)
                    ||!Float.isFinite(step)||step<=0||step>1)throw new IOException("Invalid collision dimensions");
            int bytes=(int)((cells+7)/8);byte[] bits=in.readNBytes(bytes);
            if(bits.length!=bytes||in.read()!=-1)throw new IOException("Invalid collision payload");
            return new CollisionMap(w,h,x,y,step,BitSet.valueOf(bits));
        }
    }
    public boolean walkable(float x,float y) {
        if(!Float.isFinite(x)||!Float.isFinite(y))return false;
        float col=(x-minX)/step,row=(y-minY)/step;
        if(col<0||row<0||col>width-1||row>height-1)return false;
        return walkable.get(Math.round(row)*width+Math.round(col));
    }
}
