package org.tortodrone.character;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import org.tortodrone.account.AccountStore;
import org.tortodrone.core.LockOrder;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.character.Knight;
import org.tortodrone.core.character.KnightMemory;
import org.tortodrone.core.character.KnightMemoryState;
import org.tortodrone.core.character.KnightObjective;
import org.tortodrone.core.character.KnightStory;
import org.tortodrone.core.character.RosterEntry;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.character.WorldProfile;
import org.tortodrone.core.character.WorldSummary;
import org.tortodrone.storage.Database;

/** Account-owned roster with transactional SQLite saves. */
public final class CharacterStore {
    public static final String USERNAME = AccountStore.DEFAULT_USERNAME;
    static final int MAX_KNIGHTS = 3;
    private final Database database;
    private final String username;
    private final int accountId;
    private ItemCatalog catalog = ItemCatalog.starter();
    private final KnightStorage storage;
    private final CharacterServices services;

    public ItemCatalog catalog() {
        return catalog;
    }
    /** Storage-facing services for session handlers; the roster stays storage-free. */
    public CharacterServices services() {
        return services;
    }

    private List<Knight> knights = List.of();
    private int nextId = 1;
    private long storageRevision;
    private final Set<RosterListener> listeners = new LinkedHashSet<>();

    /** One published available knight: identity plus the preview model the picker renders. */
    @FunctionalInterface
    public interface RosterListener {
        void receive(List<RosterEntry> roster) throws IOException;
    }

    @FunctionalInterface
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    /** Snapshot and registration share the mutation lock, so an update cannot overtake its snapshot. */
    public synchronized Subscription subscribe(RosterListener initial, RosterListener updates) throws IOException {
        LockOrder.enter(LockOrder.CHARACTER);
        try {
            refresh();
            initial.receive(available());
            listeners.add(updates);
            return () -> {
                LockOrder.enter(LockOrder.CHARACTER);
                try {
                    synchronized (CharacterStore.this) {
                        listeners.remove(updates);
                    }
                } finally {
                    LockOrder.exit();
                }
            };
        } finally {
            LockOrder.exit();
        }
    }

    /** Listeners enqueue bounded downstream frames; they must not block on network I/O. */
    private void notifyListeners() throws IOException {
        List<RosterEntry> published = available();
        for (var iterator = listeners.iterator(); iterator.hasNext(); ) {
            try {
                iterator.next().receive(published);
            } catch (IOException e) {
                iterator.remove();
            } // A failed peer must not invalidate a committed save.
        }
    }

    /** In-memory store for isolated wire tests. Production uses SQLite. */
    public CharacterStore() {
        this(1, USERNAME);
    }

    public CharacterStore(int accountId, String username) {
        if (accountId < 1 || username == null) throw new IllegalArgumentException("Invalid account identity");
        database = null;
        this.username = username;
        this.accountId = accountId;
        storage = new KnightStorage(database, accountId, catalog);
        services = new CharacterServices(database, catalog);
    }

    public CharacterStore(Database database, int accountId, String username) throws IOException {
        this(database, accountId, username, ItemCatalog.starter());
    }

    public CharacterStore(Database database, int accountId, String username, ItemCatalog catalog) throws IOException {
        this.database = database;
        this.accountId = accountId;
        this.username = username;
        this.catalog = catalog;
        storage = new KnightStorage(database, accountId, catalog);
        services = new CharacterServices(database, catalog);
        refresh();
    }

    public WorldProfile grantItems(String username, String name, String config, int quantity, long revision)
            throws IOException {
        if (database == null) throw new IOException("Item grants require SQLite storage");
        // No cross-account CharacterStore locks; resolve target and permissions in SQLite.
        return database.grantItems(username, name, -1, -1, catalog.require(config), quantity, accountId, revision);
    }

    public WorldProfile createItem(
            String name, String config, int quantity, long revision, boolean locked, int expireDays)
            throws IOException {
        if (database == null) throw new IOException("Item grants require SQLite storage");
        var entry = catalog.require(config);
        return database.grantItems(
                username,
                name,
                accountId,
                -1,
                entry,
                quantity,
                accountId,
                revision,
                ItemModifiers.NONE,
                org.tortodrone.core.town.TownCatalog.empty(),
                locked,
                expireDays == 0 ? entry.expireDays() : expireDays);
    }

