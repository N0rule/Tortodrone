package org.tortodrone.support;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

/** Runs standalone unit programs in one JVM to avoid starting a JVM for every small test. */
public final class StandaloneTestSuite {
    private StandaloneTestSuite() { }

    public static void main(String[] tests) {
        var failures = new ArrayList<String>();
        for (String name : tests) {
            System.out.println("== " + name);
            try {
                Class.forName(name).getMethod("main", String[].class).invoke(null, (Object) new String[0]);
            } catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                failures.add(name + ": " + cause);
                cause.printStackTrace(System.err);
            } catch (ReflectiveOperationException failure) {
                failures.add(name + ": " + failure);
                failure.printStackTrace(System.err);
            }
        }
        if (!failures.isEmpty()) throw new AssertionError("Unit test failures: " + String.join("; ", failures));
        System.out.println("PASS: " + tests.length + " standalone unit programs");
    }
}
