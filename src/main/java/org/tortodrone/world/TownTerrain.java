package org.tortodrone.world;
/** Compatibility facade; each active scene uses its own external collision map. */
public final class TownTerrain {
 private TownTerrain(){}
 public static boolean walkable(float x,float y){return SceneRegistry.defaultEntry().map().collision().walkable(x,y);}
}
