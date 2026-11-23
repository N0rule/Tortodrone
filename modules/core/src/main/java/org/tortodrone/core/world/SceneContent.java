package org.tortodrone.core.world;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import org.tortodrone.core.character.StarterCatalog;

/** Prepared native actor bindings. Runtime instances never depend on client resources. */
public record SceneContent(
        List<Entry> entries,
        Map<String, Float> scales,
        Map<String, SceneConversation> conversations,
        Map<String, Float> stats,
        Map<String, Spec> items,
        Map<String, Spec> cycles,
        Map<String, Spec> statuses,
        List<MapIcon> mapIcons,
        Map<String, Spec> actorTemplates) {
    /** Original 314 has 4.89 MB of bounded actor/action metadata; keep a hard file cap. */
    public static final int MAX_BYTES = 8_000_000;
    /** Fallback player collision and damage masks when a map's stats table omits them;
     * referenced wherever the masks are resolved so the defaults never diverge. */
    public static final float DEFAULT_PLAYER_COLLISION_MASK = 43f, DEFAULT_PLAYER_DAMAGE_MASK = 13f;

    public record MapIcon(String icon, float x, float y) {}

    public SceneContent(
            List<Entry> entries,
            Map<String, Float> scales,
            Map<String, SceneConversation> conversations,
            Map<String, Float> stats,
            Map<String, Spec> items,
            Map<String, Spec> cycles,
            Map<String, Spec> statuses,
            List<MapIcon> mapIcons) {
        this(entries, scales, conversations, stats, items, cycles, statuses, mapIcons, Map.of());
    }

    public SceneContent(
            List<Entry> entries,
            Map<String, Float> scales,
            Map<String, SceneConversation> conversations,
            Map<String, Float> stats,
            Map<String, Spec> items,
            Map<String, Spec> cycles,
            Map<String, Spec> statuses) {
        this(entries, scales, conversations, stats, items, cycles, statuses, List.of());
    }

    public SceneContent(List<Entry> entries, Map<String, Float> scales) {
        this(entries, scales, Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    public static final class Spec {
        private final Map<String, String> fields;
        // Definitions are shared by instances; decoded immutable references are safe to reuse.
        private final java.util.concurrent.ConcurrentMap<String, StarterCatalog.Model> models =
                new java.util.concurrent.ConcurrentHashMap<>();
        // Hot loops (interaction, monster think) read the same authored numbers every tick.
        private final java.util.concurrent.ConcurrentMap<String, Float> numbers =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.ConcurrentMap<String, Integer> integers =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.ConcurrentMap<String, Boolean> flags =
                new java.util.concurrent.ConcurrentHashMap<>();

        public Spec(Map<String, String> fields) {
            this.fields = Map.copyOf(fields);
        }

        public Map<String, String> fields() {
            return fields;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Spec spec && fields.equals(spec.fields);
        }

        @Override
        public int hashCode() {
            return fields.hashCode();
        }

        @Override
        public String toString() {
            return "Spec[fields=" + fields + "]";
        }

        public String text(String key, String fallback) {
            return fields.getOrDefault(key, fallback);
        }

        public float number(String key, float fallback) {
            String raw = fields.get(key);
            if (raw == null) return fallback;
            Float cached = numbers.get(key);
            if (cached != null) return cached;
            float parsed = Float.parseFloat(raw);
            numbers.putIfAbsent(key, parsed);
            return parsed;
        }

        public int integer(String key, int fallback) {
            String raw = fields.get(key);
            if (raw == null) return fallback;
            Integer cached = integers.get(key);
            if (cached != null) return cached;
            int parsed = Integer.parseInt(raw);
            integers.putIfAbsent(key, parsed);
            return parsed;
        }

        public boolean flag(String key, boolean fallback) {
            String raw = fields.get(key);
            if (raw == null) return fallback;
            Boolean cached = flags.get(key);
            if (cached != null) return cached;
            boolean parsed = Boolean.parseBoolean(raw);
            flags.putIfAbsent(key, parsed);
            return parsed;
        }

        public StarterCatalog.Model model(String key) {
            String value = fields.get(key);
            if (value == null || value.equals("null")) return null;
            return models.computeIfAbsent(key, k -> {
                try {
                    return FireCatalog.modelJson(value);
                } catch (IOException invalid) {
                    throw new IllegalArgumentException("Invalid prepared reference: " + key, invalid);
                }
            });
        }

        public StarterCatalog.Model config() {
            if (fields.containsKey("config-json")) return model("config-json");
            var args = new TreeMap<String, Object>();
            for (var e : fields.entrySet())
                if (e.getKey().startsWith("config.argument.")) {
                    String[] v = e.getValue().split("\\|", 3);
                    Object value = switch (v[0]) {
                        case "float" -> Float.parseFloat(v[1]);
                        case "int" -> Integer.parseInt(v[1]);
                        case "boolean" -> Boolean.parseBoolean(v[1]);
                        case "string" -> e.getValue().substring(7);
                        case "model" -> {
                            try {
                                yield FireCatalog.modelJson(e.getValue().substring(6));
                            } catch (IOException invalid) {
                                throw new IllegalArgumentException(invalid);
                            }
                        }
                        case "data" -> {
                            try {
                                yield FireCatalog.modelJson(e.getValue().substring(5))
                                        .arguments()
                                        .get("value");
                            } catch (IOException invalid) {
                                throw new IllegalArgumentException(invalid);
                            }
                        }
                        case "enum" -> {
                            String[] bits = e.getValue().split("\\|", 4);
                            yield new FireCatalog.EnumValue(bits[1], bits[3], Integer.parseInt(bits[2]));
                        }
                        default -> throw new IllegalArgumentException("Unsupported prepared actor argument");
                    };
                    args.put(e.getKey().substring(16), value);
                }
            return new StarterCatalog.Model(text("config", ""), args);
        }

        public Spec child() {
            var child = new TreeMap<String, String>();
            fields.forEach((k, v) -> {
                if (k.startsWith("child.")) child.put(k.substring(6), v);
            });
            return new Spec(child);
        }

        public Spec section(String prefix) {
            var section = new TreeMap<String, String>();
            fields.forEach((k, v) -> {
                if (k.startsWith(prefix)) section.put(k.substring(prefix.length()), v);
            });
            return new Spec(section);
        }

        public boolean contains(float cx, float cy, float x, float y, float radius) {
            return contains(cx, cy, 0, x, y, radius);
        }

        public boolean contains(float cx, float cy, float rotation, float x, float y, float radius) {
            float dx = x - cx, dy = y - cy, c = (float) Math.cos(rotation), s = (float) Math.sin(rotation);
            float tx = dx * c + dy * s, ty = -dx * s + dy * c;
            if (flag("interaction-area", false) || text("shape", "").equals("Compound")) {
                var shape = model("source-shape");
                if (shape != null)
                    return SceneShape.intersects(shape.arguments().get("value"), tx, ty, radius);
            }
            if (fields.containsKey("radius")) return Math.hypot(cx - x, cy - y) <= number("radius", 0) + radius;
            float gapX = Math.max(
                    number("bounds-min-x", -number("width", 0) / 2) - tx,
                    Math.max(0, tx - number("bounds-max-x", number("width", 0) / 2)));
            float gapY = Math.max(
                    number("bounds-min-y", -number("height", 0) / 2) - ty,
                    Math.max(0, ty - number("bounds-max-y", number("height", 0) / 2)));
            return gapX * gapX + gapY * gapY <= radius * radius;
        }
    }

    public record Entry(String key, float x, float y, float rotation, Spec spec) {}

    public SceneContent {
        entries = List.copyOf(entries);
        scales = Map.copyOf(scales);
        conversations = Map.copyOf(conversations);
        stats = Map.copyOf(stats);
        items = Map.copyOf(items);
        cycles = Map.copyOf(cycles);
        statuses = Map.copyOf(statuses);
        mapIcons = List.copyOf(mapIcons);
        actorTemplates = Map.copyOf(actorTemplates);
        SceneActorTemplates.validate(entries, actorTemplates);
    }

    public static SceneContent empty() {
        return new SceneContent(List.of(), Map.of());
    }
    /** Replace actor entries while retaining every other prepared scene table. */
    public SceneContent withEntries(List<Entry> replacement) {
        return new SceneContent(
                replacement, scales, conversations, stats, items, cycles, statuses, mapIcons, actorTemplates);
    }
    /** Prepared monster spawn fields omit the native config's impulse level. Restore the
     * installed client's level from the shared table so damage impulses respect it. */
    public Spec actor(Spec spec) {
        if (!spec.text("kind", "").equals("Monster") || spec.fields().containsKey("impulse-level")) return spec;
        Float level = stats.get("monster." + spec.text("config", "") + ".impulse-level");
        if (level == null) return spec;
        var fields = new TreeMap<>(spec.fields());
        fields.put("impulse-level", Float.toString(level));
        return new Spec(fields);
    }
    /** Placed NPCs own one actor binding; scene-started dialogues require reachable authored actions. */
    public void validateConversations() throws IOException {
        var pending = new ArrayDeque<String>();
        var reached = new HashSet<String>();
        for (var entry : entries)
            if (entry.spec().text("kind", "").equals("SceneTrigger"))
                pending.addAll(
                        SceneActions.conversationIds(SceneActions.rule(entry).action()));
            else pending.addAll(SceneScript.conversations(entry.spec()));
        for (var conversation : conversations.values())
            if (!conversation.actor().startsWith("@conversation/")) {
                long count = entries.stream()
                        .filter(e -> !Set.of("SceneTrigger", "SceneAnchor")
                                        .contains(e.spec().text("kind", ""))
                                && e.spec()
                                        .text("dialogue", e.spec().text("config", ""))
                                        .equals(conversation.actor()))
                        .count();
                if (count != 1)
                    throw new IOException("Missing or ambiguous NPC actor binding: " + conversation.actor());
                pending.add(conversation.actor());
            }
        while (!pending.isEmpty()) {
            String id = pending.removeFirst();
            if (!reached.add(id)) continue;
            var definition = conversations.get(id);
            if (definition == null) throw new IOException("Missing scripted conversation: " + id);
            if (definition.initialAction() != null)
                pending.addAll(SceneActions.conversationIds(definition.initialAction()));
            for (var node : definition.nodes().values())
                for (var response : node.responses())
                    if (response.action() != null) pending.addAll(SceneActions.conversationIds(response.action()));
        }
        for (var conversation : conversations.values())
            if (conversation.actor().startsWith("@conversation/") && !reached.contains(conversation.actor()))
                throw new IOException("Unreferenced scripted conversation: " + conversation.actor());
    }
    /** Read prepared content for one map; the loader owns the format and hash check. */
    public static SceneContent load(Path folder, String layoutHash) throws IOException {
        return SceneContentLoader.load(folder, layoutHash);
    }

    /** Parse already-loaded prepared properties; used by import and validation paths. */
    public static SceneContent load(Properties properties, String layoutHash) throws IOException {
        return SceneContentLoader.load(properties, layoutHash);
    }
}
