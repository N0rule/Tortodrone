package org.tortodrone.world;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import org.tortodrone.core.character.StarterCatalog;
import static org.tortodrone.support.TestWire.check;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.core.world.SceneContent;
import org.tortodrone.core.world.MonsterCatalog;
import org.tortodrone.core.world.MonsterType;
import org.tortodrone.core.world.MonsterArchetype;

/**
 * Phase 9: six scheduling policies, one behaviour check per archetype, generated per-type
 * assignment and the explicitly capped boss-scripted escape hatch.
 */
public final class MonsterArchetypeTest {
    private MonsterArchetypeTest() { }

    public static void main(String[] args) throws Exception {
        policies();
        meleeChaser();
        rangedKiter();
        bomber();
        summoner();
        stationary();
        bossScripted();
        assignment();
        bossCap();
        unknownPolicy();
        System.out.println("PASS: six archetype policies schedule independently, assignment covers every role and boss scripts stay explicitly capped");
    }

    private static void policies() {
        check(MonsterArchetype.all().size() == 6, "exactly six scheduling policies are registered");
        var ids = MonsterArchetype.all().stream().map(MonsterArchetype::id).toList();
        check(new HashSet<>(ids).size() == ids.size(), "archetype ids are unique");
        check(MonsterArchetype.fallback().id().equals(MonsterArchetype.DEFAULT_ID), "the fallback is the documented melee policy");
        for (var archetype : MonsterArchetype.all()) {
            check(archetype.activationRadius() >= 1 && archetype.activationRadius() <= 512,
                "activation radius is bounded: " + archetype.id());
            check(archetype.leashRadius() == 0 || archetype.leashRadius() >= archetype.activationRadius(),
                "a leash never starts inside the activation radius: " + archetype.id());
            check(SceneMonsterBand.DORMANT.thinkInterval(1, archetype) >= 100,
                "dormant cadence is bounded: " + archetype.id());
            check(SceneMonsterBand.ALERT.thinkInterval(1, archetype) == archetype.alertThinkMillis(),
                "alert cadence comes from the policy: " + archetype.id());
            check(SceneMonsterBand.ENGAGED.thinkInterval(1, archetype) == archetype.engagedThinkMillis(),
                "engaged cadence comes from the policy: " + archetype.id());
        }
    }

    private static void meleeChaser() throws Exception {
        wakes(MonsterArchetype.DEFAULT_ID, 32, 0);
    }

    private static void rangedKiter() throws Exception {
        wakes("ranged-kiter", 40, 0);
        var world = scene("ranged-kiter");
        check(world.archetype(world.actors().getFirst().id()).alertThinkMillis() == 200,
            "ranged monsters reacquire faster than the melee default");
    }

    private static void bomber() throws Exception {
        wakes("bomber", 20, 24);
        var world = scene("bomber");
        check(world.archetype(world.actors().getFirst().id()).repathMillis() == 250,
            "bombers re-plan pursuit on their own cadence");
    }

    private static void summoner() throws Exception {
        wakes("summoner", 48, 0);
        var world = scene("summoner");
        check(world.archetype(world.actors().getFirst().id()).dormantThinkInterval(1) >= 1400,
            "summoners sleep on a slower schedule");
    }

    private static void stationary() throws Exception {
        wakes("stationary", 20, 0);
        var world = scene("stationary");
        check(world.archetype(world.actors().getFirst().id()).alertThinkMillis() == 500,
            "sentries reacquire on a slow cadence");
    }

    private static void bossScripted() throws Exception {
        wakes(MonsterArchetype.BOSS_SCRIPTED_ID, 64, 0);
        var world = scene(MonsterArchetype.BOSS_SCRIPTED_ID);
        check(world.archetype(world.actors().getFirst().id()).engagedThinkMillis() == 25,
            "scripted bosses react faster than ordinary monsters");
    }

    /** Wake exactly at the policy boundary and prove the effective policy reached the entity. */
    private static void wakes(String archetype, float activation, float leash) throws Exception {
        var world = scene(archetype);
        int id = world.actors().getFirst().id();
        int stamp = 1000;
        world.advance(stamp, List.of());
        check(world.band(id) == SceneMonsterBand.DORMANT, archetype + " starts dormant");
        check(world.archetype(id).id().equals(archetype), archetype + " reaches the live entity");
        check(world.activationRadius(id) == activation, archetype + " activation radius reaches the live entity");
        check(world.leashRadius(id) == leash, archetype + " leash radius reaches the live entity");
        world.advance(stamp += 50, List.of(player(activation + 1, 0)));
        check(world.band(id) == SceneMonsterBand.DORMANT, archetype + " stays asleep outside its activation radius");
        world.advance(stamp += 50, List.of(player(activation - 1, 0)));
        check(world.band(id) == SceneMonsterBand.ALERT, archetype + " wakes inside its activation radius");
    }

