package org.tortodrone.world;

import java.util.ArrayDeque;

/** Server-owned movement on the scene clock. Client frames never provide positions. */
public final class TownMovement {
    public static final float SPEED = 5f;
    // Half the shortest edge of the verified Default PC collision shape, as in MobileLogic.
    public static final float MAX_STEP = .333333f;
    private static final int MAX_PENDING = 256;
    private record Frame(int timestamp, float rotation, float direction, int flags) { }
    private final ArrayDeque<Frame> pending = new ArrayDeque<>();
    private float x, y = -8, rotation, direction;
    private int flags, lastTimestamp, timestamp;
    private long lastInputAt;
    private boolean inputSeen;

    /** Clear input on scene entry/re-entry without moving the character. */
    public void resetInput(int sceneTime) {
        pending.clear();
        timestamp = sceneTime;
        flags = 0;
        inputSeen = false;
    }

    public boolean accept(int stamp, float facing, float heading, int inputFlags,
            int sceneTime, long now) {
        if (!Float.isFinite(facing) || !Float.isFinite(heading)
                || Math.abs((long) (stamp - sceneTime)) > 10000
                || (inputSeen && stamp - lastTimestamp <= 0)
                || pending.size() >= MAX_PENDING) return false;
        pending.addLast(new Frame(stamp, normalize(facing), normalize(heading),
            (inputFlags & 1) != 0 ? 2 : 0)); // Input MOVE=1; actor Mobile.MOVING=2.
        lastTimestamp = stamp;
        lastInputAt = now;
        inputSeen = true;
        return true;
    }

    /** Integrate each input segment only when its timestamp is due. */
    public void advance(int sceneTime, long now) {
        if (sceneTime - timestamp < 0) return;
        while (!pending.isEmpty() && sceneTime - pending.peekFirst().timestamp() >= 0) {
            var frame = pending.removeFirst();
            if (frame.timestamp() - timestamp > 0) moveTo(frame.timestamp());
            rotation = frame.rotation();
            direction = frame.direction();
            flags = frame.flags();
        }
        if (now - lastInputAt > 500) flags = 0;
        moveTo(sceneTime);
    }

    private void moveTo(int target) {
        int elapsed = target - timestamp;
        timestamp = target;
        if ((flags & 2) == 0) return;
        // Match the client's small collision substeps instead of one large 50 ms jump.
        while (elapsed > 0) {
            int step = Math.min(elapsed, 16);
            elapsed -= step;
            float distance = SPEED * step / 1000f;
            float nx = x + (float) Math.cos(direction) * distance;
            float ny = y + (float) Math.sin(direction) * distance;
            if (TownTerrain.walkable(nx, ny)) { x = nx; y = ny; }
            else if (TownTerrain.walkable(nx, y)) x = nx;
            else if (TownTerrain.walkable(x, ny)) y = ny;
        }
    }

    public float x() { return x; }
    public float y() { return y; }
    public float rotation() { return rotation; }
    public float direction() { return direction; }
    public int flags() { return flags; }
    private static float normalize(float angle) {
        return (float) Math.IEEEremainder(angle, Math.PI * 2);
    }
}
