package org.tortodrone.core.world;

/**
 * Monotonic server scene timestamps shared by instance transitions.
 *
 * <p>The wire carries a 32-bit millisecond stamp, so the value wraps after roughly 24.8 days.
 * Every duration in the simulation is far shorter than half that range, which makes 32-bit
 * subtraction wrap-safe. Callers must therefore compare deadlines with {@link #reached},
 * {@link #before} and {@link #later} instead of relational operators on the raw stamp;
 * the 64-bit {@link #millis()} value stays available for measurements and logs.
 */
public final class SceneClock {
    private static final long START = System.nanoTime();
    private SceneClock() { }

    /** 64-bit monotonic milliseconds since process start. */
    public static long millis() { return (System.nanoTime() - START) / 1_000_000L; }

    /** Wire-visible stamp; never zero so it can stay a sentinel for "unset". */
    public static int now() {
        int value = (int) millis();
        return value == 0 ? 1 : value;
    }

    /** Wrap-safe elapsed time from {@code earlier} to {@code later}. */
    public static int elapsed(int earlier, int later) { return later - earlier; }

    /** True once the clock has reached the deadline (inclusive). */
    public static boolean reached(int now, int deadline) { return now - deadline >= 0; }

    /** True while the clock is still before the deadline (exclusive). */
    public static boolean before(int now, int deadline) { return now - deadline < 0; }

    /** Wrap-safe later of two nearby deadlines. */
    public static int later(int left, int right) { return left - right >= 0 ? left : right; }
}
