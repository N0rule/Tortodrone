package org.tortodrone.character;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.AccountStore;
import org.tortodrone.storage.AtomicProperties;
import org.tortodrone.storage.Database;

/** Account-owned roster with transactional SQLite saves; legacy files support migration and tests. */
public final class CharacterStore {
    public static final String USERNAME = AccountStore.DEFAULT_USERNAME;
    static final int MAX_KNIGHTS = 3;
    private final Path file;
    private final Database database;
    private final String username;
    private final int accountId;
    private List<Knight> knights = List.of();
    private int nextId = 1;
    private final Set<RosterListener> listeners = new LinkedHashSet<>();

    @FunctionalInterface public interface RosterListener { void receive(List<Knight> roster) throws IOException; }
    @FunctionalInterface public interface Subscription extends AutoCloseable { @Override void close(); }

    /** Snapshot and registration share the mutation lock, so an update cannot overtake its snapshot. */
    public synchronized Subscription subscribe(RosterListener initial, RosterListener updates) throws IOException {
        initial.receive(knights);
        listeners.add(updates);
        return () -> { synchronized (CharacterStore.this) { listeners.remove(updates); } };
    }

    /** Listeners enqueue bounded downstream frames; they must not block on network I/O. */
    private void notifyListeners() {
        for (var iterator = listeners.iterator(); iterator.hasNext();) {
            try { iterator.next().receive(knights); }
            catch (IOException e) { iterator.remove(); } // A failed peer must not invalidate a committed save.
        }
    }

    /** In-memory store for isolated wire tests. Production uses SQLite. */
    public CharacterStore() { this(1, USERNAME); }

    public CharacterStore(int accountId, String username) {
        if (accountId < 1 || username == null) throw new IllegalArgumentException("Invalid account identity");
        file = null; database = null; this.username = username; this.accountId = accountId;
    }

    public CharacterStore(Path dataDirectory) throws IOException {
        this(dataDirectory, 1, USERNAME);
    }

