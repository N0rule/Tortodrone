package org.tortodrone.support;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Runs standalone unit programs in one JVM to avoid starting a JVM for every small test. */
public final class StandaloneTestSuite {
    private StandaloneTestSuite() { }

    public static void main(String[] tests) {
        if(tests.length==0)throw new IllegalArgumentException("Select at least one standalone test class");
        var failures = new ArrayList<String>();
        var durations = new LinkedHashMap<String,Long>();
        long started=System.nanoTime();
        for (String name : tests) {
            System.out.println("== " + name);
            long before=System.nanoTime();
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
                durations.put(name,System.nanoTime()-before);
            }
        }
        System.out.printf(Locale.ROOT,"Unit time: %.2fs; slowest programs:%n",(System.nanoTime()-started)/1e9);
        durations.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()).limit(5)
            .forEach(entry->System.out.printf(Locale.ROOT,"  %.2fs %s%n",entry.getValue()/1e9,entry.getKey()));
        if (!failures.isEmpty()) throw new AssertionError("Unit test failures: " + String.join("; ", failures));
        System.out.println("PASS: " + tests.length + " standalone unit programs");
    }
}
