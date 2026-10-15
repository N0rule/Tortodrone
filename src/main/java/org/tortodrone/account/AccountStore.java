package org.tortodrone.account;

import java.io.IOException;
import java.net.Socket;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.character.Knight;
import org.tortodrone.character.WorldProfile;
import org.tortodrone.character.Currency;
import org.tortodrone.character.ItemCatalog;
import org.tortodrone.storage.Database;

/** Shared account service for the game server and local administration application. */
public final class AccountStore implements AutoCloseable {
    public static final String DEFAULT_USERNAME = "tortodrone";
    public record Account(int id, String username, boolean gameMaster, long authRevision, CharacterStore characters) { }
    public enum LoginFailure { NO_SUCH_USER, INVALID_PASSWORD }
    public record LoginResult(Account account, LoginFailure failure) { }
    public record Summary(int id, String username, boolean gameMaster, int knights, long createdAt) { }
    private record Stored(int id,String username,Passwords.Protection password,boolean gameMaster,long revision,CharacterStore characters) { }
    private final Database database;
    private final ItemCatalog catalog;
    private final Map<String,Stored> memory = new TreeMap<>();
    private final Map<Integer,CharacterStore> rosters = new ConcurrentHashMap<>();
    private final Map<Integer,Attached> sessions = new ConcurrentHashMap<>();
    private final Map<Integer,Object> attachmentLocks = new ConcurrentHashMap<>();
    private ScheduledExecutorService watcher;
    private int nextId = 1;

    /** Isolated wire tests retain their explicit synthetic account, never a production backdoor. */
    public AccountStore(CharacterStore characters) throws IOException {
        database = null; catalog=characters.catalog();nextId = characters.accountId()+1;
        char[] password = DEFAULT_USERNAME.toCharArray();
        try { memory.put(canonical(characters.username()),new Stored(characters.accountId(),characters.username(),Passwords.protect(password),false,1,characters)); }
        finally { Arrays.fill(password,'\0'); }
    }
    public AccountStore(Path directory) throws IOException { this(directory,false); }
    public AccountStore(Path directory,boolean deploymentLocked) throws IOException { this(directory,deploymentLocked,org.tortodrone.resources.ContentManager.load(directory.toAbsolutePath().normalize().getParent()).items()); }
    public AccountStore(Path directory,boolean deploymentLocked,ItemCatalog catalog) throws IOException { this.catalog=Objects.requireNonNull(catalog);database = new Database(directory,deploymentLocked); }
    public ItemCatalog catalog(){return catalog;}
    public WorldProfile progress(String username,String knight,Integer rank,Integer subrank,Integer prestige,int expectedAccount,int expectedWorld)throws IOException{
        if(username!=null)validateUsername(username);if(database==null)throw new IOException("Knight progress requires SQLite storage");
        return database.progress(username,knight,expectedAccount,expectedWorld,rank,subrank,prestige);
    }
    public WorldProfile grantItems(String username,String knight,String config,int quantity,int expectedAccount,int expectedWorld)throws IOException {
        if(username!=null)validateUsername(username);if(database==null)throw new IOException("Item grants require SQLite storage");
        return database.grantItems(username,knight,expectedAccount,expectedWorld,catalog.require(config),quantity,-1,-1);
    }
    public void observeDatabase(Database.Observer observer) {if(database!=null)database.observe(observer);}
    public Path databaseFile() { return database == null ? null : database.file(); }

    public synchronized List<Summary> list() throws IOException {
        if (database != null) return database.list().stream().map(a -> new Summary(a.id(),a.username(),a.gameMaster(),a.knights(),a.createdAt())).toList();
        return memory.values().stream().map(a -> new Summary(a.id(),a.username(),a.gameMaster(),a.characters().roster().size(),0)).sorted(Comparator.comparingInt(Summary::id)).toList();
    }
    /** Read-only local administration, without authenticating or changing session privileges. */
    public synchronized List<Knight> knights(String username) throws IOException {
        return charactersForAdministration(username).roster();
    }
    public synchronized WorldProfile worldProfile(String username,String knight) throws IOException {
        try { return charactersForAdministration(username).worldProfile(knight,-1); }
        catch (CharacterStore.Refused refused) { throw new IOException(refused.getMessage()); }
    }
    public synchronized void deleteKnight(String username,String knight,int expectedAccount,int expectedWorld)throws IOException {
        validateUsername(username);
        if(database==null)throw new IOException("Local knight deletion requires SQLite storage");
        database.deleteKnight(username,knight,expectedAccount,expectedWorld);
    }
    private CharacterStore charactersForAdministration(String username) throws IOException {
        validateUsername(username);
        if (database == null) return require(username,-1).characters();
        var identity = database.identity(username);
        if (identity == null) throw new IOException("No such account");
        return new CharacterStore(database,identity.id(),identity.username(),catalog);
    }

