package org.tortodrone.world;

import static org.tortodrone.support.TestWire.check;

import java.util.*;
import org.tortodrone.core.town.TownCatalog;
import org.tortodrone.core.world.FireCatalog;

/**
 * Every sprite skill the shipped catalogue declares must resolve to a captured fire graph.
 *
 * <p>{@link PetCompanion} looks a skill up by an exact {@code attack|power|power|damage} key and
 * returns silently when nothing matches, so a missing catalogue row is a skill that does nothing
 * and says nothing. This pins the whole matrix: the three sprite families the capture contains,
 * every base and ultimate attack, all sixteen power-pair tiers and all four harness damage types.
 */
public final class SpriteCoverageTest {
    private static final int SPRITE_ACTIVITY = 292;
    private static final List<String> DAMAGE = List.of("NORMAL", "PIERCING", "ELEMENTAL", "SHADOW");

    public static void main(String[] args) throws Exception {
        var fires = FireCatalog.load(org.tortodrone.support.ContentFixture.path("town/pet-fires.json"));
        var rules = TownCatalog.load(org.tortodrone.support.ContentFixture.path("town/catalog.json"));

        check(fires.unsupportedTypes().isEmpty(), "the captured pet catalogue has no unsupported graph kinds");
        check(
                fires.entries().stream().allMatch(entry -> entry.activity() == SPRITE_ACTIVITY),
                "every captured pet graph is a sprite skill");

        // The capture names one entry per family/attack/power/damage combination; the families come
        // from those names so the matrix cannot drift from the catalogue it is checking.
        var families = new TreeSet<String>();
        for (var entry : fires.entries()) {
            String[] parts = entry.weaponConfig().split("/");
            check(parts.length >= 2 && parts[0].equals("Sprite"), "pet graph names stay Sprite/<family>/<attack>");
            families.add(parts[1]);
        }
        check(families.size() == 3, "the capture covers the three shipped sprite families");
        check(
                fires.entries().size() == families.size() * 3 * 3 * 4 * 4 * 4,
                "the capture holds every family, skill, ultimate variant, power tier and damage type");

        int resolved = 0;
        for (String family : families) {
            var sprite = rules.sprite(family);
            check(sprite != null, "the town catalogue declares captured sprite family " + family);
            check(sprite.skills().size() == 3, family + " declares its three skills");
            for (var skill : sprite.skills()) {
                check(skill.attacks().size() == 3, family + " declares a base attack and two ultimates");
                check(skill.recharge().size() == 4, family + " declares four recharge tiers");
                check(skill.minimum().size() == 4, family + " declares four minimum tiers");
                for (String attack : skill.attacks())
                    for (int first = 0; first < 4; first++)
                        for (int second = 0; second < 4; second++)
                            for (String damage : DAMAGE) {
                                String key = attack + "|" + first + "|" + second + "|" + damage;
                                var fire = fires.find(key, SPRITE_ACTIVITY);
                                if (fire == null)
                                    throw new AssertionError("a sprite skill would silently do nothing: " + key);
                                check(
                                        !fire.fireRefs().isEmpty(),
                                        key + " carries at least one authored fire reference");
                                check(
                                        fire.attack().arguments().containsKey("clear"),
                                        key + " records its own clear window");
                                resolved++;
                            }
            }
        }
        check(resolved == 1728, "the whole sprite matrix resolves, not a subset: " + resolved);

        // Skills the catalogue does not declare must not be served from a stray capture row.
        check(fires.find("Sprite/Drakon/Firebolt|0|0|NORMAL", SPRITE_ACTIVITY) != null, "a known key resolves");
        check(fires.find("Sprite/Drakon/Firebolt|4|0|NORMAL", SPRITE_ACTIVITY) == null, "a fifth power tier is absent");
        check(
                fires.find("Sprite/Drakon/Firebolt|0|0|FIRE", SPRITE_ACTIVITY) == null,
                "an unknown damage type is absent");

        System.out.println("PASS: " + resolved + " sprite skill, ultimate, power and damage combinations resolve");
    }
}
