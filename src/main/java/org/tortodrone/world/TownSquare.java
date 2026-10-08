package org.tortodrone.world;
import java.io.IOException;
/** Compatibility names for older fixtures; runtime layouts come from the external catalog. */
public final class TownSquare {
 public static final int SCENE_ID=1,SCENE_OID=10,ZONE_ID=1;
 public static final String ZONE_RECEIVER=SceneRegistry.ZONE_RECEIVER;
 private TownSquare() { }
 public static int clock(){return SceneClock.now();}
 public static byte[] model()throws IOException{return SceneRegistry.system().entry().map().layout();}
}
