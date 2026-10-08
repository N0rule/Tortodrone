package org.tortodrone.storage;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.character.Knight;
import org.tortodrone.character.WorldProfile;

/** Local SQLite database. Transactions are short; password derivation runs outside them. */
public final class Database {
    public static final int SCHEMA_VERSION = 7;
    public static final class KnightNameInUse extends IOException {
        private KnightNameInUse(SQLException cause) { super("That knight name is already in use",cause); }
    }
    public record Identity(int id, String username, boolean gameMaster, long authRevision, byte[] salt, byte[] verifier) {
        @Override public String toString() { return "Identity[redacted]"; }
    }
    public record Summary(int id, String username, boolean gameMaster, int knights, long createdAt) { }
    public record RosterState(List<Knight> knights,int next,long revision) { }
    private final Path file;
    @FunctionalInterface public interface Work<T> { T run(Connection connection) throws SQLException, IOException; }

    public Database(Path directory, boolean deploymentLocked) throws IOException {
        file = directory.toAbsolutePath().normalize().resolve("tortodrone.db");
        try { Class.forName("org.sqlite.JDBC"); }
        catch (ClassNotFoundException e) { throw new IOException("Missing bundled SQLite driver", e); }
        Files.createDirectories(file.getParent());
        if (Files.exists(file)) { int version = version(); if (version == SCHEMA_VERSION) return; if (version < 0 || version > SCHEMA_VERSION) throw new IOException("Unsupported database version; database was not replaced"); }
        if (deploymentLocked) initialize(directory);
        else {
            try (var channel = FileChannel.open(directory.resolve("server.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var lock = channel.tryLock()) {
                if (lock == null) throw new IOException("Stop the server once before initializing or migrating the database");
                if (!Files.exists(file) || version() != SCHEMA_VERSION) initialize(directory);
            } catch (OverlappingFileLockException e) { throw new IOException("Database initialization is already in progress", e); }
        }
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
        try (Connection connection = connect()) { return work.run(connection); }
        catch (SQLException e) { throw failure(e); }
    }
    public <T> T write(Work<T> work) throws IOException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            try {
                T value = work.run(connection); statement.execute("COMMIT"); return value;
            } catch (SQLException | IOException | RuntimeException e) {
                try { statement.execute("ROLLBACK"); } catch (SQLException suppressed) { e.addSuppressed(suppressed); }
                throw e;
            }
        } catch (SQLException e) { throw failure(e); }
    }
    private int version() throws IOException {
        return read(connection -> { try (Statement s = connection.createStatement(); ResultSet r = s.executeQuery("PRAGMA user_version")) { r.next(); return r.getInt(1); } });
    }
    private void initialize(Path directory) throws IOException {
        int existing = Files.exists(file) ? version() : 0;
        if (existing == SCHEMA_VERSION) return;
        if (existing >= 1 && existing < SCHEMA_VERSION) {
            write(c -> {
                int current;
                try (Statement statement = c.createStatement(); ResultSet r = statement.executeQuery("PRAGMA user_version")) {
                    r.next(); current=r.getInt(1);
                }
                if (current==SCHEMA_VERSION) return null;
                if (current<1 || current>SCHEMA_VERSION) throw new IOException("Unsupported database version; database was not replaced");
                if (current==1) WorldProfiles.initialize(c);
                if (current<=2) WorldProfiles.upgrade(c);
                if(current<=3) WorldProfiles.upgradeBinding(c);
                if(current<=4) WorldProfiles.upgradeInventory(c);
                if(current<=5)upgradeKnightNames(c);
                WorldProfiles.upgradePlayerItems(c);
                try (Statement statement = c.createStatement()) { statement.execute("PRAGMA user_version="+SCHEMA_VERSION); }
                return null;
            });
            return;
        }
        if (existing != 0) throw new IOException("Unsupported database version; database was not replaced");
        if (!Files.exists(file)) Files.createFile(file);
        read(connection -> { try (Statement s = connection.createStatement(); ResultSet r = s.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
            r.next(); if (r.getInt(1) != 0) throw new IOException("Unsupported database schema; database was not replaced");
        } return null; });
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        read(connection -> { try (Statement s = connection.createStatement()) { s.execute("PRAGMA journal_mode=WAL"); } return null; });
        write(connection -> {
            try (Statement s = connection.createStatement(); ResultSet r = s.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
                r.next(); if (r.getInt(1) != 0) throw new IOException("Unsupported database schema; database was not replaced");
            }
            try (Statement s = connection.createStatement()) {
                s.execute("CREATE TABLE metadata(key TEXT PRIMARY KEY, value INTEGER NOT NULL)");
                s.execute("INSERT INTO metadata VALUES('next-account-id',1)");
                s.execute("CREATE TABLE accounts(id INTEGER PRIMARY KEY CHECK(id>0), username TEXT NOT NULL, name_key TEXT NOT NULL UNIQUE, salt BLOB NOT NULL CHECK(length(salt)=16), verifier BLOB NOT NULL CHECK(length(verifier)=32), is_admin INTEGER NOT NULL DEFAULT 0 CHECK(is_admin IN(0,1)), auth_revision INTEGER NOT NULL DEFAULT 1, revision INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL, next_knight_id INTEGER NOT NULL DEFAULT 1 CHECK(next_knight_id>0))");
                s.execute("CREATE TABLE knights(account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE, id INTEGER NOT NULL CHECK(id>0), name TEXT NOT NULL, name_key TEXT NOT NULL, helm TEXT NOT NULL, armor TEXT NOT NULL, helm_accessory TEXT, armor_accessory TEXT, costume TEXT NOT NULL, color INTEGER NOT NULL CHECK(color BETWEEN 0 AND 255), height INTEGER NOT NULL CHECK(height BETWEEN 0 AND 4), eye INTEGER NOT NULL CHECK(eye BETWEEN 0 AND 46), created_at INTEGER NOT NULL, PRIMARY KEY(account_id,id), UNIQUE(account_id,name_key))");
                s.execute("CREATE TABLE account_events(id INTEGER PRIMARY KEY, account_id INTEGER NOT NULL, action TEXT NOT NULL, created_at INTEGER NOT NULL)");
            }
            migrate(connection, directory);
            WorldProfiles.initialize(connection);
            WorldProfiles.upgrade(connection);
            WorldProfiles.upgradeBinding(connection);
            WorldProfiles.upgradeInventory(connection);
            upgradeKnightNames(connection);
            WorldProfiles.upgradePlayerItems(connection);
            try (Statement s = connection.createStatement()) { s.execute("PRAGMA user_version="+SCHEMA_VERSION); }
            return null;
        });
    }
    private static void upgradeKnightNames(Connection c)throws SQLException,IOException {
        try(var s=c.createStatement();var r=s.executeQuery("SELECT name_key FROM knights GROUP BY name_key HAVING count(*)>1 ORDER BY name_key LIMIT 1")) {
            if(r.next())throw new IOException("Duplicate knight name prevents schema 6 upgrade: "+r.getString(1)+". Resolve duplicate names using the previous release, then retry; saved characters were not changed");
        }
        try(var s=c.createStatement()){s.execute("CREATE UNIQUE INDEX global_knight_names ON knights(name_key)");}
    }
    /** Availability is advisory; the unique index arbitrates competing writes. */
    public boolean knightNameInUse(String name,int accountId,int knightId)throws IOException {
        return read(c->{try(var s=c.prepareStatement("SELECT 1 FROM knights WHERE name_key=? AND NOT(account_id=? AND id=?) LIMIT 1")) {
            s.setString(1,name.toLowerCase(Locale.ROOT));s.setInt(2,accountId);s.setInt(3,knightId);
            try(var r=s.executeQuery()){return r.next();}
        }});
    }
    private void migrate(Connection connection, Path directory) throws IOException, SQLException {
        Path registry = directory.resolve("accounts/users.properties");
        if (!Files.exists(registry)) {
            Path profiles = directory.resolve("accounts");
            if (Files.isDirectory(profiles)) try (var paths = Files.list(profiles)) {
                var saved = paths.filter(p -> p.getFileName().toString().matches("[0-9]+\\.properties")).toList();
                if (!saved.isEmpty()) {
                    if (saved.size() != 1 || !saved.get(0).getFileName().toString().equals("1.properties")) {
                        throw new IOException("Legacy profiles need their account registry before migration");
                    }
                    // Version 0.2 had only the fixed normal account and no separate registry.
                    String username = org.tortodrone.account.AccountStore.DEFAULT_USERNAME;
                    var password = Passwords.protect(username.toCharArray());
                    CharacterStore legacy = new CharacterStore(directory,1,username);
                    insertAccount(connection,1,username,password.salt(),password.verifier(),false,legacy.nextId());
                    replaceRoster(connection,1,legacy.roster(),legacy.nextId(),-1);
                    try (Statement s = connection.createStatement()) { s.executeUpdate("UPDATE metadata SET value=2 WHERE key='next-account-id'"); }
                }
            }
            return; // A fresh installation has no automatic development account.
        }
        if (Files.size(registry) > 1024 * 1024) throw new IOException("Legacy account registry exceeds size limit");
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(registry)) { properties.load(reader); }
        try {
            if (!"1".equals(properties.getProperty("schema")) || !"PBKDF2WithHmacSHA256".equals(properties.getProperty("algorithm"))
                    || Integer.parseInt(properties.getProperty("iterations")) != Passwords.ITERATIONS) throw new IllegalArgumentException();
            int count = Integer.parseInt(properties.getProperty("accounts")), next = Integer.parseInt(properties.getProperty("next-id"));
            if (count < 1 || count > 1000 || next < 2) throw new IllegalArgumentException();
            Set<Integer> ids = new HashSet<>(); Set<String> names = new HashSet<>();
            for (int index = 0; index < count; index++) {
                String prefix = "account." + index + ".";
                int id = Integer.parseInt(properties.getProperty(prefix + "id")); String username = properties.getProperty(prefix + "username");
                org.tortodrone.account.AccountStore.validateUsername(username);
                byte[] salt = HexFormat.of().parseHex(properties.getProperty(prefix + "salt")), verifier = HexFormat.of().parseHex(properties.getProperty(prefix + "verifier"));
                if (id < 1 || id >= next || !ids.add(id) || !names.add(username.toLowerCase(Locale.ROOT)) || salt.length != 16 || verifier.length != 32) throw new IllegalArgumentException();
                CharacterStore legacy = new CharacterStore(directory, id, username);
                insertAccount(connection, id, username, salt, verifier, false, legacy.nextId());
                replaceRoster(connection, id, legacy.roster(), legacy.nextId(), -1);
            }
            Path profiles = directory.resolve("accounts");
            if (Files.isDirectory(profiles)) try (var paths = Files.list(profiles)) {
                for (Path profile : paths.filter(p -> p.getFileName().toString().matches("[0-9]+\\.properties")).toList()) {
                    int profileId = Integer.parseInt(profile.getFileName().toString().replace(".properties", ""));
                    if (!ids.contains(profileId)) throw new IOException("Legacy profile has no account owner; migration rolled back");
                }
            }
            try (PreparedStatement s = connection.prepareStatement("UPDATE metadata SET value=? WHERE key='next-account-id'")) { s.setInt(1,next); s.executeUpdate(); }
        } catch (IllegalArgumentException | NullPointerException e) { throw new IOException("Invalid legacy accounts; migration rolled back and source files preserved", e); }
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
    private static Identity identity(ResultSet r) throws SQLException {
        return new Identity(r.getInt("id"), r.getString("username"), r.getBoolean("is_admin"), r.getLong("auth_revision"), r.getBytes("salt"), r.getBytes("verifier"));
    }
    public List<Summary> list() throws IOException {
        return read(c -> { List<Summary> result = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT a.id,a.username,a.is_admin,a.created_at,count(k.id) AS knights FROM accounts a LEFT JOIN knights k ON k.account_id=a.id GROUP BY a.id ORDER BY a.id")) {
                while (r.next()) result.add(new Summary(r.getInt("id"),r.getString("username"),r.getBoolean("is_admin"),r.getInt("knights"),r.getLong("created_at")));
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
        try (PreparedStatement s = c.prepareStatement("INSERT INTO accounts(id,username,name_key,salt,verifier,is_admin,created_at,next_knight_id) VALUES(?,?,?,?,?,?,?,?)")) {
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
            try (PreparedStatement s = c.prepareStatement("UPDATE accounts SET is_admin=?,revision=revision+1 WHERE id=?")) { s.setBoolean(1,gameMaster); s.setInt(2,id); s.executeUpdate(); }
            event(c,id,gameMaster ? "game master granted" : "game master removed"); return null;
        });
    }
    public void delete(String username,int expectedId) throws IOException {
        write(c -> { int id = requireId(c,username);
            if (expectedId >= 0 && id != expectedId) throw new IOException("Account changed; refresh the account list");
            try (PreparedStatement s = c.prepareStatement("DELETE FROM accounts WHERE id=?")) { s.setInt(1,id); s.executeUpdate(); }
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
            try(var s=c.prepareStatement("UPDATE accounts SET auth_revision=auth_revision+1,revision=revision+1 WHERE id=?")){s.setInt(1,account);s.executeUpdate();}
            event(c,account,"knight deleted; world="+world);return null;
        });
    }
    static Knight knight(Connection c,int accountId,int knightId) throws SQLException, IOException {
        try (PreparedStatement s = c.prepareStatement("SELECT * FROM knights WHERE account_id=? AND id=?")) {
            s.setInt(1,accountId); s.setInt(2,knightId);
            try (ResultSet r = s.executeQuery()) { if (!r.next()) throw new IOException("Missing character during migration"); return knight(r); }
        }
    }
    private static Knight knight(ResultSet r) throws SQLException {
        return new Knight(r.getInt("id"),r.getString("name"),r.getString("helm"),r.getString("armor"),r.getString("helm_accessory"),r.getString("armor_accessory"),r.getString("costume"),r.getInt("color"),r.getInt("height"),r.getInt("eye"),r.getLong("created_at"));
    }
    public WorldProfile worldProfile(int accountId,int knightId,long authRevision) throws IOException {
        return read(c -> {
            c.setAutoCommit(false); // One read snapshot for identity and inventory, even during deletion.
            var profile = WorldProfiles.load(c,accountId,knightId,authRevision); c.commit(); return profile;
        });
    }
    public WorldProfile currency(String username,String knightName,int expectedAccount,int expectedWorld,
            org.tortodrone.character.Currency currency,long delta) throws IOException {
        if (delta==0 || delta<-Integer.MAX_VALUE || delta>Integer.MAX_VALUE) throw new IOException("Amount must be between 1 and 2147483647");
        return write(c -> {
            int accountId=requireId(c,username);
            if (expectedAccount>=0 && accountId!=expectedAccount) throw new IOException("Account changed; refresh the account list");
            int knightId;
            try (PreparedStatement s=c.prepareStatement("SELECT id FROM knights WHERE account_id=? AND name_key=?")) {
                s.setInt(1,accountId);s.setString(2,knightName.toLowerCase(Locale.ROOT));
                try(ResultSet r=s.executeQuery()){if(!r.next())throw new IOException("No such knight");knightId=r.getInt(1);}
            }
            var profile=WorldProfiles.load(c,accountId,knightId,-1);
            if(expectedWorld>=0 && profile.worldId()!=expectedWorld)throw new IOException("Knight changed; refresh the character list");
            long balance=(currency==org.tortodrone.character.Currency.CROWNS?profile.crowns():profile.energy())+delta;
            if(balance<0)throw new IOException("Insufficient "+currency.name().toLowerCase(Locale.ROOT));
            if(balance>Integer.MAX_VALUE)throw new IOException("Balance exceeds the supported limit");
            String column=currency==org.tortodrone.character.Currency.CROWNS?"crowns":"energy";
            try(PreparedStatement s=c.prepareStatement("UPDATE world_characters SET "+column+"=?,state_revision=state_revision+1 WHERE world_id=?")) {
                s.setLong(1,balance);s.setInt(2,profile.worldId());s.executeUpdate();
            }
            try(PreparedStatement s=c.prepareStatement("INSERT INTO currency_events(world_id,currency,delta,balance,created_at) VALUES(?,?,?,?,?)")) {
                s.setInt(1,profile.worldId());s.setString(2,column);s.setLong(3,delta);s.setLong(4,balance);s.setLong(5,System.currentTimeMillis());s.executeUpdate();
            }
            return WorldProfiles.load(c,accountId,knightId,-1);
        });
    }
    public WorldProfile equip(int accountId,int knightId,long revision,long itemId,Integer slot) throws IOException {
        return write(c -> {
            var profile=WorldProfiles.load(c,accountId,knightId,revision);
            var item=profile.items().stream().filter(i->i.id()==itemId).findFirst().orElseThrow(()->new IOException("No such owned item"));
            if(slot!=null && !item.slot().accepts(slot))throw new IOException("Item cannot be equipped in that slot");
            if(slot!=null)try(PreparedStatement s=c.prepareStatement("UPDATE inventory_items SET equipped_slot=NULL WHERE world_id=? AND equipped_slot=?")){s.setInt(1,profile.worldId());s.setInt(2,slot);s.executeUpdate();}
            try(PreparedStatement s=c.prepareStatement("UPDATE inventory_items SET equipped_slot=?,bound=CASE WHEN ? IS NOT NULL AND bind_on_equip=1 THEN 1 ELSE bound END WHERE world_id=? AND item_id=?")){
                if(slot==null)s.setNull(1,Types.INTEGER);else s.setInt(1,slot);
                if(slot==null)s.setNull(2,Types.INTEGER);else s.setInt(2,slot);
                s.setInt(3,profile.worldId());s.setLong(4,itemId);s.executeUpdate();
            }
            try(PreparedStatement s=c.prepareStatement("UPDATE world_characters SET state_revision=state_revision+1 WHERE world_id=?")){s.setInt(1,profile.worldId());s.executeUpdate();}
            return WorldProfiles.load(c,accountId,knightId,revision);
        });
    }
    /** Permission and target identity are checked under the same write lock as allocation. */
    public WorldProfile grantItems(String username,String knightName,int expectedAccount,int expectedWorld,
            org.tortodrone.character.ItemCatalog.Entry entry,int quantity,int actorAccount,long actorRevision)throws IOException {
        if(quantity<1||quantity>org.tortodrone.character.ItemCatalog.GRANT_LIMIT)throw new IOException("Quantity must be between 1 and 20");
        if(!entry.grantable())throw new IOException("Unsupported item for grants");
        return write(c->{
            if(actorAccount>=0)try(var s=c.prepareStatement("SELECT is_admin FROM accounts WHERE id=? AND auth_revision=?")) {
                s.setInt(1,actorAccount);s.setLong(2,actorRevision);
                try(var r=s.executeQuery()){if(!r.next()||!r.getBoolean(1))throw new IOException("Game Master permission required");}
            }
            int account=requireId(c,username),knight;
            if(expectedAccount>=0&&account!=expectedAccount)throw new IOException("Account changed; refresh");
            try(var s=c.prepareStatement("SELECT id FROM knights WHERE account_id=? AND name_key=?")) {
                s.setInt(1,account);s.setString(2,knightName.toLowerCase(Locale.ROOT));
                try(var r=s.executeQuery()){if(!r.next())throw new IOException("No such knight");knight=r.getInt(1);}
            }
            var profile=WorldProfiles.load(c,account,knight,-1);
            if(expectedWorld>=0&&profile.worldId()!=expectedWorld)throw new IOException("Knight changed; refresh");
            // Stack only plain inventory items with identical grant flags; equipment stays distinct.
            var stack=entry.stackable()?profile.items().stream().filter(i->i.config().equals(entry.config())
                &&i.slot()==org.tortodrone.character.WorldProfile.Slot.OTHER&&!i.bound()&&!i.permanent()
                &&i.equippedSlot()==null&&i.count()+quantity<=9999).findFirst().orElse(null):null;
            int instances=stack!=null?0:entry.stackable()?1:quantity;
            if(profile.items().size()+instances>org.tortodrone.character.ItemCatalog.INVENTORY_LIMIT)throw new IOException("Inventory limit is 256 item instances");
            long first=0;
            if(stack!=null) {
                first=stack.id();
                try(var s=c.prepareStatement("UPDATE inventory_items SET count=count+? WHERE item_id=? AND world_id=?")) {
                    s.setInt(1,quantity);s.setLong(2,first);s.setInt(3,profile.worldId());s.executeUpdate();
                }
            }else try(var s=c.prepareStatement("INSERT INTO inventory_items(item_id,world_id,slot,config,bind_on_equip,count,permanent,bound) VALUES(?,?,?,?,?,?,?,?)")) {
                for(int i=0;i<instances;i++) {
                    long id=WorldProfiles.allocate(c,"next-item-id",Long.MAX_VALUE);if(i==0)first=id;
                    s.setLong(1,id);s.setInt(2,profile.worldId());s.setString(3,entry.slot().name());s.setString(4,entry.config());s.setBoolean(5,entry.bindOnEquip());
                    s.setInt(6,entry.stackable()?quantity:1);s.setBoolean(7,entry.permanent());s.setBoolean(8,entry.permanent());s.executeUpdate();
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
        for (Knight k : knights) try (PreparedStatement s = c.prepareStatement("INSERT INTO knights VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(account_id,id) DO UPDATE SET name=excluded.name,name_key=excluded.name_key,helm=excluded.helm,armor=excluded.armor,helm_accessory=excluded.helm_accessory,armor_accessory=excluded.armor_accessory,costume=excluded.costume,color=excluded.color,height=excluded.height,eye=excluded.eye,created_at=excluded.created_at")) {
            s.setInt(1,id); s.setInt(2,k.id()); s.setString(3,k.name()); s.setString(4,k.name().toLowerCase(Locale.ROOT)); s.setString(5,k.helm()); s.setString(6,k.armor()); s.setString(7,k.helmAccessory()); s.setString(8,k.armorAccessory()); s.setString(9,k.costume()); s.setInt(10,k.color()); s.setInt(11,k.height()); s.setInt(12,k.eye()); s.setLong(13,k.createdAt()); s.executeUpdate();
        }
    }
    private static IOException failure(SQLException e) {
        if(e.getMessage()!=null&&e.getMessage().contains("UNIQUE constraint failed: knights.name_key"))return new KnightNameInUse(e);
        if (e.getMessage() != null && e.getMessage().contains("accounts.name_key")) return new IOException("Account already exists",e);
        return new IOException("Database operation failed; existing data was not replaced",e);
    }
}
