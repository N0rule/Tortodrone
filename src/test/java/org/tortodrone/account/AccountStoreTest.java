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
            try (AccountStore accounts = new AccountStore(directory)) {
                check(accounts.list().isEmpty(),"fresh database has no automatic login");
                accounts.create("First","first-password".toCharArray());
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
            concurrent(directory.resolve("concurrent"));
            System.out.println("PASS: SQLite accounts, gameMaster roles, password reset/check, deletion, stable IDs, stale selection, atomic failures and concurrency");
        } finally { try (var paths = Files.walk(directory)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); } }
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
