package org.tortodrone.world;

import java.util.*;
import java.util.function.LongSupplier;

/**
 * Per-scene admission control for A* searches.
 *
 * <p>At most {@value #PER_TICK} searches start in one tick, and the searches admitted in
 * one tick share a {@value #SLICE_MILLIS} ms slice. A blocked search costs milliseconds,
 * so several actors reaching their repath deadline on the same tick used to spend the
 * whole cadence inside one scene tick. A denied request is remembered in a bounded queue
 * so a burst cannot grow without limit; the oldest deferred request is dropped (and
 * counted) when the queue is full. The entity retries on a later tick because a denied
 * search leaves its {@code nextPath} deadline untouched.
 */
final class ScenePathBudget {
    static final int PER_TICK = 4;
    static final int DEPTH = 128;
    static final long SLICE_MILLIS = 12;
    private static final long SLICE_NANOS = SLICE_MILLIS * 1_000_000L;

    private final LongSupplier clock;
    private final ArrayDeque<Integer> waiting = new ArrayDeque<>();
    private int stamp = Integer.MIN_VALUE, granted, dropped;
    private long sliceStarted;

    ScenePathBudget() {
        this(System::nanoTime);
    }

    ScenePathBudget(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    void begin(int stamp) {
        if (stamp == this.stamp) return;
        this.stamp = stamp;
        granted = 0;
        sliceStarted = 0;
        // Deferred ids are hints for the current burst, not persistent reservations.
        waiting.clear();
    }

    boolean acquire(int id) {
        waiting.removeIf(value -> value == id);
        if (granted < PER_TICK && sliceAvailable()) {
            if (granted == 0) sliceStarted = clock.getAsLong();
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

    /** The first search of a tick is always admitted; later ones must fit the slice.
     *  The clock is read on admission only, so the caller's search time is what the
     *  next admission observes. */
    private boolean sliceAvailable() {
        return granted == 0 || clock.getAsLong() - sliceStarted < SLICE_NANOS;
    }

    int granted() {
        return granted;
    }

    int queued() {
        return waiting.size();
    }

    int dropped() {
        return dropped;
    }

    boolean waiting(int id) {
        return waiting.contains(id);
    }
}
