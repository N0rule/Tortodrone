package org.tortodrone.storage;

import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.AccountStore;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.character.Knight;
import org.tortodrone.core.character.NativeAchievements;
import org.tortodrone.core.character.NativeStats;
import org.tortodrone.core.town.TownCatalog;
import static org.tortodrone.support.TestWire.check;

/** Native capsule lifetime counters credited by the persistent town transaction. */
public final class CapsuleStatTest {
    private static final ItemCatalog ITEMS = ItemCatalog.starter();

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("tortodrone-capsules-");
        try (var accounts = new AccountStore(home, false, ITEMS)) {
            accounts.create("CapsuleOwner", "password-owner".toCharArray());
            accounts.create("CapsulePeer", "password-peer".toCharArray());
            var db = new Database(home, false);
            var owner = new CharacterStore(db, 1, "CapsuleOwner");
            var peer = new CharacterStore(db, 2, "CapsulePeer");
            var knight = owner.create(knight("Capsule-Knight"));
            var peerKnight = peer.create(knight("Capsule-Peer"));
            var peerInitial = peer.worldProfile(peerKnight.name(), 1);
            var town = new TownStore(db, ITEMS, TownCatalog.empty());

            for (int i = 0; i < 9; i++)
                town.stats(1, knight.id(), 1, Map.of("HEALTH_CAPSULES_USED", 1));
            var nine = owner.worldProfile(knight.name(), 1);
            check(nine.achievements().counters().get(NativeStats.HEALTH_CAPSULES_USED) == 9,
                "nine native health capsules are counted");
            check(!nine.achievements().unlocked().containsKey(NativeAchievements.ordinal("HEALTH_CAPSULES_USED_1")),
                "nine capsules remain below the native threshold");

            refused(() -> town.stats(1, knight.id(), 1, Map.of("NOT_A_STAT", 1)));
            refused(() -> town.stats(1, knight.id(), 1, Map.of("HEALTH_CAPSULES_USED", 0)));
            refused(() -> town.stats(1, knight.id(), 1, Map.of()));
            check(owner.worldProfile(knight.name(), 1).equals(nine),
                "an unknown or invalid counter refuses without a durable change");

            db.write(c -> {
                try (var s = c.createStatement()) {
                    s.execute("CREATE TRIGGER refuse_capsule_achievement BEFORE INSERT ON metadata "
                        + "WHEN NEW.key='account.achievement.1." + NativeAchievements.ordinal("HEALTH_CAPSULES_USED_1") + "' "
                        + "BEGIN SELECT RAISE(ABORT,'fixture'); END");
                }
                return null;
            });
            refused(() -> town.stats(1, knight.id(), 1, Map.of("HEALTH_CAPSULES_USED", 1)));
            check(owner.worldProfile(knight.name(), 1).equals(nine),
                "a refused achievement receipt rolls the capsule counter back with it");
            db.write(c -> {
                try (var s = c.createStatement()) { s.execute("DROP TRIGGER refuse_capsule_achievement"); }
                return null;
            });

            var tenth = town.stats(1, knight.id(), 1, Map.of("HEALTH_CAPSULES_USED", 1));
            check(tenth.profile().achievements().counters().get(NativeStats.HEALTH_CAPSULES_USED) == 10,
                "the tenth capsule commits the lifetime counter");
            check(tenth.profile().achievements().unlocked().containsKey(NativeAchievements.ordinal("HEALTH_CAPSULES_USED_1")),
                "ten health capsules unlock the native achievement in the same transaction");

            for (int i = 0; i < 10; i++)
                town.stats(1, knight.id(), 1, Map.of("REMEDY_CAPSULES_USED", 1));
            var remedy = owner.worldProfile(knight.name(), 1);
            check(remedy.achievements().counters().get(NativeStats.REMEDY_CAPSULES_USED) == 10,
                "remedy capsules keep their own counter");
            check(remedy.achievements().unlocked().containsKey(NativeAchievements.ordinal("REMEDY_CAPSULES_USED_1")),
                "ten remedy capsules unlock their native achievement");

            for (int i = 0; i < 25; i++)
                town.stats(1, knight.id(), 1, Map.of("LUCK_POTIONS_USED", 1));
            check(owner.worldProfile(knight.name(), 1).achievements().unlocked().keySet().stream()
                    .noneMatch(type -> NativeAchievements.name(type).equals("CASINO_SPIN_1")),
                "a counter with no native predicate never invents an achievement");

            check(peer.worldProfile(peerKnight.name(), 1).equals(peerInitial),
                "another account's counters and achievements are untouched");

            db.delete("CapsuleOwner", 1);
            check(db.read(c -> AccountAchievements.load(c, 1)).counters().isEmpty(),
                "deleting an account removes its lifetime counters");
        } finally {
            try (var paths = Files.walk(home)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.println("PASS: native capsule counters, achievement reconciliation, rollback and account deletion");
    }

    private static Knight knight(String name) {
        return new Knight(0, name, "Gear/Helm/Spiral Round Helm (Red)",
            "Gear/Armor/Spiral Flak Jacket (Red)", "Helm/Top/Headband/Headband",
            "Helm/Back/Plume/Plume", "Red", 7, 4, 29, 0);
    }

    private static void refused(Action action) throws Exception {
        try {
            action.run();
            throw new AssertionError("expected the operation to be refused");
        } catch (java.io.IOException expected) { }
    }

    @FunctionalInterface private interface Action { void run() throws Exception; }
}
