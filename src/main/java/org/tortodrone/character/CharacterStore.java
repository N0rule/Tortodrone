package org.tortodrone.character;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import org.tortodrone.account.AccountStore;
import org.tortodrone.storage.Database;

/** Account-owned roster with transactional SQLite saves. */
public final class CharacterStore {
    public static final String USERNAME = AccountStore.DEFAULT_USERNAME;
    static final int MAX_KNIGHTS = 3;
    private final Database database;
    private final String username;
    private final int accountId;
    private ItemCatalog catalog=ItemCatalog.starter();
    public ItemCatalog catalog(){return catalog;}
    public org.tortodrone.storage.AuctionHouse auction(org.tortodrone.town.TownCatalog rules)throws IOException{if(database==null)throw new IOException("Auctions require SQLite storage");return new org.tortodrone.storage.AuctionHouse(database,catalog,rules);}
    public org.tortodrone.storage.TownStore town(org.tortodrone.town.TownCatalog rules)throws IOException{if(database==null)throw new IOException("Town operations require SQLite storage");return new org.tortodrone.storage.TownStore(database,catalog,rules);}
    private List<Knight> knights = List.of();
    private int nextId = 1;
    private long storageRevision;
    private final Set<RosterListener> listeners = new LinkedHashSet<>();

    /** One published available knight: identity plus the preview model the picker renders. */
    public record RosterEntry(Knight knight, StarterCatalog.Model preview,int rank) {
        public RosterEntry(Knight knight,StarterCatalog.Model preview){this(knight,preview,1);}
    }

    @FunctionalInterface public interface RosterListener { void receive(List<RosterEntry> roster) throws IOException; }
    @FunctionalInterface public interface Subscription extends AutoCloseable { @Override void close(); }

    /** Snapshot and registration share the mutation lock, so an update cannot overtake its snapshot. */
    public synchronized Subscription subscribe(RosterListener initial, RosterListener updates) throws IOException {
        refresh();
        initial.receive(available());
        listeners.add(updates);
        return () -> { synchronized (CharacterStore.this) { listeners.remove(updates); } };
    }

    /** Listeners enqueue bounded downstream frames; they must not block on network I/O. */
    private void notifyListeners() throws IOException {
        List<RosterEntry> published = available();
        for (var iterator = listeners.iterator(); iterator.hasNext();) {
            try { iterator.next().receive(published); }
            catch (IOException e) { iterator.remove(); } // A failed peer must not invalidate a committed save.
        }
    }

    /** In-memory store for isolated wire tests. Production uses SQLite. */
    public CharacterStore() { this(1, USERNAME); }

    public CharacterStore(int accountId, String username) {
        if (accountId < 1 || username == null) throw new IllegalArgumentException("Invalid account identity");
        database = null; this.username = username; this.accountId = accountId;
    }

    public CharacterStore(Database database, int accountId, String username) throws IOException {
        this.database = database; this.accountId = accountId; this.username = username;
        refresh();
    }
    public CharacterStore(Database database,int accountId,String username,ItemCatalog catalog)throws IOException {
        this(database,accountId,username);this.catalog=catalog;
    }
    public WorldProfile grantItems(String username,String name,String config,int quantity,long revision)throws IOException {
        if(database==null)throw new IOException("Item grants require SQLite storage");
        // No cross-account CharacterStore locks; resolve target and permissions in SQLite.
        return database.grantItems(username,name,-1,-1,catalog.require(config),quantity,accountId,revision);
    }

    public synchronized void refresh()throws IOException {
        if(database==null)return;
        var state=database.rosterState(accountId);
        if(storageRevision==state.revision())return;
        boolean changed=!knights.equals(state.knights());
        knights=state.knights();nextId=state.next();storageRevision=state.revision();
        if(changed)notifyListeners();
    }

    public synchronized int nextId() { return nextId; }

    public boolean hasWorldStorage(){return database!=null;}
    public Path worldStorageFile(){return database.file();}
    public Map<Integer,Database.WorldState> worldStates()throws IOException{return database.worldStates();}

    public String username() { return username; }
    public int accountId() { return accountId; }

    public synchronized List<Knight> roster() { return knights; }

    /**
     * Available knights for the character picker. Each preview composes the knight's currently
     * equipped appearance, not the creation choices, so the picker follows Arsenal equipment.
     * Composed under the publication lock; a profile that cannot be read falls back to the saved
     * creation outfit instead of failing the roster publication.
     */
    public synchronized List<RosterEntry> available() throws IOException {
        refresh();
        var result = new ArrayList<RosterEntry>();
        for (Knight knight : knights) {
            WorldProfile profile=null;if(database!=null)try{profile=database.profile(accountId,knight.id());}catch(IOException unreadable){/* Preserve the creation-outfit fallback. */}
            result.add(new RosterEntry(knight,profile==null?preview(knight):StarterCatalog.equipped(knight,profile,catalog),profile==null?1:profile.progress().rank()));
        }
        return List.copyOf(result);
    }

