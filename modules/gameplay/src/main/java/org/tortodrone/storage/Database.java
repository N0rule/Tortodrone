package org.tortodrone.storage;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.core.character.Knight;
import org.tortodrone.core.character.WorldProfile;
import org.tortodrone.core.Json;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.character.SlotUpgrades;
import org.tortodrone.core.character.KnightProgress;
import org.tortodrone.core.character.KnightObjective;
import org.tortodrone.core.character.KnightMemory;
import org.tortodrone.core.character.Currency;
import org.tortodrone.core.character.DungeonGear;
import org.tortodrone.core.town.TownCatalog;
import org.tortodrone.core.mission.TutorialRoute;

/** Local SQLite database. Transactions are short; password derivation runs outside them. */
public final class Database {
    public static final int SCHEMA_VERSION = SchemaMigrations.LATEST;
    public static final int APPLICATION_ID = SchemaMigrations.APPLICATION_ID;
    public static final class KnightNameInUse extends IOException {
        private KnightNameInUse(SQLException cause) { super("That knight name is already in use",cause); }
    }
    public record Diagnostics(long operations,long slowOperations,long failures,long rollbacks,long maximumMillis) { }
    /** Storage series required by the Phase 4 contract; counters never change write behaviour. */
    public record StorageMetrics(long busyErrors,long expiryRuns,long expiryNanos,long checkpoints,long checkpointNanos,long walBytes,
            long durationP50Micros,long durationP99Micros) { }
    private static final java.util.concurrent.atomic.AtomicLong operations=new java.util.concurrent.atomic.AtomicLong(),slow=new java.util.concurrent.atomic.AtomicLong(),failures=new java.util.concurrent.atomic.AtomicLong(),rollbacks=new java.util.concurrent.atomic.AtomicLong(),maximum=new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong busyErrors=new java.util.concurrent.atomic.AtomicLong(),expiryRuns=new java.util.concurrent.atomic.AtomicLong(),expiryNanos=new java.util.concurrent.atomic.AtomicLong(),checkpoints=new java.util.concurrent.atomic.AtomicLong(),checkpointNanos=new java.util.concurrent.atomic.AtomicLong();
    private static final long[] durationSamples=new long[256];
    private static int durationCount,durationNext;
    public static Diagnostics diagnostics(){return new Diagnostics(operations.get(),slow.get(),failures.get(),rollbacks.get(),maximum.get());}
    public StorageMetrics storageMetrics(){return new StorageMetrics(busyErrors.get(),expiryRuns.get(),expiryNanos.get(),checkpoints.get(),checkpointNanos.get(),walBytes(),
        durationPercentile(.50),durationPercentile(.99));}
    private static synchronized long durationPercentile(double quantile) {
        if(durationCount==0)return 0;
        long[] copy=java.util.Arrays.copyOf(durationSamples,durationCount);java.util.Arrays.sort(copy);
        return copy[Math.min(copy.length-1,(int)Math.ceil(copy.length*quantile)-1)];
    }
    @FunctionalInterface public interface Observer {void completed(String operation,long millis,boolean failed);}
    private volatile Observer observer=(operation,millis,failed)->{};
    public void observe(Observer observer){this.observer=Objects.requireNonNull(observer);}
    private void measured(String kind,long start,boolean failed){
        long nanos=System.nanoTime()-start;long elapsed=nanos/1000000;operations.incrementAndGet();maximum.accumulateAndGet(elapsed,Math::max);
        synchronized(Database.class){durationSamples[durationNext]=(int)Math.min(Integer.MAX_VALUE,nanos/1000);durationNext=(durationNext+1)%durationSamples.length;if(durationCount<durationSamples.length)durationCount++;}
        if(elapsed>=100)slow.incrementAndGet();if(failed)failures.incrementAndGet();
        try{observer.completed(kind,elapsed,failed);}catch(RuntimeException ignored){ }
    }
    private final Path file;
    private final AccountRepository accounts;
    private final RosterRepository roster;
    private final WorldRepository worlds;
    private final PreferenceRepository preferences;
    private boolean initialized;
    @FunctionalInterface public interface Work<T> { T run(Connection connection) throws SQLException, IOException; }

