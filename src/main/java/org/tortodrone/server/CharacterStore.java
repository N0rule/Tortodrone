package org.tortodrone.server;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.protocol.Knight;
import org.tortodrone.protocol.HandshakeCodec;

/** One small, atomically replaced profile for the currently supported test account. */
final class CharacterStore {
    static final String USERNAME = HandshakeCodec.TEST_USERNAME;
    static final int MAX_KNIGHTS = 3;
    private final Path file;
    private List<Knight> knights = List.of();
    private int nextId = 1;

    /** In-memory store for isolated wire tests. Production always uses the data directory. */
    CharacterStore() { file = null; }

    CharacterStore(Path dataDirectory) throws IOException {
        file = dataDirectory.resolve("accounts/1.properties");
        if (!Files.exists(file)) return;
        if (Files.size(file) > 32768) throw new IOException("Character profile exceeds size limit");
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) { properties.load(reader); }
        try {
            String savedUsername = properties.getProperty("username");
            // The renamed test account retains account ID 1 and its saved characters.
            if (!"1".equals(properties.getProperty("schema"))
                    || (!USERNAME.equals(savedUsername) && !"tortodrone-local-probe".equals(savedUsername))) {
                throw new IllegalArgumentException();
            }
            int count = Integer.parseInt(properties.getProperty("knights"));
            nextId = Integer.parseInt(properties.getProperty("next-id"));
            if (count < 0 || count > MAX_KNIGHTS || nextId < 1) throw new IllegalArgumentException();
            List<Knight> loaded = new ArrayList<>();
            Set<Integer> ids = new HashSet<>(); Set<String> names = new HashSet<>();
            for (int i = 0; i < count; i++) {
                String prefix = "knight." + i + ".";
                Knight knight = new Knight(Integer.parseInt(properties.getProperty(prefix + "id")),
                    properties.getProperty(prefix + "name"), properties.getProperty(prefix + "helm"),
                    properties.getProperty(prefix + "armor"), properties.getProperty(prefix + "helm-accessory"),
                    properties.getProperty(prefix + "armor-accessory"), properties.getProperty(prefix + "costume"),
                    Integer.parseInt(properties.getProperty(prefix + "color")),
                    Integer.parseInt(properties.getProperty(prefix + "height")),
                    Integer.parseInt(properties.getProperty(prefix + "eye")),
                    Long.parseLong(properties.getProperty(prefix + "created-at")));
                validate(knight);
                if (knight.id() < 1 || knight.id() >= nextId || knight.createdAt() < 1
                        || !ids.add(knight.id()) || !names.add(canonical(knight.name()))) throw new IllegalArgumentException();
                loaded.add(knight);
            }
            knights = List.copyOf(loaded);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException("Invalid character profile; saved characters were not modified", e);
        }
    }

    synchronized List<Knight> roster() { return knights; }

    synchronized String nameFailure(String name) {
        String error = nameError(name);
        if (error != null) return error;
        return knights.stream().anyMatch(knight -> canonical(knight.name()).equals(canonical(name)))
            ? "That knight name is already in use" : null;
    }

    synchronized Knight create(Knight choices) throws IOException, Refused {
        String error = nameFailure(choices.name());
        if (error != null) throw new Refused(error);
        try { validate(choices); }
        catch (IllegalArgumentException e) { throw new Refused("Invalid character appearance"); }
        if (knights.size() >= MAX_KNIGHTS) throw new Refused("All three knight slots are occupied");
        if (nextId == Integer.MAX_VALUE) throw new IOException("Character ID space exhausted");
        Knight created = new Knight(nextId, choices.name(), choices.helm(), choices.armor(), choices.helmAccessory(),
            choices.armorAccessory(), choices.costume(), choices.color(), choices.height(), choices.eye(), System.currentTimeMillis());
        List<Knight> updated = new ArrayList<>(knights); updated.add(created);
        save(updated, nextId + 1); // Acknowledge only after persistence succeeds.
        knights = List.copyOf(updated); nextId++;
        return created;
    }

    private void save(List<Knight> updated, int next) throws IOException {
        if (file == null) return;
        Files.createDirectories(file.getParent());
        Properties properties = new Properties();
        properties.setProperty("schema", "1"); properties.setProperty("username", USERNAME);
        properties.setProperty("next-id", String.valueOf(next)); properties.setProperty("knights", String.valueOf(updated.size()));
        for (int i = 0; i < updated.size(); i++) {
            String prefix = "knight." + i + "."; Knight knight = updated.get(i);
            properties.setProperty(prefix + "id", String.valueOf(knight.id()));
            properties.setProperty(prefix + "name", knight.name());
            properties.setProperty(prefix + "helm", knight.helm()); properties.setProperty(prefix + "armor", knight.armor());
            if (knight.helmAccessory() != null) properties.setProperty(prefix + "helm-accessory", knight.helmAccessory());
            if (knight.armorAccessory() != null) properties.setProperty(prefix + "armor-accessory", knight.armorAccessory());
            properties.setProperty(prefix + "costume", knight.costume());
            properties.setProperty(prefix + "color", String.valueOf(knight.color()));
            properties.setProperty(prefix + "height", String.valueOf(knight.height()));
            properties.setProperty(prefix + "eye", String.valueOf(knight.eye()));
            properties.setProperty(prefix + "created-at", String.valueOf(knight.createdAt()));
        }
        StringWriter text = new StringWriter(); properties.store(text, "Tortodrone test account character profile");
        byte[] encoded = text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path temporary = Files.createTempFile(file.getParent(), ".profile-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(encoded);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
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
    static final class Refused extends Exception { Refused(String message) { super(message); } }
}
