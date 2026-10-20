package org.tortodrone.storage;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.Knight;
import org.tortodrone.character.WorldProfile;

/** Local SQLite database. Transactions are short; password derivation runs outside them. */
public final class Database {
    public static final int SCHEMA_VERSION = 15;
    public static final class KnightNameInUse extends IOException {
        private KnightNameInUse(SQLException cause) { super("That knight name is already in use",cause); }
    }
    public record Identity(int id, String username, boolean gameMaster, long authRevision, byte[] salt, byte[] verifier) {
        @Override public String toString() { return "Identity[redacted]"; }
    }
    public record Summary(int id, String username, boolean gameMaster, int knights, long createdAt) { }
    public record RosterState(List<Knight> knights,int next,long revision) { }
    public record WorldState(int accountId,int knightId,int worldId,String name,long revision,long authRevision,boolean gameMaster) { }
    public record Diagnostics(long operations,long slowOperations,long failures,long rollbacks,long maximumMillis) { }
    private static final java.util.concurrent.atomic.AtomicLong operations=new java.util.concurrent.atomic.AtomicLong(),slow=new java.util.concurrent.atomic.AtomicLong(),failures=new java.util.concurrent.atomic.AtomicLong(),rollbacks=new java.util.concurrent.atomic.AtomicLong(),maximum=new java.util.concurrent.atomic.AtomicLong();
    public static Diagnostics diagnostics(){return new Diagnostics(operations.get(),slow.get(),failures.get(),rollbacks.get(),maximum.get());}
    @FunctionalInterface public interface Observer {void completed(String operation,long millis,boolean failed);}
    private volatile Observer observer=(operation,millis,failed)->{};
    public void observe(Observer observer){this.observer=Objects.requireNonNull(observer);}
    private void measured(String kind,long start,boolean failed){
        long elapsed=(System.nanoTime()-start)/1000000;operations.incrementAndGet();maximum.accumulateAndGet(elapsed,Math::max);
        if(elapsed>=100)slow.incrementAndGet();if(failed)failures.incrementAndGet();
        try{observer.completed(kind,elapsed,failed);}catch(RuntimeException ignored){ }
    }
    private final Path file;
    private boolean initialized;
    @FunctionalInterface public interface Work<T> { T run(Connection connection) throws SQLException, IOException; }

