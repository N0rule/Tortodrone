package org.tortodrone.server;

import static org.tortodrone.support.TestWire.check;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.core.character.Currency;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.character.Knight;
import org.tortodrone.core.town.TownCatalog;
import org.tortodrone.storage.Database;
import org.tortodrone.storage.GuildStore;
import org.tortodrone.support.ContentFixture;

/** Guild reads for the local console: the listing, the record and the roster report. */
public final class GuildAdministrationTest {
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("tortodrone-guild-admin-");
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
            var guilds = new GuildStore(db);

            String empty = GuildCommands.execute("guild list", guilds);
            check(empty.equals("No guilds are formed."), "an empty database reports no guilds");

            var formed = guilds.form(alice, 1, 1, "Oracion");
            long id = guilds.guildOf(db.worldProfile(alice, 1, 1).worldId()).id();

            var listed = guilds.list();
            check(
                    listed.size() == 1
                            && listed.getFirst().id() == id
                            && listed.getFirst().name().equals("Oracion"),
                    "the listing returns the formed guild");

            check(
                    guilds.named("oracion") != null && guilds.named("  ORACION  ") != null,
                    "a guild is found by its trimmed, case-folded name");
            check(guilds.named("Nobody") == null, "an unknown name finds no guild");
            check(guilds.named(" ") == null, "a blank name finds no guild");

            String list = GuildCommands.execute("guild list", guilds);
            check(
                    list.startsWith("Guild | Id | Members | Well | Upkeep | Formed")
                            && list.contains("Oracion | " + id + " | 1/100 | 0 | 0 | "),
                    "the listing reports members, the well and upkeep");

            String info = GuildCommands.execute("guild info ORACION", guilds);
            check(info.contains("Guild Oracion | id=" + id), "the record is found case-insensitively");
            check(info.contains("Master | Alice-Knight | joined="), "the roster names the master");
            check(info.contains("lastSession=never"), "a knight who has not logged out has no session stamp");
            check(formed.profile() != null, "formation returned the founder's profile");

            db.lastSession(db.worldProfile(alice, 1, 1).worldId(), 1_700_000_000_000L);
            check(
                    GuildCommands.execute("guild info Oracion", guilds).contains("lastSession=2023-11-14T22:13:20Z"),
                    "the roster reports the stored last session stamp");

            boolean unknown = false;
            try {
                GuildCommands.execute("guild info Nobody", guilds);
            } catch (java.io.IOException refused) {
                unknown = refused.getMessage().startsWith("No such guild:");
            }
            check(unknown, "an unknown guild is refused by name");

            boolean usage = false;
            try {
                GuildCommands.execute("guild", guilds);
            } catch (java.io.IOException refused) {
                usage = refused.getMessage().startsWith("Usage:");
            }
            check(usage, "a bare command prints its usage");

            String record = GuildCommands.execute("guild log ORACION", guilds);
            check(record.startsWith("Guild Oracion | record entries="), "the console reads the record page");
            check(record.contains("newest first, showing "), "the record page states how much it shows");
            check(record.contains("type="), "a record entry keeps its stored type byte");
            check(
                    record.contains("the game client localizes them"),
                    "the console does not invent localized entry-type names");
            check(
                    !GuildCommands.execute("guild log oracion", guilds).isEmpty(),
                    "the record page is found case-insensitively");

            boolean logUsage = false;
            try {
                GuildCommands.execute("guild log", guilds);
            } catch (java.io.IOException refused) {
                logUsage = refused.getMessage().equals("Usage: guild log <guild-name>");
            }
            check(logUsage, "the record page without a guild name prints its usage");

            boolean logUnknown = false;
            try {
                GuildCommands.execute("guild log Nobody", guilds);
            } catch (java.io.IOException refused) {
                logUnknown = refused.getMessage().startsWith("No such guild:");
            }
            check(logUnknown, "the record page refuses an unknown guild by name");

            // The operator page reads the same stored record without a member to authenticate as.
            var page = guilds.operatorLog(id, Long.MAX_VALUE, 0, 25, null, null);
            check(page.entryCount() >= 0, "the operator log reports a filter total");
            check(
                    page.entries().stream().allMatch(entry -> entry.type() >= 0),
                    "every operator log entry carries its stored type byte");
            for (int i = 0; i < 3; i++)
                check(
                        guilds.operatorLog(id, Long.MAX_VALUE, 0, 25, null, null)
                                        .entryCount()
                                == page.entryCount(),
                        "the operator log is stable across reads");

            boolean badPage = false;
            try {
                guilds.operatorLog(id, Long.MAX_VALUE, 0, 0, null, null);
            } catch (java.io.IOException refused) {
                badPage = refused.getMessage().startsWith("Invalid log page");
            }
            check(badPage, "an out-of-range operator page is refused");

            boolean badId = false;
            try {
                guilds.operatorLog(0, Long.MAX_VALUE, 0, 25, null, null);
            } catch (java.io.IOException refused) {
                badId = refused.getMessage().startsWith("Invalid guild id");
            }
            check(badId, "an operator log read without a guild is refused");
        } finally {
            try (var paths = Files.walk(home)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.println("PASS: the console reports formed guilds, their records and their rosters");
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
}
