package org.tortodrone.world;

import static org.tortodrone.support.TestWire.check;

import org.tortodrone.core.world.SceneClock;

/** 32-bit wire stamps stay ordered across the millisecond wrap via difference comparisons. */
public final class ClockWrapTest {
    public static void main(String[] args) throws Exception {
        check(
                SceneClock.reached(100, 100) && !SceneClock.before(100, 100),
                "a deadline is reached exactly at its stamp");
        check(!SceneClock.reached(99, 100) && SceneClock.before(99, 100), "a future deadline is not reached");
        check(SceneClock.reached(101, 100), "a past deadline stays reached");

        int wrappedNow = Integer.MIN_VALUE + 5, beforeWrap = Integer.MAX_VALUE - 5;
        check(SceneClock.reached(wrappedNow, beforeWrap), "a deadline set before the wrap is reached after it");
        check(!SceneClock.before(wrappedNow, beforeWrap), "the wrapped clock is no longer before that deadline");
        check(SceneClock.before(beforeWrap, Integer.MIN_VALUE + 5), "a deadline after the wrap is still in the future");
        check(SceneClock.later(beforeWrap, wrappedNow) == wrappedNow, "the later deadline is chosen across the wrap");
        check(SceneClock.later(wrappedNow, beforeWrap) == wrappedNow, "the later deadline is order independent");

        int first = SceneClock.now();
        Thread.sleep(2);
        int second = SceneClock.now();
        check(first > 0 && second > 0, "wire stamps stay positive at the process epoch");
        check(SceneClock.reached(second, first), "the live clock advances");
        System.out.println("PASS: scene clock is wrap-safe across the 32-bit millisecond boundary");
    }
}
