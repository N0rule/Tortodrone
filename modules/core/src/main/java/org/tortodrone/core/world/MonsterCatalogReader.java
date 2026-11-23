package org.tortodrone.core.world;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.tortodrone.core.ContentFiles;
import org.tortodrone.core.Json;
import org.tortodrone.core.Yaml;

/** Ingestion, fingerprint verification and overlay validation for {@link MonsterCatalog}.
 *  Runtime simulation reads only the resulting immutable catalogue; file/format policy stays
 *  here. */
final class MonsterCatalogReader {
    static final int FORMAT = 1;
    /** Bound the generated document; it is client data, not administrator input. */
    static final long MAX_BYTES = 32L * 1024 * 1024;

    private MonsterCatalogReader() {}

    /** Load {@code content/monsters}; an absent folder means no catalogue was prepared. */
    static MonsterCatalog load(Path folder) throws IOException {
        if (!Files.exists(folder)) return MonsterCatalog.empty();
        if (Files.isSymbolicLink(folder) || !Files.isDirectory(folder))
            throw new IOException("Invalid monster catalogue directory: " + folder);
        Path catalog = folder.resolve("catalog.json");
        ContentFiles.regular(catalog);
        if (Files.size(catalog) > MAX_BYTES) throw new IOException("Monster catalogue exceeds size limit");
        Path hash = folder.resolve("catalog.sha256");
        ContentFiles.regular(hash);
        String expected = Files.readString(hash).trim();
        if (!expected.matches("[0-9a-f]{64}")) throw new IOException("Invalid monster catalogue fingerprint");
        if (!ContentFiles.digest(catalog).equals(expected))
            throw new IOException("Monster catalogue fingerprint mismatch; regenerate from the installed client");
        MonsterCatalog catalogTypes = parse(Files.readString(catalog));
        return withOverrides(catalogTypes, folder.resolve("overrides.yml"));
    }

    /** Parse without the on-disk fingerprint; tests and tooling use the same validation path. */
    static MonsterCatalog parse(String json) throws IOException {
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
            if (types.put(id, type(id, value)) != null) throw new IOException("Duplicate monster type: " + id);
        }
        if (types.isEmpty()) throw new IOException("Monster catalogue is empty");
        checkScriptedBosses(types, Map.of());
        return new MonsterCatalog(types, Map.of(), generatedFrom);
    }

    private static MonsterType type(String id, Map<?, ?> value) throws IOException {
        String config = text(value.get("config"), "monster config " + id);
        String display = text(value.get("display"), "monster display " + id);
        String family = text(value.get("family"), "monster family " + id);
        String actor = value.get("actor") == null ? null : text(value.get("actor"), "monster actor " + id);
        var configs = value.get("configs") == null
                ? List.<String>of()
                : strings(value.get("configs"), "monster configs " + id);
        String archetype = text(value.get("archetype"), "monster archetype " + id);
        boolean supported = flag(value.get("supported"), "monster supported " + id);
        var capabilities = strings(value.get("capabilities"), "monster capabilities " + id);
        var unsupported = strings(value.get("unsupported"), "monster refusals " + id);
        var spec = new TreeMap<String, String>();
        if (value.get("spec") != null)
            for (var field : Yaml.map(value.get("spec"), "monster spec " + id).entrySet())
                spec.put(String.valueOf(field.getKey()), String.valueOf(field.getValue()));
        try {
            return new MonsterType(
                    id, display, family, config, actor, configs, archetype, supported, capabilities, unsupported, spec);
        } catch (IllegalArgumentException invalid) {
            throw new IOException(invalid.getMessage(), invalid);
        }
    }

    private static MonsterCatalog withOverrides(MonsterCatalog base, Path file) throws IOException {
        if (!Files.exists(file)) return base;
        ContentFiles.regular(file);
        var root = Yaml.read(file);
        Yaml.fields(root, file.toString(), "monsters");
        var types = base.types();
        var generatedFrom = base.generatedFrom();
        var declared = new TreeMap<String, MonsterCatalog.Override>();
        for (var entry : Yaml.map(root.getOrDefault("monsters", Map.of()), file.toString())
                .entrySet()) {
            String id = entry.getKey();
            if (!types.containsKey(id)) throw new IOException("Override names an unknown monster type: " + id);
            var fields = Yaml.map(entry.getValue(), "monster override " + id);
            String where = file + " " + id;
            Yaml.fields(fields, where, "activation-radius", "leash-radius", "think", "archetype");
            Float activation = null, leash = null;
            String think = null, archetype = null;
            if (fields.containsKey("activation-radius")) {
                activation = Yaml.decimal(fields.get("activation-radius"), where + " activation-radius");
                if (activation < 1 || activation > 512)
                    throw new IOException(where + ": activation-radius must be 1..512");
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
                if (declared.put(id, new MonsterCatalog.Override(activation, leash, think, archetype)) != null)
                    throw new IOException("Duplicate monster override: " + id);
            } catch (IllegalArgumentException invalid) {
                throw new IOException(where + ": " + invalid.getMessage(), invalid);
            }
        }
        checkScriptedBosses(types, declared);
        return new MonsterCatalog(types, declared, generatedFrom);
    }

    /** Keep the boss escape hatch explicitly bounded; overflow is a data error, not silence. */
    private static void checkScriptedBosses(
            Map<String, MonsterType> types, Map<String, MonsterCatalog.Override> overrides) throws IOException {
        var bosses = new ArrayList<String>();
        for (var type : types.values()) {
            var tuning = overrides.get(type.id());
            String id = tuning != null && tuning.archetype() != null ? tuning.archetype() : type.archetype();
            if (MonsterArchetype.BOSS_SCRIPTED_ID.equals(id)) bosses.add(type.id());
        }
        if (bosses.size() > MonsterArchetype.MAX_SCRIPTED_BOSSES) {
            bosses.sort(String::compareTo);
            throw new IOException("At most " + MonsterArchetype.MAX_SCRIPTED_BOSSES
                    + " boss-scripted monster types are supported: " + String.join(", ", bosses));
        }
    }

    private static int integer(Object value, String where) throws IOException {
        if (!(value instanceof Number number)) throw new IOException(where + ": expected an integer");
        try {
            return new BigDecimal(number.toString()).intValueExact();
        } catch (ArithmeticException invalid) {
            throw new IOException(where + ": expected a 32-bit integer");
        }
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
