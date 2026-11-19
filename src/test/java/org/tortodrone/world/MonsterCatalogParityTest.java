package org.tortodrone.world;

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.tortodrone.resources.MapYaml;
import static org.tortodrone.support.TestWire.check;

/**
 * Phase 2 parity: a scene placement resolves its attack graph from the generated catalogue while
 * keeping its native identity, geometry, health and per-placement tuning exactly as prepared.
 */
public final class MonsterCatalogParityTest {
    private MonsterCatalogParityTest() { }

    public static void main(String[] args) throws Exception {
        var catalog = MonsterCatalog.load(Path.of("content/monsters"));
        var prepared = MapYaml.prepare(Path.of("content/maps/laboratory"), Path.of("content/shared"));
        var content = SceneContent.load(prepared.content(), prepared.descriptor().getProperty("scene-sha256"));
        var entry = content.entries().stream()
            .filter(candidate -> candidate.spec().text("monster-type", "").equals("companion_cube"))
            .findFirst().orElseThrow(() -> new AssertionError("laboratory Companion Cube placement is missing"));
        check(entry.spec().text("monster-type", "").equals("companion_cube"),
            "the migrated placement names its catalogue type");
        catalog.validateScene("laboratory", content);
        var expanded = catalog.expand(content);
        var type = catalog.type("companion_cube");
        var spec = expanded.entries().stream()
            .filter(candidate -> candidate.key().equals(entry.key())).findFirst().orElseThrow().spec();
        check(!spec.fields().containsKey("monster-type"), "the reference is consumed, not left as a runtime field");

        // Identity and per-placement tuning stay authoritative over the type.
        check(spec.text("config", "").equals("Character/NPC/Monster/Slime/Companion Cube/Companion Cube 1"),
            "the placement keeps its native actor config");
        check(spec.text("kind", "").equals("Monster"), "the placement stays a Monster actor");
        check(spec.text("native-type", "").equals("com.threerings.projectx.dungeon.data.actor.Monster"),
            "the placement keeps its native actor type");
        check(spec.integer("health", -1) == 60, "placement health is not replaced by the type");
        check(spec.text("health-scale", "").equals("Monster/Health/Health Base +05"), "placement health scale is retained");
        check(Math.abs(spec.number("speed", 0) - 6f) < .00001f, "placement speed is retained");
        check(Math.abs(spec.number("radius", 0) - .4f) < .00001f, "placement geometry is retained");
        check(Math.abs(spec.number("x", 0) - 9.783711f) < .00001f && Math.abs(spec.number("y", 0) - 41.307983f) < .00001f,
            "placement position is retained");

        // The catalogue supplies the native attack graph and timings. These are the values the
        // placement embedded before the migration; the test is the Phase 2 parity contract.
        check(spec.text("attacks", "").equals("0"), "catalogue attack index parity");
        check(spec.integer("attack.0.land", -1) == 1322, "catalogue attack land parity");
        check(spec.integer("attack.0.clear", -1) == 2226, "catalogue attack clear parity");
        check(Math.abs(spec.number("attack.0.fire-speed", 0) - 1.5f) < .00001f, "catalogue fire speed parity");
        check(spec.integer("attack.0.fire.0.delay", -1) == 150, "catalogue fire delay parity");
        check(spec.text("attack.0.fires", "").equals("0"), "catalogue fire index parity");
        check(spec.text("attack.0.fire.0.action", "").equals(type.spec().get("attack.0.fire.0.action")),
            "the fire action graph comes from the catalogue unchanged");
        // The committed placement omitted the exporter's blended flag; keep the historical engine
        // behaviour until a dedicated gameplay review adopts the native value.
        check(!spec.flag("attack.0.blended", true), "the placement override preserves the committed attack blending");

        // Unknown, unsupported and mismatched references stay fail-closed.
        expectRefusal(catalog, content, "missing_type", "unknown type");
        expectRefusal(catalog, content, "slime_tentacle", "actor config outside the type");
        check(catalog.type("companion_cube").configs().contains(spec.text("config", "")),
            "the catalogue lists the placement actor config");

        System.out.println("PASS: catalogue-backed placement expansion preserves native identity, geometry, health and attack parity while refusing unknown or mismatched types");
    }

    private static void expectRefusal(MonsterCatalog catalog, SceneContent content, String id, String message) throws Exception {
        var entries = new java.util.ArrayList<SceneContent.Entry>();
        for (var entry : content.entries()) {
            if (!entry.spec().text("monster-type", "").equals("companion_cube")) { entries.add(entry); continue; }
            var fields = new TreeMap<String, String>(entry.spec().fields());
            fields.put("monster-type", id);
            entries.add(new SceneContent.Entry(entry.key(), entry.x(), entry.y(), entry.rotation(),
                new SceneContent.Spec(fields)));
        }
        try {
            catalog.validateScene("laboratory", content.withEntries(entries));
            throw new AssertionError("Placement reference accepted: " + message);
        } catch (java.io.IOException expected) { }
    }
}
