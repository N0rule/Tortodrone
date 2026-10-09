package org.tortodrone.character;

import java.io.IOException;
import java.util.*;
import static org.tortodrone.support.TestWire.check;

public final class RosterSubscriptionTest {
    public static void main(String[] args) throws Exception {
        CharacterStore store = new CharacterStore(); List<List<CharacterStore.RosterEntry>> first = new ArrayList<>(), second = new ArrayList<>();
        var subscription = store.subscribe(first::add, first::add);
        var another = store.subscribe(second::add, second::add);
        var failed = store.subscribe(ignored -> {}, ignored -> { throw new IOException("closed peer"); });
        store.create(CharacterStoreTest.choices("First-Knight"));
        store.update("First-Knight", CharacterStoreTest.choices("Updated-Knight"));
        check(first.size() == 3 && second.equals(first), "snapshot then committed changes reach each observer");
        check(first.get(0).isEmpty() && first.get(1).get(0).knight().name().equals("First-Knight"), "published snapshots cannot mutate retroactively");
        try { store.create(CharacterStoreTest.choices("Updated-Knight")); throw new AssertionError("duplicate accepted"); }
        catch (CharacterStore.Refused expected) { }
        check(first.size() == 3, "refused mutation has no update");
        subscription.close(); subscription.close();
        store.delete("Updated-Knight");
        check(first.size() == 3 && second.size() == 4 && second.get(3).isEmpty(), "unsubscribe is idempotent and leaves other observer active");
        another.close(); failed.close();
        try { store.subscribe(ignored -> { throw new IOException("snapshot failed"); }, second::add); throw new AssertionError("failed snapshot accepted"); }
        catch (IOException expected) { }
        store.create(CharacterStoreTest.choices("Next-Knight"));
        check(second.size() == 4, "failed initial snapshot does not install a subscriber");
        System.out.println("PASS: immutable roster snapshots, ordered publication, failed-peer isolation and subscription cleanup");
    }
}
