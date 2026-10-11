import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Deterministic lifecycle checks for the standalone stock-tool child processes. */
public final class ProcessCleanupTest {
    private enum Scenario { GRACEFUL, FORCED, TIMED_WAIT_INTERRUPTED, FORCED_WAIT_INTERRUPTED, PRE_INTERRUPTED, ALREADY_EXITED }
    private enum EntryPoint { STOCK_TOOLS, CLIENT_WORLD, COMBAT_RENDERING }

    public static void main(String[] args) throws Exception {
        for (EntryPoint entryPoint : EntryPoint.values()) {
            for (Scenario scenario : Scenario.values()) run(entryPoint, scenario);
        }
        runMultiChild(EntryPoint.CLIENT_WORLD);
        runMultiChild(EntryPoint.COMBAT_RENDERING);
        System.out.println("PASS: all process cleanup paths wait for child exit and preserve interrupts");
    }

    private static void run(EntryPoint entryPoint, Scenario scenario) throws Exception {
        ScriptedProcess child = new ScriptedProcess(scenario);
        clearInterrupt();
        if (scenario == Scenario.PRE_INTERRUPTED) Thread.currentThread().interrupt();
        try {
            invokeCleanup(entryPoint, child);
            check(!child.isAlive(), entryPoint + " left child alive for " + scenario);
            boolean expectsInterrupt = scenario == Scenario.TIMED_WAIT_INTERRUPTED
                || scenario == Scenario.FORCED_WAIT_INTERRUPTED || scenario == Scenario.PRE_INTERRUPTED;
            check(Thread.currentThread().isInterrupted() == expectsInterrupt,
                entryPoint + " has wrong interrupt status for " + scenario);
            if (scenario == Scenario.ALREADY_EXITED) {
                check(!child.destroyed && !child.forced, entryPoint + " touched an exited child");
            } else {
                check(child.destroyed, entryPoint + " did not request graceful termination");
            }
            if (scenario == Scenario.FORCED || scenario == Scenario.TIMED_WAIT_INTERRUPTED
                    || scenario == Scenario.FORCED_WAIT_INTERRUPTED) {
                check(child.forced, entryPoint + " did not request forcible termination for " + scenario);
            }
            if (scenario == Scenario.FORCED_WAIT_INTERRUPTED) {
                check(child.blockingInterruptions == 2, "scripted forced-wait interruptions were not consumed");
            }
        } finally {
            clearInterrupt();
            clearChildList(entryPoint);
        }
    }

    private static void invokeCleanup(EntryPoint entryPoint, ScriptedProcess child) throws Exception {
        String className = switch (entryPoint) {
            case STOCK_TOOLS -> "StockTools";
            case CLIENT_WORLD -> "CheckClientWorld";
            case COMBAT_RENDERING -> "CheckCombatRendering";
        };
        Class<?> owner = Class.forName(className);
        if (entryPoint == EntryPoint.STOCK_TOOLS) {
            Method stop = owner.getDeclaredMethod("stop", Process.class);
            stop.setAccessible(true);
            call(stop, null, child);
            return;
        }
        Field children = owner.getDeclaredField("CHILDREN");
        children.setAccessible(true);
        @SuppressWarnings("unchecked") List<Process> processes = (List<Process>) children.get(null);
        processes.clear();
        processes.add(child);
        Method stopChildren = owner.getDeclaredMethod("stopChildren");
        stopChildren.setAccessible(true);
        call(stopChildren, null);
    }

    private static void runMultiChild(EntryPoint entryPoint) throws Exception {
        ScriptedProcess interrupted = new ScriptedProcess(Scenario.FORCED_WAIT_INTERRUPTED);
        ScriptedProcess remaining = new ScriptedProcess(Scenario.FORCED);
        clearInterrupt();
        try {
            invokeChildrenCleanup(entryPoint, List.of(remaining, interrupted));
            check(!interrupted.isAlive() && !remaining.isAlive(), entryPoint + " left a child alive after interrupted wait");
            check(interrupted.forced && remaining.forced, entryPoint + " did not force-stop both children");
            check(interrupted.blockingInterruptions == 2, entryPoint + " did not consume scripted interruptions");
            check(Thread.currentThread().isInterrupted(), entryPoint + " did not restore interruption after draining children");
        } finally {
            clearInterrupt();
            clearChildList(entryPoint);
        }
    }

    private static void invokeChildrenCleanup(EntryPoint entryPoint, List<Process> children) throws Exception {
        String className = entryPoint == EntryPoint.CLIENT_WORLD ? "CheckClientWorld" : "CheckCombatRendering";
        Class<?> owner = Class.forName(className);
        Field field = owner.getDeclaredField("CHILDREN");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") List<Process> processes = (List<Process>) field.get(null);
        processes.clear();
        processes.addAll(children);
        Method stopChildren = owner.getDeclaredMethod("stopChildren");
        stopChildren.setAccessible(true);
        call(stopChildren, null);
    }

    private static void call(Method method, Object receiver, Object... arguments) throws Exception {
        try { method.invoke(receiver, arguments); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new AssertionError(cause);
        }
    }

    private static void clearChildList(EntryPoint entryPoint) throws Exception {
        if (entryPoint == EntryPoint.STOCK_TOOLS) return;
        String className = entryPoint == EntryPoint.CLIENT_WORLD ? "CheckClientWorld" : "CheckCombatRendering";
        Field children = Class.forName(className).getDeclaredField("CHILDREN");
        children.setAccessible(true);
        @SuppressWarnings("unchecked") List<Process> processes = (List<Process>) children.get(null);
        processes.clear();
    }

    private static void clearInterrupt() { Thread.interrupted(); }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class ScriptedProcess extends Process {
        private final Scenario scenario;
        private boolean alive;
        private boolean destroyed;
        private boolean forced;
        private int blockingInterruptions;

        ScriptedProcess(Scenario scenario) {
            this.scenario = scenario;
            alive = scenario != Scenario.ALREADY_EXITED;
        }

        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }

        @Override public int waitFor() throws InterruptedException {
            if (scenario == Scenario.FORCED_WAIT_INTERRUPTED && blockingInterruptions < 2) {
                blockingInterruptions++;
                throw new InterruptedException("scripted blocking wait interruption");
            }
            alive = false;
            return 0;
        }

        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            if (scenario == Scenario.TIMED_WAIT_INTERRUPTED)
                throw new InterruptedException("scripted timed wait interruption");
            if (scenario == Scenario.GRACEFUL) {
                alive = false;
                return true;
            }
            return false;
        }

        @Override public int exitValue() {
            if (alive) throw new IllegalThreadStateException("process still running");
            return 0;
        }

        @Override public void destroy() { destroyed = true; }

        @Override public Process destroyForcibly() {
            forced = true;
            if (scenario != Scenario.FORCED_WAIT_INTERRUPTED) alive = false;
            return this;
        }

        @Override public boolean isAlive() { return alive; }
    }
}