    /** Stamp when a knight's session ended, for the guild roster. No-op without SQLite storage. */
    public void recordLastSession(int worldId, long when) throws IOException {
        if (database == null) return;
        database.lastSession(worldId, when);
    }

    public synchronized void refresh() throws IOException {
        LockOrder.enter(LockOrder.CHARACTER);
        try {
            if (database == null) return;
            var state = database.rosterState(accountId);
            if (storageRevision == state.revision()) return;
            boolean changed = !knights.equals(state.knights());
            knights = state.knights();
            nextId = state.next();
            storageRevision = state.revision();
            if (changed) notifyListeners();
        } finally {
            LockOrder.exit();
        }
    }

    public synchronized int nextId() {
        LockOrder.enter(LockOrder.CHARACTER);
        try {
            return nextId;
        } finally {
            LockOrder.exit();
        }
    }

    public boolean hasWorldStorage() {
        return database != null;
    }

    public Path worldStorageFile() {
        return database.file();
    }
    /** Domain view of the poller's batch metadata; storage records stay inside the facade. */
    public Map<Integer, WorldSummary> worldStates() throws IOException {
        return Map.copyOf(database.worldStates());
    }

    public String username() {
        return username;
    }

    public int accountId() {
        return accountId;
    }

    public synchronized List<Knight> roster() {
        LockOrder.enter(LockOrder.CHARACTER);
        try {
            return knights;
        } finally {
            LockOrder.exit();
        }
    }

    /**
     * Available knights for the character picker. Each preview composes the knight's currently
     * equipped appearance, not the creation choices, so the picker follows Arsenal equipment.
     * Composed under the publication lock; a profile that cannot be read falls back to the saved
     * creation outfit instead of failing the roster publication.
     */
    public synchronized List<RosterEntry> available() throws IOException {
        LockOrder.enter(LockOrder.CHARACTER);
        try {
            refresh();
            var result = new ArrayList<RosterEntry>();
            for (Knight knight : knights) {
                WorldProfile profile = null;
                if (database != null)
                    try {
                        profile = database.profile(accountId, knight.id());
                    } catch (IOException unreadable) {
                        /* Preserve the creation-outfit fallback. */
                    }
                result.add(new RosterEntry(
                        knight,
                        profile == null ? preview(knight) : StarterCatalog.equipped(knight, profile, catalog),
                        profile == null ? 1 : profile.progress().rank()));
            }
            return List.copyOf(result);
        } finally {
            LockOrder.exit();
        }
    }

    private StarterCatalog.Model preview(Knight knight) {
        if (database == null) return StarterCatalog.preview(knight);
        try {
            return StarterCatalog.equipped(knight, database.profile(accountId, knight.id()), catalog);
        } catch (IOException unreadable) {
            return StarterCatalog.preview(knight);
        }
    }

    public synchronized String nameFailure(String name) throws IOException {
        LockOrder.enter(LockOrder.CHARACTER);
        try {
            String error = nameError(name);
            if (error != null) return error;
            return (database != null
                            ? database.knightNameInUse(name, -1, -1)
                            : knights.stream()
                                    .anyMatch(knight -> canonical(knight.name()).equals(canonical(name))))
                    ? "That knight name is already in use"
                    : null;
        } finally {
            LockOrder.exit();
        }
    }

    public synchronized Knight create(Knight choices) throws IOException, Refused {
        LockOrder.enter(LockOrder.CHARACTER);
        try {
            return create(choices, -1);
        } finally {
            LockOrder.exit();
        }
    }

    public synchronized Knight create(Knight choices, long authRevision) throws IOException, Refused {
        LockOrder.enter(LockOrder.CHARACTER);
        try {
            return create(choices, authRevision, false);
        } finally {
            LockOrder.exit();
        }
    }
    /** Native creator: first-run state and the starter inventory share one roster transaction. */
    public synchronized Knight createForOpening(Knight choices, long authRevision) throws IOException, Refused {
        LockOrder.enter(LockOrder.CHARACTER);
        try {
            if (database == null || authRevision < 0) throw new IOException("Authenticated opening owner required");
            return create(choices, authRevision, true);
        } finally {
            LockOrder.exit();
        }
    }

