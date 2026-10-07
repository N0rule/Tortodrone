package org.tortodrone.character;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.tortodrone.support.TestWire;

/** Edits/deletions must preserve identities and remain atomic on failure and contention. */
public final class CharacterManagementStoreTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("tortodrone-management-test-");
        try {
            CharacterStore store = new CharacterStore(directory);
            Knight first = store.create(choices("First-Knight"));
            store.create(choices("Other-Knight"));
            store.create(choices("Third-Knight"));
            Knight edited = store.update("first-knight", new Knight(999, "Renamed-Knight", "Gear/Helm/Spiral Round Helm (Red)", "Gear/Armor/Spiral Flak Jacket (Red)",
                "Helm/Top/Headband/Headband", "Helm/Back/Plume/Plume", "Red", 17, 4, 46, 999));
            TestWire.check(edited.id() == first.id() && edited.createdAt() == first.createdAt(), "edit retains server identity and timestamp");
            TestWire.check(edited.color() == 17 && edited.eye() == 46 && edited.helmAccessory().equals("Helm/Top/Headband/Headband"), "appearance choices are editable");
            TestWire.check(new CharacterStore(directory).roster().equals(store.roster()), "edited appearance persists at full slot capacity");
            Path profile = directory.resolve("accounts/1.properties");
            byte[] saved = Files.readAllBytes(profile);
            refused(() -> store.update("Renamed-Knight", choices("other-knight")), "already in use");
            refused(() -> store.update("Missing-Knight", choices("New-Knight")), "No such knight");
            refused(() -> store.update("Renamed-Knight", choices("x")), "three letters");
            refused(() -> store.update("Renamed-Knight", new Knight(0, "Renamed-Knight", "../bad", "Gear/Armor/Spiral Culet (Blue)", null, null, "Blue", 1, 2, 0, 0)), "appearance");
            refused(() -> store.delete("Missing-Knight"), "No such knight");
            refused(() -> store.delete(null), "No such knight");
            TestWire.check(Arrays.equals(saved, Files.readAllBytes(profile)), "refused operations preserve the saved profile");
            List<Knight> before = store.roster();
            Path backup = directory.resolve("original-profile");
            Files.move(profile, backup);
            Files.createDirectory(profile);
            try {
                failed(() -> store.update("Renamed-Knight", choices("Lost-Knight")));
                failed(() -> store.delete("Renamed-Knight"));
                TestWire.check(store.roster().equals(before), "failed edit/delete never publishes changes");
                TestWire.check(Arrays.equals(saved, Files.readAllBytes(backup)), "failed mutations preserve original saved bytes");
            } finally {
                Files.delete(profile);
                Files.move(backup, profile);
            }
            store.delete("other-knight");
            Knight replacement = store.create(choices("Next-Knight"));
            TestWire.check(replacement.id() == 4, "deletion frees a slot without recycling character IDs");
            TestWire.check(new CharacterStore(directory).roster().equals(store.roster()), "deleted roster and replacement survive restart");
            var workers = Executors.newFixedThreadPool(2);
            try {
                Callable<Boolean> deletion = () -> {
                    try { store.delete("Renamed-Knight"); return true; }
                    catch (CharacterStore.Refused expected) { return false; }
                };
                var results = workers.invokeAll(List.of(deletion, deletion));
                TestWire.check(results.get(0).get() != results.get(1).get(), "competing deletions succeed exactly once");
                TestWire.check(new CharacterStore(directory).roster().equals(store.roster()), "competing deletions remain persisted");
            } finally { workers.shutdownNow(); }
            for (Knight knight : store.roster()) store.delete(knight.name());
            TestWire.check(new CharacterStore(directory).roster().isEmpty(), "deleting the last knight persists an empty roster");
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
