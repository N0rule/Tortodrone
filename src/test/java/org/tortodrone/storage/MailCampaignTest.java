package org.tortodrone.storage;

import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.support.*;
import static org.tortodrone.support.TestWire.check;
import org.tortodrone.core.character.Knight;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.town.AuctionState;
import org.tortodrone.core.town.TownCatalog;

/** Administrative mail campaigns: literal text, gifts, windows, retries, late knights and upgrade. */
public final class MailCampaignTest {
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("tortodrone-mail-campaign-");
        try {
            var db = new Database(home, true);
            var protection = new Passwords.Protection(new byte[16], new byte[32]);
            int aliceAccount = db.create("Alice", protection, false);
            int bobAccount = db.create("Bob", protection, false);
            var items = ItemCatalog.load(ContentFixture.path("items/catalog.json"));
            var rules = org.tortodrone.core.town.TownCatalog.load(ContentFixture.path("town/catalog.json"));
            var aliceKnight = new CharacterStore(db, aliceAccount, "Alice", items).create(knight("Alice-Knight"));
            new CharacterStore(db, bobAccount, "Bob", items).create(knight("Bob-Knight"));
            int alice = db.worldProfile(aliceAccount, aliceKnight.id(), 1).worldId();
            var house = new AuctionHouse(db, items, rules);
            var campaigns = house.campaigns();
            long now = System.currentTimeMillis();

            var gift = new MailCampaignStore.Gift("Material/Gel Drop", 2, false);
            var saved = campaigns.save(0, 0, now - 60_000, now + 86_400_000L, 6, null,
                "Supply drop", "Two gels for the road.", 25, 5, List.of(gift));
            check(saved.id() > 0 && saved.enabled() && saved.delivered() == 0, "a campaign is saved with no deliveries");
            check(saved.subject().equals("Supply drop") && saved.body().equals("Two gels for the road."), "literal staff text survives unchanged");

            check(campaigns.deliver(alice), "delivery reports new mail");
            var letter = inbox(db, alice).stream().filter(m -> m.subject().endsWith("Supply drop")).findFirst().orElseThrow();
            check(letter.subject().equals("%uplink:~Supply drop") && letter.body().equals("%uplink:~Two gels for the road."), "staff text is stored as a tainted literal inside the uplink bundle");
            check(letter.sender().equals(" 6") && letter.nativeType() == 3, "system sender and SYSTEM mail type");
            check(letter.crowns() == 25 && letter.energy() == 5 && letter.items().size() == 1 && letter.items().getFirst().count() == 2, "crowns, energy and the gift stack travel with the letter");
            check(letter.items().getFirst().expiresAt() == null, "staff gifts carry no item timer before they are claimed");

            check(!campaigns.deliver(alice) && inbox(db, alice).stream().filter(m -> m.subject().endsWith("Supply drop")).count() == 1,
                "a second visit never duplicates the gift");

            int laterAccount = db.create("Later", protection, false);
            var laterKnight = new CharacterStore(db, laterAccount, "Later", items).create(knight("Late-Knight"));
            int laterWorld = db.worldProfile(laterAccount, laterKnight.id(), 1).worldId();
            check(campaigns.deliver(laterWorld), "a knight created after the campaign still receives it");
            check(inbox(db, laterWorld).stream().anyMatch(m -> m.subject().endsWith("Supply drop")), "the late knight's inbox holds the same letter");

            house.mail(aliceAccount, aliceKnight.id(), 1, 2, List.of(letter.id()));
            var claimed = db.worldProfile(aliceAccount, aliceKnight.id(), 1);
            check(claimed.crowns() >= 25 && claimed.energy() >= 5, "claiming credits the campaign crowns and energy");
            var gel = claimed.items().stream().filter(i -> i.config().equals("Material/Gel Drop")).findFirst().orElseThrow();
            check(gel.count() == 2 && gel.expiresAt() == null, "claiming a permanent gift leaves it permanent");

            long revision = campaigns.list().getFirst().updated();
            refused(() -> campaigns.save(saved.id(), revision - 1, now, now + 60_000, 6, null, "stale", "stale", 0, 0, List.of()));
            refused(() -> campaigns.save(0, 0, now, now, 6, null, "empty window", "body", 0, 0, List.of()));
            refused(() -> campaigns.save(0, 0, now, now + 60_000, 6, null, "x".repeat(51), "body", 0, 0, List.of()));
            refused(() -> campaigns.save(0, 0, now, now + 60_000, 6, null, "bad gift", "body", 0, 0, List.of(new MailCampaignStore.Gift("Missing/Item", 1, false))));
            refused(() -> campaigns.save(0, 0, now, now + 60_000, 6, null, "too much", "body", 0, 0, List.of(new MailCampaignStore.Gift("Material/Gel Drop", 15, false))));
            check(campaigns.list().size() == 1, "refused saves leave the campaign list unchanged");

            var future = campaigns.save(0, 0, now + 60_000, now + 120_000, 6, null, "Later gift", "Not yet.", 0, 0, List.of());
            check(!campaigns.deliver(laterWorld), "a scheduled campaign is not delivered before its window");
            campaigns.save(0, 0, now - 120_000, now - 60_000, 6, null, "Missed gift", "Too late.", 0, 0, List.of());
            check(!campaigns.deliver(laterWorld), "an expired campaign is not delivered");

            campaigns.cancel(future.id(), future.updated());
            refused(() -> campaigns.cancel(future.id(), future.updated()));
            check(!campaigns.deliver(laterWorld), "a cancelled campaign never delivers");

            var welcome = campaigns.saveWelcome(true, 6, null, "Welcome aboard", "Read the rules.", 0, 0, List.of());
            check(welcome.enabled() && welcome.enabledAt() > 0, "welcome mail can be enabled");
            check(!campaigns.deliverWelcome(alice, aliceKnight.createdAt()), "existing knights do not receive a newly enabled welcome");
            int newAccount = db.create("Newcomer", protection, false);
            var newKnight = new CharacterStore(db, newAccount, "Newcomer", items).create(knight("New-Knight"));
            int newWorld = db.worldProfile(newAccount, newKnight.id(), 1).worldId();
            check(campaigns.deliverWelcome(newWorld, newKnight.createdAt()), "a knight created after enabling receives the welcome");
            check(inbox(db, newWorld).stream().anyMatch(m -> m.subject().endsWith("Welcome aboard")), "the welcome letter is in the new inbox");
            check(!campaigns.deliverWelcome(newWorld, newKnight.createdAt()), "welcome mail is delivered exactly once");
            long enabledAt = welcome.enabledAt();
            var edited = campaigns.saveWelcome(true, 5, null, "Updated welcome", "New instructions.", 0, 0, List.of());
            check(edited.enabledAt() == enabledAt, "editing the welcome letter keeps its eligibility clock");
            int midAccount = db.create("Middle", protection, false);
            var midKnight = new CharacterStore(db, midAccount, "Middle", items).create(knight("Mid-Knight"));
            int midWorld = db.worldProfile(midAccount, midKnight.id(), 1).worldId();
            check(campaigns.deliverWelcome(midWorld, midKnight.createdAt()), "a knight created between enable and edit still receives the edited welcome");
            check(inbox(db, midWorld).stream().anyMatch(m -> m.subject().endsWith("Updated welcome")), "the current welcome text is delivered");
            int pendingAccount = db.create("Pending", protection, false);
            var pendingKnight = new CharacterStore(db, pendingAccount, "Pending", items).create(knight("Pending-Knight"));
            int pendingWorld = db.worldProfile(pendingAccount, pendingKnight.id(), 1).worldId();
            campaigns.saveWelcome(false, 5, null, "Updated welcome", "New instructions.", 0, 0, List.of());
            check(campaigns.deliverWelcome(pendingWorld, pendingKnight.createdAt()), "a knight created while enabled still receives the welcome after it is disabled");
            check(inbox(db, pendingWorld).stream().anyMatch(m -> m.subject().endsWith("Updated welcome")), "the pending knight receives the text that was current while enabled");
            int offAccount = db.create("Disabled", protection, false);
            var offKnight = new CharacterStore(db, offAccount, "Disabled", items).create(knight("Off-Knight"));
            int offWorld = db.worldProfile(offAccount, offKnight.id(), 1).worldId();
            check(!campaigns.deliverWelcome(offWorld, offKnight.createdAt()), "a disabled welcome never delivers");

            long direct = campaigns.sendTo(midWorld, 4, null, "Direct gift", "For you.", 3, 1,
                List.of(new MailCampaignStore.Gift("Material/Gel Drop", 1, false)));
            check(direct > 0, "direct mail returns the inbox row id");
            var directLetter = inbox(db, midWorld).stream().filter(m -> m.subject().endsWith("Direct gift")).findFirst().orElseThrow();
            check(directLetter.crowns() == 3 && directLetter.energy() == 1 && directLetter.items().size() == 1,
                "direct mail carries crowns, energy and gifts while the knight is offline");
            refused(() -> campaigns.sendTo(999_999, 6, null, "Nowhere", "Nobody.", 0, 0, List.of()));
            int timedAccount = db.create("Timed", protection, false);
            var timedKnight = new CharacterStore(db, timedAccount, "Timed", items).create(knight("Timed-Knight"));
            int timedWorld = db.worldProfile(timedAccount, timedKnight.id(), 1).worldId();
            campaigns.sendTo(timedWorld, 6, null, "Timed gift", "A temporary disguise.", 0, 0,
                List.of(new MailCampaignStore.Gift("Costume/Helm/Trojan Disguise", 1, false)));
            var timedLetter = inbox(db, timedWorld).stream().filter(m -> m.subject().endsWith("Timed gift")).findFirst().orElseThrow();
            check(timedLetter.items().getFirst().expiresAt() == null, "a temporary item does not expire while it sits in mail");
            house.mail(timedAccount, timedKnight.id(), 1, 2, List.of(timedLetter.id()));
            var timed = db.worldProfile(timedAccount, timedKnight.id(), 1).items().stream()
                .filter(i -> i.config().equals("Costume/Helm/Trojan Disguise")).findFirst().orElseThrow();
            check(timed.expiresAt() != null && timed.expiresAt() > now + 29L * 86_400_000L,
                "claiming applies the stock temporary-item expiry");
            var custom = campaigns.save(0, 0, now - 1000, now + 3_600_000, 4, "Server Team",
                "Custom sender", "Sent by the staff team.", 0, 0, List.of());
            check(custom.senderLabel().equals("Server Team"), "a custom sender label is stored");
            check(campaigns.deliver(midWorld), "the custom-sender campaign delivers");
            var customLetter = inbox(db, midWorld).stream().filter(m -> m.subject().endsWith("Custom sender")).findFirst().orElseThrow();
            check(customLetter.nativeType() == 3 && customLetter.sender().equals("Server Team") && customLetter.subject().equals("Custom sender"), "custom sender mail uses ANNOUNCE plain text and the literal name");
            refused(() -> campaigns.save(0, 0, now, now + 60_000, 4, " Leading", "Invalid sender", "Body", 0, 0, List.of()));
            var giftWelcome = campaigns.saveWelcome(true, 5, "Aurora", "Welcome gift", "Take these.",
                10, 5, List.of(new MailCampaignStore.Gift("Material/Gel Drop", 2, false)));
            check(giftWelcome.crowns() == 10 && giftWelcome.gifts().size() == 1, "welcome mail stores rewards and gifts");
            int giftAccount = db.create("Gifted", protection, false);
            var giftKnight = new CharacterStore(db, giftAccount, "Gifted", items).create(knight("Gifted-Knight"));
            int giftWorld = db.worldProfile(giftAccount, giftKnight.id(), 1).worldId();
            check(campaigns.deliverWelcome(giftWorld, giftKnight.createdAt()), "the gift welcome delivers to a new knight");
            var welcomeLetter = inbox(db, giftWorld).stream().filter(m -> m.subject().endsWith("Welcome gift")).findFirst().orElseThrow();
            check(welcomeLetter.nativeType() == 3 && welcomeLetter.sender().equals("Aurora") && welcomeLetter.subject().equals("Welcome gift")
                && welcomeLetter.crowns() == 10 && welcomeLetter.energy() == 5 && welcomeLetter.items().size() == 1, "welcome mail carries its custom sender, plain text, crowns, energy and gift");
            campaigns.saveWelcome(true, 5, null, "Updated welcome", "New instructions.", 0, 0, List.of());

            db.write(c -> { try (var s = c.createStatement()) { s.execute("PRAGMA user_version=16"); } return null; });
            var upgraded = SchemaFixture.upgrade(home);
            check(version(upgraded) == Database.SCHEMA_VERSION, "schema 16 upgrades to the campaign-aware schema");
            var upgradedHouse = new AuctionHouse(upgraded, items, rules);
            check(upgradedHouse.campaigns().list().stream().anyMatch(c -> c.subject().equals("Supply drop")), "campaigns survive the explicit upgrade");
            check(upgradedHouse.campaigns().welcome().enabled() && upgradedHouse.campaigns().welcome().subject().equals("Updated welcome"), "the welcome letter survives the explicit upgrade");
            int postAccount = upgraded.create("Post", protection, false);
            var postKnight = new CharacterStore(upgraded, postAccount, "Post", items).create(knight("Post-Knight"));
            int postWorld = upgraded.worldProfile(postAccount, postKnight.id(), 1).worldId();
            check(upgradedHouse.campaigns().deliver(postWorld) && inbox(upgraded, postWorld).stream().anyMatch(m -> m.subject().endsWith("Supply drop")), "a freshly upgraded database still delivers active campaigns");
            check(inbox(upgraded, midWorld).stream().anyMatch(m -> m.subject().endsWith("Direct gift")), "direct offline mail survives the explicit upgrade");
            System.out.println("PASS: admin mail campaigns store literal text, gifts, crowns and energy; deliver once per knight including late knights; honour windows and cancellation; send direct offline mail with no item timer until claim; apply stock item expiry on claim; deliver an optional welcome once; survive schema 16 to 17 and claim native mail");
        } finally {
            try (var paths = Files.walk(home)) { for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
    private static Knight knight(String name) {
        return new Knight(0, name, "Gear/Helm/Spiral Sallet (Blue)", "Gear/Armor/Spiral Culet (Blue)", null, null, "Blue", 1, 2, 0, 0);
    }
    private static List<org.tortodrone.core.town.AuctionState.Mail> inbox(Database db, int world) throws Exception {
        return db.read(c -> AuctionHouse.loadMail(c, world));
    }
    private static int version(Database db) throws Exception {
        return db.read(c -> { try (var s = c.createStatement(); var r = s.executeQuery("PRAGMA user_version")) { r.next(); return r.getInt(1); } });
    }
    private static void refused(Operation operation) throws Exception {
        try { operation.run(); throw new AssertionError("Expected refusal"); } catch (java.io.IOException expected) { }
    }
    private interface Operation { void run() throws Exception; }
}
