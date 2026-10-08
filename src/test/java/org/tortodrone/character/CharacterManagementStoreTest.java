package org.tortodrone.character;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.tortodrone.support.TestWire;
import org.tortodrone.account.AccountStore;
import org.tortodrone.storage.Database;

/** Edits/deletions must preserve identities and remain atomic on failure and contention. */
public final class CharacterManagementStoreTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("tortodrone-management-test-");
        try {
            try(var accounts=new AccountStore(directory)){accounts.create("Alice","alice-password".toCharArray());}
            var db=new Database(directory,false);
            CharacterStore store = new CharacterStore(db,1,"Alice");
            Knight first = store.create(choices("First-Knight"));
            store.create(choices("Other-Knight"));
            store.create(choices("Third-Knight"));
            Knight edited = store.update("first-knight", new Knight(999, "Renamed-Knight", "Gear/Helm/Spiral Round Helm (Red)", "Gear/Armor/Spiral Flak Jacket (Red)",
                "Helm/Top/Headband/Headband", "Helm/Back/Plume/Plume", "Red", 17, 4, 46, 999));
            TestWire.check(edited.id() == first.id() && edited.createdAt() == first.createdAt(), "edit retains server identity and timestamp");
            TestWire.check(edited.color() == 17 && edited.eye() == 46 && edited.helmAccessory().equals("Helm/Top/Headband/Headband"), "appearance choices are editable");
            TestWire.check(new CharacterStore(new Database(directory,false),1,"Alice").roster().equals(store.roster()), "edited appearance persists at full slot capacity");
            var saved = db.rosterState(1);
            refused(() -> store.update("Renamed-Knight", choices("other-knight")), "already in use");
            refused(() -> store.update("Missing-Knight", choices("New-Knight")), "No such knight");
            refused(() -> store.update("Renamed-Knight", choices("x")), "three letters");
            refused(() -> store.update("Renamed-Knight", new Knight(0, "Renamed-Knight", "../bad", "Gear/Armor/Spiral Culet (Blue)", null, null, "Blue", 1, 2, 0, 0)), "appearance");
            refused(() -> store.delete("Missing-Knight"), "No such knight");
            refused(() -> store.delete(null), "No such knight");
            TestWire.check(saved.equals(db.rosterState(1)), "refused operations preserve the saved roster");
            List<Knight> before = store.roster();
            db.write(c->{try(var statement=c.createStatement()) {
                statement.execute("CREATE TRIGGER refuse_edit BEFORE UPDATE ON knights BEGIN SELECT RAISE(ABORT,'fixture'); END");
                statement.execute("CREATE TRIGGER refuse_delete BEFORE DELETE ON knights BEGIN SELECT RAISE(ABORT,'fixture'); END");
            }return null;});
            try {
                failed(() -> store.update("Renamed-Knight", choices("Lost-Knight")));
                failed(() -> store.delete("Renamed-Knight"));
                TestWire.check(store.roster().equals(before) && saved.equals(db.rosterState(1)), "failed edit/delete preserves saved state and publishes nothing");
            } finally {
                db.write(c->{try(var statement=c.createStatement()){statement.execute("DROP TRIGGER refuse_edit");statement.execute("DROP TRIGGER refuse_delete");}return null;});
            }
            store.delete("other-knight");
            Knight replacement = store.create(choices("Next-Knight"));
            TestWire.check(replacement.id() == 4, "deletion frees a slot without recycling character IDs");
            TestWire.check(new CharacterStore(new Database(directory,false),1,"Alice").roster().equals(store.roster()), "deleted roster and replacement survive restart");
            var workers = Executors.newFixedThreadPool(2);
            try {
                Callable<Boolean> deletion = () -> {
                    try { store.delete("Renamed-Knight"); return true; }
                    catch (CharacterStore.Refused expected) { return false; }
                };
                var results = workers.invokeAll(List.of(deletion, deletion));
                TestWire.check(results.get(0).get() != results.get(1).get(), "competing deletions succeed exactly once");
                TestWire.check(new CharacterStore(new Database(directory,false),1,"Alice").roster().equals(store.roster()), "competing deletions remain persisted");
            } finally { workers.shutdownNow(); }
            for (Knight knight : store.roster()) store.delete(knight.name());
            TestWire.check(new CharacterStore(new Database(directory,false),1,"Alice").roster().isEmpty(), "deleting the last knight persists an empty roster");
            TestWire.check(store.create(choices("Fresh-Knight")).id() == 5, "empty rosters retain the next character ID");
            System.out.println("PASS: character edits, deletion, ownership, slot reuse, identity preservation, atomic failures and competing deletions");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
    private static Knight choices(String name) { return new Knight(0, name, "Gear/Helm/Spiral Sallet (Blue)", "Gear/Armor/Spiral Culet (Blue)", null, null, "Blue", 1, 2, 0, 0); }
    private static void refused(Operation operation, String reason) throws Exception {
        try { operation.run(); throw new AssertionError("Refused operation succeeded"); }
        catch (CharacterStore.Refused expected) { TestWire.check(expected.getMessage().contains(reason), "useful refusal reason"); }
    }
    private static void failed(Operation operation) throws Exception {
        try { operation.run(); throw new AssertionError("Failed write accepted"); }
        catch (IOException expected) { }
    }
    @FunctionalInterface private interface Operation { void run() throws Exception; }
}
