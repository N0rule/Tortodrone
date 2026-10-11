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
    /** Traverses the sampled grid; this does not recover the original collision shapes. */
    public boolean walkableSegment(float fromX,float fromY,float toX,float toY) {
        if(!walkable(fromX,fromY)||!walkable(toX,toY))return false;
        // Use the same float coordinate conversion and rounding as walkable().
        double x=(fromX-minX)/step,y=(fromY-minY)/step;
        double endX=(toX-minX)/step,endY=(toY-minY)/step;
        int col=Math.round((float)x),row=Math.round((float)y);
        int endCol=Math.round((float)endX),endRow=Math.round((float)endY);
        double dx=endX-x,dy=endY-y;
        int sx=Double.compare(dx,0),sy=Double.compare(dy,0);
        double deltaX=dx==0?Double.POSITIVE_INFINITY:1/Math.abs(dx);
        double deltaY=dy==0?Double.POSITIVE_INFINITY:1/Math.abs(dy);
        double nextX=dx==0?Double.POSITIVE_INFINITY:(col+sx*.5-x)/dx;
        double nextY=dy==0?Double.POSITIVE_INFINITY:(row+sy*.5-y)/dy;
        while(col!=endCol||row!=endRow){
            double crossX=col==endCol?Double.POSITIVE_INFINITY:nextX;
            double crossY=row==endRow?Double.POSITIVE_INFINITY:nextY;
            // Math.round assigns an exact corner to the higher row and column,
            // which can be a side cell when the axes travel in opposite directions.
            if(crossX==crossY&&!walkable.get(Math.max(row,row+sy)*width+Math.max(col,col+sx)))return false;
            if(crossX<=crossY){col+=sx;nextX+=deltaX;}
            if(crossY<=crossX){row+=sy;nextY+=deltaY;}
            if(!walkable.get(row*width+col))return false;
        }
        return true;
    }
}
