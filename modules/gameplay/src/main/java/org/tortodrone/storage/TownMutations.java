package org.tortodrone.storage;

import java.io.IOException;
import java.sql.*;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.character.WorldProfile;

/** The authenticated town mutation wrapper shared by every feature store: one write
 *  transaction, expiry reconciliation, touch, and the achievement pass before publish. */
final class TownMutations {
    @FunctionalInterface
    interface Mutation {
        Object run(Connection c, WorldProfile profile) throws SQLException, IOException;
    }

    @FunctionalInterface
    interface Transaction {
        TownStore.Result run(Database.Work<TownStore.Result> work) throws IOException;
    }

    private TownMutations() {}

    static TownStore.Result change(
            Database database, ItemCatalog items, int account, int knight, long auth, Mutation mutation)
            throws IOException {
        return change(database, items, account, knight, auth, mutation, database::write);
    }

    /** Scene-locked item use and revive: refuse before opening a connection when the
     *  shared writer is still busy after the queue budget. */
    static TownStore.Result changeBounded(
            Database database, ItemCatalog items, int account, int knight, long auth, Mutation mutation)
            throws IOException {
        return change(
                database,
                items,
                account,
                knight,
                auth,
                mutation,
                work -> database.writeBounded(Database.LOCKED_WRITE_TIMEOUT_MILLIS, work));
    }

    private static TownStore.Result change(
            Database database,
            ItemCatalog items,
            int account,
            int knight,
            long auth,
            Mutation mutation,
            Transaction transaction)
            throws IOException {
        return transaction.run(c -> {
            var profile = WorldProfiles.load(c, account, knight, auth);
            Object result = mutation.run(c, profile);
            InventoryExpiry.reconcile(c, profile.worldId(), System.currentTimeMillis());
            TownLedger.touch(c, profile.worldId());
            var updated = WorldProfiles.load(c, account, knight, auth);
            updated = AccountAchievements.reconcile(
                    c, updated, items, updated.progress().tutorialComplete() ? 32 : 0, System.currentTimeMillis());
            return new TownStore.Result(updated, result);
        });
    }
}
