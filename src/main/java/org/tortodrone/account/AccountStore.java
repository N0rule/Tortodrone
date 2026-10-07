package org.tortodrone.account;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.storage.AtomicProperties;

/** Persistent local normal accounts; one shared character store per account. */
public final class AccountStore {
    public static final String DEFAULT_USERNAME = "tortodrone";
    private static final int ITERATIONS = 600000;
    private static final int MAX_ACCOUNTS = 1000;
    private record Stored(int id, String username, byte[] salt, byte[] verifier, CharacterStore characters) { }
    public record Account(int id, String username, CharacterStore characters) { }
    public record Summary(int id, String username) { }
    private final Path directory;
    private final Path file;
    private final Map<String, Stored> accounts = new TreeMap<>();
    private int nextId = 1;

    /** Isolated protocol tests can supply an in-memory roster without deployment state. */
    public AccountStore(CharacterStore characters) throws IOException {
        directory = null; file = null; nextId = characters.accountId() + 1;
        char[] password = DEFAULT_USERNAME.toCharArray();
        try { accounts.put(canonical(characters.username()), protectedAccount(characters.accountId(),
            characters.username(), password, characters)); }
        finally { Arrays.fill(password, '\0'); }
    }

    public AccountStore(Path dataDirectory) throws IOException {
        directory = dataDirectory;
        file = directory.resolve("accounts/users.properties");
        if (!Files.exists(file)) {
            // Never attach unidentified profiles to newly allocated identities.
            if (Files.isDirectory(file.getParent())) {
                try (var paths = Files.list(file.getParent())) {
                    if (paths.anyMatch(path -> path.getFileName().toString().matches("[0-9]+\\.properties")
                            && !path.getFileName().toString().equals("1.properties"))) {
                        throw new IOException("Missing account registry with existing account profiles");
                    }
                }
            }
            char[] password = DEFAULT_USERNAME.toCharArray();
            try { create(DEFAULT_USERNAME, password); }
            finally { Arrays.fill(password, '\0'); }
            return;
        }
        if (Files.size(file) > 1024 * 1024) throw new IOException("Account registry exceeds size limit");
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) { properties.load(reader); }
        try {
            if (!"1".equals(properties.getProperty("schema"))
                    || !"PBKDF2WithHmacSHA256".equals(properties.getProperty("algorithm"))
                    || Integer.parseInt(properties.getProperty("iterations")) != ITERATIONS) throw new IllegalArgumentException();
            int count = Integer.parseInt(properties.getProperty("accounts"));
            nextId = Integer.parseInt(properties.getProperty("next-id"));
            if (count < 1 || count > MAX_ACCOUNTS || nextId < 2) throw new IllegalArgumentException();
            Set<Integer> ids = new HashSet<>();
            for (int i = 0; i < count; i++) {
                String prefix = "account." + i + ".";
                int id = Integer.parseInt(properties.getProperty(prefix + "id"));
                String username = properties.getProperty(prefix + "username");
                validateUsername(username);
                byte[] salt = HexFormat.of().parseHex(properties.getProperty(prefix + "salt"));
                byte[] verifier = HexFormat.of().parseHex(properties.getProperty(prefix + "verifier"));
                if (id < 1 || id >= nextId || !ids.add(id) || salt.length != 16 || verifier.length != 32
                        || accounts.containsKey(canonical(username))) throw new IllegalArgumentException();
                accounts.put(canonical(username), new Stored(id, username, salt, verifier,
                    new CharacterStore(directory, id, username)));
            }
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException("Invalid account registry; existing accounts were not modified", e);
        }
    }

    public synchronized List<Summary> list() {
        return accounts.values().stream().map(a -> new Summary(a.id(), a.username())).sorted(
            Comparator.comparingInt(Summary::id)).toList();
    }

    public synchronized void create(String username, char[] password) throws IOException {
        validateUsername(username);
        if (accounts.containsKey(canonical(username))) throw new IOException("Account already exists");
        if (accounts.size() >= MAX_ACCOUNTS || nextId == Integer.MAX_VALUE) throw new IOException("Account limit reached");
        validatePassword(password);
        // Loading before the registry write preserves the legacy ID 1 profile on upgrades.
        CharacterStore characters = directory == null ? new CharacterStore(nextId, username)
            : new CharacterStore(directory, nextId, username);
        Stored created = protectedAccount(nextId, username, password, characters);
        Map<String, Stored> updated = new TreeMap<>(accounts);
        updated.put(canonical(username), created);
        save(updated, nextId + 1);
        accounts.put(canonical(username), created);
        nextId++;
    }

    public synchronized void setPassword(String username, char[] password) throws IOException {
        Stored previous = accounts.get(canonical(username));
        if (previous == null) throw new IOException("No such account");
        validatePassword(password);
        Stored updated = protectedAccount(previous.id(), previous.username(), password, previous.characters());
        Map<String, Stored> replacements = new TreeMap<>(accounts);
        replacements.put(canonical(username), updated);
        save(replacements, nextId);
        accounts.put(canonical(username), updated);
    }

    /** Input is the client's legacy MD5 transform, never a plaintext wire password. */
    public synchronized Account authenticate(String username, char[] clientHash) throws IOException {
        if (username == null || clientHash == null || clientHash.length != 32) return null;
        for (char character : clientHash) if (!(character >= '0' && character <= '9')
                && !(character >= 'a' && character <= 'f')) return null;
        Stored stored = accounts.get(canonical(username));
        // Unknown users still perform a derivation and receive the same wire failure.
        byte[] salt = stored == null ? new byte[16] : stored.salt();
        byte[] candidate = derive(clientHash, salt);
        try {
            if (stored == null || !MessageDigest.isEqual(candidate, stored.verifier())) return null;
            return new Account(stored.id(), stored.username(), stored.characters());
        } finally { Arrays.fill(candidate, (byte) 0); }
    }

    private static Stored protectedAccount(int id, String username, char[] password, CharacterStore characters)
            throws IOException {
        byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt);
        char[] transformed = transform(password);
        try { return new Stored(id, username, salt, derive(transformed, salt), characters); }
        finally { Arrays.fill(transformed, '\0'); }
    }

    private static byte[] derive(char[] transformed, byte[] salt) throws IOException {
        PBEKeySpec spec = new PBEKeySpec(transformed, salt, ITERATIONS, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (GeneralSecurityException e) { throw new IOException("Password verification unavailable", e); }
        finally { spec.clearPassword(); }
    }

    /** Compatibility with ProjectXCredentials; not the format stored in the registry. */
    public static char[] transform(char[] password) throws IOException {
        java.nio.ByteBuffer utf8 = StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(password));
        byte[] bytes = new byte[utf8.remaining()]; utf8.get(bytes);
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes)).toCharArray(); }
        catch (NoSuchAlgorithmException e) { throw new IOException("Client password transform unavailable", e); }
        finally {
            Arrays.fill(bytes, (byte) 0);
            if (utf8.hasArray()) Arrays.fill(utf8.array(), (byte) 0);
        }
    }

    private void save(Map<String, Stored> updated, int next) throws IOException {
        if (file == null) return;
        Properties properties = new Properties();
        properties.setProperty("schema", "1"); properties.setProperty("algorithm", "PBKDF2WithHmacSHA256");
        properties.setProperty("iterations", String.valueOf(ITERATIONS));
        properties.setProperty("accounts", String.valueOf(updated.size())); properties.setProperty("next-id", String.valueOf(next));
        int index = 0;
        for (Stored account : updated.values()) {
            String prefix = "account." + index++ + ".";
            properties.setProperty(prefix + "id", String.valueOf(account.id()));
            properties.setProperty(prefix + "username", account.username());
            properties.setProperty(prefix + "salt", HexFormat.of().formatHex(account.salt()));
            properties.setProperty(prefix + "verifier", HexFormat.of().formatHex(account.verifier()));
        }
        AtomicProperties.save(file, properties, "Tortodrone normal accounts; password verifiers only");
    }

    private static String canonical(String username) { return username.toLowerCase(Locale.ROOT); }
    private static void validateUsername(String username) throws IOException {
        if (username == null || !username.matches("[A-Za-z][A-Za-z0-9_-]{2,31}")) {
            throw new IOException("Usernames need 3–32 ASCII characters: start with a letter, then letters, digits, underscores or hyphens");
        }
    }
    private static void validatePassword(char[] password) throws IOException {
        if (password == null || password.length < 8 || password.length > 128) {
            throw new IOException("Passwords need 8–128 characters");
        }
        for (char value : password) if (Character.isISOControl(value)) throw new IOException("Password contains control characters");
    }
}
