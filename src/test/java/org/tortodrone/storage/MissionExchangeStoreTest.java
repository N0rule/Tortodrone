package org.tortodrone.storage;

import static org.tortodrone.support.TestWire.check;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.core.character.Knight;
import org.tortodrone.core.character.WorldProfile;
import org.tortodrone.resources.ContentManager;

/** Exchange turn-in settlement only: ingredient verification, atomic consume/pay and the MEDIUM row. */
public final class MissionExchangeStoreTest {
    public static void main(String[] args) throws Exception {
        var content = ContentManager.load(Path.of("."));
        Path home = Files.createTempDirectory("tortodrone-exchange-missions-");
        try {
            var db = new Database(home, true);
            var protection = new Passwords.Protection(new byte[16], new byte[32]);
            int account = db.create("ExchangeOwner", protection, false),
                    foreign = db.create("ExchangePeer", protection, false);
            var owner = new CharacterStore(db, account, "ExchangeOwner", content.items());
            var peer = new CharacterStore(db, foreign, "ExchangePeer", content.items());
            var knight = owner.createForOpening(knight("Exchange-Knight"), 1);
            var other = peer.createForOpening(knight("Exchange-Peer"), 1);
            var seed = owner.worldProfile(knight.name(), 1);
            int world = seed.worldId();
            // Reach 2-2 and complete the prerequisite without going through the alchemy floor.
            db.write(c -> {
                try (var s = c.prepareStatement(
                        "INSERT INTO metadata(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
                    s.setString(1, "knight.progress." + world + ".rank");
                    s.setString(2, "22");
                    s.executeUpdate();
                    s.setString(1, "world." + world + ".mission.alchemy_beginners");
                    s.setString(2, "1:");
                    s.executeUpdate();
                }
                return null;
            });
            var store = owner.services().openingMissions(content.town(), content.missions());
            var inventory = new TownStore(db, content.items(), content.town());
            var seeded = owner.worldProfile(knight.name(), 1);
            long hot = db.write(c -> inventory.create(c, seeded, "Weapon/Sword/Hot Edge", 1, false, 0, 0, List.of()));
            long frost =
                    db.write(c -> inventory.create(c, seeded, "Weapon/Handgun/Frost Gun", 1, false, 0, 0, List.of()));
            long spare = db.write(c -> inventory.create(c, seeded, "Material/Sharp Fang", 1, false, 0, 0, List.of()));
            var before = owner.worldProfile(knight.name(), 1);
            var peerBefore = peer.worldProfile(other.name(), 1);
            refused(() ->
                    store.exchange(foreign, other.id(), 1, peerBefore, "time_to_crafty", new long[] {hot, frost}));
            refused(() -> store.exchange(account, knight.id(), 1, before, "time_to_crafty", new long[] {hot, spare}));
            refused(() -> store.exchange(account, knight.id(), 1, before, "time_to_crafty", new long[] {hot, hot}));
            refused(() -> store.exchange(account, knight.id(), 1, before, "time_to_crafty", new long[] {hot}));
            check(
                    owner.worldProfile(knight.name(), 1).equals(before),
                    "a refused exchange consumes nothing and writes no receipt");
            var settled = store.exchange(account, knight.id(), 1, before, "time_to_crafty", new long[] {hot, frost});
            var after = settled.profile();
            check(
                    settled.first()
                            && settled.completion().missionId().equals("time_to_crafty")
                            && settled.completion().difficulty().equals("MEDIUM"),
                    "exchange completes once with the MEDIUM row");
            check(
                    after.items().stream().noneMatch(i -> i.id() == hot || i.id() == frost),
                    "both ingredients are consumed");
            check(
                    count(after, "Weapon/Sword/Hot Edge") == 1 && count(after, "Weapon/Handgun/Frost Gun") == 1,
                    "the reward grants replace the consumed weapons");
            check(
                    after.items().stream()
                            .filter(i -> i.config().equals("Weapon/Sword/Hot Edge") && i.id() != hot)
                            .allMatch(i -> i.bound() && i.variants().size() == 1),
                    "exchange rewards keep their explicit variant and binding");
            check(
                    after.progress().prestige()
                                    == before.progress().prestige()
                                            + content.missions().prestige("time_to_crafty")
                            && after.revision() == before.revision() + 1,
                    "exchange credits catalogue prestige in the same transaction");
            check(
                    store.load(account, knight.id(), 1).get("time_to_crafty").equals(settled.completion()),
                    "the receipt survives a reload");
            refused(() -> store.exchange(account, knight.id(), 1, after, "time_to_crafty", new long[] {hot, frost}));
            check(
                    owner.worldProfile(knight.name(), 1).equals(after)
                            && peer.worldProfile(other.name(), 1).equals(peerBefore),
                    "a repeated exchange and foreign attempts leave state intact");
            db.write(c -> {
                try (var s = c.prepareStatement("UPDATE metadata SET value=52 WHERE key=?")) {
                    s.setString(1, "knight.progress." + world + ".rank");
                    s.executeUpdate();
                }
                return null;
            });
            var talkBefore = owner.worldProfile(knight.name(), 1);
            var talk = store.complete(account, knight.id(), 1, talkBefore, "another_trace", null);
            check(
                    talk.first()
                            && talk.completion().difficulty() == null
                            && talk.profile().progress().prestige()
                                    == talkBefore.progress().prestige()
                                            + content.missions().prestige("another_trace")
                            && talk.profile().progress().openingStage()
                                    == talkBefore.progress().openingStage(),
                    "a non-opening conversation mission completes by id without touching the opening checkpoint");
        } finally {
            try (var paths = Files.walk(home)) {
                for (var p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
        System.out.println(
                "PASS: exchange ingredient verification, generic conversation completion, atomic consume/pay, MEDIUM receipt, explicit variants, replay and ownership refusal");
    }

    private static int count(WorldProfile p, String config) {
        return p.items().stream()
                .filter(i -> i.config().equals(config))
                .mapToInt(WorldProfile.Item::count)
                .sum();
    }

    private static Knight knight(String name) {
        return new Knight(
                0,
                name,
                "Gear/Helm/Spiral Sallet (Blue)",
                "Gear/Armor/Spiral Culet (Blue)",
                null,
                null,
                "Blue",
                1,
                2,
                0,
                0);
    }

    private interface Action {
        void run() throws Exception;
    }

    private static void refused(Action action) throws Exception {
        try {
            action.run();
            throw new AssertionError("Invalid exchange accepted");
        } catch (IOException expected) {
        }
    }
}
