package org.tortodrone.world;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.tortodrone.resources.ContentFiles;
import org.tortodrone.resources.Json;
import org.tortodrone.resources.Yaml;

/**
 * Generated monster type catalogue and the small hand-authored AI overlay.
 *
 * <p>The catalogue is read-only product data. Unknown formats are refused; unsupported types stay
 * listed but cannot be spawned. Runtime code resolves types here instead of inventing configs.
 */
public final class MonsterCatalog {
    public static final int FORMAT = 1;
    /** Bound the generated document; it is client data, not administrator input. */
    static final long MAX_BYTES = 32L * 1024 * 1024;
    private static final MonsterCatalog EMPTY = new MonsterCatalog(Map.of(), Map.of(), Map.of());

    /** Hand-authored scheduling knobs only; stats, damage and timing stay native. */
    public record Override(Float activationRadius, Float leashRadius, String think, String archetype) {
        public Override {
            if (think != null && !Set.of("dormant", "alert", "engaged").contains(think))
                throw new IllegalArgumentException("Unknown monster think band: " + think);
            if (archetype != null && !archetype.matches("[a-z][a-z0-9-]{0,31}"))
                throw new IllegalArgumentException("Invalid monster archetype: " + archetype);
        }
    }

    private final Map<String, MonsterType> types;
    private final Map<String, Override> overrides;
    private final Map<String, String> generatedFrom;

    private MonsterCatalog(Map<String, MonsterType> types, Map<String, Override> overrides,
                           Map<String, String> generatedFrom) {
        this.types = Map.copyOf(types);
        this.overrides = Map.copyOf(overrides);
        this.generatedFrom = Map.copyOf(generatedFrom);
    }

    public static MonsterCatalog empty() { return EMPTY; }

    public Map<String, MonsterType> types() { return types; }
    public Map<String, Override> overrides() { return overrides; }
    public Map<String, String> generatedFrom() { return generatedFrom; }
    public int size() { return types.size(); }
    public MonsterType type(String id) { return id == null ? null : types.get(id); }
    public Override override(String id) { return id == null ? null : overrides.get(id); }

    /** Resolve a spawn request, refusing unknown and unsupported types with a clear reason. */
    public MonsterType spawnable(String id) throws IOException {
        MonsterType type = type(id);
        if (type == null) throw new IOException("Unknown monster type: " + id);
        type.requireSpawnable();
        return type;
    }

    /** Load {@code content/monsters}; an absent folder means no catalogue was prepared. */
    public static MonsterCatalog load(Path folder) throws IOException {
        if (!Files.exists(folder)) return empty();
        if (Files.isSymbolicLink(folder) || !Files.isDirectory(folder))
            throw new IOException("Invalid monster catalogue directory: " + folder);
        Path catalog = folder.resolve("catalog.json");
        ContentFiles.regular(catalog);
        if (Files.size(catalog) > MAX_BYTES) throw new IOException("Monster catalogue exceeds size limit");
        Path hash = folder.resolve("catalog.sha256");
        ContentFiles.regular(hash);
        String expected = Files.readString(hash).trim();
        if (!expected.matches("[0-9a-f]{64}"))
            throw new IOException("Invalid monster catalogue fingerprint");
        if (!ContentFiles.digest(catalog).equals(expected))
            throw new IOException("Monster catalogue fingerprint mismatch; regenerate from the installed client");
        MonsterCatalog catalogTypes = parse(Files.readString(catalog));
        return catalogTypes.withOverrides(folder.resolve("overrides.yml"));
    }

