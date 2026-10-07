package org.tortodrone.account;

import java.io.IOException;
import java.net.Socket;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.storage.Database;

/** Shared account service for the game server and local administration application. */
public final class AccountStore implements AutoCloseable {
    public static final String DEFAULT_USERNAME = "tortodrone";
    public record Account(int id, String username, boolean admin, long authRevision, CharacterStore characters) { }
    public record Summary(int id, String username, boolean admin, int knights, long createdAt) { }
    private record Stored(int id,String username,Passwords.Protection password,boolean admin,long revision,CharacterStore characters) { }
    private final Database database;
    private final Map<String,Stored> memory = new TreeMap<>();
    private final Map<Integer,CharacterStore> rosters = new ConcurrentHashMap<>();
    private final Map<Socket,Account> sessions = new ConcurrentHashMap<>();
    private ScheduledExecutorService watcher;
    private int nextId = 1;

    /** Isolated wire tests retain their explicit synthetic account, never a production backdoor. */
    public AccountStore(CharacterStore characters) throws IOException {
        database = null; nextId = characters.accountId()+1;
        char[] password = DEFAULT_USERNAME.toCharArray();
        try { memory.put(canonical(characters.username()),new Stored(characters.accountId(),characters.username(),Passwords.protect(password),false,1,characters)); }
        finally { Arrays.fill(password,'\0'); }
    }
    public AccountStore(Path directory) throws IOException { this(directory,false); }
    public AccountStore(Path directory,boolean deploymentLocked) throws IOException { database = new Database(directory,deploymentLocked); }
    public Path databaseFile() { return database == null ? null : database.file(); }

    public synchronized List<Summary> list() throws IOException {
        if (database != null) return database.list().stream().map(a -> new Summary(a.id(),a.username(),a.admin(),a.knights(),a.createdAt())).toList();
        return memory.values().stream().map(a -> new Summary(a.id(),a.username(),a.admin(),a.characters().roster().size(),0)).sorted(Comparator.comparingInt(Summary::id)).toList();
    }
    public void create(String username,char[] password) throws IOException { create(username,password,false); }
    public synchronized void create(String username,char[] password,boolean admin) throws IOException {
        validateUsername(username);
        Passwords.Protection protection = Passwords.protect(password);
        if (database != null) { database.create(username,protection,admin); return; }
        if (memory.containsKey(canonical(username))) throw new IOException("Account already exists");
        if (memory.size() >= 1000 || nextId == Integer.MAX_VALUE) throw new IOException("Account limit reached");
        memory.put(canonical(username),new Stored(nextId,username,protection,admin,1,new CharacterStore(nextId,username))); nextId++;
    }
    public void setPassword(String username,char[] password) throws IOException { setPassword(username,password,-1); }
    public synchronized void setPassword(String username,char[] password,int expectedId) throws IOException {
        Passwords.Protection protection = Passwords.protect(password);
        if (database != null) { database.password(username,protection,expectedId); return; }
        Stored old = require(username,expectedId);
        memory.put(canonical(username),new Stored(old.id(),old.username(),protection,old.admin(),old.revision()+1,old.characters()));
    }
    public void setAdmin(String username,boolean admin) throws IOException { setAdmin(username,admin,-1); }
    public synchronized void setAdmin(String username,boolean admin,int expectedId) throws IOException {
        if (database != null) { database.admin(username,admin,expectedId); return; }
        Stored old = require(username,expectedId);
        memory.put(canonical(username),new Stored(old.id(),old.username(),old.password(),admin,old.revision(),old.characters()));
    }
    public void delete(String username) throws IOException { delete(username,-1); }
    public synchronized void delete(String username,int expectedId) throws IOException {
        if (database != null) { database.delete(username,expectedId); return; }
        require(username,expectedId); memory.remove(canonical(username));
    }
    public synchronized boolean isAdmin(int id) throws IOException {
        return list().stream().anyMatch(a -> a.id() == id && a.admin());
    }
    public boolean checkPassword(String username,char[] password) throws IOException {
        char[] hash = Passwords.transform(password);
        try { return authenticate(username,hash) != null; }
        finally { Arrays.fill(hash,'\0'); }
    }
    public Account authenticate(String username,char[] hash) throws IOException {
        if (username == null) return null;
        if (database != null) {
            var identity = database.identity(username);
            byte[] salt = identity == null ? new byte[16] : identity.salt(), verifier = identity == null ? new byte[32] : identity.verifier();
            boolean matches = Passwords.matches(hash,salt,verifier);
            if (!matches || identity == null || !database.current(identity.id(),identity.authRevision())) return null;
            CharacterStore characters = rosters.get(identity.id());
            if (characters == null) {
                characters = new CharacterStore(database,identity.id(),identity.username());
                CharacterStore previous = rosters.putIfAbsent(identity.id(),characters);
                if (previous != null) characters = previous;
            }
            return new Account(identity.id(),identity.username(),identity.admin(),identity.authRevision(),characters);
        }
        synchronized (this) {
            Stored stored = memory.get(canonical(username));
            boolean matches = Passwords.matches(hash,stored == null ? new byte[16] : stored.password().salt(),stored == null ? new byte[32] : stored.password().verifier());
            return matches && stored != null ? new Account(stored.id(),stored.username(),stored.admin(),stored.revision(),stored.characters()) : null;
        }
    }
    public synchronized boolean current(Account account) throws IOException {
        if (database != null) return database.current(account.id(),account.authRevision());
        Stored stored = memory.get(canonical(account.username()));
        return stored != null && stored.id() == account.id() && stored.revision() == account.authRevision();
    }
    @FunctionalInterface public interface Session extends AutoCloseable { @Override void close(); }
    public Session attach(Account account,Socket socket) throws IOException {
        sessions.put(socket,account);
        if (!current(account)) { sessions.remove(socket); socket.close(); throw new IOException("Account changed during login"); }
        return () -> sessions.remove(socket);
    }
    /** The server owns this watcher. The desktop tool opens no networking endpoint. */
    public synchronized void watchSessions() {
        if (watcher != null) return;
        watcher = Executors.newSingleThreadScheduledExecutor(task -> { Thread t = new Thread(task,"AccountSessions"); t.setDaemon(true); return t; });
        watcher.scheduleWithFixedDelay(() -> sessions.forEach((socket,account) -> {
            try { if (!current(account)) { sessions.remove(socket); socket.close(); } }
            catch (IOException failure) { sessions.remove(socket); try { socket.close(); } catch (IOException ignored) { } }
        }),250,250,TimeUnit.MILLISECONDS);
    }
    @Override public synchronized void close() {
        if (watcher != null) watcher.shutdownNow();
        sessions.keySet().forEach(socket -> { try { socket.close(); } catch (IOException ignored) { } }); sessions.clear();
    }
    public static char[] transform(char[] password) throws IOException { return Passwords.transform(password); }
    private Stored require(String username,int expectedId) throws IOException { Stored stored = memory.get(canonical(username)); if (stored == null) throw new IOException("No such account"); if (expectedId >= 0 && stored.id() != expectedId) throw new IOException("Account changed; refresh the account list"); return stored; }
    private static String canonical(String username) { return username.toLowerCase(Locale.ROOT); }
    public static void validateUsername(String username) throws IOException {
        if (username == null || !username.matches("[A-Za-z][A-Za-z0-9_-]{2,31}")) throw new IOException("Usernames need 3–32 ASCII characters: start with a letter, then letters, digits, underscores or hyphens");
    }
}
