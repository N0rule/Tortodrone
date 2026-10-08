package org.tortodrone.account;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.character.*;
import org.tortodrone.storage.Database;
import static org.tortodrone.support.TestWire.check;

public final class AccountStoreTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("tortodrone-accounts-");
        try {
            CharacterStore legacy = new CharacterStore(directory);
            Knight knight = legacy.create(choices("Legacy-Knight"));
            Path profile = directory.resolve("accounts/1.properties"); byte[] oldProfile = Files.readAllBytes(profile);
            legacyRegistry(directory);
            Path registry = directory.resolve("accounts/users.properties"); byte[] oldRegistry = Files.readAllBytes(registry);
            try (AccountStore accounts = new AccountStore(directory)) {
                var first = login(accounts,"TORTODRONE","tortodrone");
                check(first.id() == 1 && !first.gameMaster() && first.characters().roster().equals(List.of(knight)),"migration preserves identity, verifier and appearance; Game Master defaults false");
                check(Arrays.equals(oldProfile,Files.readAllBytes(profile)) && Arrays.equals(oldRegistry,Files.readAllBytes(registry)),"legacy files remain untouched");
                accounts.create("Alice","alice-password".toCharArray(),true); accounts.create("Bob","bob-password".toCharArray());
                var alice = login(accounts,"alice","alice-password"); var bob = login(accounts,"Bob","bob-password");
                check(alice.id() == 2 && bob.id() == 3 && alice.gameMaster() && !bob.gameMaster(),"separate stable identities and Game Master flag");
                alice.characters().create(choices("Alice-Knight")); check(bob.characters().roster().isEmpty(),"private character rosters");
                refused(() -> accounts.create("ALICE","new-password".toCharArray()));
                refused(() -> accounts.create("../bad","new-password".toCharArray()));
                refused(() -> accounts.create("Short","short".toCharArray()));
                check(accounts.checkPassword("Alice","alice-password".toCharArray()) && !accounts.checkPassword("Alice","wrong-password".toCharArray()),"password verification");
                accounts.setPassword("ALICE","changed-password".toCharArray());
                refused(() -> alice.characters().create(choices("Stale-Revision"),alice.authRevision()));
                check(!accounts.current(alice) && login(accounts,"Alice","alice-password") == null,"reset invalidates old credentials and session revisions");
                check(login(accounts,"Alice","changed-password").characters().roster().size() == 1,"reset preserves characters");
                accounts.setGameMaster("Alice",false); check(!accounts.isGameMaster(alice.id()),"Game Master changes read current database state");
                Path file = accounts.databaseFile(), backup = directory.resolve("saved.db");
                Files.move(file,backup); Files.createDirectory(file);
                refused(() -> accounts.create("Retry","retry-password".toCharArray()));
                refused(() -> accounts.setPassword("Bob","changed-password".toCharArray()));
                Files.delete(file); Files.move(backup,file);
                check(login(accounts,"Bob","bob-password") != null,"failed write preserves old password");
                accounts.create("Retry","retry-password".toCharArray()); check(login(accounts,"Retry","retry-password").id() == 4,"failed write does not consume identity");
                accounts.delete("Alice",alice.id()); check(login(accounts,"Alice","changed-password") == null,"deleted account cannot authenticate");
                check(new Database(directory,false).roster(alice.id()).isEmpty(),"account deletion removes owned characters atomically");
                try { alice.characters().create(choices("Stale-Knight")); throw new AssertionError("deleted account resurrected"); } catch (IOException expected) { }
                accounts.create("Alice","replacement-password".toCharArray()); check(login(accounts,"Alice","replacement-password").id() == 5,"deleted identities never recycled");
                refused(() -> accounts.delete("Alice",alice.id())); refused(() -> accounts.setPassword("Alice","new-password".toCharArray(),alice.id()));
                check(login(accounts,"Alice","replacement-password") != null,"stale GUI selection cannot modify replacement account");
                if (Files.getFileStore(file).supportsFileAttributeView("posix")) check(Files.getPosixFilePermissions(file).equals(PosixFilePermissions.fromString("rw-------")),"database is owner-only");
            }
            try (AccountStore restored = new AccountStore(directory)) {
                check(login(restored,"Alice","replacement-password").id() == 5 && !login(restored,"Alice","replacement-password").gameMaster(),"database restart preserves account and role");
            }
            Path fresh = directory.resolve("fresh");
            try (AccountStore accounts = new AccountStore(fresh)) { check(accounts.list().isEmpty(),"fresh database has no automatic development login"); accounts.create("First","first-password".toCharArray()); accounts.delete("First"); }
            try (AccountStore accounts = new AccountStore(fresh)) { check(accounts.list().isEmpty(),"deleting last account does not recreate a default login"); }
            Path corrupt = directory.resolve("corrupt"); Files.createDirectories(corrupt); Files.writeString(corrupt.resolve("tortodrone.db"),"not a database");
            byte[] malformed = Files.readAllBytes(corrupt.resolve("tortodrone.db")); refused(() -> new AccountStore(corrupt));
            check(Arrays.equals(malformed,Files.readAllBytes(corrupt.resolve("tortodrone.db"))),"corrupt database never replaced");
            Path failed = directory.resolve("bad-migration"); legacyRegistry(failed); Files.writeString(failed.resolve("accounts/1.properties"),"broken profile");
            refused(() -> new AccountStore(failed)); check(Files.readString(failed.resolve("accounts/1.properties")).equals("broken profile"),"failed migration preserves source");
            Files.delete(failed.resolve("accounts/1.properties"));
            try (AccountStore repaired = new AccountStore(failed)) { check(repaired.list().size() == 1,"failed migration rolled back entire schema and can retry"); }
            Path profileOnly = directory.resolve("version-02");
            Knight oldKnight = new CharacterStore(profileOnly).create(choices("Old-Knight"));
            byte[] source = Files.readAllBytes(profileOnly.resolve("accounts/1.properties"));
            try (AccountStore accounts = new AccountStore(profileOnly)) {
                var account = login(accounts,"tortodrone","tortodrone");
                check(account.id() == 1 && account.characters().roster().equals(List.of(oldKnight)),"version 0.2 profile migration retains characters and fixed login");
                check(Arrays.equals(source,Files.readAllBytes(profileOnly.resolve("accounts/1.properties"))),"profile-only migration leaves source untouched");
            }
            Path orphan = directory.resolve("orphan"); legacyRegistry(orphan); new CharacterStore(orphan,2,"Orphan").create(choices("Lost-Knight"));
            refused(() -> new AccountStore(orphan));
            Path unsupported = directory.resolve("unsupported");
            Database future = new Database(unsupported,false); future.write(c -> { try (var statement = c.createStatement()) { statement.execute("PRAGMA user_version=99"); } return null; });
            refused(() -> new AccountStore(unsupported));
            concurrent(directory.resolve("concurrent"));
            System.out.println("PASS: SQLite migration, gameMaster roles, password reset/check, deletion, stable IDs, stale selection, atomic failures and concurrency");
        } finally { try (var paths = Files.walk(directory)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); } }
    }
    public static void legacyRegistry(Path directory) throws IOException {
        Files.createDirectories(directory.resolve("accounts")); var password = Passwords.protect("tortodrone".toCharArray()); Properties p = new Properties();
        p.setProperty("schema","1"); p.setProperty("algorithm","PBKDF2WithHmacSHA256"); p.setProperty("iterations",String.valueOf(Passwords.ITERATIONS)); p.setProperty("accounts","1"); p.setProperty("next-id","2");
        p.setProperty("account.0.id","1"); p.setProperty("account.0.username","tortodrone"); p.setProperty("account.0.salt",HexFormat.of().formatHex(password.salt())); p.setProperty("account.0.verifier",HexFormat.of().formatHex(password.verifier()));
        try (Writer writer = Files.newBufferedWriter(directory.resolve("accounts/users.properties"))) { p.store(writer,"Synthetic legacy migration fixture"); }
    }
    private static void concurrent(Path directory) throws Exception {
        try (AccountStore one = new AccountStore(directory); AccountStore two = new AccountStore(directory)) {
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                Callable<Boolean> first = () -> { try { one.create("SameUser","first-password".toCharArray()); return true; } catch (IOException expected) { return false; } };
                Callable<Boolean> second = () -> { try { two.create("sameuser","other-password".toCharArray()); return true; } catch (IOException expected) { return false; } };
                var results = workers.invokeAll(List.of(first,second)); check(results.get(0).get() != results.get(1).get() && one.list().size() == 1,"two processes share case-insensitive uniqueness transaction");
            } finally { workers.shutdownNow(); }
        }
    }
    static AccountStore.Account login(AccountStore accounts,String username,String password) throws IOException {
        char[] hash = AccountStore.transform(password.toCharArray()); try { return accounts.authenticate(username,hash); } finally { Arrays.fill(hash,'\0'); }
    }
    static Knight choices(String name) { return new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0); }
    static void refused(Operation operation) throws Exception { try { operation.run(); throw new AssertionError("Invalid operation accepted"); } catch (IOException expected) { } }
    @FunctionalInterface interface Operation { void run() throws Exception; }
}