    public synchronized WorldProfile currency(String username,String knight,Currency currency,long delta,int expectedAccount,int expectedWorld) throws IOException {
        if(username!=null)validateUsername(username);
        if(database==null)throw new IOException("Currency requires SQLite storage");
        return database.currency(username,knight,expectedAccount,expectedWorld,currency,delta);
    }
    public WorldProfile setCurrency(String username,String knight,Currency currency,long amount,int expectedAccount,int expectedWorld) throws IOException {
        if(username!=null)validateUsername(username);
        if(database==null)throw new IOException("Currency requires SQLite storage");
        return database.setCurrency(username,knight,expectedAccount,expectedWorld,currency,amount);
    }
    public WorldProfile inspectKnight(String name) throws IOException {
        if(database==null)throw new IOException("Inspection requires SQLite storage");
        return database.inspectKnight(null,name);
    }
    public WorldProfile itemQuantity(String username,String knight,long item,int quantity,int expectedAccount,int expectedWorld) throws IOException {
        validateUsername(username);
        if(database==null)throw new IOException("Inventory administration requires SQLite storage");
        return database.itemQuantity(username,knight,expectedAccount,expectedWorld,item,quantity,catalog);
    }
    public void create(String username,char[] password) throws IOException { create(username,password,false); }
    public synchronized void create(String username,char[] password,boolean gameMaster) throws IOException {
        validateUsername(username);
        Passwords.Protection protection = Passwords.protect(password);
        if (database != null) { database.create(username,protection,gameMaster); return; }
        if (memory.containsKey(canonical(username))) throw new IOException("Account already exists");
        if (memory.size() >= 1000 || nextId == Integer.MAX_VALUE) throw new IOException("Account limit reached");
        memory.put(canonical(username),new Stored(nextId,username,protection,gameMaster,1,new CharacterStore(nextId,username))); nextId++;
    }
    public void setPassword(String username,char[] password) throws IOException { setPassword(username,password,-1); }
    public synchronized void setPassword(String username,char[] password,int expectedId) throws IOException {
        Passwords.Protection protection = Passwords.protect(password);
        if (database != null) { database.password(username,protection,expectedId); return; }
        Stored old = require(username,expectedId);
        memory.put(canonical(username),new Stored(old.id(),old.username(),protection,old.gameMaster(),old.revision()+1,old.characters()));
    }
    public void setGameMaster(String username,boolean gameMaster) throws IOException { setGameMaster(username,gameMaster,-1); }
    public synchronized void setGameMaster(String username,boolean gameMaster,int expectedId) throws IOException {
        if (database != null) { database.gameMaster(username,gameMaster,expectedId); return; }
        Stored old = require(username,expectedId);
        memory.put(canonical(username),new Stored(old.id(),old.username(),old.password(),gameMaster,old.revision(),old.characters()));
    }
    public synchronized boolean toggleGameMaster(String username) throws IOException {
        validateUsername(username);
        if(database!=null)return database.toggleGameMaster(username);
        Stored old=require(username,-1);
        boolean enabled=!old.gameMaster();
        setGameMaster(username,enabled,old.id());
        return enabled;
    }
    public void delete(String username) throws IOException { delete(username,-1); }
    public synchronized void delete(String username,int expectedId) throws IOException {
        if (database != null) { database.delete(username,expectedId); return; }
        require(username,expectedId); memory.remove(canonical(username));
    }
    public synchronized boolean isGameMaster(int id) throws IOException {
        return list().stream().anyMatch(a -> a.id() == id && a.gameMaster());
    }
    public boolean checkPassword(String username,char[] password) throws IOException {
        char[] hash = Passwords.transform(password);
        try { return authenticate(username,hash) != null; }
        finally { Arrays.fill(hash,'\0'); }
    }
    public Account authenticate(String username,char[] hash) throws IOException {
        return login(username,hash).account();
    }
    /** Resolve the error from the same identity snapshot used to verify the password. */
    public LoginResult login(String username,char[] hash) throws IOException {
        if (username == null) return new LoginResult(null,LoginFailure.NO_SUCH_USER);
        if (database != null) {
            var identity = database.identity(username);
            byte[] salt = identity == null ? new byte[16] : identity.salt(), verifier = identity == null ? new byte[32] : identity.verifier();
            boolean matches = Passwords.matches(hash,salt,verifier);
            if (identity == null) return new LoginResult(null,LoginFailure.NO_SUCH_USER);
            if (!matches || !database.current(identity.id(),identity.authRevision())) return new LoginResult(null,LoginFailure.INVALID_PASSWORD);
            CharacterStore characters = rosters.get(identity.id());
            if (characters == null) {
                characters = new CharacterStore(database,identity.id(),identity.username(),catalog);
                CharacterStore previous = rosters.putIfAbsent(identity.id(),characters);
                if (previous != null) characters = previous;
            }
            characters.refresh();
            return new LoginResult(new Account(identity.id(),identity.username(),identity.gameMaster(),identity.authRevision(),characters),null);
        }
        synchronized (this) {
            Stored stored = memory.get(canonical(username));
            boolean matches = Passwords.matches(hash,stored == null ? new byte[16] : stored.password().salt(),stored == null ? new byte[32] : stored.password().verifier());
            return stored == null ? new LoginResult(null,LoginFailure.NO_SUCH_USER)
                : matches ? new LoginResult(new Account(stored.id(),stored.username(),stored.gameMaster(),stored.revision(),stored.characters()),null)
                : new LoginResult(null,LoginFailure.INVALID_PASSWORD);
        }
    }
    public synchronized boolean current(Account account) throws IOException {
        if (database != null) return database.current(account.id(),account.authRevision());
        Stored stored = memory.get(canonical(account.username()));
        return stored != null && stored.id() == account.id() && stored.revision() == account.authRevision();
    }
    @FunctionalInterface public interface Session extends AutoCloseable {
        @Override void close();
        default boolean current() { return true; }
        default boolean gameMaster() throws IOException { return false; }
        default void onInvalidated(Runnable cleanup) { }
    }
    public Session attach(Account account,Socket socket) throws IOException {
        synchronized(attachmentLocks.computeIfAbsent(account.id(),id->new Object())) {
            Attached next=new Attached(account,socket),previous;
            synchronized(this) {
                if(!current(account)||socket.isClosed()) { socket.close();throw new IOException("Account changed during login"); }
                previous=sessions.put(account.id(),next);
            }
            // Serialize takeovers per account; a third login cannot overtake old cleanup.
            if(previous!=null)previous.close();
            return next;
        }
    }
    private final class Attached implements Session {
        final Account account;
        final Socket socket;
        private volatile boolean live=true;
        private Runnable cleanup;
        private final CompletableFuture<Void> finished=new CompletableFuture<>();
        Attached(Account account,Socket socket){this.account=account;this.socket=socket;}
        @Override public boolean current(){return live&&!socket.isClosed()&&sessions.get(account.id())==this;}
        @Override public boolean gameMaster() throws IOException {
            if(!current())throw new IOException("Session replaced");
            synchronized(AccountStore.this) {
                if(database!=null) {
                    var identity=database.identity(account.username());
                    if(identity==null||identity.id()!=account.id()||identity.authRevision()!=account.authRevision())
                        throw new IOException("Account changed; reconnect");
                    return identity.gameMaster();
                }
                var stored=require(account.username(),account.id());
                if(stored.revision()!=account.authRevision())throw new IOException("Account changed; reconnect");
                return stored.gameMaster();
            }
        }
        @Override public void onInvalidated(Runnable action) {
            synchronized(this) { if(live) { cleanup=action;return; } }
            action.run();
        }
        @Override public void close() {
            Runnable action;
            boolean owner;
            synchronized(this) { owner=live;live=false;action=cleanup;cleanup=null; }
            if(!owner){finished.join();return;}
            try {
                try{socket.close();}catch(IOException ignored) { }
                if(action!=null)action.run();
            }finally {
                sessions.remove(account.id(),this);
                finished.complete(null);
            }
        }
    }
    /** The server owns this watcher. The desktop tool opens no networking endpoint. */
    public synchronized void watchSessions() {
        if (watcher != null) return;
        watcher = Executors.newSingleThreadScheduledExecutor(task -> { Thread t = new Thread(task,"AccountSessions"); t.setDaemon(true); return t; });
        watcher.scheduleWithFixedDelay(() -> sessions.forEach((id,session) -> {
            try { if (!current(session.account)||!session.current()) session.close(); }
            catch (IOException failure) { session.close(); }
        }),250,250,TimeUnit.MILLISECONDS);
    }
    @Override public void close() {
        synchronized(this){if (watcher != null) watcher.shutdownNow();}
        List.copyOf(sessions.values()).forEach(Attached::close);
    }
    public static char[] transform(char[] password) throws IOException { return Passwords.transform(password); }
    private Stored require(String username,int expectedId) throws IOException { Stored stored = memory.get(canonical(username)); if (stored == null) throw new IOException("No such account"); if (expectedId >= 0 && stored.id() != expectedId) throw new IOException("Account changed; refresh the account list"); return stored; }
    private static String canonical(String username) { return username.toLowerCase(Locale.ROOT); }
    public static void validateUsername(String username) throws IOException {
        if (username == null || !username.matches("[A-Za-z][A-Za-z0-9_-]{2,31}")) throw new IOException("Usernames need 3–32 ASCII characters: start with a letter, then letters, digits, underscores or hyphens");
    }
}