    public CharacterStore(Path dataDirectory, int accountId, String username) throws IOException {
        if (accountId < 1 || username == null) throw new IllegalArgumentException("Invalid account identity");
        this.username = username; this.accountId = accountId; database = null;
        file = dataDirectory.resolve("accounts/" + accountId + ".properties");
        if (!Files.exists(file)) return;
        if (Files.size(file) > 32768) throw new IOException("Character profile exceeds size limit");
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) { properties.load(reader); }
        try {
            String savedUsername = properties.getProperty("username");
            // The renamed test account retains account ID 1 and its saved characters.
            if (!"1".equals(properties.getProperty("schema"))
                    || (!username.equals(savedUsername) && !(accountId == 1 && USERNAME.equals(username)
                        && "tortodrone-local-probe".equals(savedUsername)))) {
                throw new IllegalArgumentException();
            }
            int count = Integer.parseInt(properties.getProperty("knights"));
            nextId = Integer.parseInt(properties.getProperty("next-id"));
            if (count < 0 || count > MAX_KNIGHTS || nextId < 1) throw new IllegalArgumentException();
            List<Knight> loaded = new ArrayList<>();
            Set<Integer> ids = new HashSet<>(); Set<String> names = new HashSet<>();
            for (int i = 0; i < count; i++) {
                String prefix = "knight." + i + ".";
                Knight knight = new Knight(Integer.parseInt(properties.getProperty(prefix + "id")),
                    properties.getProperty(prefix + "name"), properties.getProperty(prefix + "helm"),
                    properties.getProperty(prefix + "armor"), properties.getProperty(prefix + "helm-accessory"),
                    properties.getProperty(prefix + "armor-accessory"), properties.getProperty(prefix + "costume"),
                    Integer.parseInt(properties.getProperty(prefix + "color")),
                    Integer.parseInt(properties.getProperty(prefix + "height")),
                    Integer.parseInt(properties.getProperty(prefix + "eye")),
                    Long.parseLong(properties.getProperty(prefix + "created-at")));
                validate(knight);
                if (knight.id() < 1 || knight.id() >= nextId || knight.createdAt() < 1
                        || !ids.add(knight.id()) || !names.add(canonical(knight.name()))) throw new IllegalArgumentException();
                loaded.add(knight);
            }
            knights = List.copyOf(loaded);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException("Invalid character profile; saved characters were not modified", e);
        }
    }

    public CharacterStore(Database database, int accountId, String username) throws IOException {
        this.database = database; this.file = null; this.accountId = accountId; this.username = username;
        knights = database.roster(accountId); nextId = database.nextKnight(accountId);
    }

    public synchronized int nextId() { return nextId; }

    public String username() { return username; }
    public int accountId() { return accountId; }

    public synchronized List<Knight> roster() { return knights; }

    public synchronized String nameFailure(String name) {
        String error = nameError(name);
        if (error != null) return error;
        return knights.stream().anyMatch(knight -> canonical(knight.name()).equals(canonical(name)))
            ? "That knight name is already in use" : null;
    }

    public synchronized Knight create(Knight choices) throws IOException, Refused {
        return create(choices, -1);
    }

    public synchronized Knight create(Knight choices, long authRevision) throws IOException, Refused {
        String error = nameFailure(choices.name());
        if (error != null) throw new Refused(error);
        try { validate(choices); if (!StarterCatalog.accepts(choices)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException e) { throw new Refused("Invalid character appearance"); }
        if (knights.size() >= MAX_KNIGHTS) throw new Refused("All three knight slots are occupied");
        if (nextId == Integer.MAX_VALUE) throw new IOException("Character ID space exhausted");
        Knight created = new Knight(nextId, choices.name(), choices.helm(), choices.armor(), choices.helmAccessory(),
            choices.armorAccessory(), choices.costume(), choices.color(), choices.height(), choices.eye(), System.currentTimeMillis());
        List<Knight> updated = new ArrayList<>(knights); updated.add(created);
        save(updated, nextId + 1, authRevision); // Acknowledge only after persistence succeeds.
        knights = List.copyOf(updated); nextId++;
        notifyListeners();
        return created;
    }

    public synchronized Knight update(String oldName, Knight choices) throws IOException, Refused {
        return update(oldName, choices, -1);
    }

    public synchronized Knight update(String oldName, Knight choices, long authRevision) throws IOException, Refused {
        int index = indexOf(oldName);
        if (index < 0) throw new Refused("No such knight");
        String error = nameError(choices.name());
        if (error != null) throw new Refused(error);
        try { validate(choices); if (!StarterCatalog.accepts(choices)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException e) { throw new Refused("Invalid character appearance"); }
        Knight previous = knights.get(index);
        for (Knight knight : knights) {
            if (knight.id() != previous.id() && canonical(knight.name()).equals(canonical(choices.name()))) {
                throw new Refused("That knight name is already in use");
            }
        }
        Knight updated = new Knight(previous.id(), choices.name(), choices.helm(), choices.armor(),
            choices.helmAccessory(), choices.armorAccessory(), choices.costume(), choices.color(),
            choices.height(), choices.eye(), previous.createdAt());
        List<Knight> roster = new ArrayList<>(knights);
        roster.set(index, updated);
        save(roster, nextId, authRevision);
        knights = List.copyOf(roster);
        notifyListeners();
        return updated;
    }

    public synchronized void delete(String name) throws IOException, Refused {
        delete(name, -1);
    }

    public synchronized void delete(String name, long authRevision) throws IOException, Refused {
        int index = indexOf(name);
        if (index < 0) throw new Refused("No such knight");
        List<Knight> roster = new ArrayList<>(knights);
        roster.remove(index);
        save(roster, nextId, authRevision);
        knights = List.copyOf(roster);
        notifyListeners();
    }

    /** Resolves only an owned character. Loading this foundation does not acknowledge world entry. */
    public synchronized WorldProfile worldProfile(String name,long authRevision) throws IOException, Refused {
        int index = indexOf(name);
        if (index < 0) throw new Refused("No such knight");
        if (database == null) throw new IOException("World profiles require SQLite storage");
        return database.worldProfile(accountId,knights.get(index).id(),authRevision);
    }

    private int indexOf(String name) {
        if (name == null) return -1;
        for (int i = 0; i < knights.size(); i++) {
            if (canonical(knights.get(i).name()).equals(canonical(name))) return i;
        }
        return -1;
    }

    private void save(List<Knight> updated, int next, long authRevision) throws IOException {
        if (database != null) { database.saveRoster(accountId, updated, next, authRevision); return; }
        if (file == null) return;
        Files.createDirectories(file.getParent());
        Properties properties = new Properties();
        properties.setProperty("schema", "1"); properties.setProperty("username", username);
        properties.setProperty("next-id", String.valueOf(next)); properties.setProperty("knights", String.valueOf(updated.size()));
        for (int i = 0; i < updated.size(); i++) {
            String prefix = "knight." + i + "."; Knight knight = updated.get(i);
            properties.setProperty(prefix + "id", String.valueOf(knight.id()));
            properties.setProperty(prefix + "name", knight.name());
            properties.setProperty(prefix + "helm", knight.helm()); properties.setProperty(prefix + "armor", knight.armor());
            if (knight.helmAccessory() != null) properties.setProperty(prefix + "helm-accessory", knight.helmAccessory());
            if (knight.armorAccessory() != null) properties.setProperty(prefix + "armor-accessory", knight.armorAccessory());
            properties.setProperty(prefix + "costume", knight.costume());
            properties.setProperty(prefix + "color", String.valueOf(knight.color()));
            properties.setProperty(prefix + "height", String.valueOf(knight.height()));
            properties.setProperty(prefix + "eye", String.valueOf(knight.eye()));
            properties.setProperty(prefix + "created-at", String.valueOf(knight.createdAt()));
        }
        AtomicProperties.save(file, properties, "Tortodrone character profile");
    }

    private static void validate(Knight knight) {
        if (nameError(knight.name()) != null || !asset(knight.helm(), false) || !asset(knight.armor(), false)
                || !asset(knight.helmAccessory(), true) || !asset(knight.armorAccessory(), true)
                || !asset(knight.costume(), false) || knight.color() < 0 || knight.color() > 255
                || knight.height() < 0 || knight.height() >= 5 || knight.eye() < 0 || knight.eye() >= 47) {
            throw new IllegalArgumentException("Invalid character choices");
        }
    }

    private static boolean asset(String value, boolean optional) {
        if (value == null) return optional;
        if (value.isEmpty()) return optional;
        return value.length() <= 256 && value.chars().noneMatch(c -> Character.isISOControl(c))
            && !value.contains("..") && !value.contains("\\") && !value.startsWith("/");
    }
    private static String canonical(String name) { return name.toLowerCase(Locale.ROOT); }
    private static String nameError(String name) {
        if (name == null || name.length() < 3) return "Knight names need at least three letters";
        if (name.length() > 16) return "Knight names may contain at most sixteen characters";
        if (!name.matches("[A-Za-z]+(?:-[A-Za-z]+)*")) return "Use letters with optional single hyphens in the knight name";
        return null;
    }
    public static final class Refused extends Exception { Refused(String message) { super(message); } }
}