    /** Parse without the on-disk fingerprint; tests and tooling use the same validation path. */
    public static MonsterCatalog parse(String json) throws IOException {
        var document = Yaml.map(Json.parseCatalog(json), "monster catalogue");
        if (integer(document.get("format"), "monster catalogue format") != FORMAT)
            throw new IOException("Unsupported monster catalogue format");
        var generatedFrom = new TreeMap<String, String>();
        Object source = document.get("generatedFrom");
        if (source instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                String value = String.valueOf(entry.getValue());
                if (!value.matches("[0-9a-f]{64}"))
                    throw new IOException("Invalid monster source fingerprint: " + entry.getKey());
                generatedFrom.put(String.valueOf(entry.getKey()), value);
            }
        }
        if (generatedFrom.get("actorSha256") == null || generatedFrom.get("descriptionSha256") == null)
            throw new IOException("Monster catalogue is missing source fingerprints");
        var types = new TreeMap<String, MonsterType>();
        for (var entry : Yaml.map(document.get("types"), "monster types").entrySet()) {
            String id = entry.getKey();
            var value = Yaml.map(entry.getValue(), "monster type " + id);
            if (types.put(id, type(id, value)) != null)
                throw new IOException("Duplicate monster type: " + id);
        }
        if (types.isEmpty()) throw new IOException("Monster catalogue is empty");
        return new MonsterCatalog(types, Map.of(), generatedFrom);
    }

    private static MonsterType type(String id, Map<?, ?> value) throws IOException {
        String config = text(value.get("config"), "monster config " + id);
        String display = text(value.get("display"), "monster display " + id);
        String family = text(value.get("family"), "monster family " + id);
        String actor = value.get("actor") == null ? null : text(value.get("actor"), "monster actor " + id);
        var configs = value.get("configs") == null ? List.<String>of() : strings(value.get("configs"), "monster configs " + id);
        String archetype = text(value.get("archetype"), "monster archetype " + id);
        boolean supported = flag(value.get("supported"), "monster supported " + id);
        var capabilities = strings(value.get("capabilities"), "monster capabilities " + id);
        var unsupported = strings(value.get("unsupported"), "monster refusals " + id);
        var spec = new TreeMap<String, String>();
        if (value.get("spec") != null)
            for (var field : Yaml.map(value.get("spec"), "monster spec " + id).entrySet())
                spec.put(String.valueOf(field.getKey()), String.valueOf(field.getValue()));
        try {
            return new MonsterType(id, display, family, config, actor, configs, archetype, supported,
                capabilities, unsupported, spec);
        } catch (IllegalArgumentException invalid) {
            throw new IOException(invalid.getMessage(), invalid);
        }
    }

    private MonsterCatalog withOverrides(Path file) throws IOException {
        if (!Files.exists(file)) return this;
        ContentFiles.regular(file);
        var root = Yaml.read(file);
        Yaml.fields(root, file.toString(), "monsters");
        var declared = new TreeMap<String, Override>();
        for (var entry : Yaml.map(root.getOrDefault("monsters", Map.of()), file.toString()).entrySet()) {
            String id = entry.getKey();
            if (!types.containsKey(id)) throw new IOException("Override names an unknown monster type: " + id);
            var fields = Yaml.map(entry.getValue(), "monster override " + id);
            String where = file + " " + id;
            Yaml.fields(fields, where, "activation-radius", "leash-radius", "think", "archetype");
            Float activation = null, leash = null;
            String think = null, archetype = null;
            if (fields.containsKey("activation-radius")) {
                activation = Yaml.decimal(fields.get("activation-radius"), where + " activation-radius");
                if (activation < 1 || activation > 512) throw new IOException(where + ": activation-radius must be 1..512");
            }
            if (fields.containsKey("leash-radius")) {
                leash = Yaml.decimal(fields.get("leash-radius"), where + " leash-radius");
                if (leash < 1 || leash > 1024) throw new IOException(where + ": leash-radius must be 1..1024");
            }
            if (activation != null && leash != null && leash < activation)
                throw new IOException(where + ": leash-radius must not be smaller than activation-radius");
            if (fields.containsKey("think")) think = Yaml.text(fields.get("think"), where + " think");
            if (fields.containsKey("archetype")) archetype = Yaml.text(fields.get("archetype"), where + " archetype");
            try {
                if (declared.put(id, new Override(activation, leash, think, archetype)) != null)
                    throw new IOException("Duplicate monster override: " + id);
            } catch (IllegalArgumentException invalid) {
                throw new IOException(where + ": " + invalid.getMessage(), invalid);
            }
        }
        return new MonsterCatalog(types, declared, generatedFrom);
    }

    /** Validate type-backed placements of one prepared scene. */
    public void validateScene(String mapName, SceneContent content) throws IOException {
        for (var entry : content.entries()) {
            String id = entry.spec().text("monster-type", "");
            if (id.isEmpty()) continue;
            MonsterType type;
            try {
                type = spawnable(id);
            } catch (IOException refused) {
                throw new IOException(mapName + " / " + entry.key() + ": " + refused.getMessage(), refused);
            }
            if (!"Monster".equals(entry.spec().text("kind", "")))
                throw new IOException(mapName + " / " + entry.key() + ": monster-type requires a Monster actor");
            if (!type.configs().contains(entry.spec().text("config", "")))
                throw new IOException(mapName + " / " + entry.key() + ": actor config differs from catalogue type " + id);
        }
    }

    /**
     * Expand type-backed placements into the prepared fields the scene runtime reads.
     *
     * <p>The catalogue supplies the native attack graph and timing; the placement keeps its
     * identity, geometry, health and any explicit tuning override. References are validated before
     * this runs, and are removed so no later consumer mistakes the reference for a prepared field.
     */
    public SceneContent expand(SceneContent content) throws IOException {
        var entries = new ArrayList<SceneContent.Entry>(content.entries().size());
        for (var entry : content.entries()) {
            String id = entry.spec().text("monster-type", "");
            if (id.isEmpty()) { entries.add(entry); continue; }
            MonsterType type = spawnable(id);
            if (!"Monster".equals(entry.spec().text("kind", "")))
                throw new IOException("monster-type requires a Monster actor: " + entry.key());
            if (!type.configs().contains(entry.spec().text("config", "")))
                throw new IOException("Actor config differs from catalogue type " + id + ": " + entry.key());
            var fields = new TreeMap<>(type.spec());
            var tuning = overrides.get(id);
            // The overlay owns scheduling only. Placement fields still win over type defaults.
            if (tuning != null && tuning.activationRadius() != null)
                fields.put("activation-radius", Float.toString(tuning.activationRadius()));
            if (tuning != null && tuning.leashRadius() != null)
                fields.put("leash-radius", Float.toString(tuning.leashRadius()));
            fields.putAll(entry.spec().fields());
            fields.remove("monster-type");
            entries.add(new SceneContent.Entry(entry.key(), entry.x(), entry.y(), entry.rotation(),
                new SceneContent.Spec(fields)));
        }
        return content.withEntries(entries);
    }

    private static int integer(Object value, String where) throws IOException {
        if (!(value instanceof Number number)) throw new IOException(where + ": expected an integer");
        try { return new BigDecimal(number.toString()).intValueExact(); }
        catch (ArithmeticException invalid) { throw new IOException(where + ": expected a 32-bit integer"); }
    }

    private static boolean flag(Object value, String where) throws IOException {
        if (!(value instanceof Boolean flag)) throw new IOException(where + ": expected true or false");
        return flag;
    }

    private static String text(Object value, String where) throws IOException {
        if (!(value instanceof String text) || text.isBlank() || text.length() > 512)
            throw new IOException(where + ": expected short text");
        return text;
    }

    private static List<String> strings(Object value, String where) throws IOException {
        if (!(value instanceof List<?> list)) throw new IOException(where + ": expected a list");
        var result = new ArrayList<String>();
        for (var item : list) {
            String text = text(item, where);
            if (text.length() > 512) throw new IOException(where + ": entry is too long");
            result.add(text);
        }
        return result;
    }
}