    private StarterCatalog.Model preview(Knight knight) {
        if (database == null) return StarterCatalog.preview(knight);
        try { return StarterCatalog.equipped(knight, database.profile(accountId, knight.id()), catalog); }
        catch (IOException unreadable) { return StarterCatalog.preview(knight); }
    }

    public synchronized String nameFailure(String name) throws IOException {
        String error = nameError(name);
        if (error != null) return error;
        return (database!=null ? database.knightNameInUse(name,-1,-1)
            : knights.stream().anyMatch(knight -> canonical(knight.name()).equals(canonical(name))))
            ? "That knight name is already in use" : null;
    }

    public synchronized Knight create(Knight choices) throws IOException, Refused {
        return create(choices, -1);
    }

    public synchronized Knight create(Knight choices, long authRevision) throws IOException, Refused {
        refresh();
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
        refresh();
        int index = indexOf(oldName);
        if (index < 0) throw new Refused("No such knight");
        String error = nameError(choices.name());
        if (error != null) throw new Refused(error);
        try { validate(choices); if (!StarterCatalog.accepts(choices)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException e) { throw new Refused("Invalid character appearance"); }
        Knight previous = knights.get(index);
        if(database!=null&&database.knightNameInUse(choices.name(),accountId,previous.id()))
            throw new Refused("That knight name is already in use");
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
        refresh();
        int index = indexOf(name);
        if (index < 0) throw new Refused("No such knight");
        List<Knight> roster = new ArrayList<>(knights);
        roster.remove(index);
        save(roster, nextId, authRevision);
        knights = List.copyOf(roster);
        notifyListeners();
    }

    /** Resolves only an owned character. The network layer publishes selection separately. */
    public synchronized WorldProfile worldProfile(String name,long authRevision) throws IOException, Refused {
        return worldProfile(name,authRevision,null);
    }
    /** Reuse unchanged inventory after a current ownership/authenticated revision check. */
    public synchronized WorldProfile worldProfile(String name,long authRevision,WorldProfile cached) throws IOException, Refused {
        refresh();
        int index = indexOf(name);
        if (index < 0) throw new Refused("No such knight");
        if (database == null) throw new IOException("World profiles require SQLite storage");
        return database.worldProfile(accountId,knights.get(index).id(),authRevision,cached);
    }

    public synchronized WorldProfile equip(int knightId,long revision,long itemId,Integer slot)throws IOException {
        if(database==null)throw new IOException("Equipment requires SQLite storage");
        return database.equip(accountId,knightId,revision,itemId,slot);
    }

    /** Trade coordinator never takes an account's roster lock while holding its own lock. */
    public WorldProfile tradeProfile(int knightId,long authRevision)throws IOException {
        if(database==null)throw new IOException("Trading requires SQLite storage");
        return database.worldProfile(accountId,knightId,authRevision);
    }
    public boolean inspectionHidden(long authRevision)throws IOException {
        return database!=null&&database.inspectionHidden(accountId,authRevision);
    }
    public void inspectionHidden(long authRevision,boolean hidden)throws IOException {
        if(database==null)throw new IOException("Preferences require SQLite storage");
        database.inspectionHidden(accountId,authRevision,hidden);
    }
    public List<WorldProfile> trade(WorldProfile first,long firstAuth,Map<Long,Integer> firstItems,int firstCrowns,int firstEnergy,
            WorldProfile second,long secondAuth,Map<Long,Integer> secondItems,int secondCrowns,int secondEnergy)throws IOException {
        if(database==null||first.accountId()!=accountId)throw new IOException("Invalid trade owner");
        return database.trade(first,firstAuth,firstItems,firstCrowns,firstEnergy,second,secondAuth,secondItems,secondCrowns,secondEnergy,catalog);
    }

    private int indexOf(String name) {
        if (name == null) return -1;
        for (int i = 0; i < knights.size(); i++) {
            if (canonical(knights.get(i).name()).equals(canonical(name))) return i;
        }
        return -1;
    }

    private void save(List<Knight> updated, int next, long authRevision) throws IOException, Refused {
        if (database != null) {
            try { database.saveRoster(accountId, updated, next, authRevision,storageRevision); }
            catch(Database.KnightNameInUse conflict){throw new Refused(conflict.getMessage());}
            storageRevision++;return;
        }
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
