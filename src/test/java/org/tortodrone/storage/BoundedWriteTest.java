package org.tortodrone.storage;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.tortodrone.support.TestWire.check;

/** The bounded writer handoff used by the scene/party commit-under-lock paths. */
public final class BoundedWriteTest {
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("tortodrone-bounded-");
        try {
            var db = new Database(home.resolve("data"), false);

            // A task that has started is never abandoned: the caller waits for the real outcome.
            String started = db.writeBounded(25, connection -> { pause(150); return "committed"; });
            check(started.equals("committed"), "a started bounded write completes even past the queue deadline");

            CountDownLatch running = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicBoolean refusedWorkRan = new AtomicBoolean();
            var blocker = Thread.ofPlatform().start(() -> {
                try {
                    db.write(connection -> { running.countDown(); await(release); return null; });
                } catch (IOException ignored) { }
            });
            running.await();
            long start = System.nanoTime();
            boolean refused = false;
            try {
                db.writeBounded(100, connection -> { refusedWorkRan.set(true); return "late"; });
            } catch (IOException expected) {
                refused = expected.getMessage().contains("Storage busy");
            }
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
            check(refused, "a queued bounded write is refused when the writer misses its deadline");
            check(elapsedMillis < 1_000, "the refusal returns near the deadline instead of waiting for the writer");
            check(!refusedWorkRan.get(), "the refused write never runs");
            release.countDown();
            blocker.join();

            check("after".equals(db.writeBounded(1_000, connection -> "after")),
                "the writer remains usable after a bounded refusal");
            db.close();
            System.out.println("PASS: bounded writer handoff completes started work, refuses queued work and keeps the writer usable");
        } finally {
            try (var paths = Files.walk(home)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void pause(long millis) throws IOException {
        try { Thread.sleep(millis); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", interrupted);
        }
    }
    private static void await(CountDownLatch latch) throws IOException {
        try { latch.await(); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", interrupted);
        }
    }
}
