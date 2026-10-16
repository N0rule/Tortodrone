package org.tortodrone.character;

import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.storage.Database;
import static org.tortodrone.support.TestWire.check;

/** Account inspection visibility is private, revision-guarded metadata outside character state. */
public final class InspectionPreferenceTest {
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("tortodrone-inspection-preference-");
        try {
            var protection = new Passwords.Protection(new byte[16], new byte[32]);
            var db = new Database(home, false);
            int aliceId = db.create("Alice", protection, false);
            int bobId = db.create("Bob", protection, false);
            var alice = new CharacterStore(db, aliceId, "Alice");
            var bob = new CharacterStore(db, bobId, "Bob");
            Knight aliceKnight = alice.create(choices("Alice-Knight"));
            bob.create(choices("Bob-Knight"));
            long aliceAuth = db.identity("Alice").authRevision();
            long bobAuth = db.identity("Bob").authRevision();

            check(schemaVersion(db) == Database.SCHEMA_VERSION,
                "inspection preferences reuse schema " + Database.SCHEMA_VERSION + " metadata without a dedicated table");
            check(!alice.inspectionHidden(aliceAuth) && !bob.inspectionHidden(bobAuth),
                "new accounts default to inspection visible");

            alice.inspectionHidden(aliceAuth, true);
            check(alice.inspectionHidden(aliceAuth) && !bob.inspectionHidden(bobAuth),
                "preference belongs only to its account");
            bob.inspectionHidden(bobAuth, true);
            alice.inspectionHidden(aliceAuth, false);
            check(!alice.inspectionHidden(aliceAuth) && bob.inspectionHidden(bobAuth),
                "changing one account does not change the other account");

            alice.inspectionHidden(aliceAuth, true);
            expectRefusal(() -> alice.inspectionHidden(aliceAuth - 1), "stale revision getter refused");
            expectRefusal(() -> alice.inspectionHidden(aliceAuth - 1, false), "stale revision setter refused");
            expectRefusal(() -> alice.inspectionHidden(-1), "negative revision getter refused");
            expectRefusal(() -> alice.inspectionHidden(-1, false), "negative revision setter refused");
            check(alice.inspectionHidden(aliceAuth) && metadataValue(db, aliceId) == 1,
                "rejected stale and unauthenticated operations leave the stored preference unchanged");

            db.password("Alice", protection, aliceId);
            long currentAliceAuth = db.identity("Alice").authRevision();
            check(currentAliceAuth != aliceAuth, "password change revokes the previous auth revision");
            expectRefusal(() -> alice.inspectionHidden(aliceAuth), "old auth revision cannot read after password change");
            expectRefusal(() -> alice.inspectionHidden(aliceAuth, false), "old auth revision cannot write after password change");
            check(alice.inspectionHidden(currentAliceAuth), "password change preserves the account preference");

            // Reopen the database object to prove that the preference is durable.
            var reopened = new Database(home, false);
            var restoredAlice = new CharacterStore(reopened, aliceId, "Alice");
            var restoredBob = new CharacterStore(reopened, bobId, "Bob");
            check(restoredAlice.inspectionHidden(currentAliceAuth) && restoredBob.inspectionHidden(bobAuth),
                "preferences survive database restart for both accounts");

            var profileBeforeToggles = restoredAlice.worldProfile(aliceKnight.name(), currentAliceAuth);
            var rosterBeforeToggles = reopened.rosterState(aliceId);
            for (boolean hidden : List.of(false, true, true, false, false, true)) {
                restoredAlice.inspectionHidden(currentAliceAuth, hidden);
                check(restoredAlice.inspectionHidden(currentAliceAuth) == hidden, "preference toggle is readable");
            }
            check(reopened.worldProfile(aliceId, aliceKnight.id(), currentAliceAuth).equals(profileBeforeToggles),
                "preference toggles leave inventory and currency profile unchanged");
            check(reopened.rosterState(aliceId).equals(rosterBeforeToggles),
                "preference toggles leave roster and roster revision unchanged");

            reopened.delete("Bob", bobId);
            check(!metadataPresent(reopened, bobId), "account deletion removes its inspection metadata key");
            System.out.println("PASS: inspection preference defaults, account isolation, revision guards, durability, password revocation, state isolation and deletion cleanup");
        } finally {
            try (var paths = Files.walk(home)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static Knight choices(String name) {
        return new Knight(0, name, "Gear/Helm/Spiral Sallet (Blue)", "Gear/Armor/Spiral Culet (Blue)",
            null, null, "Blue", 1, 2, 0, 0);
    }

    private static int schemaVersion(Database db) throws IOException {
        return db.read(c -> {
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("PRAGMA user_version")) {
                r.next();
                return r.getInt(1);
            }
        });
    }

    private static int metadataValue(Database db, int accountId) throws IOException {
        return db.read(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT value FROM metadata WHERE key=?")) {
                s.setString(1, preferenceKey(accountId));
                try (ResultSet r = s.executeQuery()) {
                    if (!r.next()) throw new IOException("Inspection preference disappeared");
                    return r.getInt(1);
                }
            }
        });
    }

    private static boolean metadataPresent(Database db, int accountId) throws IOException {
        return db.read(c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT 1 FROM metadata WHERE key=?")) {
                s.setString(1, preferenceKey(accountId));
                try (ResultSet r = s.executeQuery()) { return r.next(); }
            }
        });
    }

    private static String preferenceKey(int accountId) {
        return "account." + accountId + ".inspection-hidden";
    }

    private static void expectRefusal(Operation operation, String message) throws Exception {
        try {
            operation.run();
            throw new AssertionError(message);
        } catch (IOException expected) {
            check(expected.getMessage() != null && !expected.getMessage().isBlank(), message + " reports reason");
        }
    }

    @FunctionalInterface private interface Operation { void run() throws Exception; }
}
