package org.tortodrone.world;

import java.util.List;
import java.util.Map;

/**
 * One immutable, generated monster definition from {@code content/monsters/catalog.json}.
 *
 * <p>The record carries native identity and the prepared attack fields exported from the installed
 * client. It owns no mutable state: every scene instance shares one type.
 */
public record MonsterType(
        String id,
        String display,
        String family,
        String config,
        String actor,
        List<String> configs,
        String archetype,
        boolean supported,
        List<String> capabilities,
        List<String> unsupported,
        Map<String, String> spec) {
    public MonsterType {
        if (id == null || !id.matches("[a-z0-9][a-z0-9_]{0,95}"))
            throw new IllegalArgumentException("Invalid monster type id");
        if (display == null || display.isBlank() || display.length() > 256)
            throw new IllegalArgumentException("Invalid monster display name");
        if (family == null || family.isBlank() || family.length() > 64)
            throw new IllegalArgumentException("Invalid monster family");
        if (config == null || config.isBlank() || config.length() > 512)
            throw new IllegalArgumentException("Invalid monster description config");
        if (actor != null && (actor.isBlank() || actor.length() > 512))
            throw new IllegalArgumentException("Invalid monster actor config");
        if (!MonsterArchetype.known(archetype))
            throw new IllegalArgumentException("Invalid monster archetype: " + archetype);
        configs = List.copyOf(configs);
        if (supported && (actor == null || !configs.contains(actor)))
            throw new IllegalArgumentException("Supported monster actor is not one of its native configs: " + id);
        capabilities = List.copyOf(capabilities);
        unsupported = List.copyOf(unsupported);
        spec = Map.copyOf(spec);
        if (supported && (!unsupported.isEmpty() || spec.isEmpty() || !"Monster".equals(spec.get("kind"))))
            throw new IllegalArgumentException("Supported monster type needs a prepared spec and no refusals: " + id);
        if (!supported && unsupported.isEmpty())
            throw new IllegalArgumentException("Unsupported monster type must state why: " + id);
    }

    /** Fail-closed spawn check used by placement resolution and administrative tools. */
    public void requireSpawnable() throws java.io.IOException {
        if (!supported) throw new java.io.IOException(
            "Unsupported monster type " + id + ": " + String.join(", ", unsupported));
        if (actor == null) throw new java.io.IOException("Monster type has no native actor config: " + id);
    }
}
