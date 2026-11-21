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
            check(version(db) == Database.SCHEMA_VERSION && Database.SCHEMA_VERSION == SchemaMigrations.BASELINE,
                "fresh database starts at the 0.42.0 baseline");
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

            // Ordered migration runner: every step above the stored version runs once, in order.
            Path migration = home.resolve("migration");
            new Database(migration, false);
            var order = new ArrayList<Integer>();
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + migration.resolve("tortodrone.db"));
                 Statement statement = c.createStatement()) {
                statement.execute("PRAGMA foreign_keys=OFF");
                statement.execute("BEGIN IMMEDIATE");
                SchemaMigrations.apply(c, 1, List.of(
                    new SchemaMigrations.Migration(2, "second", connection -> order.add(2)),
                    new SchemaMigrations.Migration(3, "third", connection -> order.add(3))));
                statement.execute("PRAGMA user_version=3");
                statement.execute("COMMIT");
                check(order.equals(List.of(2,3)), "ordered steps run above the stored version");
                try (var r = statement.executeQuery("SELECT version FROM schema_migrations ORDER BY version")) {
                    var rows = new ArrayList<Integer>();
                    while (r.next()) rows.add(r.getInt(1));
                    check(rows.equals(List.of(1,2,3)), "each applied step is recorded");
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
