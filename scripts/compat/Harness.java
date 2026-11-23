import java.io.ByteArrayOutputStream;
import java.util.Objects;

/** Shared assertions and native frame helpers for the read-only client probes in this directory.
 * Keep this file dependency-free so a probe can still be launched with
 * {@code java scripts/compat/SomeProbe.java}. */
public final class Harness {
    private Harness() {}
    /** Fail the probe when a required behavior is absent. */
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    /** Fail the probe when two values differ, naming the compared fact. */
    static void eq(String name, Object actual, Object expected) {
        if (!Objects.equals(actual, expected))
            throw new AssertionError(name + " differs: " + actual + " != " + expected);
    }
    /** Append a length-prefixed native frame, dropping its four-byte header. */
    static void append(ByteArrayOutputStream out, byte[] frame) {
        out.write(frame, 4, frame.length - 4);
    }
}
