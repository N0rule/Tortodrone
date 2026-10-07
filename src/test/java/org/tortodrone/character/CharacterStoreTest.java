package org.tortodrone.character;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.support.TestWire;

/** Persistence failures and competing creations must never lose or duplicate characters. */
public final class CharacterStoreTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("tortodrone-profile-test-");
        try {
            CharacterStore store = new CharacterStore(directory);
            TestWire.check(store.roster().isEmpty(), "fresh account");
            Knight first = store.create(choices("Saved-Knight"));
            TestWire.check(first.id() == 1 && first.createdAt() > 0, "server assigns identity and timestamp");
            Path profile = directory.resolve("accounts/1.properties");
            byte[] saved = Files.readAllBytes(profile);
            refused(() -> store.create(choices("saved-knight")), "already in use");
            refused(() -> store.create(choices("x")), "three letters");
            refused(() -> store.create(new Knight(0, "Bad-Choice", "../helm", "Gear/Armor/Spiral Culet (Blue)", null, null, "Blue", 1, 2, 0, 0)), "appearance");
            TestWire.check(Arrays.equals(saved, Files.readAllBytes(profile)), "rejections preserve existing save");
            // Renaming the login must preserve profiles created with the previous test account.
            Files.writeString(profile, Files.readString(profile).replace("username=tortodrone\n", "username=tortodrone-local-probe\n"));
            CharacterStore restored = new CharacterStore(directory);
            TestWire.check(restored.roster().equals(store.roster()), "all saved fields restored");
            TestWire.check(restored.create(choices("Second-Knight")).id() == 2, "IDs continue after restart");
            TestWire.check(Files.readString(profile).contains("username=tortodrone\n"), "next save uses renamed account");
            restored.create(choices("Third-Knight"));
            refused(() -> restored.create(choices("Fourth-Knight")), "slots");

            // A failure before the atomic rename must neither publish nor consume an ID.
            Path blocked = directory.resolve("blocked"); Files.createDirectories(blocked);
            Files.writeString(blocked.resolve("accounts"), "not a directory");
            CharacterStore failure = new CharacterStore(blocked);
            try { failure.create(choices("Retry-Knight")); throw new AssertionError("Save failure accepted"); }
            catch (IOException expected) { }
            TestWire.check(failure.roster().isEmpty(), "failed write not published");
            Files.delete(blocked.resolve("accounts"));
            TestWire.check(failure.create(choices("Retry-Knight")).id() == 1, "failed save did not consume ID");

            byte[] complete = Files.readAllBytes(profile);
            Files.writeString(profile, "schema=1\nusername=tortodrone-local-probe\nknights=1\nnext-id=2\n");
            byte[] corrupt = Files.readAllBytes(profile);
            try { new CharacterStore(directory); throw new AssertionError("Corrupt profile accepted"); }
            catch (IOException expected) { }
            TestWire.check(Arrays.equals(corrupt, Files.readAllBytes(profile)), "corruption never silently resets data");
            Files.write(profile, complete);

            CharacterStore competing = new CharacterStore(directory.resolve("competing"));
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                Callable<Boolean> create = () -> { try { competing.create(choices("Same-Knight")); return true; }
                    catch (CharacterStore.Refused expected) { return false; } };
                var results = workers.invokeAll(List.of(create, create));
                TestWire.check(results.get(0).get() != results.get(1).get() && competing.roster().size() == 1,
                    "competing requests produce one character");
                TestWire.check(new CharacterStore(directory.resolve("competing")).roster().equals(competing.roster()),
                    "concurrent creation persisted");
            } finally { workers.shutdownNow(); }
            System.out.println("PASS: profile persistence, duplicate names, slots, malformed saves, failed writes, concurrent creation");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
    static Knight choices(String name) { return new Knight(0, name, "Gear/Helm/Spiral Sallet (Blue)", "Gear/Armor/Spiral Culet (Blue)", null, null, "Blue", 1, 2, 0, 0); }
    private static void refused(Operation operation, String reason) throws Exception {
        try { operation.run(); throw new AssertionError("Invalid creation accepted"); }
        catch (CharacterStore.Refused expected) { TestWire.check(expected.getMessage().contains(reason), "useful refusal reason"); }
    }
    @FunctionalInterface private interface Operation { void run() throws Exception; }
}
