package org.tortodrone.support;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Runs standalone unit programs in one JVM to avoid starting a JVM for every small test. */
public final class StandaloneTestSuite {
    private StandaloneTestSuite() {}

    public static void main(String[] tests) {
        if (tests.length == 0) throw new IllegalArgumentException("Select at least one standalone test class");
        System.out.printf(
                Locale.ROOT,
                "Run %s of %s programs (shard %s)%n",
                tests.length,
                System.getProperty("unitPrograms", String.valueOf(tests.length)),
                System.getProperty("unitShard", "1/1"));
        var failures = new ArrayList<String>();
        var durations = new LinkedHashMap<String, Long>();
        long started = System.nanoTime();
        for (String name : tests) {
            System.out.println("== " + name);
            long before = System.nanoTime();
            try {
                Class.forName(name).getMethod("main", String[].class).invoke(null, (Object) new String[0]);
            } catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                failures.add(name + ": " + cause);
                cause.printStackTrace(System.err);
            } catch (ReflectiveOperationException failure) {
                failures.add(name + ": " + failure);
                failure.printStackTrace(System.err);
            } finally {
                durations.put(name, System.nanoTime() - before);
            }
        }
        System.out.printf(Locale.ROOT, "Unit time: %.2fs; slowest programs:%n", (System.nanoTime() - started) / 1e9);
        // Every program, not the top five: a shard split or a prune needs the whole
        // distribution, and the log is the only place it is visible.
        durations.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(entry ->
                        System.out.printf(Locale.ROOT, "  %.2fs %s%n", entry.getValue() / 1e9, entry.getKey()));
        String timings = System.getProperty("unitTimings", "");
        if (!timings.isEmpty()) {
            var file = java.nio.file.Path.of(timings);
            try {
                java.nio.file.Files.createDirectories(file.getParent());
                var lines = new ArrayList<String>();
                // Sorted, so the record stays diffable when it is copied into
                // scripts/tests/unit-timings.properties as the checked-in baseline.
                durations.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(entry -> lines.add(entry.getKey() + "=" + entry.getValue()));
                java.nio.file.Files.write(file, lines);
            } catch (java.io.IOException ignored) {
                // A missing record only falls the next sharded run back to index modulo.
            }
        }
        if (!failures.isEmpty()) throw new AssertionError("Unit test failures: " + String.join("; ", failures));
        System.out.println("PASS: " + tests.length + " standalone unit programs");
    }
}
