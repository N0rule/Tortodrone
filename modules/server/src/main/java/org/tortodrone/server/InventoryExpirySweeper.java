package org.tortodrone.server;

import java.util.concurrent.*;
import org.tortodrone.account.AccountStore;

/** Reclaim expired inventory on its own schedule, outside every session and scene monitor.
 *
 *  <p>The old write path pruned globally before each caller's work, so every write paid the
 *  sweep. Reads already hide an item whose deadline passed; this daemon owns the durable
 *  deletion and the revision publication for worlds no observer currently polls. */
final class InventoryExpirySweeper implements AutoCloseable {
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "InventoryExpiry");
        t.setDaemon(true);
        return t;
    });

    InventoryExpirySweeper(AccountStore accounts, ServerLog log) {
        worker.scheduleWithFixedDelay(
                () -> {
                    try {
                        accounts.sweepExpiredItems();
                    } catch (Exception failure) {
                        log.warn("Inventory expiry failed: " + failure.getMessage());
                    }
                },
                0,
                30,
                TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(10, TimeUnit.SECONDS)) worker.shutdownNow();
        } catch (InterruptedException interrupted) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
