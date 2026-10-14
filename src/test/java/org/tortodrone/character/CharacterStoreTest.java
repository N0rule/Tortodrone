package org.tortodrone.character;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.support.TestWire;
import org.tortodrone.account.AccountStore;
import org.tortodrone.storage.Database;

/** Persistence failures and competing creations must never lose or duplicate characters. */
public final class CharacterStoreTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("tortodrone-profile-test-");
        try {
            CharacterStore store = store(directory);
            TestWire.check(store.roster().isEmpty(), "fresh account");
            Knight first = store.create(choices("Saved-Knight"));
            TestWire.check(first.id() == 1 && first.createdAt() > 0, "server assigns identity and timestamp");
            var db = new Database(directory,false);
            var saved = db.rosterState(1);
            refused(() -> store.create(choices("saved-knight")), "already in use");
            refused(() -> store.create(choices("x")), "three letters");
            refused(() -> store.create(new Knight(0, "Bad-Choice", "../helm", "Gear/Armor/Spiral Culet (Blue)", null, null, "Blue", 1, 2, 0, 0)), "appearance");
            TestWire.check(saved.equals(db.rosterState(1)), "rejections preserve existing save");
            CharacterStore restored = store(directory);
            TestWire.check(restored.roster().equals(store.roster()), "all saved fields restored");
            TestWire.check(restored.create(choices("Second-Knight")).id() == 2, "IDs continue after restart");
            restored.create(choices("Third-Knight"));
            refused(() -> restored.create(choices("Fourth-Knight")), "slots");

            // A storage failure must neither publish nor consume roster/world/item IDs.
            Path blocked = directory.resolve("blocked");
            CharacterStore failure = store(blocked);
            var failedDb = new Database(blocked,false);
            failedDb.write(c -> {try(var statement=c.createStatement()) {
                statement.execute("CREATE TRIGGER refuse_save BEFORE INSERT ON knights BEGIN SELECT RAISE(ABORT,'fixture'); END");
            }return null;});
            try { failure.create(choices("Retry-Knight")); throw new AssertionError("Save failure accepted"); }
            catch (IOException expected) { }
            TestWire.check(failure.roster().isEmpty() && failedDb.roster(1).isEmpty(), "failed write not published");
            failedDb.write(c -> {try(var statement=c.createStatement()){statement.execute("DROP TRIGGER refuse_save");}return null;});
            TestWire.check(failure.create(choices("Retry-Knight")).id() == 1, "failed save did not consume ID");

            CharacterStore competing = store(directory.resolve("competing"));
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                Callable<Boolean> create = () -> { try { competing.create(choices("Same-Knight")); return true; }
                    catch (CharacterStore.Refused expected) { return false; } };
                var results = workers.invokeAll(List.of(create, create));
                TestWire.check(results.get(0).get() != results.get(1).get() && competing.roster().size() == 1,
                    "competing requests produce one character");
                TestWire.check(store(directory.resolve("competing")).roster().equals(competing.roster()),
                    "concurrent creation persisted");
            } finally { workers.shutdownNow(); }
            System.out.println("PASS: profile persistence, duplicate names, slots, failed writes, concurrent creation");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
    private static CharacterStore store(Path directory) throws IOException {
        try(var accounts=new AccountStore(directory,false,org.tortodrone.character.ItemCatalog.starter())) {
            if(accounts.list().isEmpty())accounts.create("Alice","alice-password".toCharArray());
        }
        return new CharacterStore(new Database(directory,false),1,"Alice");
    }
    static Knight choices(String name) { return new Knight(0, name, "Gear/Helm/Spiral Sallet (Blue)", "Gear/Armor/Spiral Culet (Blue)", null, null, "Blue", 1, 2, 0, 0); }
    private static void refused(Operation operation, String reason) throws Exception {
        try { operation.run(); throw new AssertionError("Invalid creation accepted"); }
        catch (CharacterStore.Refused expected) { TestWire.check(expected.getMessage().contains(reason), "useful refusal reason"); }
    }
    @FunctionalInterface private interface Operation { void run() throws Exception; }
}
