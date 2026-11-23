package org.tortodrone.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import static org.tortodrone.support.TestWire.check;
import org.tortodrone.core.character.Knight;
import org.tortodrone.core.character.WorldProfile;

/** The 0.42.0 baseline marker, ordered migrations, backups and untouched pre-0.42.0 refusal. */
public final class DatabaseSchemaTest {
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("tortodrone-schema-");
        try {
            Path current = home.resolve("current");
            var db = new Database(current, false);
            check(applicationId(db) == SchemaMigrations.APPLICATION_ID, "fresh database carries the project marker");
            check(version(db) == Database.SCHEMA_VERSION && Database.SCHEMA_VERSION == SchemaMigrations.LATEST,
                "fresh database starts at the latest schema with every migration recorded");
            check(db.read(c -> indexes(c).containsAll(List.of("inventory_items_world","town_mail_world",
                    "town_mail_auction","town_sprites_world","town_harnesses_world","currency_events_world",
                    "item_events_world","account_events_account","town_auction_bids_world",
                    "town_auctions_seller","town_auctions_bidder","mail_campaign_deliveries_world"))),
                "the baseline build carries the lookup and foreign-key indexes");
            check(db.read(DatabaseSchemaTest::allStrict), "every game table is STRICT on a fresh database");
            check(db.read(c -> { try (var s = c.createStatement();
                    var r = s.executeQuery("SELECT COUNT(*) FROM schema_migrations WHERE version=1")) { r.next(); return r.getInt(1); } }) == 1,
                "the baseline row is recorded in schema_migrations");
            db.create("Alice", new Passwords.Protection(new byte[16], new byte[32]), true);
            var characters = new CharacterStore(db, 1, "Alice");
            var knight = characters.create(new Knight(0,"Saved-Knight","Gear/Helm/Spiral Sallet (Blue)",
                "Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            var profile = characters.worldProfile(knight.name(), 1);
            check(profile.items().size()==5 && profile.items().stream().allMatch(WorldProfile.Item::bound)
                && Arrays.stream(profile.equipment()).filter(id->id!=0).count()==5, "direct schema creates exactly one equipped bound starter kit");
            try (var channel=FileChannel.open(current.resolve("server.lock"),StandardOpenOption.WRITE);var lock=channel.lock()) {
                check(new Database(current,false).worldProfile(1,1,1).equals(profile), "current schema can open alongside a live server");
            }

            // A baseline-era file (schema 1) opens through the ordered step, after a backup.
            Path upgrade = home.resolve("upgrade");
            new Database(upgrade, false);
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + upgrade.resolve("tortodrone.db"));
                 Statement statement = c.createStatement()) {
                for (String index : List.of("inventory_items_world","town_mail_world","town_mail_auction",
                        "town_sprites_world","town_harnesses_world","currency_events_world","item_events_world",
                        "account_events_account","town_auction_bids_world"))
                    statement.execute("DROP INDEX " + index);
                statement.execute("DELETE FROM schema_migrations WHERE version>" + SchemaMigrations.BASELINE);
                statement.execute("PRAGMA user_version=1");
            }
            var upgraded = new Database(upgrade, false);
            check(version(upgraded) == Database.SCHEMA_VERSION, "a schema-1 file upgrades to the latest schema");
            check(upgraded.read(c -> indexes(c).containsAll(List.of("inventory_items_world","town_auctions_seller"))),
                "the upgrade creates the lookup and foreign-key indexes");
            check(upgraded.read(DatabaseSchemaTest::allStrict), "the upgrade leaves every game table STRICT");
            try (var backups = Files.list(upgrade.resolve("backups"))) {
                check(backups.anyMatch(path -> path.getFileName().toString().startsWith("tortodrone-pre-1-to-")),
                    "the upgrade takes a consistent pre-migration backup");
            }

            // A real schema-2 file has the same tables without STRICT. Rebuild it through the
            // released migration path and check that the live rows and indexes survive.
            Path rebuild = home.resolve("rebuild");
            var legacyDb = new Database(rebuild, false);
            legacyDb.create("Bob", new Passwords.Protection(new byte[16], new byte[32]), false);
            var legacyCharacters = new CharacterStore(legacyDb, 1, "Bob");
            var legacyKnight = legacyCharacters.create(new Knight(0,"Legacy-Knight","Gear/Helm/Spiral Sallet (Blue)",
                "Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            var legacyProfile = legacyCharacters.worldProfile(legacyKnight.name(), 1);
            legacyDb.close();
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + rebuild.resolve("tortodrone.db"));
                 Statement statement = c.createStatement()) {
                statement.execute("PRAGMA writable_schema=ON");
                statement.execute("UPDATE sqlite_master SET sql=replace(sql,' STRICT','') WHERE type='table' AND sql LIKE '% STRICT'");
                statement.execute("PRAGMA writable_schema=OFF");
                statement.execute("DELETE FROM schema_migrations WHERE version>" + (SchemaMigrations.BASELINE + 1));
                statement.execute("PRAGMA user_version=" + (SchemaMigrations.BASELINE + 1));
                check(!allStrict(c), "the fixture starts without STRICT tables");
            }
            var rebuilt = new Database(rebuild, false);
            check(version(rebuilt) == Database.SCHEMA_VERSION, "a schema-2 file upgrades to the latest schema");
            check(rebuilt.read(DatabaseSchemaTest::allStrict), "the strict-table step rebuilds the real schema");
            check(rebuilt.worldProfile(1,1,1).equals(legacyProfile), "the strict-table rebuild keeps live rows");
            check(rebuilt.read(c -> indexes(c).containsAll(List.of("inventory_items_world","town_mail_world",
                    "equipped_items","starter_items","global_knight_names"))),
                "the strict-table rebuild keeps explicit and partial indexes");
            try (var backups = Files.list(rebuild.resolve("backups"))) {
                check(backups.anyMatch(path -> path.getFileName().toString().startsWith("tortodrone-pre-2-to-")),
                    "the strict-table upgrade takes a pre-migration backup");
            }

            // A non-STRICT table is rebuilt without losing rows, indexes, triggers or its
            // AUTOINCREMENT high-water mark; the rebuilt table then rejects a wrong type.
            Path legacy = home.resolve("legacy.db");
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + legacy);
                 Statement statement = c.createStatement()) {
                statement.execute("PRAGMA foreign_keys=OFF");
                statement.execute("CREATE TABLE parent(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL)");
                statement.execute("CREATE UNIQUE INDEX parent_name ON parent(name)");
                statement.execute("CREATE TABLE child(id INTEGER PRIMARY KEY,parent_id INTEGER NOT NULL REFERENCES parent(id),amount REAL,note TEXT)");
                statement.execute("CREATE INDEX child_parent ON child(parent_id)");
                statement.execute("CREATE TRIGGER child_note AFTER INSERT ON child BEGIN UPDATE child SET note=COALESCE(note,'') WHERE id=NEW.id; END");
                statement.execute("INSERT INTO parent(id,name) VALUES(1,'one'),(2,'two')");
                statement.execute("DELETE FROM parent WHERE id=2");
                statement.execute("INSERT INTO child(id,parent_id,amount,note) VALUES(7,1,1.5,'kept')");
                statement.execute("BEGIN IMMEDIATE");
                SchemaMigrations.strictTables(c);
                statement.execute("COMMIT");
                check(allStrict(c), "a non-STRICT table is rebuilt as STRICT");
                check(c.createStatement().executeQuery("SELECT COUNT(*) FROM parent").getInt(1) == 1,
                    "the rebuilt table keeps its rows");
                check(indexes(c).containsAll(List.of("parent_name","child_parent")),
                    "the rebuilt tables keep their explicit indexes");
                statement.execute("INSERT INTO parent(name) VALUES('three')");
                check(c.createStatement().executeQuery("SELECT id FROM parent WHERE name='three'").getInt(1) == 3,
                    "the rebuilt AUTOINCREMENT table keeps its high-water mark");
                statement.execute("INSERT INTO child(id,parent_id,amount) VALUES(8,1,2.5)");
                check(c.createStatement().executeQuery("SELECT note FROM child WHERE id=8").getString(1).equals(""),
                    "the rebuilt table keeps its triggers");
                boolean wrongType = false;
                try { statement.execute("INSERT INTO child(id,parent_id,amount) VALUES(9,1,'not a number')"); }
                catch (SQLException expected) { wrongType = true; }
                check(wrongType, "a STRICT table rejects a wrong column type");
                try (var check = statement.executeQuery("PRAGMA foreign_key_check")) {
                    check(!check.next(), "the rebuilt foreign keys still validate");
                }
            }

            // Ordered migration runner: every step above the stored version runs once, in order.
            Path migration = home.resolve("migration");
            new Database(migration, false);
            var order = new ArrayList<Integer>();
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + migration.resolve("tortodrone.db"));
                 Statement statement = c.createStatement()) {
                statement.execute("PRAGMA foreign_keys=OFF");
                statement.execute("BEGIN IMMEDIATE");
                SchemaMigrations.apply(c, SchemaMigrations.LATEST, List.of(
                    new SchemaMigrations.Migration(SchemaMigrations.LATEST + 1, "next", connection -> order.add(SchemaMigrations.LATEST + 1)),
                    new SchemaMigrations.Migration(SchemaMigrations.LATEST + 2, "later", connection -> order.add(SchemaMigrations.LATEST + 2))));
                statement.execute("PRAGMA user_version=" + (SchemaMigrations.LATEST + 2));
                statement.execute("COMMIT");
                check(order.equals(List.of(SchemaMigrations.LATEST + 1,SchemaMigrations.LATEST + 2)), "ordered steps run above the stored version");
                try (var r = statement.executeQuery("SELECT version FROM schema_migrations ORDER BY version")) {
                    var rows = new ArrayList<Integer>();
                    while (r.next()) rows.add(r.getInt(1));
                    var expected = new ArrayList<Integer>();
                    for (int v = SchemaMigrations.BASELINE; v <= SchemaMigrations.LATEST + 2; v++) expected.add(v);
                    check(rows.equals(expected), "each applied step is recorded");
                }
            }
            boolean refusedStep = false;
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + migration.resolve("tortodrone.db"))) {
                SchemaMigrations.apply(c, 1, List.of(new SchemaMigrations.Migration(2, "duplicate", connection -> { })));
            } catch (SQLException expected) { refusedStep = true; }
            check(refusedStep, "a migration row cannot be recorded twice");

            // Consistent backup via SQLite's own snapshot.
            Path backup = db.backup(home.resolve("backups/taken.db"));
            Path restoredDir = home.resolve("restored"); Files.createDirectories(restoredDir);
            Files.copy(backup, restoredDir.resolve("tortodrone.db"));
            var restored = new Database(restoredDir, false);
            check(Files.isRegularFile(backup) && restored.identity("Alice").gameMaster()
                && restored.worldProfile(1,1,1).equals(profile), "VACUUM INTO backup reopens with identities and inventory");
            boolean duplicate = false;
            try { db.backup(backup); } catch (IOException expected) { duplicate = true; }
            check(duplicate, "a backup never overwrites an existing file");

            // Every historical version is refused byte-identically, with no marker written.
            for (int historical : HistoricalSchemaFixture.VERSIONS) {
                Path directory = home.resolve("pre-" + historical);
                Path file = HistoricalSchemaFixture.create(directory, historical);
                byte[] saved = Files.readAllBytes(file);
                for (boolean locked : List.of(false, true)) {
                    try { new Database(directory, locked); throw new AssertionError("Pre-0.42.0 schema accepted"); }
                    catch (IOException expected) {
                        check(expected.getMessage().contains("predates Tortodrone 0.42.0"), "pre-0.42.0 refusal is actionable");
                    }
                    check(Arrays.equals(saved, Files.readAllBytes(file)), "refusal leaves the database byte-identical");
                    check(applicationIdAt(file) == 0, "refusal never writes the project marker");
                }
            }

            // Marker present with a bad version is a refusal, not a silent downgrade or guess.
            for (int badVersion : new int[]{0, SchemaMigrations.LATEST + 1}) {
                Path directory = home.resolve("marker-" + badVersion); Files.createDirectories(directory);
                try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("tortodrone.db"));
                     Statement statement = c.createStatement()) {
                    statement.execute("PRAGMA application_id=" + SchemaMigrations.APPLICATION_ID);
                    statement.execute("PRAGMA user_version=" + badVersion);
                    statement.execute("CREATE TABLE probe(id INTEGER)");
                }
                try { new Database(directory, false); throw new AssertionError("Unsupported version accepted"); }
                catch (IOException expected) { check(expected.getMessage().contains("Unsupported database schema"), "bad version is refused"); }
            }
            Path unknownMarker = home.resolve("unknown-marker"); Files.createDirectories(unknownMarker);
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + unknownMarker.resolve("tortodrone.db"));
                 Statement statement = c.createStatement()) {
                statement.execute("PRAGMA application_id=1234");
                statement.execute("PRAGMA user_version=1");
                statement.execute("CREATE TABLE probe(id INTEGER)");
            }
            try { new Database(unknownMarker, false); throw new AssertionError("Unknown marker accepted"); }
            catch (IOException expected) { check(expected.getMessage().contains("application_id"), "unknown marker is refused"); }

            Path blocked=home.resolve("blocked");Files.createDirectories(blocked);
            try(var channel=FileChannel.open(blocked.resolve("server.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.lock()) {
                try{new Database(blocked,false);throw new AssertionError("Initialized while locked");}catch(IOException expected){}
                check(!Files.exists(blocked.resolve("tortodrone.db")),"refused initialization creates no database");
            }
            check(new Database(blocked,false).list().isEmpty(),"fresh initialization succeeds after lock release");
            Path properties=home.resolve("properties");Files.createDirectories(properties.resolve("accounts"));
            Path savedProfile=properties.resolve("accounts/1.properties");Files.writeString(savedProfile,"obsolete profile");
            check(new Database(properties,false).list().isEmpty() && Files.readString(savedProfile).equals("obsolete profile"),
                "property files have no reader, importer or automatic development account");
            System.out.println("PASS: marker, baseline, ordered migrations, backup, untouched pre-0.42.0 refusal, unknown-marker/version refusal, initialization lock and no property import");
        } finally {try(var paths=Files.walk(home)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }

    private static Set<String> indexes(Connection c) throws SQLException {
        var names = new HashSet<String>();
        try (var s = c.createStatement(); var r = s.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='index'")) {
            while (r.next()) names.add(r.getString(1));
        }
        return names;
    }
    /** Every game table, not SQLite's internal sequence table, carries STRICT. */
    private static boolean allStrict(Connection c) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
            while (r.next()) {
                var sql = r.getString(1);
                if (sql == null || !sql.matches("(?is).*\\)\\s*STRICT\\s*$"))
                    return false;
            }
        }
        return true;
    }
    private static int applicationId(Database db) throws IOException {
        return db.read(c -> { try (var s=c.createStatement(); var r=s.executeQuery("PRAGMA application_id")) { r.next(); return r.getInt(1); } });
    }
    private static int applicationIdAt(Path file) throws SQLException {
        try (Connection c=DriverManager.getConnection("jdbc:sqlite:"+file); var s=c.createStatement(); var r=s.executeQuery("PRAGMA application_id")) {
            r.next(); return r.getInt(1);
        }
    }
    private static int version(Database db) throws IOException {
        return db.read(c -> { try (var s=c.createStatement(); var r=s.executeQuery("PRAGMA user_version")) { r.next(); return r.getInt(1); } });
    }
}
