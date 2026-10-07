package org.tortodrone.account;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.tortodrone.character.*;
import static org.tortodrone.support.TestWire.check;

public final class AccountStoreTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("tortodrone-accounts-");
        try {
            CharacterStore legacy = new CharacterStore(directory);
            Knight knight = legacy.create(choices("Legacy-Knight"));
            byte[] oldProfile = Files.readAllBytes(directory.resolve("accounts/1.properties"));
            AccountStore accounts = new AccountStore(directory);
            var first = login(accounts, "TORTODRONE", "tortodrone");
            check(first.id() == 1 && first.username().equals("tortodrone") && first.characters().roster().equals(List.of(knight)), "legacy identity and knights survive account migration");
            check(Arrays.equals(oldProfile, Files.readAllBytes(directory.resolve("accounts/1.properties"))), "migration does not rewrite profile");
            accounts.create("Alice", "alice-password".toCharArray());
            accounts.create("Bob", "bob-password".toCharArray());
            var alice = login(accounts, "alice", "alice-password");
            var bob = login(accounts, "Bob", "bob-password");
            check(alice.id() == 2 && bob.id() == 3 && alice.characters() != bob.characters(), "independent stable account IDs and stores");
            alice.characters().create(choices("Alice-Knight"));
            check(bob.characters().roster().isEmpty() && first.characters().roster().size() == 1, "rosters are isolated");
            try { bob.characters().delete("Alice-Knight"); throw new AssertionError("foreign knight deleted"); }
            catch (CharacterStore.Refused expected) { }
            check(login(accounts, "Alice", "wrong-password") == null && login(accounts, "Missing", "alice-password") == null, "generic rejection for wrong or missing account");
            byte[] registry = Files.readAllBytes(directory.resolve("accounts/users.properties"));
            refused(() -> accounts.create("ALICE", "another-password".toCharArray()));
            refused(() -> accounts.create("../bad", "another-password".toCharArray()));
            refused(() -> accounts.create("Short", "short".toCharArray()));
            refused(() -> accounts.setPassword("Missing", "another-password".toCharArray()));
            check(Arrays.equals(registry, Files.readAllBytes(directory.resolve("accounts/users.properties"))), "refused commands preserve registry");
            check(!new String(registry).contains("alice-password") && !new String(registry).contains(new String(AccountStore.transform("alice-password".toCharArray()))), "neither plaintext nor reusable client transform stored");
            if (Files.getFileStore(directory).supportsFileAttributeView("posix")) check(Files.getPosixFilePermissions(directory.resolve("accounts/users.properties")).equals(PosixFilePermissions.fromString("rw-------")), "registry permissions");
            accounts.setPassword("ALICE", "changed-password".toCharArray());
            check(login(accounts, "Alice", "alice-password") == null && login(accounts, "Alice", "changed-password").id() == 2, "password change preserves identity");
            AccountStore restored = new AccountStore(directory);
            check(login(restored, "Alice", "changed-password").characters().roster().equals(alice.characters().roster()), "accounts and characters persist across restart");
            restored.create("Charlie", "charlie-password".toCharArray());
            check(login(restored, "Charlie", "charlie-password").id() == 4, "account IDs continue after restart");

            Path registryPath = directory.resolve("accounts/users.properties");
            byte[] complete = Files.readAllBytes(registryPath);
            Files.delete(registryPath); Files.createDirectory(registryPath);
            refused(() -> restored.create("Retry", "retry-password".toCharArray()));
            check(login(restored, "Retry", "retry-password") == null && restored.list().size() == 4, "failed registry save does not publish account");
            refused(() -> restored.setPassword("Bob", "changed-password".toCharArray()));
            check(login(restored, "Bob", "bob-password") != null, "failed password save preserves old password");
            Files.delete(registryPath); Files.write(registryPath, complete);
            restored.create("Retry", "retry-password".toCharArray());
            check(login(restored, "Retry", "retry-password").id() == 5, "failed save does not consume ID");
            complete = Files.readAllBytes(registryPath);
            Files.writeString(registryPath, "schema=broken\n");
            byte[] corrupt = Files.readAllBytes(registryPath);
            refused(() -> new AccountStore(directory));
            check(Arrays.equals(corrupt, Files.readAllBytes(registryPath)), "invalid registry never reset");
            Files.write(registryPath, complete);
            Files.delete(registryPath);
            refused(() -> new AccountStore(directory));
            check(!Files.exists(registryPath), "missing registry with additional profiles refuses identity reassignment");
            System.out.println("PASS: normal account migration, password protection/reset, stable IDs, isolated profiles, restart and failed writes");
        } finally {
            try (var paths = Files.walk(directory)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
        }
    }
    static AccountStore.Account login(AccountStore accounts, String username, String password) throws IOException {
        char[] hash = AccountStore.transform(password.toCharArray());
        try { return accounts.authenticate(username, hash); }
        finally { Arrays.fill(hash, '\0'); }
    }
    static Knight choices(String name) { return new Knight(0, name, "helm", "armor", null, null, "costume", 1, 2, 0, 0); }
    static void refused(Operation operation) throws Exception {
        try { operation.run(); throw new AssertionError("Invalid or failed account write accepted"); }
        catch (IOException expected) { }
    }
    @FunctionalInterface interface Operation { void run() throws Exception; }
}
