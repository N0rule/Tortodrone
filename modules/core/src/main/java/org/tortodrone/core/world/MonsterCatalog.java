package org.tortodrone.core.world;

import java.io.IOException;
import java.util.*;

/**
 * Generated monster type catalogue and the small hand-authored AI overlay.
 *
 * <p>The catalogue is read-only product data. Unknown formats are refused; unsupported types stay
 * listed but cannot be spawned. Runtime code resolves types here instead of inventing configs.
 */
public final class MonsterCatalog {
    private static final MonsterCatalog EMPTY = new MonsterCatalog(Map.of(), Map.of(), Map.of());

    /** Hand-authored scheduling knobs only; stats, damage and timing stay native. */
    public record Override(Float activationRadius, Float leashRadius, String think, String archetype) {
        public Override {
            if (think != null && !Set.of("dormant", "alert", "engaged").contains(think))
                throw new IllegalArgumentException("Unknown monster think band: " + think);
            if (archetype != null && !MonsterArchetype.known(archetype))
                throw new IllegalArgumentException("Unknown monster archetype: " + archetype);
        }
    }

    private final Map<String, MonsterType> types;
    private final Map<String, Override> overrides;
    private final Map<String, String> generatedFrom;

    MonsterCatalog(Map<String, MonsterType> types, Map<String, Override> overrides,
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

    /** Effective policy: the generated assignment unless the overlay names another one. */
    public MonsterArchetype archetype(MonsterType type) {
        Override tuning = override(type.id());
        String id = tuning != null && tuning.archetype() != null ? tuning.archetype() : type.archetype();
        return MonsterArchetype.of(id);
    }

    /**
     * Runtime fields for a catalogue type: native prepared graph plus scheduling policy and
     * the overlay's explicit knobs. A placement may still override any of these fields.
     */
    public TreeMap<String, String> fields(MonsterType type) {
        var fields = new TreeMap<>(type.spec());
        fields.put("archetype", archetype(type).id());
        Override tuning = override(type.id());
        if (tuning != null) {
            if (tuning.activationRadius() != null)
                fields.put("activation-radius", Float.toString(tuning.activationRadius()));
            if (tuning.leashRadius() != null)
                fields.put("leash-radius", Float.toString(tuning.leashRadius()));
            if (tuning.think() != null) fields.put("think", tuning.think());
        }
        return fields;
    }

    /** Resolve a spawn request, refusing unknown and unsupported types with a clear reason. */
    public MonsterType spawnable(String id) throws IOException {
        MonsterType type = type(id);
        if (type == null) throw new IOException("Unknown monster type: " + id);
        type.requireSpawnable();
        return type;
    }

    /**
     * Resolve an administrative reference without accepting arbitrary config data. Catalogue
     * ids are exact; legacy chat and the stock panel may also use a native description or actor
     * config name, which is matched only when exactly one catalogue type owns it.
     */
    public MonsterType resolveSpawnable(String reference) throws IOException {
        if (reference == null || reference.isBlank() || reference.length() > 512)
            throw new IOException("Enter a monster type ID");
        MonsterType exact = types.get(reference);
        if (exact != null) {
            exact.requireSpawnable();
            return exact;
        }
        var matches = new ArrayList<MonsterType>();
        for (var type : types.values())
            if (type.config().equals(reference) || reference.equals(type.actor())
                    || type.configs().contains(reference))
                matches.add(type);
        if (matches.isEmpty()) throw new IOException("Unknown monster type: " + reference);
        if (matches.size() > 1)
            throw new IOException("Ambiguous monster type reference " + reference + ": "
                + String.join(", ", matches.stream().map(MonsterType::id).toList()));
        MonsterType resolved = matches.getFirst();
        resolved.requireSpawnable();
        return resolved;
    }

    /** Load {@code content/monsters}; an absent folder means no catalogue was prepared. */
    public static MonsterCatalog load(java.nio.file.Path folder) throws IOException {
        return MonsterCatalogReader.load(folder);
    }

    /** Parse without the on-disk fingerprint; tests and tooling use the same validation path. */
    public static MonsterCatalog parse(String json) throws IOException {
        return MonsterCatalogReader.parse(json);
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
            // The overlay and archetype own scheduling only. Placement fields still win.
            var fields = fields(type);
            fields.putAll(entry.spec().fields());
            fields.remove("monster-type");
            entries.add(new SceneContent.Entry(entry.key(), entry.x(), entry.y(), entry.rotation(),
                new SceneContent.Spec(fields)));
        }
        return content.withEntries(entries);
    }
}
