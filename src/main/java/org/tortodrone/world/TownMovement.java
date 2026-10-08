package org.tortodrone.world;

import java.util.ArrayDeque;

/** Server-owned movement on the scene clock. Client frames never provide positions. */
public final class TownMovement {
    public static final float SPEED = 5f;
    // Half the shortest edge of the verified Default PC collision shape, as in MobileLogic.
    public static final float MAX_STEP = .333333f;
    // Verified stock KnightAdvancer inventory activities and closing duration.
    public static final int INVENTORY = 3, INVENTORY_END = 4, INVENTORY_END_MILLIS = 889;
    private static final int MAX_PENDING = 256;
    private record Frame(int timestamp, float rotation, float direction, int flags) { }
    private final ArrayDeque<Frame> pending = new ArrayDeque<>();
    private float x, y = -8, rotation, direction;
    private int flags, inputFlags, activity, activityStarted, lastTimestamp, timestamp;
    private long lastInputAt;
    private boolean inputSeen;

    /** Clear input on scene entry/re-entry without moving the character. */
    public void resetInput(int sceneTime) {
        pending.clear();
        timestamp = sceneTime;
        flags = 0;
        inputFlags = 0;
        activity = 0;
        activityStarted = sceneTime;
        inputSeen = false;
    }

    public boolean accept(int stamp, float facing, float heading, int inputFlags,
            int sceneTime, long now) {
        if (!Float.isFinite(facing) || !Float.isFinite(heading)
                || Math.abs((long) (stamp - sceneTime)) > 10000
                || (inputSeen && stamp - lastTimestamp <= 0)
                || pending.size() >= MAX_PENDING) return false;
        pending.addLast(new Frame(stamp, normalize(facing), normalize(heading), inputFlags & 3));
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
            if (activity == 0) rotation = frame.rotation();
            direction = frame.direction();
            inputFlags = frame.flags();
            if ((inputFlags & 2) != 0 && activity != INVENTORY) setActivity(INVENTORY, timestamp);
            else if ((inputFlags & 2) == 0 && activity == INVENTORY) setActivity(INVENTORY_END, timestamp);
            updateFlags();
        }
        if (now - lastInputAt > 500) {
            inputFlags = 0;
            if (activity == INVENTORY) setActivity(INVENTORY_END, timestamp);
            updateFlags();
        }
        moveTo(sceneTime);
    }

    private void moveTo(int target) {
        if (activity == INVENTORY_END && target - activityStarted >= INVENTORY_END_MILLIS) {
            int ended = activityStarted + INVENTORY_END_MILLIS;
            if (ended - timestamp > 0) timestamp = ended;
            setActivity(0, ended);
            updateFlags();
        }
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
    public int activity() { return activity; }
    public int activityStarted() { return activityStarted; }
    private void setActivity(int value, int started) {
        activity = value;
        activityStarted = started;
    }
    private void updateFlags() {
        flags = activity == 0 && (inputFlags & 1) != 0 ? 2 : 0;
    }
    private static float normalize(float angle) {
        return (float) Math.IEEEremainder(angle, Math.PI * 2);
    }
}
