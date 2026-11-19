package org.tortodrone.world;

import java.util.*;

/**
 * Per-scene admission control for A* searches.
 *
 * <p>At most {@value #PER_TICK} searches start in one tick. A denied request is remembered in
 * a bounded queue so a burst cannot grow without limit; the oldest deferred request is dropped
 * (and counted) when the queue is full. The entity retries on a later tick because a denied
 * search leaves its {@code nextPath} deadline untouched.
 */
final class ScenePathBudget {
    static final int PER_TICK = 4;
    static final int DEPTH = 128;

    private final ArrayDeque<Integer> waiting = new ArrayDeque<>();
    private int stamp = Integer.MIN_VALUE, granted, dropped;

    void begin(int stamp) {
        if (stamp == this.stamp) return;
        this.stamp = stamp;
        granted = 0;
        // Deferred ids are hints for the current burst, not persistent reservations.
        waiting.clear();
    }

    boolean acquire(int id) {
        waiting.removeIf(value -> value == id);
        if (granted < PER_TICK) {
            granted++;
            return true;
        }
        if (waiting.size() >= DEPTH) {
            waiting.removeFirst();
            dropped++;
        }
        waiting.addLast(id);
        return false;
    }

    int granted() { return granted; }
    int queued() { return waiting.size(); }
    int dropped() { return dropped; }
    boolean waiting(int id) { return waiting.contains(id); }
}
