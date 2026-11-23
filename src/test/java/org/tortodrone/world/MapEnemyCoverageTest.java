package org.tortodrone.world;

import static org.tortodrone.support.TestWire.check;

import java.nio.file.*;
import java.util.*;
import org.tortodrone.core.world.MonsterCatalog;
import org.tortodrone.core.world.MonsterType;
import org.tortodrone.core.world.SceneActions;
import org.tortodrone.core.world.SceneContent;
import org.tortodrone.resources.ContentManager;
import org.tortodrone.resources.ContentRegistry;

/**
 * Every shipped map's hostile monster placement, not just Crash Site: native configs resolve to
 * the generated catalogue, the migrated Laboratory placement expands from its type, training
 * dummies stay authored embedded targets and towns/Hall of Heroes carry no hostiles.
 */
public final class MapEnemyCoverageTest {
    private static final String TRAINING_CUBE = "Character/NPC/Advanced Training Gym/Training Cube";
    private static final String SPIRAL_KNIGHT =
            "Character/Scripted/Missions/Tutorial/Final Test/Knight Squad/Spiral Knight";
    private static final String SCORCHER =
            "Character/Scripted/Missions/Tutorial/Ancient Generator/Mini Boss/Scorcher, Intro Conversation";
    private static final String SNARBOLAX_PART = "Character/NPC/Monster/Boss/Snarbolax/Parts/Snarbolax, Watcher";

    private MapEnemyCoverageTest() {}

    public static void main(String[] args) throws Exception {
        var content = ContentManager.load(Path.of("."));
        var catalog = MonsterCatalog.load(Path.of("content/monsters"));
        var scenes = content.scenes().scenes();
        check(scenes.size() == 17, "all seventeen shipped scenes load");
        var totals = new TreeMap<String, Integer>();
        for (var scene : scenes) {
            var placements = placements(scene);
            var expectedTypes = new TreeSet<String>();
            for (var placement : placements) {
                String config = placement.config();
                if (scene.id() == 398) totals.merge(config, 1, Integer::sum);
                if (config.equals(TRAINING_CUBE)) {
                    check(scene.id() == 294, "training cubes stay in Advanced Training");
                    check(
                            placement.fields().get("child.attack").equals("0")
                                    && placement.fields().get("child.health").equals("30"),
                            "training cube keeps its inert authored target stats");
                    var cubeTypes = resolve(catalog, config);
                    check(
                            cubeTypes.size() == 1
                                    && cubeTypes.getFirst().id().equals("shufflebot")
                                    && cubeTypes.getFirst().supported(),
                            "the gym cube is the supported Shufflebot actor");
                } else if (config.equals(SPIRAL_KNIGHT)) {
                    check(
                            scene.id() == 398
                                    && placements.stream()
                                                    .filter(p -> p.config().equals(SPIRAL_KNIGHT))
                                                    .count()
                                            == 6,
                            "the six scripted tutorial allies stay in Crash Site");
                    check(resolve(catalog, config).isEmpty(), "the scripted ally is not a spawnable monster type");
                } else if (config.equals(SNARBOLAX_PART)) {
                    // Snarbolax parts are authored live boss pieces on its mission floors, not
                    // independently spawnable catalogue monsters.
                    check(
                            scene.id() == 380 || scene.id() == 382 || scene.id() == 384,
                            "Snarbolax parts stay on the Shadow Wildwoods floors");
                    check(
                            placements.stream()
                                            .filter(p -> p.config().equals(SNARBOLAX_PART))
                                            .count()
                                    == (scene.id() == 380 ? 3 : scene.id() == 382 ? 2 : 1),
                            "each Shadow Wildwoods floor keeps its authored Snarbolax part count");
                    check(resolve(catalog, config).isEmpty(), "a boss part is not a spawnable monster type");
                } else if (config.startsWith("Character/Scripted/")) {
                    check(
                            scene.id() == 398 || scene.id() == 337,
                            "scripted story actors stay in their authored mission scene: " + config);
                    if (scene.id() == 398)
                        check(
                                config.startsWith("Character/Scripted/Missions/Tutorial/Crashsite/"),
                                "Crash Site mini-boss knights keep their authored identities: " + config);
                    else
                        check(
                                config.equals(SCORCHER),
                                "the Ancient Generator intro mini-boss keeps its scripted identity: " + config);
                    check(resolve(catalog, config).isEmpty(), "a scripted story actor is not a spawnable monster type");
                } else {
                    var types = resolve(catalog, config);
                    check(types.size() == 1, "one catalogue type owns " + config);
                    check(
                            types.getFirst().supported(),
                            "placed monster is catalogue-supported: "
                                    + types.getFirst().id());
                    expectedTypes.add(types.getFirst().id());
                }
            }
            if (scene.town()) {
                if (scene.id() == 218) {
                    // Rescue Camp state 2 is a defended town: four scripted Retrode attackers
                    // arrive from its authored waves. They are real catalogue monsters.
                    check(
                            placements.size() == 4
                                    && placements.stream()
                                            .allMatch(p ->
                                                    p.config().startsWith("Character/NPC/Monster/Construct/Retrode/")),
                            "Rescue Camp state 2 keeps its four scripted Retrode attackers");
                } else check(placements.isEmpty(), "town scene has no hostile placements: " + scene.name());
            }
            if (scene.id() == 318) check(placements.isEmpty(), "Hall of Heroes has no hostile placements");
            if (scene.id() == 351) check(placements.isEmpty(), "the Mission Lobby has no hostile placements");
            if (scene.id() == 398)
                check(
                        placements.size() == 23,
                        "Crash Site keeps fourteen hostile spawns and nine scripted story actors");
            if (scene.id() == 294)
                check(placements.size() == 11, "Advanced Training keeps eleven authored training dummies");
            if (scene.id() == 30893)
                check(expectedTypes.equals(Set.of("companion_cube")), "Laboratory carries only the Companion Cube");
        }
        check(
                totals.getOrDefault("Character/NPC/Monster/Slime/Jelly Cube (Intro)", 0) == 5
                        && totals.getOrDefault("Character/NPC/Monster/Beast/Chromalisk/Chromalisk Whelp (Intro)", 0)
                                == 2
                        && totals.getOrDefault("Character/NPC/Monster/Construct/Gun Puppy/Gun Puppy (TG)", 0) == 6
                        && totals.getOrDefault("Character/NPC/Monster/Boss/Tutorial, Ironwood Giant/Ironwood Giant", 0)
                                == 1,
                "Crash Site's four hostile identities keep their stock counts");
        laboratoryExpansion(content, catalog);
        rescueCampTurrets(content, catalog);
        embeddedScheduling(content.scenes().scene(398), catalog);
        reachableLevels(content);
        System.out.println(
                "PASS: 17 maps audited; Crash Site, Advanced Training and the Rescue Camp keep authored placements, Laboratory is catalogue-expanded and ordinary towns/Hall of Heroes/Mission Lobby stay empty");
    }

