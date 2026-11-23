package org.tortodrone.core;

/**
 * Written monitor order shared by the server, world and storage owners.
 *
 * <p>Ranks are acquired in increasing order. The check is a development guard used by the
 * owning classes around their own monitor: nested acquisition of a lower rank raises
 * {@link IllegalStateException}, which focused tests exercise directly. Storage is normally
 * outside every runtime monitor; the named scene commit paths intentionally acquire it while
 * the scene is held, and those paths are documented in {@code docs/architecture.md}.
 */
public final class LockOrder {
    public static final int CONTROL = 10;
    public static final int REGISTRY = 20;
    public static final int SCENE = 30;
    public static final int CHARACTER = 40;
    public static final int OUTPUT = 50;
    public static final int STORAGE = 60;

    private static final ThreadLocal<int[]> STACK = ThreadLocal.withInitial(() -> new int[32]);
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private LockOrder() {}

    public static void enter(int rank) {
        int depth = DEPTH.get();
        int[] stack = STACK.get();
        if (depth > 0 && rank < stack[depth - 1])
            throw new IllegalStateException("Lock-order inversion: " + name(stack[depth - 1]) + " -> " + name(rank));
        if (depth == stack.length) {
            int[] grown = new int[stack.length * 2];
            System.arraycopy(stack, 0, grown, 0, stack.length);
            STACK.set(grown);
            stack = grown;
        }
        stack[depth] = rank;
        DEPTH.set(depth + 1);
    }

    public static void exit() {
        int depth = DEPTH.get();
        if (depth > 0) DEPTH.set(depth - 1);
    }

    public static int depth() {
        return DEPTH.get();
    }

    public static String name(int rank) {
        return switch (rank) {
            case CONTROL -> "control";
            case REGISTRY -> "registry";
            case SCENE -> "scene";
            case CHARACTER -> "character";
            case OUTPUT -> "output";
            case STORAGE -> "storage";
            default -> "rank-" + rank;
        };
    }
}
