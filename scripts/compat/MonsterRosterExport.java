import com.threerings.config.*;
import com.threerings.projectx.config.DescriptionConfig;
import com.threerings.projectx.dungeon.config.DungeonActorConfig;
import com.threerings.tudey.config.ActorConfig;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipFile;
import org.tortodrone.core.Json;

/**
 * Read-only roster export for every spawnable stock monster description.
 *
 * <p>The roster is derived from the installed client's {@code description.dat} (the same entries the
 * client shows), never from hand-authored names. Each entry records every actor config that carries
 * it, the canonical config used by the catalogue, and whether the exported attack graph validates
 * under the server's fail-closed scene-action parser.
 */
public final class MonsterRosterExport {
    public static final int FORMAT = 1;

    private MonsterRosterExport() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3)
            throw new IllegalArgumentException(
                    "Usage: MonsterRosterExport game-directory output-directory [--catalog]");
        Path game = Path.of(args[0]).toRealPath();
        Path out = Path.of(args[1]).toAbsolutePath().normalize();
        Path ancestor = out;
        while (!Files.exists(ancestor)) ancestor = ancestor.getParent();
        if (ancestor.toRealPath().startsWith(game) || Files.exists(out))
            throw new IOException("Choose a fresh output directory outside the installation");
        boolean catalog = args.length == 3 && args[2].equals("--catalog");
        if (args.length == 3 && !catalog) throw new IllegalArgumentException("Unknown option: " + args[2]);
        var document = export(game, catalog);
        Files.createDirectories(out);
        Path output = out.resolve(catalog ? "catalog.json" : "monster-roster.json");
        Files.writeString(output, Json.stringify(document) + "\n");
        if (catalog) Files.writeString(out.resolve("catalog.sha256"), sha256(output) + "\n");
        @SuppressWarnings("unchecked")
        var types = (Map<String, Object>) document.get("types");
        long supported = types.values().stream()
                .filter(value -> Boolean.TRUE.equals(((Map<?, ?>) value).get("supported")))
                .count();
        if (!catalog) Files.writeString(out.resolve("monster-roster.txt"), summary(types));
        System.out.printf(
                "PASS: exported %d monster types (%d supported, %d unsupported) to %s%n",
                types.size(), supported, types.size() - supported, out);
        if (!catalog) {
            var families = new TreeMap<String, int[]>();
            for (Object raw : types.values()) {
                var type = (Map<?, ?>) raw;
                var counts = families.computeIfAbsent(String.valueOf(type.get("family")), key -> new int[2]);
                counts[Boolean.TRUE.equals(type.get("supported")) ? 0 : 1]++;
            }
            families.forEach((family, counts) ->
                    System.out.printf("FAMILY\t%s\t%d supported\t%d unsupported%n", family, counts[0], counts[1]));
        }
    }

    /** Deterministic catalogue/report document; maps are sorted by the JSON writer. */
    static Map<String, Object> export(Path game, boolean includeSpec) throws Exception {
        var manager = manager(game);
        var messages = monsterMessages(game);
        var actors = actorConfigs(manager);
        var descriptions = new ArrayList<DescriptionConfig>();
        for (var config : manager.be(DescriptionConfig.class).kR())
            if (config instanceof DescriptionConfig.Monster) descriptions.add(config);
        descriptions.sort(Comparator.comparing(DescriptionConfig::getName));
        var roster = new ArrayList<Row>();
        for (var description : descriptions) {
            if (!spawnable(description)) continue;
            roster.add(row(game, manager, messages, actors, description, includeSpec));
        }
        var ids = new HashSet<String>();
        for (var row : roster) row.id = uniqueId(row, ids);
        var types = new TreeMap<String, Object>();
        for (var row : roster) types.put(row.id, row.type);
        var document = new LinkedHashMap<String, Object>();
        document.put("format", FORMAT);
        document.put(
                "generatedFrom",
                Map.of(
                        "actorSha256", sha256(game.resolve("rsrc/config/actor.dat")),
                        "descriptionSha256", sha256(game.resolve("rsrc/config/description.dat")),
                        "monsterPropertiesSha256", sha256(game.resolve("code/projectx-config.jar"))));
        document.put("types", types);
        return document;
    }

    private static Row row(
            Path game,
            ConfigManager manager,
            Properties messages,
            List<ActorRow> actors,
            DescriptionConfig description,
            boolean includeSpec)
            throws Exception {
        String name = description.getName();
        String leaf = leaf(name);
        String family = family(name);
        String display = display(description, messages, leaf);
        var matching = new ArrayList<ActorRow>();
        for (var actor : actors) if (name.equals(actor.description)) matching.add(actor);
        if (matching.isEmpty())
            for (var actor : actors) if (leaf.equalsIgnoreCase(leaveName(actor.config))) matching.add(actor);
        // Prefer the actor whose own name matches the description, then the shortest concrete
        // config. A part/preset variant that cannot export must never mask a supported base.
        String target = leaf.toLowerCase(Locale.ROOT);
        matching.sort(Comparator.comparingInt((ActorRow actor) -> {
                    String own = leaveName(actor.config).toLowerCase(Locale.ROOT);
                    return own.equals(target) ? 0 : own.startsWith(target) ? 1 : 2;
                })
                .thenComparingInt(actor -> actor.config.length())
                .thenComparing(actor -> actor.config));
        var type = new LinkedHashMap<String, Object>();
        type.put("config", name);
        type.put("display", display);
        type.put("family", family);
        type.put("archetype", "stationary");
        type.put("supported", false);
        type.put("capabilities", List.of());
        type.put("unsupported", List.of("no-actor-config"));
        type.put("configs", matching.stream().map(actor -> actor.config).toList());
        if (matching.isEmpty()) return new Row(name, family, display, type);
        var unsupported = new TreeSet<String>();
        Map<String, String> supportedSpec = null, chosenSpec = null;
        String canonical = matching.getFirst().config;
        for (var candidate : matching) {
            var spec = export(manager, candidate);
            if (chosenSpec == null) chosenSpec = spec;
            var failures = new TreeSet<String>();
            canonicalize(spec);
            for (var field : spec.entrySet()) {
                if (field.getKey().endsWith(".chain-unsupported")) failures.add("chain: " + field.getValue());
                if (field.getKey().equals("export-refused")) failures.add(field.getValue());
                if (!field.getValue().startsWith("{") && !field.getValue().startsWith("[")) continue;
                try {
                    scan(Json.parse(field.getValue(), 64), failures);
                } catch (IOException ignored) {
                    // Exported scalar fields are not JSON; only action graphs are scanned.
                }
            }
            if (failures.isEmpty()) {
                supportedSpec = spec;
                chosenSpec = spec;
                canonical = candidate.config;
                unsupported.clear();
                break;
            }
            unsupported.addAll(failures);
        }
        type.put("supported", supportedSpec != null);
        type.put("archetype", archetype(chosenSpec));
        type.put("actor", canonical);
        type.put("unsupported", List.copyOf(unsupported));
        if (includeSpec && supportedSpec != null) type.put("spec", supportedSpec);
        return new Row(name, family, display, type);
    }

    /** One actor's attack export, or the exact refusal reason. */
    private static TreeMap<String, String> export(ConfigManager manager, ActorRow actor) {
        var spec = new TreeMap<String, String>();
        try {
            spec.put("config", actor.config);
            spec.put("kind", "Monster");
            spec.put("native-type", "com.threerings.projectx.dungeon.data.actor.Monster");
            SceneMonsterExport.export(manager, spec, "", actor.monster);
        } catch (Exception refused) {
            spec.clear();
            spec.put("config", actor.config);
            String reason = refused.getMessage() == null ? refused.getClass().getSimpleName() : refused.getMessage();
            spec.put("export-refused", "export: " + reason);
        }
        return spec;
    }

    private static String uniqueId(Row row, Set<String> used) {
        String base = slug(row.display);
        String id = base;
        if (used.contains(id)) id = slug(row.family + "_" + row.display);
        if (used.contains(id)) id = slug(row.family + "_" + leaf(row.config));
        for (int suffix = 2; used.contains(id); suffix++) id = slug(row.family + "_" + leaf(row.config) + "_" + suffix);
        used.add(id);
        return id;
    }

    /** Stable lower_snake identifier; empty results are refused so no type loses its name. */
    static String slug(String text) {
        String slug =
                text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (slug.isEmpty()) throw new IllegalArgumentException("Cannot derive a monster type identifier from: " + text);
        return slug;
    }

    /** The stock roster excludes abstract family bases and training props by contract. */
    static boolean spawnable(DescriptionConfig description) {
        String name = description.getName();
        return !name.endsWith(" (Base)") && !name.contains("/Training Bag/");
    }

    static String family(String name) {
        String[] path = name.split("/");
        return path.length > 1 ? path[1] : "No Family";
    }

    static String leaf(String name) {
        return name.substring(name.lastIndexOf('/') + 1);
    }

    private static String leaveName(String actor) {
        String leaf = leaf(actor);
        return leaf.replaceFirst(" [0-9]+$", "");
    }

    private static String display(DescriptionConfig description, Properties messages, String fallback) {
        String key = description.dname;
        if (key == null || key.isBlank()) return fallback;
        String value = resolve(messages, key);
        return value == null || value.isBlank()
                ? fallback
                : value.replaceAll("\\s+", " ").trim();
    }

    /** Native dname supports composite keys joined with {@code |} and {@code {0}} substitution. */
    static String resolve(Properties messages, String key) {
        var parts = key.split("\\|", -1);
        var values = new ArrayList<String>(parts.length);
        for (String part : parts) {
            String value = messages.getProperty(part);
            if (value == null) return null;
            values.add(value);
        }
        var out = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            String value = values.get(i);
            if (value.contains("{0}")) {
                var rest = new StringBuilder();
                for (int j = i + 1; j < values.size(); j++) rest.append(values.get(j));
                value = value.replace("{0}", rest);
                out.append(value);
                break;
            }
            out.append(value);
        }
        return out.toString();
    }

    private static Properties monsterMessages(Path game) throws IOException {
        var properties = new Properties();
        try (var zip = new ZipFile(game.resolve("code/projectx-config.jar").toFile())) {
            var entry = zip.getEntry("rsrc/i18n/monster.properties");
            if (entry == null) throw new IOException("Installed client has no monster.properties");
            try (var in = zip.getInputStream(entry)) {
                properties.load(in);
            }
        }
        return properties;
    }

    private static List<ActorRow> actorConfigs(ConfigManager manager) {
        var rows = new ArrayList<ActorRow>();
        for (var config : manager.be(ActorConfig.class).kR()) {
            if (!(config.al(manager) instanceof DungeonActorConfig.Monster monster)) continue;
            rows.add(new ActorRow(
                    config.getName(), monster.property == null ? null : monster.property.getName(), monster));
        }
        rows.sort(Comparator.comparing(actor -> actor.config));
        return rows;
    }

    private static ConfigManager manager(Path game) throws Exception {
        var manager = NativeConfig.load(game);
        return manager;
    }

    /** Attack graphs may contain unsupported action kinds; the exporter lists them, never drops them. */
    private static void scan(Object value, Set<String> unsupported) {
        if (value instanceof Map<?, ?> map) {
            if ("UNSUPPORTED".equals(map.get("kind"))) unsupported.add(String.valueOf(map.get("unsupportedType")));
            map.values().forEach(child -> scan(child, unsupported));
        } else if (value instanceof List<?> list) list.forEach(child -> scan(child, unsupported));
    }

    /**
     * Client reflection field order is not stable across JVM runs. Action graphs are keyed data, so
     * sort object keys and descriptor {@code fields} lists; value lists keep their authored order.
     */
    private static void canonicalize(Map<String, String> spec) {
        for (var key : new ArrayList<>(spec.keySet())) {
            String value = spec.get(key);
            if (!value.startsWith("{") && !value.startsWith("[")) continue;
            try {
                spec.put(key, Json.stringify(canonical(Json.parse(value, 64), false), 64));
            } catch (IOException ignored) {
                // Not JSON; leave the exported scalar untouched.
            }
        }
    }

    private static Object canonical(Object value, boolean descriptorFields) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            for (var entry : map.entrySet())
                sorted.put(
                        String.valueOf(entry.getKey()), canonical(entry.getValue(), "fields".equals(entry.getKey())));
            return sorted;
        }
        if (value instanceof List<?> list) {
            var values = new ArrayList<Object>();
            for (var item : list) values.add(canonical(item, false));
            if (descriptorFields)
                values.sort(Comparator.comparing(item ->
                        item instanceof Map<?, ?> map ? String.valueOf(map.get("name")) : String.valueOf(item)));
            return values;
        }
        return value;
    }

    /**
     * Derive a scheduling archetype from the exported native action graph. The graph decides the
     * role; no name, family or hand-authored stat participates. Unsupported types cannot spawn,
     * but still receive the closest classification their partial export supports.
     */
    private static String archetype(Map<String, String> spec) {
        if (spec == null || spec.getOrDefault("attacks", "").isBlank()) return "stationary";
        boolean[] flags = new boolean[3]; // projectile, bomb, summon
        for (var entry : spec.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("attack.") || !key.endsWith(".action")) continue;
            try {
                scanArchetype(Json.parse(entry.getValue(), 64), flags);
            } catch (IOException ignored) {
                // A failed action export cannot contribute a role; the remaining slots still can.
            }
        }
        if (flags[2]) return "summoner";
        if (flags[1]) return "bomber";
        if (flags[0]) return "ranged-kiter";
        return "melee-chaser";
    }

    private static void scanArchetype(Object node, boolean[] flags) {
        if (!(node instanceof Map<?, ?> action)) return;
        Object kind = action.get("kind");
        if ("EMIT_PROJECTILE".equals(kind)) {
            flags[0] = true;
            if (action.get("actor") instanceof Map<?, ?> actor && "BOMB".equals(actor.get("nativeType")))
                flags[1] = true;
        }
        if ("SPAWN_ACTOR".equals(kind) || "SPAWN_FACING".equals(kind) || "RANDOM_TRANSLATED".equals(kind))
            flags[2] = true;
        if (action.get("children") instanceof List<?> children) for (var child : children) scanArchetype(child, flags);
    }

    private static String summary(Map<String, Object> types) {
        var out = new StringBuilder("Monster roster summary\n=====================\n");
        var families = new TreeMap<String, int[]>();
        for (Object raw : types.values()) {
            var type = (Map<?, ?>) raw;
            var counts = families.computeIfAbsent(String.valueOf(type.get("family")), key -> new int[2]);
            counts[Boolean.TRUE.equals(type.get("supported")) ? 0 : 1]++;
        }
        families.forEach((family, counts) ->
                out.append(String.format("%-12s %3d supported %3d unsupported%n", family, counts[0], counts[1])));
        out.append(String.format("%-12s %3d total%n", "", types.size()));
        out.append("\nUnsupported types\n-----------------\n");
        for (var entry : types.entrySet()) {
            var type = (Map<?, ?>) entry.getValue();
            if (Boolean.TRUE.equals(type.get("supported"))) continue;
            out.append(entry.getKey()).append(" (").append(type.get("config")).append("): ");
            out.append(String.join(
                            ", ",
                            ((List<?>) type.get("unsupported"))
                                    .stream().map(String::valueOf).toList()))
                    .append('\n');
        }
        return out.toString();
    }

    private static String sha256(Path file) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                var buffer = new byte[65536];
                for (int read; (read = in.read(buffer)) > 0; ) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class Row {
        String id;
        final String config, family, display;
        final Map<String, Object> type;

        Row(String config, String family, String display, Map<String, Object> type) {
            this.config = config;
            this.family = family;
            this.display = display;
            this.type = type;
        }
    }

    private record ActorRow(String config, String description, DungeonActorConfig.Monster monster) {}
}