    private final boolean upgradeCandidate;
    private final DepotSeed.Data legacyDepot;
    public Database(Path directory, boolean deploymentLocked) throws IOException { this(directory,deploymentLocked,false,null); }
    Database(Path directory,boolean deploymentLocked,boolean upgradeCandidate,DepotSeed.Data legacyDepot)throws IOException {
        this.upgradeCandidate=upgradeCandidate;this.legacyDepot=legacyDepot;
        file = directory.toAbsolutePath().normalize().resolve("tortodrone.db");
        try { Class.forName("org.sqlite.JDBC"); }
        catch (ClassNotFoundException e) { throw new IOException("Missing bundled SQLite driver", e); }
        Files.createDirectories(file.getParent());
        if (Files.exists(file)) {
            if((version()==9||version()==10||version()==11||version()==12||version()==13||version()==14)&&!deploymentLocked){
                try(var channel=FileChannel.open(directory.resolve("server.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()){
                    if(lock==null)throw new IOException("Stop the server before upgrading the database");requireCurrentVersion();
                }catch(OverlappingFileLockException live){throw new IOException("Stop the server before upgrading the database",live);}
            }else requireCurrentVersion();
            initialized=true;return;
        }
        if (deploymentLocked) initialize();
        else {
            try (var channel = FileChannel.open(directory.resolve("server.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var lock = channel.tryLock()) {
                if (lock == null) throw new IOException("Stop the server before initializing the database");
                if (Files.exists(file)) requireCurrentVersion(); else initialize();
            } catch (OverlappingFileLockException e) { throw new IOException("Database initialization is already in progress", e); }
        }
        initialized=true;
    }
    private void requireCurrentVersion() throws IOException {
        int version = version();
        if(version==9||version==10||version==11||version==12||version==13||version==14){
            if(!upgradeCandidate)throw new IOException("Database schema "+version+" requires offline EconomyUpgrade; export and review a fresh candidate. Saved data was not changed");
            // Only known predecessors are eligible; unknown versions remain untouched.
            try(var c=connect();var statement=c.createStatement()){
                statement.execute("PRAGMA foreign_keys=OFF");statement.execute("BEGIN IMMEDIATE");
                try{
                    int lockedVersion;try(var r=statement.executeQuery("PRAGMA user_version")){r.next();lockedVersion=r.getInt(1);}
                    if(lockedVersion==9){AuctionSchema.upgrade(c);DepotStore.initialize(c);}
                    else if(lockedVersion==10)DepotStore.initialize(c);
                    else if(lockedVersion!=11&&lockedVersion!=12&&lockedVersion!=13&&lockedVersion!=14&&lockedVersion!=SCHEMA_VERSION)throw new IOException("Database schema changed during upgrade");
                    if(lockedVersion<12)EnergyExchange.initialize(c);
                    if(lockedVersion<13)GiftStore.initialize(c);
                    if(lockedVersion<14)TownStore.knightRenameClock(c);
                    if(lockedVersion<15)DepotStore.upgrade(c,legacyDepot);
                    try(var r=statement.executeQuery("PRAGMA foreign_key_check")){if(r.next())throw new IOException("Database upgrade failed foreign key validation");}
                    statement.execute("PRAGMA user_version="+SCHEMA_VERSION);statement.execute("COMMIT");
                }catch(SQLException|IOException|RuntimeException failure){statement.execute("ROLLBACK");throw failure;}
                finally{statement.execute("PRAGMA foreign_keys=ON");}
            }catch(SQLException failure){throw failure(failure);}
            return;
        }
        if (version != SCHEMA_VERSION) throw new IOException("Unsupported database schema " + version
            + "; expected " + SCHEMA_VERSION + ". Inspect a backup or use a supported explicit upgrade; saved data was not changed");
    }
    public Path file() { return file; }
    private Connection connect() throws SQLException {
        if (!Files.isRegularFile(file)) throw new SQLException("Database file is missing or not a regular file");
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON"); statement.execute("PRAGMA busy_timeout=5000");
        } catch (SQLException e) { connection.close(); throw e; }
        return connection;
    }
    public <T> T read(Work<T> work) throws IOException {
        long start=System.nanoTime();boolean failed=true;
        try (Connection connection=connect()) {T result=work.run(connection);failed=false;return result;}
        catch(SQLException error){throw failure(error);}
        finally{measured("read",start,failed);}
    }
    public <T> T write(Work<T> work) throws IOException {
        long start=System.nanoTime();boolean failed=true;
        try(Connection connection=connect();Statement statement=connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            try {
                if(initialized)InventoryExpiry.prune(connection,System.currentTimeMillis());
                T value=work.run(connection);statement.execute("COMMIT");failed=false;return value;
            } catch(SQLException|IOException|RuntimeException error) {
                rollbacks.incrementAndGet();
                try{statement.execute("ROLLBACK");if(error instanceof Refused)failed=false;}catch(SQLException rollback){error.addSuppressed(rollback);}
                throw error;
            }
        } catch(SQLException error){throw failure(error);}
        finally{measured("write",start,failed);}
    }
    /** Expected business refusal: rollback normally, without reporting a storage failure. */
    public static final class Refused extends IOException {
        public Refused(String message){super(message);}
    }
    private int version() throws IOException {
        return read(connection -> { try (Statement s = connection.createStatement(); ResultSet r = s.executeQuery("PRAGMA user_version")) { r.next(); return r.getInt(1); } });
    }
    private void initialize() throws IOException {
        Files.createFile(file);
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        read(connection -> { try (Statement s = connection.createStatement()) { s.execute("PRAGMA journal_mode=WAL"); } return null; });
        write(connection -> {
            try (Statement s = connection.createStatement()) {
                s.execute("CREATE TABLE metadata(key TEXT PRIMARY KEY, value INTEGER NOT NULL)");
                s.execute("INSERT INTO metadata VALUES('next-account-id',1)");
                s.execute("CREATE TABLE accounts(id INTEGER PRIMARY KEY CHECK(id>0), username TEXT NOT NULL, name_key TEXT NOT NULL UNIQUE, salt BLOB NOT NULL CHECK(length(salt)=16), verifier BLOB NOT NULL CHECK(length(verifier)=32), game_master INTEGER NOT NULL DEFAULT 0 CHECK(game_master IN(0,1)), auth_revision INTEGER NOT NULL DEFAULT 1, revision INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL, next_knight_id INTEGER NOT NULL DEFAULT 1 CHECK(next_knight_id>0))");
                s.execute("CREATE TABLE knights(account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE, id INTEGER NOT NULL CHECK(id>0), name TEXT NOT NULL, name_key TEXT NOT NULL, helm TEXT NOT NULL, armor TEXT NOT NULL, helm_accessory TEXT, armor_accessory TEXT, costume TEXT NOT NULL, color INTEGER NOT NULL CHECK(color BETWEEN 0 AND 255), height INTEGER NOT NULL CHECK(height BETWEEN 0 AND 4), eye INTEGER NOT NULL CHECK(eye BETWEEN 0 AND 46), created_at INTEGER NOT NULL, PRIMARY KEY(account_id,id), UNIQUE(account_id,name_key))");
                s.execute("CREATE TABLE account_events(id INTEGER PRIMARY KEY, account_id INTEGER NOT NULL, action TEXT NOT NULL, created_at INTEGER NOT NULL)");
            }
            WorldProfiles.initialize(connection);
            TownStore.initialize(connection);
            DepotStore.upgrade(connection,DepotSeed.bundled());
            try (Statement s = connection.createStatement()) {
                s.execute("CREATE UNIQUE INDEX global_knight_names ON knights(name_key)");
                s.execute("PRAGMA user_version="+SCHEMA_VERSION);
            }
            return null;
        });
    }
    /** Availability is advisory; the unique index arbitrates competing writes. */
    public boolean knightNameInUse(String name,int accountId,int knightId)throws IOException {
        return read(c->{try(var s=c.prepareStatement("SELECT 1 FROM knights WHERE name_key=? AND NOT(account_id=? AND id=?) LIMIT 1")) {
            s.setString(1,name.toLowerCase(Locale.ROOT));s.setInt(2,accountId);s.setInt(3,knightId);
            try(var r=s.executeQuery()){return r.next();}
        }});
    }
    public Identity identity(String username) throws IOException {
        return read(c -> { try (PreparedStatement s = c.prepareStatement("SELECT * FROM accounts WHERE name_key=?")) {
            s.setString(1, username.toLowerCase(Locale.ROOT)); try (ResultSet r = s.executeQuery()) { return r.next() ? identity(r) : null; }
        } });
    }
    public boolean current(int id, long revision) throws IOException {
        return read(c -> { try (PreparedStatement s = c.prepareStatement("SELECT auth_revision FROM accounts WHERE id=?")) {
            s.setInt(1,id); try (ResultSet r = s.executeQuery()) { return r.next() && r.getLong(1) == revision; }
        } });
    }
    /** Ordinary account preference rows in the existing integer metadata table; schema stays unchanged. */
    public boolean inspectionHidden(int accountId,long authRevision)throws IOException {
        return read(c->{requirePreferenceOwner(c,accountId,authRevision);
            try(var s=c.prepareStatement("SELECT value FROM metadata WHERE key=?")) {
                s.setString(1,"account."+accountId+".inspection-hidden");
                try(var r=s.executeQuery()) {
                    if(!r.next())return false;
                    int value=r.getInt(1);if(value!=0&&value!=1)throw new IOException("Invalid inspection preference");return value==1;
                }
            }
        });
    }
    public void inspectionHidden(int accountId,long authRevision,boolean hidden)throws IOException {
        write(c->{requirePreferenceOwner(c,accountId,authRevision);
            try(var s=c.prepareStatement("INSERT INTO metadata(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
                s.setString(1,"account."+accountId+".inspection-hidden");s.setInt(2,hidden?1:0);s.executeUpdate();
            }return null;
        });
    }
    private static void requirePreferenceOwner(Connection c,int accountId,long authRevision)throws SQLException,IOException {
        if(authRevision<0)throw new IOException("Authenticated preference owner required");
        try(var s=c.prepareStatement("SELECT 1 FROM accounts WHERE id=? AND auth_revision=?")) {
            s.setInt(1,accountId);s.setLong(2,authRevision);
            try(var r=s.executeQuery()){if(!r.next())throw new IOException("Account changed; reconnect");}
        }
    }
    private static Identity identity(ResultSet r) throws SQLException {
        return new Identity(r.getInt("id"), r.getString("username"), r.getBoolean("game_master"), r.getLong("auth_revision"), r.getBytes("salt"), r.getBytes("verifier"));
    }
    public List<Summary> list() throws IOException {
        return read(c -> { List<Summary> result = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT a.id,a.username,a.game_master,a.created_at,count(k.id) AS knights FROM accounts a LEFT JOIN knights k ON k.account_id=a.id GROUP BY a.id ORDER BY a.id")) {
                while (r.next()) result.add(new Summary(r.getInt("id"),r.getString("username"),r.getBoolean("game_master"),r.getInt("knights"),r.getLong("created_at")));
            } return List.copyOf(result);
        });
    }
    public int create(String username, Passwords.Protection protection, boolean gameMaster) throws IOException {
        return write(c -> {
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT count(*) FROM accounts")) {
                r.next(); if (r.getInt(1) >= 1000) throw new IOException("Account limit reached");
            }
            int id;
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT value FROM metadata WHERE key='next-account-id'")) { r.next(); id = r.getInt(1); }
            if (id >= Integer.MAX_VALUE) throw new IOException("Account ID space exhausted");
            insertAccount(c,id,username,protection.salt(),protection.verifier(),gameMaster,1);
            try (Statement s = c.createStatement()) { s.executeUpdate("UPDATE metadata SET value=value+1 WHERE key='next-account-id'"); }
            event(c,id,"created"); return id;
        });
    }
    private static void insertAccount(Connection c, int id, String username, byte[] salt, byte[] verifier, boolean gameMaster, int next) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO accounts(id,username,name_key,salt,verifier,game_master,created_at,next_knight_id) VALUES(?,?,?,?,?,?,?,?)")) {
            s.setInt(1,id); s.setString(2,username); s.setString(3,username.toLowerCase(Locale.ROOT)); s.setBytes(4,salt); s.setBytes(5,verifier); s.setBoolean(6,gameMaster); s.setLong(7,System.currentTimeMillis()); s.setInt(8,next); s.executeUpdate();
        }
    }
    public void password(String username, Passwords.Protection protection,int expectedId) throws IOException {
        write(c -> {
            int id = requireId(c,username);
            if (expectedId >= 0 && id != expectedId) throw new IOException("Account changed; refresh the account list");
            try (PreparedStatement s = c.prepareStatement("UPDATE accounts SET salt=?,verifier=?,auth_revision=auth_revision+1,revision=revision+1 WHERE id=?")) {
                s.setBytes(1,protection.salt()); s.setBytes(2,protection.verifier()); s.setInt(3,id); s.executeUpdate();
            } event(c,id,"password changed"); return null;
        });
    }
    public void gameMaster(String username, boolean gameMaster,int expectedId) throws IOException {
        write(c -> { int id = requireId(c,username);
            if (expectedId >= 0 && id != expectedId) throw new IOException("Account changed; refresh the account list");
            try (PreparedStatement s = c.prepareStatement("UPDATE accounts SET game_master=?,revision=revision+1 WHERE id=?")) { s.setBoolean(1,gameMaster); s.setInt(2,id); s.executeUpdate(); }
            event(c,id,gameMaster ? "game master granted" : "game master removed"); return null;
        });
    }
    /** Toggle atomically with other local tools; no stale read/modify/write window. */
    public boolean toggleGameMaster(String username) throws IOException {
        return write(c -> {
            int id=requireId(c,username);
            boolean enabled;
            try(var s=c.prepareStatement("UPDATE accounts SET game_master=1-game_master,revision=revision+1 WHERE id=? RETURNING game_master")) {
                s.setInt(1,id);try(var r=s.executeQuery()){r.next();enabled=r.getBoolean(1);}
            }
            event(c,id,enabled ? "game master granted" : "game master removed");
            return enabled;
        });
    }
    public void delete(String username,int expectedId) throws IOException {
        write(c -> { int id = requireId(c,username);
            if (expectedId >= 0 && id != expectedId) throw new IOException("Account changed; refresh the account list");
            try (PreparedStatement s = c.prepareStatement("DELETE FROM accounts WHERE id=?")) { s.setInt(1,id); s.executeUpdate(); }
            WorldProfiles.cleanProgress(c);
            try(var s=c.prepareStatement("DELETE FROM metadata WHERE key=?")){s.setString(1,"account."+id+".inspection-hidden");s.executeUpdate();}
            event(c,id,"deleted"); return null;
        });
    }
    private static int requireId(Connection c, String username) throws IOException, SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT id FROM accounts WHERE name_key=?")) {
            s.setString(1,username.toLowerCase(Locale.ROOT)); try (ResultSet r = s.executeQuery()) { if (!r.next()) throw new IOException("No such account"); return r.getInt(1); }
        }
    }
    private static void event(Connection c,int id,String action) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO account_events(account_id,action,created_at) VALUES(?,?,?)")) {
            s.setInt(1,id); s.setString(2,action); s.setLong(3,System.currentTimeMillis()); s.executeUpdate();
        }
    }
    public int nextKnight(int id) throws IOException {
        return read(c -> { try (PreparedStatement s = c.prepareStatement("SELECT next_knight_id FROM accounts WHERE id=?")) {
            s.setInt(1,id); try (ResultSet r = s.executeQuery()) { if (!r.next()) throw new IOException("Account no longer exists"); return r.getInt(1); }
        } });
    }
    public List<Knight> roster(int id) throws IOException {
        return read(c -> { List<Knight> result = new ArrayList<>();
            try (PreparedStatement s = c.prepareStatement("SELECT * FROM knights WHERE account_id=? ORDER BY id")) {
                s.setInt(1,id); try (ResultSet r = s.executeQuery()) { while (r.next()) result.add(knight(r)); }
            } return List.copyOf(result);
        });
    }
    /** One SQLite snapshot for the roster, allocation counter and compare-and-swap revision. */
    public RosterState rosterState(int id)throws IOException {
        return read(c->{
            c.setAutoCommit(false);
            int next;long revision;
            try(var s=c.prepareStatement("SELECT next_knight_id,revision FROM accounts WHERE id=?")) {
                s.setInt(1,id);try(var r=s.executeQuery()){if(!r.next())throw new IOException("Account no longer exists");next=r.getInt(1);revision=r.getLong(2);}
            }
            var knights=new ArrayList<Knight>();
            try(var s=c.prepareStatement("SELECT * FROM knights WHERE account_id=? ORDER BY id")) {
                s.setInt(1,id);try(var r=s.executeQuery()){while(r.next())knights.add(knight(r));}
            }
            return new RosterState(List.copyOf(knights),next,revision);
        });
    }
    public void deleteKnight(String username,String name,int expectedAccount,int expectedWorld)throws IOException {
        write(c->{
            int account=requireId(c,username);
            if(account!=expectedAccount)throw new IOException("Account changed; refresh the account list");
            int knight,world;
            try(var s=c.prepareStatement("SELECT k.id,w.world_id FROM knights k JOIN world_characters w ON w.account_id=k.account_id AND w.knight_id=k.id WHERE k.account_id=? AND k.name_key=?")) {
                s.setInt(1,account);s.setString(2,name.toLowerCase(Locale.ROOT));
                try(var r=s.executeQuery()){if(!r.next())throw new IOException("No such knight");knight=r.getInt(1);world=r.getInt(2);}
            }
            if(world!=expectedWorld)throw new IOException("Knight changed; refresh the knight list");
            try(var s=c.prepareStatement("DELETE FROM currency_events WHERE world_id=?")){s.setInt(1,world);s.executeUpdate();}
            try(var s=c.prepareStatement("DELETE FROM knights WHERE account_id=? AND id=?")){s.setInt(1,account);s.setInt(2,knight);s.executeUpdate();}
            WorldProfiles.cleanProgress(c);
            try(var s=c.prepareStatement("UPDATE accounts SET auth_revision=auth_revision+1,revision=revision+1 WHERE id=?")){s.setInt(1,account);s.executeUpdate();}
            event(c,account,"knight deleted; world="+world);return null;
        });
    }
    private static Knight knight(ResultSet r) throws SQLException {
        return new Knight(r.getInt("id"),r.getString("name"),r.getString("helm"),r.getString("armor"),r.getString("helm_accessory"),r.getString("armor_accessory"),r.getString("costume"),r.getInt("color"),r.getInt("height"),r.getInt("eye"),r.getLong("created_at"));
    }
    public WorldProfile worldProfile(int accountId,int knightId,long authRevision) throws IOException {
        return worldProfile(accountId,knightId,authRevision,null);
    }
    public WorldProfile worldProfile(int accountId,int knightId,long authRevision,WorldProfile cached) throws IOException {
        return read(c -> {
            c.setAutoCommit(false); // One read snapshot for identity and inventory, even during deletion.
            var profile = WorldProfiles.load(c,accountId,knightId,authRevision,cached); c.commit(); return profile;
        });
    }
    /** Lightweight consistent metadata for active world observers; inventories load only on change. */
    public Map<Integer,WorldState> worldStates()throws IOException {
        if(read(c->InventoryExpiry.due(c,System.currentTimeMillis())))write(c->null);
        return read(c->{
            var states=new HashMap<Integer,WorldState>();
            try(var s=c.createStatement();var rows=s.executeQuery("SELECT w.account_id,w.knight_id,w.world_id,k.name,w.state_revision,a.auth_revision,a.game_master FROM world_characters w JOIN accounts a ON a.id=w.account_id JOIN knights k ON k.account_id=w.account_id AND k.id=w.knight_id")) {
                while(rows.next()) {
                    var state=new WorldState(rows.getInt(1),rows.getInt(2),rows.getInt(3),rows.getString(4),rows.getLong(5),rows.getLong(6),rows.getBoolean(7));
                    states.put(state.worldId(),state);
                }
            }
            return Map.copyOf(states);
        });
    }
    /** Current world state without an authentication-revision check, for read-only publications. */
    public WorldProfile profile(int accountId,int knightId) throws IOException {
        return worldProfile(accountId,knightId,-1);
    }

    private record Target(int account, int knight) { }
    private static Target target(Connection c, String username, String knightName) throws SQLException, IOException {
        try (var s=c.prepareStatement("SELECT k.account_id,k.id FROM knights k JOIN accounts a ON a.id=k.account_id WHERE k.name_key=?" + (username==null ? "" : " AND a.name_key=?"))) {
            s.setString(1,knightName.toLowerCase(Locale.ROOT));
            if(username!=null)s.setString(2,username.toLowerCase(Locale.ROOT));
            try(var r=s.executeQuery()){if(!r.next())throw new IOException("No such knight");return new Target(r.getInt(1),r.getInt(2));}
        }
    }
    private static WorldProfile administrativeProfile(Connection c, String username, String knightName,
            int expectedAccount, int expectedWorld) throws SQLException, IOException {
        var target=target(c,username,knightName);
        if(expectedAccount>=0&&target.account()!=expectedAccount)throw new IOException("Account changed; refresh");
        var profile=WorldProfiles.load(c,target.account(),target.knight(),-1);
        if(expectedWorld>=0&&profile.worldId()!=expectedWorld)throw new IOException("Knight changed; refresh");
        return profile;
    }
    public WorldProfile inspectKnight(String username, String knightName) throws IOException {
        return read(c->{c.setAutoCommit(false);var result=administrativeProfile(c,username,knightName,-1,-1);c.commit();return result;});
    }
    /** Local administration only; target identity, revision and audit share one transaction. */
    public WorldProfile progress(String username,String knightName,int expectedAccount,int expectedWorld,Integer rank,Integer subrank,Integer prestige)throws IOException{
        return progress(username,knightName,expectedAccount,expectedWorld,rank,subrank,prestige,null);
    }
    public WorldProfile progress(String username,String knightName,int expectedAccount,int expectedWorld,Integer rank,Integer subrank,Integer prestige,String starterSprite)throws IOException{
        return write(c->{var profile=administrativeProfile(c,username,knightName,expectedAccount,expectedWorld);var old=profile.progress();
            org.tortodrone.character.KnightProgress next;
            try{if((rank==null)!=(subrank==null))throw new IllegalArgumentException();next=new org.tortodrone.character.KnightProgress(rank==null?old.rank():rank,subrank==null?old.subrank():subrank,prestige==null?old.prestige():prestige);}
            catch(IllegalArgumentException invalid){throw new IOException("Rank must be 1-10, subrank 1-4 and prestige nonnegative",invalid);}
            boolean granted=starterSprite!=null&&TownStore.grantStarterSprite(c,profile,starterSprite);
            if(next.equals(old)&&!granted)return profile;
            try(var s=c.prepareStatement("INSERT INTO metadata(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")){
                s.setString(1,WorldProfiles.progressKey(profile.worldId(),"rank"));s.setInt(2,next.rank()*10+next.subrank());s.executeUpdate();
                s.setString(1,WorldProfiles.progressKey(profile.worldId(),"prestige"));s.setInt(2,next.prestige());s.executeUpdate();
            }
            try(var s=c.prepareStatement("UPDATE world_characters SET state_revision=state_revision+1 WHERE world_id=?")){s.setInt(1,profile.worldId());s.executeUpdate();}
            event(c,profile.accountId(),"knight progress; world="+profile.worldId()+"; rank="+next.rankPair()+"; prestige="+next.prestige()+(granted?"; testing starter sprite="+starterSprite:""));
            return WorldProfiles.load(c,profile.accountId(),profile.knightId(),-1);
        });
    }
    public WorldProfile currency(String username,String knightName,int expectedAccount,int expectedWorld,
            org.tortodrone.character.Currency currency,long delta) throws IOException {
        return balance(username,knightName,expectedAccount,expectedWorld,currency,delta,false);
    }
    public WorldProfile setCurrency(String username,String knightName,int expectedAccount,int expectedWorld,
            org.tortodrone.character.Currency currency,long amount) throws IOException {
        return balance(username,knightName,expectedAccount,expectedWorld,currency,amount,true);
    }
    private WorldProfile balance(String username,String knightName,int expectedAccount,int expectedWorld,
            org.tortodrone.character.Currency currency,long amount,boolean absolute) throws IOException {
        Objects.requireNonNull(currency);
        if(absolute ? amount<0||amount>Integer.MAX_VALUE : amount==0||amount<-Integer.MAX_VALUE||amount>Integer.MAX_VALUE)
            throw new IOException(absolute ? "Balance must be between 0 and 2147483647" : "Amount must be between 1 and 2147483647");
        return write(c -> {
            var profile=administrativeProfile(c,username,knightName,expectedAccount,expectedWorld);
            long previous=currency==org.tortodrone.character.Currency.CROWNS?profile.crowns():profile.energy();
            long balance=absolute?amount:previous+amount, delta=balance-previous;
            if(balance<0)throw new IOException("Insufficient "+currency.name().toLowerCase(Locale.ROOT));
            if(balance>Integer.MAX_VALUE)throw new IOException("Balance exceeds the supported limit");
            String column=currency==org.tortodrone.character.Currency.CROWNS?"crowns":"energy";
            try(var s=c.prepareStatement("UPDATE world_characters SET "+column+"=?,state_revision=state_revision+1 WHERE world_id=?")) {
                s.setLong(1,balance);s.setInt(2,profile.worldId());s.executeUpdate();
            }
            try(var s=c.prepareStatement("INSERT INTO currency_events(world_id,currency,delta,balance,created_at) VALUES(?,?,?,?,?)")) {
                s.setInt(1,profile.worldId());s.setString(2,column);s.setLong(3,delta);s.setLong(4,balance);s.setLong(5,System.currentTimeMillis());s.executeUpdate();
            }
            event(c,profile.accountId(),"currency "+(absolute?"set":"grant")+"; world="+profile.worldId()+"; "+column+"="+balance+"; delta="+delta);
            return WorldProfiles.load(c,profile.accountId(),profile.knightId(),-1);
        });
    }
    /** Local administrator quantity/removal, audited and published by the existing revision poller. */
    public WorldProfile itemQuantity(String username,String knightName,int expectedAccount,int expectedWorld,
            long itemId,int quantity,org.tortodrone.character.ItemCatalog catalog) throws IOException {
        if(quantity<0||quantity>9999)throw new IOException("Quantity must be between 0 and 9999");
        return write(c->{
            var profile=administrativeProfile(c,username,knightName,expectedAccount,expectedWorld);
            var item=profile.items().stream().filter(i->i.id()==itemId).findFirst().orElseThrow(()->new IOException("No such owned item"));
            if(item.starter()||item.permanent()||item.equippedSlot()!=null)
                throw new IOException("Starter, permanent or equipped items cannot be removed or adjusted; unequip ordinary items in the client first");
            var definition=catalog.find(item.config());
            if(quantity>1&&(definition==null||!definition.stackable()))throw new IOException("This item is not stackable");
            if(quantity==0)try(var s=c.prepareStatement("DELETE FROM inventory_items WHERE world_id=? AND item_id=?")){s.setInt(1,profile.worldId());s.setLong(2,itemId);s.executeUpdate();}
            else try(var s=c.prepareStatement("UPDATE inventory_items SET count=? WHERE world_id=? AND item_id=?")){s.setInt(1,quantity);s.setInt(2,profile.worldId());s.setLong(3,itemId);s.executeUpdate();}
            try(var s=c.prepareStatement("UPDATE world_characters SET state_revision=state_revision+1 WHERE world_id=?")){s.setInt(1,profile.worldId());s.executeUpdate();}
            try(var s=c.prepareStatement("INSERT INTO item_events(world_id,actor_account,config,quantity,first_item_id,created_at) VALUES(?,NULL,?,?,?,?)")){
                s.setInt(1,profile.worldId());s.setString(2,item.config());s.setInt(3,quantity-item.count());s.setLong(4,itemId);s.setLong(5,System.currentTimeMillis());s.executeUpdate();
            }
            event(c,profile.accountId(),"inventory quantity; world="+profile.worldId()+"; item="+itemId+"; count="+quantity);
            return WorldProfiles.load(c,profile.accountId(),profile.knightId(),-1);
        });
    }
    public WorldProfile equip(int accountId,int knightId,long revision,long itemId,Integer slot) throws IOException {
        return write(c -> {
            var profile=WorldProfiles.load(c,accountId,knightId,revision);
            var item=profile.items().stream().filter(i->i.id()==itemId).findFirst().orElseThrow(()->new IOException("No such owned item"));
            if(slot!=null && !item.slot().accepts(slot))throw new IOException("Item cannot be equipped in that slot");
            if(slot!=null&&!org.tortodrone.character.SlotUpgrades.unlocked(profile,slot))throw new IOException("Activate a slot upgrade first");
            try(var st=c.prepareStatement("DELETE FROM metadata WHERE key IN (SELECT 'item.weapon-slot.'||item_id FROM inventory_items WHERE world_id=?) AND (key=? OR value=?)")) {
                st.setInt(1,profile.worldId());st.setString(2,"item.weapon-slot."+itemId);if(slot==null)st.setNull(3,Types.INTEGER);else st.setInt(3,slot);st.executeUpdate();
            }

            if(slot!=null)try(PreparedStatement s=c.prepareStatement("UPDATE inventory_items SET equipped_slot=NULL WHERE world_id=? AND equipped_slot=?")){s.setInt(1,profile.worldId());s.setInt(2,slot);s.executeUpdate();}
            try(PreparedStatement s=c.prepareStatement("UPDATE inventory_items SET equipped_slot=?,bound=CASE WHEN ? IS NOT NULL AND bind_on_equip=1 THEN 1 ELSE bound END WHERE world_id=? AND item_id=?")){
                if(slot==null||slot==8||slot==9)s.setNull(1,Types.INTEGER);else s.setInt(1,slot);
                if(slot==null)s.setNull(2,Types.INTEGER);else s.setInt(2,slot);
                s.setInt(3,profile.worldId());s.setLong(4,itemId);s.executeUpdate();
            }
            if(slot!=null&&(slot==8||slot==9))try(var st=c.prepareStatement("INSERT INTO metadata(key,value) VALUES(?,?)")){st.setString(1,"item.weapon-slot."+itemId);st.setInt(2,slot);st.executeUpdate();}
            try(PreparedStatement s=c.prepareStatement("UPDATE world_characters SET state_revision=state_revision+1 WHERE world_id=?")){s.setInt(1,profile.worldId());s.executeUpdate();}
            return WorldProfiles.load(c,accountId,knightId,revision);
        });
    }
    /** Permission and target identity are checked under the same write lock as allocation. */
    public WorldProfile grantItems(String username,String knightName,int expectedAccount,int expectedWorld,
            org.tortodrone.character.ItemCatalog.Entry entry,int quantity,int actorAccount,long actorRevision)throws IOException {
        return grantItems(username,knightName,expectedAccount,expectedWorld,entry,quantity,actorAccount,actorRevision,org.tortodrone.character.ItemModifiers.NONE,org.tortodrone.town.TownCatalog.empty());
    }
    public WorldProfile grantItems(String username,String knightName,int expectedAccount,int expectedWorld,
            org.tortodrone.character.ItemCatalog.Entry entry,int quantity,int actorAccount,long actorRevision,
            org.tortodrone.character.ItemModifiers modifiers,org.tortodrone.town.TownCatalog rules)throws IOException {
        return grantItems(username,knightName,expectedAccount,expectedWorld,entry,quantity,actorAccount,actorRevision,modifiers,rules,false,entry.expireDays());
    }
    public WorldProfile grantItems(String username,String knightName,int expectedAccount,int expectedWorld,
            org.tortodrone.character.ItemCatalog.Entry entry,int quantity,int actorAccount,long actorRevision,
            org.tortodrone.character.ItemModifiers modifiers,org.tortodrone.town.TownCatalog rules,boolean locked,int expireDays)throws IOException {
        if(expireDays<0||expireDays>3650||expireDays>0&&entry.permanent())throw new IOException("Expiry must be 0–3650 days and cannot apply to permanent items");
        if(quantity<1||quantity>org.tortodrone.character.ItemCatalog.GRANT_LIMIT)throw new IOException("Quantity must be between 1 and 20");
        if(!entry.grantable())throw new IOException("Unsupported item for grants");
        modifiers.validate(entry,rules);
        return write(c->{
            if(actorAccount>=0)try(var s=c.prepareStatement("SELECT game_master FROM accounts WHERE id=? AND auth_revision=?")) {
                s.setInt(1,actorAccount);s.setLong(2,actorRevision);
                try(var r=s.executeQuery()){if(!r.next()||!r.getBoolean(1))throw new IOException("Game Master permission required");}
            }
            int account,knight;
            if (username == null) {
                // Global name lookup shares the grant transaction and permission check.
                try (var s=c.prepareStatement("SELECT account_id FROM knights WHERE name_key=?")) {
                    s.setString(1,knightName.toLowerCase(Locale.ROOT));
                    try (var r=s.executeQuery()) { if(!r.next())throw new IOException("No such knight");account=r.getInt(1); }
                }
            } else account=requireId(c,username);
            if(expectedAccount>=0&&account!=expectedAccount)throw new IOException("Account changed; refresh");
            try(var s=c.prepareStatement("SELECT id FROM knights WHERE account_id=? AND name_key=?")) {
                s.setInt(1,account);s.setString(2,knightName.toLowerCase(Locale.ROOT));
                try(var r=s.executeQuery()){if(!r.next())throw new IOException("No such knight");knight=r.getInt(1);}
            }
            var profile=WorldProfiles.load(c,account,knight,-1);
            if(expectedWorld>=0&&profile.worldId()!=expectedWorld)throw new IOException("Knight changed; refresh");
            // Stack only plain inventory items with identical grant flags; equipment stays distinct.
            var stack=expireDays==0&&entry.stackable()&&modifiers.equals(org.tortodrone.character.ItemModifiers.NONE)?profile.items().stream().filter(i->i.config().equals(entry.config())
                &&i.slot()==org.tortodrone.character.WorldProfile.Slot.OTHER&&i.expiresAt()==null&&i.bound()==(locked||entry.locked()||entry.permanent())&&i.permanent()==entry.permanent()
                &&i.equippedSlot()==null&&i.level()==0&&i.heatProgress()==0&&i.variants().isEmpty()&&i.count()+quantity<=9999).findFirst().orElse(null):null;
            int instances=stack!=null?0:entry.stackable()?1:quantity;
            if(profile.items().size()+instances>org.tortodrone.character.ItemCatalog.INVENTORY_LIMIT)throw new IOException("Inventory limit is 256 item instances");
            long first=0;
            if(stack!=null) {
                first=stack.id();
                try(var s=c.prepareStatement("UPDATE inventory_items SET count=count+? WHERE item_id=? AND world_id=?")) {
                    s.setInt(1,quantity);s.setLong(2,first);s.setInt(3,profile.worldId());s.executeUpdate();
                }
            }else try(var s=c.prepareStatement("INSERT INTO inventory_items(item_id,world_id,slot,config,bind_on_equip,count,permanent,bound,level,heat_progress,variants) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
                for(int i=0;i<instances;i++) {
                    long id=WorldProfiles.allocate(c,"next-item-id",Long.MAX_VALUE);if(i==0)first=id;
                    s.setLong(1,id);s.setInt(2,profile.worldId());s.setString(3,entry.slot().name());s.setString(4,entry.config());s.setBoolean(5,entry.bindOnEquip());
                    s.setInt(6,entry.stackable()?quantity:1);s.setBoolean(7,entry.permanent());s.setBoolean(8,locked||entry.locked()||entry.permanent());
                    s.setInt(9,modifiers.level());s.setFloat(10,modifiers.heat());s.setString(11,org.tortodrone.resources.Json.stringify(modifiers.variants()));s.executeUpdate();
                    InventoryExpiry.save(c,id,InventoryExpiry.deadline(expireDays));
                }
            }
            try(var s=c.prepareStatement("UPDATE world_characters SET state_revision=state_revision+1 WHERE world_id=?")){s.setInt(1,profile.worldId());s.executeUpdate();}
            try(var s=c.prepareStatement("INSERT INTO item_events(world_id,actor_account,config,quantity,first_item_id,created_at) VALUES(?,?,?,?,?,?)")) {
                s.setInt(1,profile.worldId());if(actorAccount<0)s.setNull(2,Types.INTEGER);else s.setInt(2,actorAccount);
                s.setString(3,entry.config());s.setInt(4,quantity);s.setLong(5,first);s.setLong(6,System.currentTimeMillis());s.executeUpdate();
            }
            return WorldProfiles.load(c,account,knight,-1);
        });
    }
    /** Local administration only; a stale inspector cannot overwrite newly forged or traded gear. */
    public WorldProfile itemModifiers(String username,String knightName,int expectedAccount,int expectedWorld,long expectedRevision,
            long itemId,org.tortodrone.character.ItemModifiers modifiers,org.tortodrone.character.ItemCatalog catalog,org.tortodrone.town.TownCatalog rules)throws IOException{
        return write(c->{
            var profile=administrativeProfile(c,username,knightName,expectedAccount,expectedWorld);if(profile.revision()!=expectedRevision)throw new IOException("Inventory changed; refresh before editing");
            var item=profile.items().stream().filter(i->i.id()==itemId).findFirst().orElseThrow(()->new IOException("Item is not owned by this knight"));
            modifiers.validate(catalog.require(item.config()),rules);
            try(var s=c.prepareStatement("UPDATE inventory_items SET level=?,heat_progress=?,variants=? WHERE world_id=? AND item_id=?")){s.setInt(1,modifiers.level());s.setFloat(2,modifiers.heat());s.setString(3,org.tortodrone.resources.Json.stringify(modifiers.variants()));s.setInt(4,profile.worldId());s.setLong(5,itemId);if(s.executeUpdate()!=1)throw new IOException("Item changed");}
            TownStore.touch(c,profile.worldId());event(c,profile.accountId(),"item modifiers; world="+profile.worldId()+"; item="+itemId);
            return WorldProfiles.load(c,profile.accountId(),profile.knightId(),-1);
        });
    }
    public List<WorldProfile> trade(WorldProfile first,long firstAuth,Map<Long,Integer> firstItems,int firstCrowns,int firstEnergy,
            WorldProfile second,long secondAuth,Map<Long,Integer> secondItems,int secondCrowns,int secondEnergy)throws IOException {
        return trade(first,firstAuth,firstItems,firstCrowns,firstEnergy,second,secondAuth,secondItems,secondCrowns,secondEnergy,org.tortodrone.character.ItemCatalog.starter());
    }
    public List<WorldProfile> trade(WorldProfile first,long firstAuth,Map<Long,Integer> firstItems,int firstCrowns,int firstEnergy,
            WorldProfile second,long secondAuth,Map<Long,Integer> secondItems,int secondCrowns,int secondEnergy,org.tortodrone.character.ItemCatalog catalog)throws IOException {
        var a=Map.copyOf(firstItems);var b=Map.copyOf(secondItems);
        return write(c->TradeTransfer.commit(c,first,firstAuth,a,firstCrowns,firstEnergy,second,secondAuth,b,secondCrowns,secondEnergy,catalog));
    }

    public void saveRoster(int id,List<Knight> knights,int next,long revision,long rosterRevision) throws IOException {
        write(c -> {
            try(var s=c.prepareStatement("SELECT revision FROM accounts WHERE id=?")) {
                s.setInt(1,id);try(var r=s.executeQuery()) {
                    if(!r.next()||r.getLong(1)!=rosterRevision)throw new IOException("Roster changed; refresh or reconnect before saving characters");
                }
            }
            replaceRoster(c,id,knights,next,revision);
            for (Knight knight : knights) { boolean fresh;
                try(PreparedStatement statement=c.prepareStatement("SELECT world_id FROM world_characters WHERE account_id=? AND knight_id=?")) {
                    statement.setInt(1,id);statement.setInt(2,knight.id());try(ResultSet r=statement.executeQuery()){fresh=!r.next();}
                }
                WorldProfiles.ensure(c,id,knight);
                if(fresh)try(PreparedStatement statement=c.prepareStatement("UPDATE inventory_items SET bound=1,starter=1,equipped_slot=CASE slot WHEN 'HELM' THEN 0 WHEN 'ARMOR' THEN 1 WHEN 'SHIELD' THEN 2 WHEN 'SWORD' THEN 6 WHEN 'GUN' THEN 7 END WHERE world_id=(SELECT world_id FROM world_characters WHERE account_id=? AND knight_id=?)")) {statement.setInt(1,id);statement.setInt(2,knight.id());statement.executeUpdate();}
                WorldProfiles.appearance(c,id,knight); }
            return null;
        });
    }
    private static void replaceRoster(Connection c,int id,List<Knight> knights,int next,long revision) throws IOException, SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE accounts SET next_knight_id=?,revision=revision+1 WHERE id=?" + (revision >= 0 ? " AND auth_revision=?" : ""))) {
            s.setInt(1,next); s.setInt(2,id); if (revision >= 0) s.setLong(3,revision);
            if (s.executeUpdate() != 1) throw new IOException("Account changed; reconnect before saving characters");
        }
        // Delete removed rows first so a rename can reuse a deleted name without a false unique conflict.
        String placeholders = String.join(",", Collections.nCopies(knights.size(),"?"));
        try (PreparedStatement s = c.prepareStatement("DELETE FROM knights WHERE account_id=?" + (knights.isEmpty() ? "" : " AND id NOT IN(" + placeholders + ")"))) {
            s.setInt(1,id); for (int i=0;i<knights.size();i++) s.setInt(i+2,knights.get(i).id()); s.executeUpdate();
        }
        for (Knight k : knights) try (PreparedStatement s = c.prepareStatement("INSERT INTO knights(account_id,id,name,name_key,helm,armor,helm_accessory,armor_accessory,costume,color,height,eye,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(account_id,id) DO UPDATE SET name=excluded.name,name_key=excluded.name_key,helm=excluded.helm,armor=excluded.armor,helm_accessory=excluded.helm_accessory,armor_accessory=excluded.armor_accessory,costume=excluded.costume,color=excluded.color,height=excluded.height,eye=excluded.eye,created_at=excluded.created_at,last_renamed=knights.last_renamed")) {
            s.setInt(1,id); s.setInt(2,k.id()); s.setString(3,k.name()); s.setString(4,k.name().toLowerCase(Locale.ROOT)); s.setString(5,k.helm()); s.setString(6,k.armor()); s.setString(7,k.helmAccessory()); s.setString(8,k.armorAccessory()); s.setString(9,k.costume()); s.setInt(10,k.color()); s.setInt(11,k.height()); s.setInt(12,k.eye()); s.setLong(13,k.createdAt()); s.executeUpdate();
        }
        WorldProfiles.cleanProgress(c);
    }
    private static IOException failure(SQLException e) {
        if(e.getMessage()!=null&&e.getMessage().contains("UNIQUE constraint failed: knights.name_key"))return new KnightNameInUse(e);
        if (e.getMessage() != null && e.getMessage().contains("accounts.name_key")) return new IOException("Account already exists",e);
        return new IOException("Database operation failed; existing data was not replaced",e);
    }
}
