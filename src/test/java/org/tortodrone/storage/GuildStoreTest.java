package org.tortodrone.storage;

import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.character.Currency;
import org.tortodrone.support.ContentFixture;
import org.tortodrone.town.*;
import static org.tortodrone.support.TestWire.check;

/** Guild formation, invitation mail, ranks, record and well, end to end on one database. */
public final class GuildStoreTest {
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("tortodrone-guild-");
        try {
            var db = new Database(home, true);
            var protection = new Passwords.Protection(new byte[16], new byte[32]);
            int alice = db.create("Alice", protection, false), bob = db.create("Bob", protection, false);
            var items = ItemCatalog.load(ContentFixture.path("items/catalog.json"));
            var rules = TownCatalog.load(ContentFixture.path("town/catalog.json"));
            new CharacterStore(db, alice, "Alice", items).create(knight("Alice-Knight"));
            new CharacterStore(db, bob, "Bob", items).create(knight("Bob-Knight"));
            db.setCurrency("Alice", "Alice-Knight", alice, -1, Currency.CROWNS, 10_000);
            db.setCurrency("Alice", "Alice-Knight", alice, -1, Currency.ENERGY, 500);
            db.setCurrency("Bob", "Bob-Knight", bob, -1, Currency.ENERGY, 2000);
            var guilds = new GuildStore(db);
            var house = new AuctionHouse(db, items, rules);
            int aliceWorld = db.worldProfile(alice, 1, 1).worldId();
            int bobWorld = db.worldProfile(bob, 1, 1).worldId();

            guilds.checkName("Knights of the Spiral");
            check(guilds.guildOf(aliceWorld) == null, "a new knight belongs to no guild");
            var formed = guilds.form(alice, 1, 1, "Knights of the Spiral");
            var guild = guilds.guildOf(aliceWorld);
            check(guild != null && guild.name().equals("Knights of the Spiral"), "the founder's guild is stored");
            check(guild.memberLimit() == GuildStore.MAX_MEMBERS, "the stock member limit is published");
            check(formed.profile().crowns() == 8000 && formed.profile().energy() == 400,
                "formation charges the captured 2000 crowns and 100 energy");
            check(guilds.view(guild.id()).members().size() == 1
                && guilds.view(guild.id()).members().getFirst().rank() == 0, "the founder is Guild Master");

            boolean duplicate = false;
            try { guilds.form(bob, 1, 1, "knights of the spiral"); }
            catch (java.io.IOException expected) { duplicate = true; }
            check(duplicate, "guild names are unique case-insensitively");

            guilds.invite(alice, 1, 1, "Bob-Knight", "Join us", "Bring a sword");
            var letter = db.worldProfile(bob, 1, 1).town().mail().stream()
                .filter(m -> m.guildInvite()).findFirst().orElseThrow();
            check(letter.nativeType() == 2 && letter.guildInviteId() == guild.id(),
                "the invitation is a GUILD-type mail carrying the guild id");
            house.mail(bob, 1, 1, 1, List.of(letter.id()));
            check(guilds.guildOf(bobWorld) != null && guilds.view(guild.id()).members().size() == 2,
                "accepting the letter adds the knight");
            check(guilds.view(guild.id()).members().stream().filter(m -> m.worldId() == bobWorld)
                .findFirst().orElseThrow().rank() == 4, "a new member joins as Recruit");

            guilds.changeRank(alice, 1, 1, "Bob-Knight", (byte) 1);
            check(guilds.view(guild.id()).members().stream().filter(m -> m.worldId() == bobWorld)
                .findFirst().orElseThrow().rank() == 1, "the master promotes the recruit to Officer");

            boolean officerCannotMintMaster = false;
            try { guilds.changeRank(bob, 1, 1, "Bob-Knight", (byte) 0); }
            catch (java.io.IOException expected) { officerCannotMintMaster = true; }
            check(officerCannotMintMaster, "an officer cannot promote above their own rank");

            var motd = guilds.setMotd(bob, 1, 1, guild.id(), "Officers only");
            check(motd.profile() != null, "an officer writes the message of the day");
            check(guilds.guildOf(aliceWorld).motd().equals("Officers only"), "the message is stored on the guild");

            guilds.depositEnergy(bob, 1, 1, guild.id(), 500);
            check(guilds.guildOf(aliceWorld).energy() == 500, "500 energy goes into the well");
            var well = guilds.withdrawEnergy(bob, 1, 1, guild.id());
            check(well.profile().energy() == 1600 && guilds.guildOf(aliceWorld).energy() == 400,
                "one withdrawal takes min(100, well) and charges the knight's daily window");
            boolean daily = false;
            try { guilds.withdrawEnergy(bob, 1, 1, guild.id()); }
            catch (java.io.IOException expected) { daily = true; }
            check(daily, "a second withdrawal inside 24 hours is refused");

            var page = guilds.log(alice, 1, 1, guild.id(), Long.MAX_VALUE, 0, 100, null, null);
            check(page.entryCount() >= 5 && page.entries().size() == page.entryCount(),
                "the guild record answers a page and its total");
            check(page.entries().stream().anyMatch(e -> e.type() == 0),
                "the roster additions are recorded");
            check(page.entries().stream().anyMatch(e -> e.type() == 9),
                "the well deposit is recorded");

            guilds.expel(alice, 1, 1, "Bob-Knight");
            check(guilds.guildOf(bobWorld) == null && guilds.view(guild.id()).members().size() == 1,
                "expelling the officer removes them from the roster");

            guilds.leave(alice, 1, 1);
            check(guilds.guildOf(aliceWorld) == null, "the last member can leave");
            boolean gone = false;
            try { guilds.view(guild.id()); }
            catch (java.io.IOException expected) { gone = true; }
            check(gone, "a guild with no members is disbanded");

            // Schema 17 (a database written before guild tables existed) upgrades to 18.
            db.write(c -> {
                try (var s = c.createStatement()) {
                    s.execute("DROP TABLE guild_log");
                    s.execute("DROP TABLE guild_members");
                    s.execute("DROP TABLE guild_expansions");
                    s.execute("DROP TABLE guilds");
                    s.execute("PRAGMA user_version=17");
                }
                return null;
            });
            var upgraded = org.tortodrone.support.SchemaFixture.upgrade(home);
            check(new GuildStore(upgraded).guildOf(aliceWorld) == null, "the 17 to 18 upgrade recreates an empty guild store");
            upgraded.setCurrency("Alice", "Alice-Knight", alice, -1, Currency.CROWNS, 10_000);
            check(new GuildStore(upgraded).form(alice, 1, 1, "Upgraded Guild").profile().crowns() == 8000,
                "guilds can be formed on the upgraded schema");
        } finally {
            try (var paths = Files.walk(home)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.println("GuildStoreTest PASS");
    }

    private static Knight knight(String name) {
        return new Knight(0, name, "Gear/Helm/Spiral Sallet (Blue)", "Gear/Armor/Spiral Culet (Blue)",
            null, null, "Blue", 1, 2, 0, 0);
    }
}
