package org.tortodrone.world;
/** Monotonic server scene timestamps, shared by instance transitions. */
public final class SceneClock {
 private static final long START=System.nanoTime();
 private SceneClock() { }
 public static int now(){return 1+(int)((System.nanoTime()-START)/1_000_000L);}
}
