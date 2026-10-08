package org.tortodrone.world;
import java.io.*;
/** One archived scene, with its own explicit network object and zone identity. */
public final class TownSquare {
 public static final int SCENE_ID=1,SCENE_OID=10,ZONE_ID=1;
 public static final String ZONE_RECEIVER="2d900cf54355111b4bb4befcdff42b82";
 private static final long START=System.nanoTime();
 public static int clock(){return 1+(int)((System.nanoTime()-START)/1_000_000L);}
 private static byte[] cached;
 private TownSquare() { }
 public static synchronized byte[] model()throws IOException {
  if(cached==null)try(var in=TownSquare.class.getResourceAsStream("/scenes/town-square.dat")){
   if(in==null)throw new IOException("Town Square archive is missing; rebuild the server");
   cached=in.readNBytes(1024*1024);if(cached.length<8||cached.length==1024*1024)throw new IOException("Invalid Town Square scene size");
  }
  return cached.clone();
 }
}
