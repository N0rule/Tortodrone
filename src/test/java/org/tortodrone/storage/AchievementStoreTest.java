package org.tortodrone.storage;

import static org.tortodrone.support.TestWire.check;

import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.AccountStore;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.character.Knight;
import org.tortodrone.core.character.NativeAchievements;
import org.tortodrone.core.character.NativeStats;

/** Account-scoped achievement receipts and lifetime counters on disposable SQLite. */
public final class AchievementStoreTest {
    private static final ItemCatalog ITEMS = ItemCatalog.starter();

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("tortodrone-achievements-");
        try (var accounts = new AccountStore(home, false, ITEMS)) {
            accounts.create("AchievementOwner", "password-owner".toCharArray());
            accounts.create("AchievementPeer", "password-peer".toCharArray());
            var db = new Database(home, false);
            var owner = new CharacterStore(db, 1, "AchievementOwner");
            var peer = new CharacterStore(db, 2, "AchievementPeer");
            var knight = owner.create(knight("Achievement-Knight"));
            var peerKnight = peer.create(knight("Achievement-Peer"));
            long now = System.currentTimeMillis();

            var initial = owner.worldProfile(knight.name(), 1);
            var peerInitial = peer.worldProfile(peerKnight.name(), 1);
            check(initial.achievements().unlocked().isEmpty(), "a new account has no native achievements");
            var noUnlocks = db.write(c -> {
                AccountAchievements.increment(c, 1, NativeStats.ITEMS_CRAFTED, 1);
                var loaded = WorldProfiles.load(c, 1, knight.id(), 1);
                return AccountAchievements.reconcile(c, loaded, ITEMS, 0, now);
            });
            check(noUnlocks.achievements().unlocked().isEmpty(), "below-threshold counters do not invent achievements");

            var first = db.write(c -> {
                AccountAchievements.increment(c, 1, NativeStats.ITEMS_CRAFTED, 4);
                var loaded = WorldProfiles.load(c, 1, knight.id(), 1);
                return AccountAchievements.reconcile(c, loaded, ITEMS, 32, now);
            });
            check(
                    first.achievements()
                            .unlocked()
                            .equals(Map.of(0, now, NativeAchievements.ordinal("ITEMS_CRAFTED_1"), now)),
                    "flag 32 and five crafted items unlock exactly their native types");
            check(
                    first.achievements().counters().get(NativeStats.ITEMS_CRAFTED) == 5,
                    "craft counter persisted atomically");

            var reread = owner.worldProfile(knight.name(), 1);
            check(
                    reread.achievements().equals(first.achievements()),
                    "unlocks and counters survive a fresh profile load");

            var replay = db.write(c -> {
                var loaded = WorldProfiles.load(c, 1, knight.id(), 1);
                return AccountAchievements.reconcile(c, loaded, ITEMS, 32, now + 60_000);
            });
            check(
                    replay.achievements().unlocked().entrySet().stream().allMatch(e -> e.getValue() == now),
                    "a replay never replaces the original unlock timestamp");

            db.write(c -> {
                try (var s = c.createStatement()) {
                    s.execute("CREATE TRIGGER refuse_achievement BEFORE INSERT ON metadata "
                            + "WHEN NEW.key='account.achievement.1." + NativeAchievements.ordinal("ITEMS_CRAFTED_2")
                            + "' "
                            + "BEGIN SELECT RAISE(ABORT,'fixture'); END");
                }
                return null;
            });
            refused(() -> db.write(c -> {
                AccountAchievements.increment(c, 1, NativeStats.ITEMS_CRAFTED, 5);
                var loaded = WorldProfiles.load(c, 1, knight.id(), 1);
                return AccountAchievements.reconcile(c, loaded, ITEMS, 32, now);
            }));
            var rolledBack = owner.worldProfile(knight.name(), 1);
            check(rolledBack.equals(reread), "a failed receipt rolls the counter and every unlock back with it");
            db.write(c -> {
                try (var s = c.createStatement()) {
                    s.execute("DROP TRIGGER refuse_achievement");
                }
                return null;
            });

            var second = db.write(c -> {
                AccountAchievements.increment(c, 1, NativeStats.ITEMS_CRAFTED, 5);
                var loaded = WorldProfiles.load(c, 1, knight.id(), 1);
                return AccountAchievements.reconcile(c, loaded, ITEMS, 32, now);
            });
            check(
                    second.achievements().unlocked().containsKey(NativeAchievements.ordinal("ITEMS_CRAFTED_2")),
                    "the threshold unlocks once the receipt can commit");
            check(
                    peer.worldProfile(peerKnight.name(), 1).equals(peerInitial),
                    "another account's achievements and counters are untouched");

            long beforeDelete = second.achievements().unlocked().size();
            check(beforeDelete == 3, "three receipts before account deletion");
            db.delete("AchievementOwner", 1);
            check(
                    db.read(c -> AccountAchievements.load(c, 1)).unlocked().isEmpty(),
                    "deleting an account removes its achievement rows");
        } finally {
            try (var paths = Files.walk(home)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.println("PASS: native achievement receipts, counters, rollback and account deletion");
    }

    private static Knight knight(String name) {
        return new Knight(
                0,
                name,
                "Gear/Helm/Spiral Round Helm (Red)",
                "Gear/Armor/Spiral Flak Jacket (Red)",
                "Helm/Top/Headband/Headband",
                "Helm/Back/Plume/Plume",
                "Red",
                7,
                4,
                29,
                0);
    }

    private static void refused(Action action) throws Exception {
        try {
            action.run();
            throw new AssertionError("expected the operation to be refused");
        } catch (java.io.IOException expected) {
        }
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }
}
