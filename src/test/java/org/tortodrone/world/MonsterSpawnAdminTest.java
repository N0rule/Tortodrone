package org.tortodrone.world;

import java.util.*;
import static org.tortodrone.support.TestWire.check;
import org.tortodrone.core.world.MonsterCatalog;

/**
 * Phase 8 administrative spawn contract: catalogue-only resolution, all-or-nothing creation,
 * scene caps and the same prepared spec the wave controller uses.
 */
public final class MonsterSpawnAdminTest {
    private MonsterSpawnAdminTest() { }

    public static void main(String[] args) throws Exception {
        var catalog = MonsterFixture.catalog("fixture_wave", "Monster/Fixture/Wave", "Fixture Wave", MonsterFixture.unsupported("fixture_unsupported", "Monster/Fixture/Broken", "Fixture Broken"));
        resolves(catalog);
        spawns(catalog);
        refusals(catalog);
        caps(catalog);
        System.out.println("PASS: GM monster spawn resolves catalogue/native names, enforces caps without partial actors");
    }

    private static void resolves(MonsterCatalog catalog) throws Exception {
        check(catalog.resolveSpawnable("fixture_wave").id().equals("fixture_wave"),
            "the catalogue id resolves exactly");
        check(catalog.resolveSpawnable("Monster/Fixture/Wave").id().equals("fixture_wave"),
            "the native description config resolves to its type");
        check(catalog.resolveSpawnable("Character/NPC/Monster/Fixture/Wave").id().equals("fixture_wave"),
            "the stock actor config resolves to its type");
        try {
            catalog.resolveSpawnable("fixture_unsupported");
            throw new AssertionError("An unsupported type was accepted");
        } catch (java.io.IOException expected) {
            check(expected.getMessage().contains("missing-action") && expected.getMessage().contains("missing-reward"),
                "an unsupported type refusal carries every failing capability/action");
        }
        try {
            catalog.resolveSpawnable("Not/A/Monster");
            throw new AssertionError("An unknown type was accepted");
        } catch (java.io.IOException expected) {
            check(expected.getMessage().contains("Unknown monster type"), "an unknown type is named in the refusal");
        }
    }

    private static void spawns(MonsterCatalog catalog) throws Exception {
        var world = MonsterFixture.scene(catalog);
        int created = world.spawnMonsters("Character/NPC/Monster/Fixture/Wave", 2, 3, 90, 3, 2, 1000);
        check(created == 3 && world.liveMonsters() == 3, "a GM batch creates exactly the requested live monsters");
        var monsters = world.actors().stream().filter(a -> a.entity() != null && "Monster".equals(a.entity().kind())).toList();
        check(monsters.size() == 3, "spawned actors publish as native monsters");
        check(monsters.stream().allMatch(a -> a.config().name().equals("Character/NPC/Monster/Fixture/Wave")),
            "the catalogue actor config is used, not free-form client data");
        check(monsters.stream().allMatch(a -> Math.hypot(a.x() - 2, a.y() - 3) <= 2.001f),
            "count spawns stay inside the requested radius");
        check(monsters.stream().allMatch(a -> a.rotation() == 90), "the requested facing is preserved");
        check(monsters.stream().map(a -> a.id()).distinct().count() == 3, "each spawned monster owns a unique id");
    }

    private static void refusals(MonsterCatalog catalog) throws Exception {
        for (Object[] request : new Object[][]{
                {"fixture_wave", 0, 0, 0, 0, 0},
                {"fixture_wave", 0, 0, 0, SceneEntities.MAX_ALIVE_MONSTERS + 1, 0},
                {"fixture_wave", 0, 0, 0, 1, -1},
                {"fixture_wave", 0, 0, 0, 1, SceneEntities.MAX_ADMIN_SPAWN_RADIUS + 1},
                {"fixture_wave", 0, 0, 0, 1, Float.NaN},
                {"fixture_unsupported", 0, 0, 0, 1, 0},
                {"missing_type", 0, 0, 0, 1, 0},
                {"fixture_wave", 1000, 1000, 0, 1, 0}}) {
            var world = MonsterFixture.scene(catalog);
            try {
                world.spawnMonsters((String) request[0], ((Number) request[1]).floatValue(), ((Number) request[2]).floatValue(),
                    ((Number) request[3]).floatValue(), (Integer) request[4], ((Number) request[5]).floatValue(), 1000);
                throw new AssertionError("Refused spawn was accepted: " + Arrays.toString(request));
            } catch (java.io.IOException expected) {
                check(world.actors().isEmpty() && world.liveMonsters() == 0,
                    "a refused spawn creates no partial actor: " + Arrays.toString(request));
            }
        }
        var world = MonsterFixture.scene(catalog);
        check(world.spawnMonsters("fixture_wave", 0, 0, 0,
            SceneEntities.MAX_ALIVE_MONSTERS - 1, 0, 1000) == SceneEntities.MAX_ALIVE_MONSTERS - 1,
            "the fixture fills all but one slot");
        try {
            world.spawnMonsters("fixture_wave", 0, 0, 0, 2, 0, 1000);
            throw new AssertionError("An over-cap batch was accepted");
        } catch (java.io.IOException expected) {
            check(expected.getMessage().contains("limit is " + SceneEntities.MAX_ALIVE_MONSTERS),
                "the cap refusal states the live-monster limit");
            check(world.liveMonsters() == SceneEntities.MAX_ALIVE_MONSTERS - 1,
                "an over-cap batch does not partially fill the remaining slot");
        }
    }

    private static void caps(MonsterCatalog catalog) throws Exception {
        var world = MonsterFixture.scene(catalog);
        check(world.spawnMonsters("fixture_wave", 0, 0, 0, SceneEntities.MAX_ALIVE_MONSTERS, 0, 1000)
            == SceneEntities.MAX_ALIVE_MONSTERS, "the exact alive cap is admitted");
        try {
            world.spawnMonsters("fixture_wave", 0, 0, 0, 1, 0, 1000);
            throw new AssertionError("A spawn above the alive cap was accepted");
        } catch (java.io.IOException expected) {
            check(world.liveMonsters() == SceneEntities.MAX_ALIVE_MONSTERS, "the live cap is never exceeded");
        }
    }
}
