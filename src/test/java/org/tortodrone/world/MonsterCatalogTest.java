package org.tortodrone.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import org.tortodrone.resources.Json;
import static org.tortodrone.support.TestWire.check;

/** Generated catalogue contract: format, fingerprints, fail-closed refusals and overlay limits. */
public final class MonsterCatalogTest {
    private MonsterCatalogTest() { }

    public static void main(String[] args) throws Exception {
        var shipped = MonsterCatalog.load(Path.of("content/monsters"));
        check(shipped.size() == 253, "the stock roster catalogues all 253 spawnable descriptions");
        long supported = shipped.types().values().stream().filter(MonsterType::supported).count();
        check(supported >= 152, "exporter coverage clears the 60% Phase 0 gate: " + supported);
        for (var type : shipped.types().values()) {
            check(type.id().matches("[a-z0-9][a-z0-9_]{0,95}"), "stable type id: " + type.id());
            check(!type.display().isBlank() && !type.family().isBlank(), "identity is complete: " + type.id());
            if (type.supported()) {
                check(type.unsupported().isEmpty(), "supported types carry no refusal: " + type.id());
                check("Monster".equals(type.spec().get("kind")), "supported type carries a prepared monster spec: " + type.id());
                check(type.actor() != null, "supported type resolves a native actor: " + type.id());
                type.requireSpawnable();
            } else {
                check(!type.unsupported().isEmpty(), "unsupported types state the failing kinds: " + type.id());
                try { type.requireSpawnable(); throw new AssertionError("Unsupported spawn accepted: " + type.id()); }
                catch (IOException expected) { }
            }
        }
        check(shipped.type("darkfang_demo").family().equals("Gremlin"), "family comes from the native description path");
        check(shipped.type("royal_jelly").display().equals("Royal Jelly"), "display name comes from monster.properties");
        check(shipped.type("golden_dream_wisp").display().equals("Golden Dream Wisp"), "composite display keys resolve");

        Path temporary = Files.createTempDirectory("tortodrone-monster-catalog-");
        try {
            var document = fixture("1", supportedType("fixture_melee"), unsupportedType());
            write(temporary, document, null);
            var catalog = MonsterCatalog.load(temporary);
            check(catalog.type("fixture_melee").display().equals("Fixture Melee"), "catalogue round-trips generated identity");
            check(catalog.type("fixture_melee").spec().get("health").equals("120"), "prepared fields round-trip");
            check(catalog.spawnable("fixture_melee").id().equals("fixture_melee"), "supported type resolves for spawning");
            try { catalog.spawnable("fixture_broken"); throw new AssertionError("Unsupported type resolved for spawning"); }
            catch (IOException refused) { check(refused.getMessage().contains("DamageTarget"), "refusal names the unsupported action kind"); }
            try { catalog.spawnable("missing_type"); throw new AssertionError("Unknown type resolved"); }
            catch (IOException expected) { }

            var overrides = temporary.resolve("overrides.yml");
            Files.writeString(overrides, "monsters:\n  fixture_melee:\n    activation-radius: 12\n    leash-radius: 20\n    think: alert\n    archetype: melee-chaser\n");
            var tuned = MonsterCatalog.load(temporary);
            check(tuned.override("fixture_melee").activationRadius() == 12f, "overlay activation radius loads");
            check(tuned.override("fixture_melee").leashRadius() == 20f, "overlay leash radius loads");
            check(tuned.override("fixture_melee").think().equals("alert"), "overlay think band loads");
            Files.writeString(overrides, "monsters:\n  missing_type:\n    activation-radius: 12\n");
            refuse(temporary, "unknown override type");
            Files.writeString(overrides, "monsters:\n  fixture_melee:\n    health: 999\n");
            refuse(temporary, "override may not carry native stats");
            Files.writeString(overrides, "monsters:\n  fixture_melee:\n    activation-radius: 9\n    leash-radius: 4\n");
            refuse(temporary, "leash smaller than activation");
            Files.writeString(overrides, "monsters:\n  fixture_melee:\n    activation-radius: 0\n");
            refuse(temporary, "out of range activation");
            Files.writeString(overrides, "monsters:\n  fixture_melee:\n    think: asleep\n");
            refuse(temporary, "unknown think band");
            Files.delete(overrides);

            write(temporary, fixture("2", supportedType("fixture_melee"), unsupportedType()), null);
            refuse(temporary, "unknown catalogue format");
            write(temporary, fixture("1", supportedType("fixture_melee"), unsupportedType()), "0".repeat(64));
            refuse(temporary, "catalogue fingerprint mismatch");
            write(temporary, fixture("1", supportedType("fixture_melee"), unsupportedType()), null);

            var supportedConflict = supportedType("fixture_melee");
            supportedConflict.put("unsupported", List.of("DamageTarget"));
            refuse(temporary, fixture("1", supportedConflict, unsupportedType()), null, "supported type with refusals");
            var unsupportedEmpty = unsupportedType();
            unsupportedEmpty.put("unsupported", List.of());
            refuse(temporary, fixture("1", supportedType("fixture_melee"), unsupportedEmpty), null, "unsupported type without a reason");
            var badId = supportedType("Bad Id");
            refuse(temporary, fixture("1", badId, unsupportedType()), null, "invalid type id");
            var foreignActor = supportedType("fixture_melee");
            @SuppressWarnings("unchecked")
            var foreignFields = (Map<String, Object>) foreignActor.get("fixture_melee");
            foreignFields.put("actor", "Character/Other");
            refuse(temporary, fixture("1", foreignActor, unsupportedType()), null, "supported actor outside its configs");

            write(temporary, fixture("1", supportedType("fixture_melee"), unsupportedType()), null);
            catalog = MonsterCatalog.load(temporary);
            var fields = new TreeMap<>(catalog.type("fixture_melee").spec());
            fields.put("monster-type", "fixture_melee");
            var content = new SceneContent(List.of(new SceneContent.Entry("1", 0, 0, 0, new SceneContent.Spec(fields))), Map.of());
            catalog.validateScene("fixture", content);
            var alternative = new TreeMap<>(fields);
            alternative.put("config", "Character/NPC/Monster/Fixture/Melee Alt");
            catalog.validateScene("fixture", new SceneContent(List.of(new SceneContent.Entry("1", 0, 0, 0, new SceneContent.Spec(alternative))), Map.of()));
            var missing = new TreeMap<>(fields);
            missing.put("monster-type", "missing_type");
            expectSceneRefusal(catalog, new SceneContent.Spec(missing), "unknown placement type");
            var broken = new TreeMap<>(fields);
            broken.put("monster-type", "fixture_broken");
            expectSceneRefusal(catalog, new SceneContent.Spec(broken), "unsupported placement type");
            var wrongKind = new TreeMap<>(fields);
            wrongKind.put("kind", "Pickup");
            expectSceneRefusal(catalog, new SceneContent.Spec(wrongKind), "monster-type on a non-monster actor");
            var wrongActor = new TreeMap<>(fields);
            wrongActor.put("config", "Character/Other");
            expectSceneRefusal(catalog, new SceneContent.Spec(wrongActor), "actor identity differs from catalogue");
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("PASS: stock roster, generated format/fingerprint validation, fail-closed unsupported/unknown types, immutable prepared specs, overlay limits and type-backed placement validation");
    }

    private static void expectSceneRefusal(MonsterCatalog catalog, SceneContent.Spec spec, String message) throws IOException {
        var content = new SceneContent(List.of(new SceneContent.Entry("1", 0, 0, 0, spec)), Map.of());
        try { catalog.validateScene("fixture", content); throw new AssertionError("Scene placement accepted: " + message); }
        catch (IOException expected) { }
    }

    private static Map<String, Object> supportedType(String id) {
        var type = new LinkedHashMap<String, Object>();
        type.put("config", "Monster/Fixture/Melee");
        type.put("display", "Fixture Melee");
        type.put("family", "Gremlin");
        type.put("archetype", "melee-chaser");
        type.put("supported", true);
        type.put("capabilities", List.of());
        type.put("unsupported", List.of());
        type.put("actor", "Character/NPC/Monster/Fixture/Melee");
        type.put("configs", List.of("Character/NPC/Monster/Fixture/Melee", "Character/NPC/Monster/Fixture/Melee Alt"));
        var spec = new TreeMap<String, String>();
        spec.put("config", "Character/NPC/Monster/Fixture/Melee");
        spec.put("kind", "Monster");
        spec.put("native-type", "com.threerings.projectx.dungeon.data.actor.Monster");
        spec.put("health", "120");
        type.put("spec", spec);
        var types = new LinkedHashMap<String, Object>();
        types.put(id, type);
        return types;
    }

    private static Map<String, Object> unsupportedType() {
        var type = new LinkedHashMap<String, Object>();
        type.put("config", "Monster/Fixture/Broken");
        type.put("display", "Fixture Broken");
        type.put("family", "Undead");
        type.put("archetype", "melee-chaser");
        type.put("supported", false);
        type.put("capabilities", List.of());
        type.put("unsupported", List.of("com.threerings.projectx.dungeon.config.DungeonActionConfig$DamageTarget"));
        type.put("configs", List.of("Character/NPC/Monster/Fixture/Broken"));
        var types = new LinkedHashMap<String, Object>();
        types.put("fixture_broken", type);
        return types;
    }

    private static Map<String, Object> fixture(String format, Map<String, Object> first, Map<String, Object> second) {
        var types = new TreeMap<String, Object>();
        types.putAll(first);
        types.putAll(second);
        var document = new LinkedHashMap<String, Object>();
        document.put("format", Integer.parseInt(format));
        document.put("generatedFrom", Map.of("actorSha256", "1".repeat(64), "descriptionSha256", "2".repeat(64)));
        document.put("types", types);
        return document;
    }

    private static void write(Path folder, Map<String, Object> document, String hash) throws Exception {
        Path catalog = folder.resolve("catalog.json");
        Files.writeString(catalog, Json.stringify(document) + "\n");
        Files.writeString(folder.resolve("catalog.sha256"), (hash == null ? digest(catalog) : hash) + "\n");
    }

    private static void refuse(Path folder, String message) throws Exception {
        refuse(folder, null, null, message);
    }

    private static void refuse(Path folder, Map<String, Object> document, String hash, String message) throws Exception {
        if (document != null) write(folder, document, hash);
        try { MonsterCatalog.load(folder); throw new AssertionError("Catalogue accepted: " + message); }
        catch (IOException expected) { }
    }

    private static String digest(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
