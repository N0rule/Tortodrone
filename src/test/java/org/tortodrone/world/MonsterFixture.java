package org.tortodrone.world;

import java.util.*;
import org.tortodrone.core.world.FireCatalog;
import org.tortodrone.core.world.MonsterCatalog;
import org.tortodrone.core.world.SceneCollision;
import org.tortodrone.core.world.SceneContent;
import org.tortodrone.core.world.SceneStatus;

/** Shared synthetic monster scene and catalogue fixtures; never official data. */
final class MonsterFixture {
    private MonsterFixture() {}

    static SceneEntities scene(MonsterCatalog catalog) throws Exception {
        return new SceneEntities(
                SceneContent.empty(),
                TestCollision.floor(-32, -32, 64, 64),
                SceneCollision.empty(),
                new Random(7),
                true,
                1000,
                catalog);
    }

    static void kill(SceneEntities world, int stamp) {
        var pulse = new SceneStatus.Pulse(
                new FireCatalog.DataValue(
                        "com.threerings.projectx.dungeon.config.DamageConfig$Normal",
                        Map.of("flags", 2, "amount", 1000f)),
                100f,
                128);
        world.statusPulse(0, pulse, 0, 0, stamp);
    }

    static MonsterCatalog catalog(String typeId, String config, String display) throws Exception {
        return parse(supported(typeId, config, display));
    }

    static MonsterCatalog catalog(String typeId, String config, String display, String extraTypes) throws Exception {
        return parse(supported(typeId, config, display) + ",\n" + extraTypes);
    }

    static String unsupported(String typeId, String config, String display) {
        return """
            "%s": {
              "config": "%s",
              "display": "%s",
              "family": "Gremlin",
              "archetype": "melee-chaser",
              "supported": false,
              "capabilities": [],
              "unsupported": ["missing-action", "missing-reward"],
              "actor": "%s",
              "configs": ["%s"]
            }""".formatted(typeId, config, display, actor(config), actor(config));
    }

    private static MonsterCatalog parse(String types) throws Exception {
        return MonsterCatalog.parse("""
            {
              "format": 1,
              "generatedFrom": {"actorSha256": "%s", "descriptionSha256": "%s"},
              "types": {
            %s
              }
            }
            """.formatted("1".repeat(64), "2".repeat(64), types));
    }

    private static String supported(String typeId, String config, String display) {
        String actor = actor(config);
        return """
            "%s": {
              "config": "%s",
              "display": "%s",
              "family": "Gremlin",
              "archetype": "melee-chaser",
              "supported": true,
              "capabilities": [],
              "unsupported": [],
              "actor": "%s",
              "configs": ["%s"],
              "spec": {
                "config": "%s",
                "kind": "Monster",
                "native-type": "com.threerings.projectx.dungeon.data.actor.Monster",
                "health": "100",
                "radius": ".4",
                "damage-mask": "2",
                "collision-mask": "1",
                "collision-flags": "8",
                "speed": "4",
                "acquire-distance": "40",
                "acquisition-interval": "0",
                "attack-distance": "1",
                "attack": "0",
                "turn-rate": "100"
              }
            }""".formatted(typeId, config, display, actor, actor, actor);
    }

    private static String actor(String config) {
        return "Character/NPC/Monster/" + config.substring("Monster/".length());
    }
}