    private Knight create(Knight choices, long authRevision, boolean opening) throws IOException, Refused {
        refresh();
        String error = nameFailure(choices.name());
        if (error != null) throw new Refused(error);
        try {
            validate(choices);
            if (!StarterCatalog.accepts(choices)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new Refused("Invalid character appearance");
        }
        if (knights.size() >= MAX_KNIGHTS) throw new Refused("All three knight slots are occupied");
        if (nextId == Integer.MAX_VALUE) throw new IOException("Character ID space exhausted");
        Knight created = new Knight(
                nextId,
                choices.name(),
                choices.helm(),
                choices.armor(),
                choices.helmAccessory(),
                choices.armorAccessory(),
                choices.costume(),
                choices.color(),
                choices.height(),
                choices.eye(),
                System.currentTimeMillis());
        List<Knight> updated = new ArrayList<>(knights);
        updated.add(created);
        save(updated, nextId + 1, authRevision, opening ? created.id() : 0); // Commit before acknowledgement.
        knights = List.copyOf(updated);
        nextId++;
        notifyListeners();
        return created;
    }

    public synchronized Knight update(String oldName, Knight choices) throws IOException, Refused {
        LockOrder.enter(LockOrder.CHARACTER);
        try {
            return update(oldName, choices, -1);
        } finally {
            LockOrder.exit();
        }
    }

    public synchronized Knight update(String oldName, Knight choices, long authRevision) throws IOException, Refused {
        LockOrder.enter(LockOrder.CHARACTER);
        try {
            refresh();
            int index = indexOf(oldName);
            if (index < 0) throw new Refused("No such knight");
            String error = nameError(choices.name());
            if (error != null) throw new Refused(error);
            try {
                validate(choices);
                if (!StarterCatalog.accepts(choices)) throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) {
                throw new Refused("Invalid character appearance");
            }
            Knight previous = knights.get(index);
            if (database != null && database.knightNameInUse(choices.name(), accountId, previous.id()))
                throw new Refused("That knight name is already in use");
            for (Knight knight : knights) {
                if (knight.id() != previous.id() && canonical(knight.name()).equals(canonical(choices.name()))) {
                    throw new Refused("That knight name is already in use");
                }
            }
            Knight updated = new Knight(
                    previous.id(),
                    choices.name(),
                    choices.helm(),
                    choices.armor(),
                    choices.helmAccessory(),
                    choices.armorAccessory(),
                    choices.costume(),
                    choices.color(),
                    choices.height(),
                    choices.eye(),
                    previous.createdAt());
            List<Knight> roster = new ArrayList<>(knights);
            roster.set(index, updated);
            save(roster, nextId, authRevision);
            knights = List.copyOf(roster);
            notifyListeners();
            return updated;
        } finally {
            LockOrder.exit();
        }
    }

    public synchronized void delete(String name) throws IOException, Refused {
        LockOrder.enter(LockOrder.CHARACTER);
        try {
            delete(name, -1);
        } finally {
            LockOrder.exit();
        }
    }

    public synchronized void delete(String name, long authRevision) throws IOException, Refused {
        LockOrder.enter(LockOrder.CHARACTER);
        try {
            refresh();
            int index = indexOf(name);
            if (index < 0) throw new Refused("No such knight");
            List<Knight> roster = new ArrayList<>(knights);
            roster.remove(index);
            save(roster, nextId, authRevision);
            knights = List.copyOf(roster);
            notifyListeners();
        } finally {
            LockOrder.exit();
        }
    }

    /** Resolves only an owned character. The network layer publishes selection separately. */
    public synchronized WorldProfile worldProfile(String name, long authRevision) throws IOException, Refused {
        LockOrder.enter(LockOrder.CHARACTER);
        try {
            return worldProfile(name, authRevision, null);
        } finally {
            LockOrder.exit();
        }
    }
    /** Reuse unchanged inventory after a current ownership/authenticated revision check. */
    public synchronized WorldProfile worldProfile(String name, long authRevision, WorldProfile cached)
            throws IOException, Refused {
        LockOrder.enter(LockOrder.CHARACTER);
        try {
            refresh();
            int index = indexOf(name);
            if (index < 0) throw new Refused("No such knight");
            return storage.worldProfile(knights.get(index).id(), authRevision, cached);
        } finally {
            LockOrder.exit();
        }
    }

    public synchronized WorldProfile equip(int knightId, long revision, long itemId, Integer slot) throws IOException {
        LockOrder.enter(LockOrder.CHARACTER);
        try {
            return storage.equip(knightId, revision, itemId, slot);
        } finally {
            LockOrder.exit();
        }
    }

    /** Trade coordinator never takes an account's roster lock while holding its own lock. */
    public WorldProfile tradeProfile(int knightId, long authRevision) throws IOException {
        return storage.tradeProfile(knightId, authRevision);
    }

    public boolean inspectionHidden(long authRevision) throws IOException {
        return storage.inspectionHidden(authRevision);
    }

    public int lastTownScene(int knightId, long authRevision) throws IOException {
        return storage.lastTownScene(knightId, authRevision);
    }

    public void lastTownScene(int knightId, long authRevision, int scene) throws IOException {
        storage.lastTownScene(knightId, authRevision, scene);
    }

    public WorldProfile completeTutorial(int knightId, long authRevision, WorldProfile expected) throws IOException {
        return completeTutorial(knightId, authRevision, expected, 0, null);
    }

    public WorldProfile completeTutorial(
            int knightId,
            long authRevision,
            WorldProfile expected,
            float heat,
            org.tortodrone.core.town.TownCatalog rules)
            throws IOException {
        return storage.completeTutorial(knightId, authRevision, expected, heat, rules);
    }

    public Set<String> knightMemories(int knightId, long authRevision) throws IOException {
        return storage.knightMemories(knightId, authRevision);
    }

    public Map<String, KnightObjective.State> knightObjectives(int knightId, long authRevision) throws IOException {
        return storage.knightObjectives(knightId, authRevision);
    }

    public KnightStory knightStory(
            int knightId,
            long authRevision,
            WorldProfile expected,
            List<KnightMemory> memories,
            List<KnightObjective> objectives)
            throws IOException {
        return storage.knightStory(knightId, authRevision, expected, memories, objectives);
    }

    public KnightMemoryState knightMemories(
            int knightId, long authRevision, WorldProfile expected, List<KnightMemory> operations) throws IOException {
        return storage.knightMemories(knightId, authRevision, expected, operations);
    }

    public void inspectionHidden(long authRevision, boolean hidden) throws IOException {
        storage.inspectionHidden(authRevision, hidden);
    }

    public List<WorldProfile> trade(
            WorldProfile first,
            long firstAuth,
            Map<Long, Integer> firstItems,
            int firstCrowns,
            int firstEnergy,
            WorldProfile second,
            long secondAuth,
            Map<Long, Integer> secondItems,
            int secondCrowns,
            int secondEnergy)
            throws IOException {
        return storage.trade(
                first,
                firstAuth,
                firstItems,
                firstCrowns,
                firstEnergy,
                second,
                secondAuth,
                secondItems,
                secondCrowns,
                secondEnergy);
    }

    private int indexOf(String name) {
        if (name == null) return -1;
        for (int i = 0; i < knights.size(); i++) {
            if (canonical(knights.get(i).name()).equals(canonical(name))) return i;
        }
        return -1;
    }

    private void save(List<Knight> updated, int next, long authRevision) throws IOException, Refused {
        save(updated, next, authRevision, 0);
    }

    private void save(List<Knight> updated, int next, long authRevision, int openingKnight)
            throws IOException, Refused {
        if (database != null) {
            try {
                database.saveRoster(accountId, updated, next, authRevision, storageRevision, openingKnight);
            } catch (Database.KnightNameInUse conflict) {
                throw new Refused(conflict.getMessage());
            }
            storageRevision++;
            return;
        }
    }

    private static void validate(Knight knight) {
        KnightIdentity.validate(knight);
    }

    private static String canonical(String name) {
        return KnightIdentity.canonical(name);
    }
    /** Stock knight-name policy, shared with auction and town-name checks. */
    public static String nameError(String name) {
        return KnightIdentity.nameError(name);
    }

    public static final class Refused extends Exception {
        Refused(String message) {
            super(message);
        }
    }
}
