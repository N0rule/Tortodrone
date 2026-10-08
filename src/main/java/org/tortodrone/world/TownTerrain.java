package org.tortodrone.world;
import java.io.*;import java.util.*;import java.util.zip.GZIPInputStream;
/** Sampled static town collision; only positions in the verified walkable mask are accepted. */
public final class TownTerrain {
 private static final int WIDTH=280,HEIGHT=304;
 private static final BitSet WALKABLE=load();
 private TownTerrain(){}
 public static boolean walkable(float x,float y) {
  if(!Float.isFinite(x)||!Float.isFinite(y))return false;
  int col=Math.round((x+35)*4),row=Math.round((y+32)*4);
  return col>=0&&col<WIDTH&&row>=0&&row<HEIGHT&&WALKABLE.get(row*WIDTH+col);
 }
 private static BitSet load(){
  try(var raw=TownTerrain.class.getResourceAsStream("/town-walkability.gz")){
   if(raw==null)throw new IOException("Missing town collision data");
   try(var in=new DataInputStream(new GZIPInputStream(raw))){
    if(in.readInt()!=0x54445731||in.readInt()!=WIDTH||in.readInt()!=HEIGHT||in.readFloat()!=-35||in.readFloat()!=-32||in.readFloat()!=.25f)throw new IOException("Invalid town collision header");
    byte[] bits=in.readNBytes((WIDTH*HEIGHT+7)/8);if(bits.length!=(WIDTH*HEIGHT+7)/8||in.read()!=-1)throw new IOException("Invalid town collision size");return BitSet.valueOf(bits);
   }
  }catch(IOException failure){throw new ExceptionInInitializerError(failure);}
 }
}