    private static void assignment() throws Exception {
        var catalog = MonsterCatalog.load(Path.of("content/monsters"));
        var roles = catalog.types().values().stream()
            .filter(MonsterType::supported)
            .map(type -> catalog.archetype(type).id())
            .collect(java.util.stream.Collectors.toSet());
        check(roles.equals(Set.of("melee-chaser", "ranged-kiter", "bomber", "summoner", "stationary", "boss-scripted")),
            "the generated catalogue assigns every role: " + roles);
        check(catalog.archetype(catalog.type("gun_puppy")).id().equals("ranged-kiter"),
            "a native projectile user is ranged");
        check(catalog.archetype(catalog.type("darkfang_demo")).id().equals("bomber"),
            "a native bomb user is a bomber");
        check(catalog.archetype(catalog.type("almirian_crusader")).id().equals("summoner"),
            "a native actor spawner is a summoner");
        check(catalog.archetype(catalog.type("ice_queen")).id().equals("stationary"),
            "an actor with no attacks is stationary");
        var bosses = catalog.types().values().stream()
            .filter(type -> catalog.archetype(type).id().equals(MonsterArchetype.BOSS_SCRIPTED_ID))
            .map(MonsterType::id).sorted().toList();
        check(bosses.equals(List.of("darkfire_lord_vanaduke", "lord_vanaduke", "margrel",
            "roarmulus_twins", "royal_jelly", "snarbolax")),
            "boss-scripted is the explicit six-entry list: " + bosses);
        check(bosses.size() <= MonsterArchetype.MAX_SCRIPTED_BOSSES, "the shipped boss list respects the cap");
    }

    private static void bossCap() throws Exception {
        Path folder = Files.createTempDirectory("tortodrone-monster-archetype-");
        try {
            var types = new ArrayList<String>();
            for (int index = 0; index < 7; index++) types.add(typeJson("fixture_boss_" + index));
            Path catalog = folder.resolve("catalog.json");
            Files.writeString(catalog, "{\"format\":1,\"generatedFrom\":{\"actorSha256\":\"%s\",\"descriptionSha256\":\"%s\"},\"types\":{%s}}\n"
                .formatted("1".repeat(64), "2".repeat(64), String.join(",", types)));
            Files.writeString(folder.resolve("catalog.sha256"), digest(catalog) + "\n");
            var overrides = new StringBuilder("monsters:\n");
            for (int index = 0; index < 7; index++)
                overrides.append("  fixture_boss_").append(index).append(":\n    archetype: boss-scripted\n");
            Files.writeString(folder.resolve("overrides.yml"), overrides);
            try {
                MonsterCatalog.load(folder);
                throw new AssertionError("A seventh boss-scripted type was accepted");
            } catch (java.io.IOException refused) {
                check(refused.getMessage().contains("At most " + MonsterArchetype.MAX_SCRIPTED_BOSSES),
                    "the refusal states the scripted-boss cap");
            }
        } finally {
            try (var paths = Files.walk(folder)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void unknownPolicy() throws Exception {
        String json = "{\"format\":1,\"generatedFrom\":{\"actorSha256\":\"%s\",\"descriptionSha256\":\"%s\"},\"types\":{%s}}\n"
            .formatted("1".repeat(64), "2".repeat(64), typeJson("fixture_unknown").replace("melee-chaser", "not-a-policy"));
        try {
            MonsterCatalog.parse(json);
            throw new AssertionError("An unknown generated archetype was accepted");
        } catch (java.io.IOException refused) {
            check(refused.getMessage().contains("Invalid monster archetype"), "unknown generated policies fail closed");
        }
    }

    private static String typeJson(String id) {
        return ("""
            "%s":{"config":"Monster/Fixture/%s","display":"Fixture %s","family":"Gremlin",
            "archetype":"melee-chaser","supported":true,"capabilities":[],"unsupported":[],
            "actor":"Character/NPC/Monster/Fixture/%s","configs":["Character/NPC/Monster/Fixture/%s"],
            "spec":{"config":"Character/NPC/Monster/Fixture/%s","kind":"Monster",
            "native-type":"com.threerings.projectx.dungeon.data.actor.Monster","health":"10"}}
            """).formatted(id, id, id, id, id, id).replaceAll("\\s+", "");
    }

    private static SceneEntities scene(String archetype) throws Exception {
        var fields = new HashMap<String, String>();
        fields.putAll(Map.ofEntries(
            Map.entry("config", "Fixture/Archetype"), Map.entry("kind", "Monster"),
            Map.entry("native-type", "com.threerings.projectx.dungeon.data.actor.Monster"),
            Map.entry("health", "10"), Map.entry("damage-mask", "2"), Map.entry("radius", ".4"),
            Map.entry("speed", "1"), Map.entry("acquire-distance", "1"), Map.entry("acquisition-interval", "0"),
            Map.entry("attack-distance", "2"), Map.entry("attack", "0"), Map.entry("turn-rate", "100")));
        fields.putAll(Map.of("attack.0.land", "0", "attack.0.clear", "100",
            "attack.0.fire-speed", "1", "attack.0.fires", ""));
        if (archetype != null) fields.put("archetype", archetype);
        var content = new SceneContent(List.of(new SceneContent.Entry("monster", 0, 0, 0,
            new SceneContent.Spec(fields))), Map.of());
        return new SceneEntities(content, SceneWeaponsTest.openTerrain());
    }

    private static ActorState player(float x, float y) {
        return new ActorState(219, 1001, "Fixture", 256, 1, x, y, 0, 0, 0,
            new StarterCatalog.Model("Fixture", Map.of()));
    }

    private static String digest(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