    /**
     * Rescue Camp state 2 carries two native <code>Monster</code> actors whose configs are authored
     * auto-turrets rather than spawnable characters. They are ally props, not catalogue monsters.
     */
    private static void rescueCampTurrets(ContentRegistry content, MonsterCatalog catalog) throws Exception {
        var camp = content.scenes().scene(218);
        var turrets = new ArrayList<SceneContent.Entry>();
        for (var entry : camp.content().entries())
            if ("Monster".equals(entry.spec().text("kind", ""))
                    && entry.spec().text("config", "").startsWith("Dynamic/")) turrets.add(entry);
        check(turrets.size() == 2, "Rescue Camp state 2 keeps its two authored auto-turrets");
        for (var turret : turrets) {
            String config = turret.spec().text("config", "");
            check(
                    config.equals(
                            "Dynamic/Energy-Consuming Objects/Crates/Turret, Auto - Railgun/Railgun (Spiral Order) rescue camp"),
                    "the auto-turret keeps its native dynamic config");
            check(
                    turret.spec().text("config.argument.Tags", "").contains("faction_ally"),
                    "the auto-turret stays an allied prop");
            check(resolve(catalog, config).isEmpty(), "a dynamic auto-turret is not a spawnable monster type");
        }
    }

    private static void laboratoryExpansion(ContentRegistry content, MonsterCatalog catalog) throws Exception {
        var laboratory = content.scenes().scene(30893);
        var cube = laboratory.content().entries().stream()
                .filter(e -> "Monster".equals(e.spec().text("kind", "")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Laboratory Companion Cube is missing"));
        check(cube.spec().text("monster-type", "").isEmpty(), "the catalogue reference is consumed before publication");
        check(
                cube.spec().integer("attack.0.land", -1) == 1322 && cube.spec().integer("attack.0.clear", -1) == 2226,
                "the Companion Cube keeps its native attack graph");
        check(
                cube.spec()
                        .text("archetype", "")
                        .equals(catalog.archetype(catalog.type("companion_cube"))
                                .id()),
                "the migrated placement carries its catalogue scheduling policy");
    }

    /**
     * Crash Site and Advanced Training are deliberately not migrated yet: their placements keep
     * embedded native graphs and the documented fallback policy. This pins that boundary so a
     * future migration changes this test on purpose.
     */
    private static void embeddedScheduling(org.tortodrone.core.world.MapScene crash, MonsterCatalog catalog)
            throws Exception {
        var embedded = new ArrayList<Placement>();
        for (var entry : crash.content().entries()) addPlacements(entry, embedded);
        check(
                embedded.stream()
                        .filter(p -> p.config().startsWith("Character/NPC/Monster/"))
                        .allMatch(p -> p.fields().getOrDefault("archetype", "").isEmpty()),
                "Crash Site hostiles stay on the documented default scheduling fallback");
        var archetypes = new TreeSet<String>();
        for (var placement : embedded)
            for (var type : resolve(catalog, placement.config()))
                archetypes.add(catalog.archetype(type).id());
        check(
                archetypes.equals(Set.of("melee-chaser", "ranged-kiter")),
                "the official catalogue already distinguishes the placed Crash Site families: " + archetypes);
    }

    /** Both secondary levels are ordinary Haven links, not mission-only content. */
    private static void reachableLevels(ContentRegistry content) throws Exception {
        var east = content.scenes().scene(445);
        check(
                east.portals().triggers().stream()
                                .anyMatch(p ->
                                        p.destination() == 318 && p.entrance().equals("level_start"))
                        && east.portals().triggers().stream()
                                .anyMatch(p ->
                                        p.destination() == 30893 && p.entrance().equals("level_start")),
                "Haven Eastside links to Hall of Heroes and the Laboratory");
        for (int id : new int[] {318, 30893}) {
            var scene = content.scenes().scene(id);
            check(
                    scene.portals().triggers().stream()
                            .anyMatch(p -> p.destination() == 445
                                    && p.entrance().equals("from_" + (id == 318 ? "hoh" : "lab"))),
                    "scene " + id + " returns to Haven Eastside");
        }
    }

    private static List<Placement> placements(org.tortodrone.core.world.MapScene scene) throws Exception {
        var placements = new ArrayList<Placement>();
        for (var entry : scene.content().entries()) addPlacements(entry, placements);
        return placements;
    }

    private static void addPlacements(SceneContent.Entry entry, List<Placement> placements) throws Exception {
        var spec = entry.spec();
        if ("Monster".equals(spec.text("kind", "")) && spec.text("config", "").startsWith("Character/"))
            placements.add(new Placement(spec.text("config", ""), spec.fields()));
        if ("Monster".equals(spec.text("child.kind", ""))
                && spec.text("child.config", "").startsWith("Character/"))
            placements.add(new Placement(spec.text("child.config", ""), spec.fields()));
        if (spec.text("action", "").isEmpty()) return;
        for (var fields : spawnFields(SceneActions.rule(entry).action()))
            if ("Monster".equals(fields.get("kind"))
                    && fields.getOrDefault("config", "").startsWith("Character/"))
                placements.add(new Placement(fields.get("config"), fields));
    }

    private static List<MonsterType> resolve(MonsterCatalog catalog, String config) {
        var matches = new ArrayList<MonsterType>();
        for (var type : catalog.types().values())
            if (type.config().equals(config) || type.configs().contains(config)) matches.add(type);
        return matches;
    }

    static List<Map<String, String>> spawnFields(Object action) throws Exception {
        if (action instanceof SceneActions.Spawn spawn) return List.of(spawn.fields());
        var found = new ArrayList<Map<String, String>>();
        if (action instanceof Record record)
            for (var component : record.getClass().getRecordComponents()) {
                Object value = component.getAccessor().invoke(record);
                if (value instanceof SceneActions.Action child) found.addAll(spawnFields(child));
                else if (value instanceof List<?> list) for (Object child : list) found.addAll(spawnFields(child));
            }
        return found;
    }

    private record Placement(String config, Map<String, String> fields) {}
}
