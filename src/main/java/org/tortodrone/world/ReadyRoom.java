package org.tortodrone.world;

/** Client-local destination; never allocate a SceneRegistry instance for this room. */
public final class ReadyRoom {
    public static final int ZONE = 5 << 24;
    public static final int SCENE = Integer.MAX_VALUE;
    public static final int PLACE = 0;
    private ReadyRoom() { }
}