    public Database(Path directory, boolean deploymentLocked) throws IOException {
        file = directory.toAbsolutePath().normalize().resolve("tortodrone.db");
        try { Class.forName("org.sqlite.JDBC"); }
        catch (ClassNotFoundException e) { throw new IOException("Missing bundled SQLite driver", e); }
        Files.createDirectories(file.getParent());
        if (Files.exists(file)) openExisting(directory, deploymentLocked);
        else if (deploymentLocked) initialize();
        else initializeUnderLock(directory);
        initialized = true;
        accounts = new AccountRepository(this);
        roster = new RosterRepository(this);
        worlds = new WorldRepository(this);
        preferences = new PreferenceRepository(this);
        DepotStore.alignDefaults(this);
    }
    /**
     * Open the 0.42.0 baseline. A database without the project marker is a pre-0.42.0 or
     * unknown file and is refused untouched; the marker is never written by a read.
     */
    private void openExisting(Path directory, boolean deploymentLocked) throws IOException {
        if (applicationId() != SchemaMigrations.APPLICATION_ID) {
            int marker = applicationId();
            throw new IOException((marker == 0
                ? "This database predates Tortodrone 0.42.0 and has no project marker; saved data was not changed. Use the last 0.41.x build if it must be exported."
                : "Unsupported database application_id " + marker + "; saved data was not changed"));
        }
        int version = version();
        if (version < SchemaMigrations.BASELINE || version > SchemaMigrations.LATEST)
            throw new IOException("Unsupported database schema " + version + "; expected "
                + SchemaMigrations.LATEST + ". Saved data was not changed");
        if (version == SchemaMigrations.LATEST) return;
        if (deploymentLocked) migrate(version);
        else migrateUnderLock(directory, version);
    }
    private void initializeUnderLock(Path directory) throws IOException {
        try (var channel = FileChannel.open(directory.resolve("server.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Stop the server before initializing the database");
            if (Files.exists(file)) openExisting(directory, false); else initialize();
        } catch (OverlappingFileLockException e) { throw new IOException("Database initialization is already in progress", e); }
    }
    private void migrateUnderLock(Path directory, int from) throws IOException {
        try (var channel = FileChannel.open(directory.resolve("server.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Stop the server before upgrading the database");
            int current = version();
            if (current == SchemaMigrations.LATEST) return;
            if (current != from) throw new IOException("Database schema changed during upgrade");
            migrate(from);
        } catch (OverlappingFileLockException e) { throw new IOException("Stop the server before upgrading the database", e); }
    }
    /** Back up the baseline, then run the ordered steps under one transaction. */
    private void migrate(int from) throws IOException {
        Path backups = file.getParent().resolve("backups");
        Files.createDirectories(backups);
        backup(backups.resolve("tortodrone-pre-" + from + "-to-" + SchemaMigrations.LATEST + "-"
            + System.currentTimeMillis() + ".db"));
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=OFF");
            statement.execute("BEGIN IMMEDIATE");
            try {
                int locked; try (var r = statement.executeQuery("PRAGMA user_version")) { r.next(); locked = r.getInt(1); }
                if (locked != from) throw new IOException("Database schema changed during upgrade");
                SchemaMigrations.apply(connection, from);
                try (var check = statement.executeQuery("PRAGMA foreign_key_check")) {
                    if (check.next()) throw new IOException("Database upgrade failed foreign key validation");
                }
                statement.execute("PRAGMA user_version=" + SchemaMigrations.LATEST);
                statement.execute("COMMIT");
            } catch (SQLException | IOException | RuntimeException failure) {
                try { statement.execute("ROLLBACK"); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            } finally {
                try { statement.execute("PRAGMA foreign_keys=ON"); } catch (SQLException ignored) { }
            }
        } catch (SQLException error) { throw failure(error); }
    }
    public Path file() { return file; }
    /** Fold the WAL back into the main file after every writer has stopped. */
    public void checkpoint() { checkpoint(true); }
    private void checkpoint(boolean truncate) {
        long start=System.nanoTime();
        try (Connection connection = connect(); var statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(" + (truncate ? "TRUNCATE" : "PASSIVE") + ")");
        } catch (SQLException ignored) {
            // Shutdown must not fail because the file was already closed or removed.
        } finally {
            checkpoints.incrementAndGet();checkpointNanos.addAndGet(System.nanoTime()-start);
        }
    }
    /** Current WAL size; used by the maintenance alarm and shutdown report. */
    public long walBytes() {
        Path wal = Path.of(file + "-wal");
        try { return Files.isRegularFile(wal) ? Files.size(wal) : 0; } catch (IOException ignored) { return 0; }
    }
    /**
     * Periodic maintenance: checkpoint a WAL that has grown past {@code alarmBytes} and
     * report the observed size so the caller can raise an alarm. Never drops writes.
     */
    public long maintain(long alarmBytes) {
        long before = walBytes();
        if (before >= alarmBytes) checkpoint(false);
        return before;
    }
    /** Consistent single-file backup using SQLite's own snapshot; the target must not exist. */
    public Path backup(Path target) throws IOException {
        Path normalized = target.toAbsolutePath().normalize();
        Files.createDirectories(normalized.getParent());
        if (Files.exists(normalized)) throw new IOException("Backup target already exists: " + normalized);
        read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("VACUUM INTO ?")) {
                statement.setString(1, normalized.toString());
                statement.execute();
            }
            return null;
        });
        return normalized;
    }
    private Connection connect() throws SQLException {
        if (!Files.isRegularFile(file)) throw new SQLException("Database file is missing or not a regular file");
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA wal_autocheckpoint=1000");
            statement.execute("PRAGMA journal_size_limit=67108864");
            statement.execute("PRAGMA cache_size=-32768");
        } catch (SQLException e) { connection.close(); throw e; }
        return connection;
    }
    private volatile boolean closed;
    private final ThreadLocal<Boolean> storageThread=ThreadLocal.withInitial(()->Boolean.FALSE);
    private ExecutorService writer,readers;

    /** One platform writer thread; SQLite never runs JDBC on a session virtual thread. */
    private synchronized ExecutorService writer() {
        if(writer==null)writer=Executors.newSingleThreadExecutor(runnable->{
            Thread thread=new Thread(runnable,"StorageWriter");thread.setDaemon(true);return thread;});
        return writer;
    }
    /** A small platform reader pool; reads never share the writer thread. */
    private synchronized ExecutorService readers() {
        if(readers==null)readers=Executors.newFixedThreadPool(4,runnable->{
            Thread thread=new Thread(runnable,"StorageReader");thread.setDaemon(true);return thread;});
        return readers;
    }
    public <T> T read(Work<T> work) throws IOException {
        if(closed)throw new IOException("Storage is closed");
        return submit(readers(),"read",work);
    }
    public <T> T write(Work<T> work) throws IOException {
        if(closed)throw new IOException("Storage is closed");
        return submit(writer(),"write",work);
    }
    private <T> T submit(ExecutorService pool,String kind,Work<T> work)throws IOException {
        // A storage callback may read or write as part of its own transaction; run it inline.
        if(storageThread.get())return kind.equals("write")?writeDirect(work):readDirect(work);
        try{return pool.submit(()->{
            storageThread.set(Boolean.TRUE);
            try{return kind.equals("write")?writeDirect(work):readDirect(work);}
            finally{storageThread.remove();}
        }).get();}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("Storage interrupted",interrupted);}
        catch(ExecutionException failure){
            Throwable cause=failure.getCause();
            if(cause instanceof IOException io)throw io;
            if(cause instanceof RuntimeException runtime)throw runtime;
            if(cause instanceof Error error)throw error;
            throw new IOException("Storage failed",cause);
        }
    }
    private <T> T readDirect(Work<T> work) throws IOException {
        long start=System.nanoTime();boolean failed=true;
        try (Connection connection=connect()) {T result=work.run(connection);failed=false;return result;}
        catch(SQLException error){throw failure(error);}
        finally{measured("read",start,failed);}
    }
    private <T> T writeDirect(Work<T> work) throws IOException {
        long start=System.nanoTime();boolean failed=true;
        try(Connection connection=connect();Statement statement=connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            try {
                if(initialized) {
                    long expiryStart=System.nanoTime();
                    try { InventoryExpiry.prune(connection,System.currentTimeMillis()); }
                    finally { expiryRuns.incrementAndGet();expiryNanos.addAndGet(System.nanoTime()-expiryStart); }
                }
                T value=work.run(connection);statement.execute("COMMIT");failed=false;return value;
            } catch(SQLException|IOException|RuntimeException error) {
                rollbacks.incrementAndGet();
                try{statement.execute("ROLLBACK");if(error instanceof Refused)failed=false;}catch(SQLException rollback){error.addSuppressed(rollback);}
                throw error;
            }
        } catch(SQLException error){throw failure(error);}
        finally{measured("write",start,failed);}
    }
    /** Stop accepting work and join both dedicated pools before the final checkpoint. */
    public void close() {
        ExecutorService stoppingWriter,stoppingReaders;
        synchronized(this){closed=true;stoppingWriter=writer;stoppingReaders=readers;writer=readers=null;}
        if(stoppingWriter!=null)stoppingWriter.shutdown();
        if(stoppingReaders!=null)stoppingReaders.shutdown();
        try{
            if(stoppingWriter!=null&&!stoppingWriter.awaitTermination(5,TimeUnit.SECONDS))stoppingWriter.shutdownNow();
            if(stoppingReaders!=null&&!stoppingReaders.awaitTermination(2,TimeUnit.SECONDS))stoppingReaders.shutdownNow();
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
    }
    /** Expected business refusal: rollback normally, without reporting a storage failure. */
    public static final class Refused extends IOException {
        public Refused(String message){super(message);}
    }
    private int version() throws IOException {
        return read(connection -> { try (Statement s = connection.createStatement(); ResultSet r = s.executeQuery("PRAGMA user_version")) { r.next(); return r.getInt(1); } });
    }
    private int applicationId() throws IOException {
        return read(connection -> { try (Statement s = connection.createStatement(); ResultSet r = s.executeQuery("PRAGMA application_id")) { r.next(); return r.getInt(1); } });
    }
    private void initialize() throws IOException {
        Files.createFile(file);
        try {
            if (Files.getFileStore(file).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            read(connection -> { try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL"); } return null; });
            // The marker is written before the first table so a partial initialization is
            // distinguishable from a pre-0.42.0 file and can never be adopted silently.
            write(connection -> { try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA application_id=" + SchemaMigrations.APPLICATION_ID); } return null; });
            write(connection -> {
                SchemaMigrations.baseline(connection);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA user_version=" + SchemaMigrations.LATEST);
                }
                return null;
            });
        } catch (IOException | RuntimeException failure) {
            try { Files.deleteIfExists(file); } catch (IOException ignored) { }
            throw failure;
        }
    }
    /** Availability is advisory; the unique index arbitrates competing writes. */
    public boolean knightNameInUse(String name,int accountId,int knightId)throws IOException {
        return roster.knightNameInUse(name,accountId,knightId);
    }
    public AccountIdentity identity(String username) throws IOException { return accounts.identity(username); }
    public boolean current(int id, long revision) throws IOException { return accounts.current(id,revision); }
    /** Ordinary account preference rows in the existing integer metadata table; schema stays unchanged. */
    public boolean inspectionHidden(int accountId,long authRevision)throws IOException {
        return preferences.inspectionHidden(accountId,authRevision);
    }
    public void inspectionHidden(int accountId,long authRevision,boolean hidden)throws IOException {
        preferences.inspectionHidden(accountId,authRevision,hidden);
    }

    /** Last acknowledged permanent scene, keyed by the non-recycled world identity. */
    public int lastTownScene(int accountId,int knightId,long authRevision)throws IOException {
        return preferences.lastTownScene(accountId,knightId,authRevision);
    }
    public void lastTownScene(int accountId,int knightId,long authRevision,int scene)throws IOException {
        preferences.lastTownScene(accountId,knightId,authRevision,scene);
    }
    /** SystemSender.ORIENTATION ("Spiral HQ") in the stock uplink enum. */
    public static final int ORIENTATION_SENDER=6;
    /** The 2026-11-05 new-player route: knights skip Rescue Camp and arrive in Haven at 2-1. */
    public static final int ARRIVAL_RANK=2,ARRIVAL_SUBRANK=1;
    public static final String SKIPPED_CAMP_ATTACHMENT="Weapon/Bomb/Proto Bomb";
    /** Server-owned Crash Site settlement. Collected heat, receipt, safe return, the 2-1 arrival
     * rank and the stock "skipped rescue camp" orientation letter commit together, once. */
    public WorldProfile completeTutorial(int accountId,int knightId,long authRevision,int worldId,long revision)throws IOException {
        return worlds.completeTutorial(accountId,knightId,authRevision,worldId,revision);
    }
    public WorldProfile completeTutorial(int accountId,int knightId,long authRevision,int worldId,long revision,float heat,org.tortodrone.core.town.TownCatalog rules)throws IOException {
        return worlds.completeTutorial(accountId,knightId,authRevision,worldId,revision,heat,rules);
    }
    public Set<String> knightMemories(int accountId,int knightId,long authRevision)throws IOException {
        return worlds.knightMemories(accountId,knightId,authRevision);
    }
    public org.tortodrone.core.character.KnightMemoryState knightMemories(int accountId,int knightId,long authRevision,int worldId,long revision,List<org.tortodrone.core.character.KnightMemory> operations)throws IOException {
        return worlds.knightMemories(accountId,knightId,authRevision,worldId,revision,operations);
    }
    public Map<String,org.tortodrone.core.character.KnightObjective.State> knightObjectives(int accountId,int knightId,long authRevision)throws IOException {
        return worlds.knightObjectives(accountId,knightId,authRevision);
    }
    public org.tortodrone.core.character.KnightStory knightStory(int accountId,int knightId,long authRevision,int worldId,long revision,List<org.tortodrone.core.character.KnightMemory> operations,List<org.tortodrone.core.character.KnightObjective> objectives)throws IOException {
        return worlds.knightStory(accountId,knightId,authRevision,worldId,revision,operations,objectives);
    }
    public List<AccountSummary> list() throws IOException { return accounts.list(); }
    public int create(String username, Passwords.Protection protection, boolean gameMaster) throws IOException {
        return accounts.create(username,protection,gameMaster);
    }
    public void password(String username, Passwords.Protection protection,int expectedId) throws IOException {
        accounts.password(username,protection,expectedId);
    }
    public void gameMaster(String username, boolean gameMaster,int expectedId) throws IOException {
        accounts.gameMaster(username,gameMaster,expectedId);
    }
    /** Toggle atomically with other local tools; no stale read/modify/write window. */
    public boolean toggleGameMaster(String username) throws IOException { return accounts.toggleGameMaster(username); }
    public void delete(String username,int expectedId) throws IOException { accounts.delete(username,expectedId); }
    public int nextKnight(int id) throws IOException { return accounts.nextKnight(id); }
    public List<Knight> roster(int id) throws IOException { return roster.roster(id); }
    /** One SQLite snapshot for the roster, allocation counter and compare-and-swap revision. */
    public RosterState rosterState(int id)throws IOException { return roster.rosterState(id); }
    public void deleteKnight(String username,String name,int expectedAccount,int expectedWorld)throws IOException {
        roster.deleteKnight(username,name,expectedAccount,expectedWorld);
    }
    public WorldProfile worldProfile(int accountId,int knightId,long authRevision) throws IOException {
        return worlds.worldProfile(accountId,knightId,authRevision);
    }
    public WorldProfile worldProfile(int accountId,int knightId,long authRevision,WorldProfile cached) throws IOException {
        return worlds.worldProfile(accountId,knightId,authRevision,cached);
    }
    /** Lightweight consistent metadata for active world observers; inventories load only on change. */
    public Map<Integer,org.tortodrone.core.character.WorldSummary> worldStates()throws IOException { return worlds.worldStates(); }
    /** Indexed revision probe; does not materialize inventory or town state. */
    public org.tortodrone.core.character.WorldSummary knightState(String username,String knight)throws IOException { return worlds.knightState(username,knight); }
    /** Current world state without an authentication-revision check, for read-only publications. */
    public WorldProfile profile(int accountId,int knightId) throws IOException { return worlds.profile(accountId,knightId); }

    private final AdministrationRepository administration = new AdministrationRepository(this);

    public WorldProfile inspectKnight(String username, String knightName) throws IOException {
        return administration.inspectKnight(username,knightName);
    }
    /** Local administration only; target identity, revision and audit share one transaction. */
    public WorldProfile progress(String username,String knightName,int expectedAccount,int expectedWorld,Integer rank,Integer subrank,Integer prestige)throws IOException{
        return administration.progress(username,knightName,expectedAccount,expectedWorld,rank,subrank,prestige);
    }
    public WorldProfile progress(String username,String knightName,int expectedAccount,int expectedWorld,Integer rank,Integer subrank,Integer prestige,String starterSprite)throws IOException{
        return administration.progress(username,knightName,expectedAccount,expectedWorld,rank,subrank,prestige,starterSprite);
    }
    public WorldProfile progress(String username,String knightName,int expectedAccount,int expectedWorld,Integer rank,Integer subrank,Integer prestige,String starterSprite,long expectedRevision)throws IOException {
        return administration.progress(username,knightName,expectedAccount,expectedWorld,rank,subrank,prestige,starterSprite,expectedRevision);
    }
    public WorldProfile currency(String username,String knightName,int expectedAccount,int expectedWorld,
            org.tortodrone.core.character.Currency currency,long delta) throws IOException {
        return administration.currency(username,knightName,expectedAccount,expectedWorld,currency,delta);
    }
    public WorldProfile setCurrency(String username,String knightName,int expectedAccount,int expectedWorld,
            org.tortodrone.core.character.Currency currency,long amount) throws IOException {
        return administration.setCurrency(username,knightName,expectedAccount,expectedWorld,currency,amount);
    }
    public WorldProfile administrativeCurrency(String username,String knightName,int expectedAccount,int expectedWorld,
            org.tortodrone.core.character.Currency currency,long amount,boolean absolute,long expectedRevision)throws IOException {
        return administration.administrativeCurrency(username,knightName,expectedAccount,expectedWorld,currency,amount,absolute,expectedRevision);
    }
    /** Local administrator quantity/removal, audited and published by the existing revision poller. */
    public WorldProfile itemQuantity(String username,String knightName,int expectedAccount,int expectedWorld,
            long itemId,int quantity,org.tortodrone.core.character.ItemCatalog catalog) throws IOException {
        return administration.itemQuantity(username,knightName,expectedAccount,expectedWorld,itemId,quantity,catalog);
    }
    public WorldProfile itemQuantity(String username,String knightName,int expectedAccount,int expectedWorld,long itemId,int quantity,org.tortodrone.core.character.ItemCatalog catalog,long expectedRevision)throws IOException {
        return administration.itemQuantity(username,knightName,expectedAccount,expectedWorld,itemId,quantity,catalog,expectedRevision);
    }
    public WorldProfile equip(int accountId,int knightId,long revision,long itemId,Integer slot) throws IOException {
        return administration.equip(accountId,knightId,revision,itemId,slot);
    }
    public WorldProfile administrativeEquipment(String username,String knight,int account,int world,long revision,long itemId,Integer slot,String grant,org.tortodrone.core.character.ItemCatalog catalog,org.tortodrone.core.town.TownCatalog rules)throws IOException {
        return administration.administrativeEquipment(username,knight,account,world,revision,itemId,slot,grant,catalog,rules);
    }
    /** Permission and target identity are checked under the same write lock as allocation. */
    public WorldProfile grantItems(String username,String knightName,int expectedAccount,int expectedWorld,
            org.tortodrone.core.character.ItemCatalog.Entry entry,int quantity,int actorAccount,long actorRevision)throws IOException {
        return administration.grantItems(username,knightName,expectedAccount,expectedWorld,entry,quantity,actorAccount,actorRevision);
    }
    public WorldProfile grantItems(String username,String knightName,int expectedAccount,int expectedWorld,
            org.tortodrone.core.character.ItemCatalog.Entry entry,int quantity,int actorAccount,long actorRevision,
            org.tortodrone.character.ItemModifiers modifiers,org.tortodrone.core.town.TownCatalog rules)throws IOException {
        return administration.grantItems(username,knightName,expectedAccount,expectedWorld,entry,quantity,actorAccount,actorRevision,modifiers,rules);
    }
    public WorldProfile grantItems(String username,String knightName,int expectedAccount,int expectedWorld,
            org.tortodrone.core.character.ItemCatalog.Entry entry,int quantity,int actorAccount,long actorRevision,
            org.tortodrone.character.ItemModifiers modifiers,org.tortodrone.core.town.TownCatalog rules,boolean locked,int expireDays)throws IOException {
        return administration.grantItems(username,knightName,expectedAccount,expectedWorld,entry,quantity,actorAccount,actorRevision,modifiers,rules,locked,expireDays);
    }
    /** Local administration only; a stale inspector cannot overwrite newly forged or traded gear. */
    public WorldProfile itemModifiers(String username,String knightName,int expectedAccount,int expectedWorld,long expectedRevision,
            long itemId,org.tortodrone.character.ItemModifiers modifiers,org.tortodrone.core.character.ItemCatalog catalog,org.tortodrone.core.town.TownCatalog rules)throws IOException{
        return administration.itemModifiers(username,knightName,expectedAccount,expectedWorld,expectedRevision,itemId,modifiers,catalog,rules);
    }
    /** Web/local administration; expected identity and revision are checked inside BEGIN IMMEDIATE. */
    public WorldProfile itemAccessory(String username,String knightName,int expectedAccount,int expectedWorld,long expectedRevision,long itemId,String accessory,org.tortodrone.core.town.TownCatalog rules)throws IOException {
        return administration.itemAccessory(username,knightName,expectedAccount,expectedWorld,expectedRevision,itemId,accessory,rules);
    }
    public List<WorldProfile> trade(WorldProfile first,long firstAuth,Map<Long,Integer> firstItems,int firstCrowns,int firstEnergy,
            WorldProfile second,long secondAuth,Map<Long,Integer> secondItems,int secondCrowns,int secondEnergy)throws IOException {
        return administration.trade(first,firstAuth,firstItems,firstCrowns,firstEnergy,second,secondAuth,secondItems,secondCrowns,secondEnergy);
    }
    public List<WorldProfile> trade(WorldProfile first,long firstAuth,Map<Long,Integer> firstItems,int firstCrowns,int firstEnergy,
            WorldProfile second,long secondAuth,Map<Long,Integer> secondItems,int secondCrowns,int secondEnergy,org.tortodrone.core.character.ItemCatalog catalog)throws IOException {
        return administration.trade(first,firstAuth,firstItems,firstCrowns,firstEnergy,second,secondAuth,secondItems,secondCrowns,secondEnergy,catalog);
    }

    public void saveRoster(int id,List<Knight> knights,int next,long revision,long rosterRevision) throws IOException {
        roster.saveRoster(id,knights,next,revision,rosterRevision);
    }
    public void saveRoster(int id,List<Knight> knights,int next,long revision,long rosterRevision,int openingKnight)throws IOException {
        roster.saveRoster(id,knights,next,revision,rosterRevision,openingKnight);
    }
    private static IOException failure(SQLException e) {
        if(e.getMessage()!=null&&(e.getErrorCode()==5||e.getErrorCode()==6||e.getMessage().contains("SQLITE_BUSY")||e.getMessage().contains("database is locked")))busyErrors.incrementAndGet();
        if(e.getMessage()!=null&&e.getMessage().contains("UNIQUE constraint failed: knights.name_key"))return new KnightNameInUse(e);
        if (e.getMessage() != null && e.getMessage().contains("accounts.name_key")) return new IOException("Account already exists",e);
        return new IOException("Database operation failed; existing data was not replaced",e);
    }
}
