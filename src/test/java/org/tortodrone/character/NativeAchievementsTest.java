package org.tortodrone.character;

import static org.tortodrone.support.TestWire.check;

import java.util.Map;
import java.util.Set;
import org.tortodrone.core.character.NativeAchievements;
import org.tortodrone.core.character.NativeStats;

/** Exact client predicates: no rule may unlock a type the stock enum would not. */
public final class NativeAchievementsTest {
    public static void main(String[] args) {
        int raritySet = NativeAchievements.ordinal("RARITY_SET_1");
        int slotsFilled = NativeAchievements.ordinal("SLOTS_FILLED_1");
        int reachedCamp = NativeAchievements.ordinal("REACHED_RESCUE_CAMP");
        int swords = NativeAchievements.ordinal("SWORDS_OWNED_1");
        int crafted = NativeAchievements.ordinal("ITEMS_CRAFTED_1");

        check(NativeAchievements.count() == 66, "all stock enum ordinals are catalogued");
        check(NativeAchievements.name(reachedCamp).equals("REACHED_RESCUE_CAMP"), "ordinals are stable");
        check(NativeAchievements.ordinal("UNUSED") == 65, "the final stock constant stays at 65");

        var fiveStarsWithoutSword = Map.of(
                NativeStats.FIVE_STAR_HELMS_OWNED, 1,
                NativeStats.FIVE_STAR_ARMORS_OWNED, 1,
                NativeStats.FIVE_STAR_SHIELDS_OWNED, 1,
                NativeStats.FIVE_STAR_HANDGUNS_OWNED, 1,
                NativeStats.FIVE_STAR_BOMBS_OWNED, 1);
        check(
                NativeAchievements.qualifying(fiveStarsWithoutSword, 0, 0, new long[16])
                        .contains(raritySet),
                "RARITY_SET_1 ignores five-star swords exactly like the client");
        var fiveStarsWithSwordOnly = Map.of(NativeStats.FIVE_STAR_SWORDS_OWNED, 1);
        check(
                !NativeAchievements.qualifying(fiveStarsWithSwordOnly, 0, 0, new long[16])
                        .contains(raritySet),
                "a five-star sword alone cannot satisfy RARITY_SET_1");

        long[] equipment = new long[16];
        for (int slot : new int[] {2, 3, 4, 6, 7, 8, 9, 10, 11, 15}) equipment[slot] = slot + 1L;
        check(
                NativeAchievements.qualifying(Map.of(), 0, 0, equipment).contains(slotsFilled),
                "every slot required by SLOTS_FILLED_1 unlocks it");
        equipment[0] = 99;
        equipment[1] = 99;
        check(
                NativeAchievements.qualifying(Map.of(), 0, 0, equipment).contains(slotsFilled),
                "helm and armor are not part of the stock SLOTS_FILLED_1 predicate");
        equipment[15] = 0;
        check(
                !NativeAchievements.qualifying(Map.of(), 0, 0, equipment).contains(slotsFilled),
                "a missing costume-shield slot keeps SLOTS_FILLED_1 locked");
        check(
                !NativeAchievements.qualifying(Map.of(), 0, 0, null).contains(slotsFilled),
                "missing equipment can never unlock SLOTS_FILLED_1");

        check(
                NativeAchievements.qualifying(Map.of(), 32, 0, new long[16]).contains(reachedCamp),
                "flag 32 unlocks REACHED_RESCUE_CAMP");
        check(
                !NativeAchievements.qualifying(Map.of(), 0, 0, new long[16]).contains(reachedCamp),
                "flag 0 does not unlock REACHED_RESCUE_CAMP");
        check(
                NativeAchievements.qualifying(Map.of(NativeStats.SWORDS_OWNED, 3), 0, 0, new long[16])
                        .contains(swords),
                "three owned swords unlock SWORDS_OWNED_1");
        check(
                NativeAchievements.qualifying(Map.of(NativeStats.ITEMS_CRAFTED, 5), 0, 0, new long[16])
                        .contains(crafted),
                "five crafted items unlock ITEMS_CRAFTED_1");

        Set<Integer> unlocked = NativeAchievements.qualifying(
                Map.of(
                        NativeStats.ITEMS_CRAFTED, 50,
                        NativeStats.SWORDS_OWNED, 10,
                        NativeStats.HANDGUNS_OWNED, 10,
                        NativeStats.BOMBS_OWNED, 10,
                        NativeStats.HELMS_OWNED, 10,
                        NativeStats.ARMORS_OWNED, 10,
                        NativeStats.SHIELDS_OWNED, 10),
                32,
                0,
                equipment);
        for (int ordinal :
                new int[] {0, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38}) {
            check(unlocked.contains(ordinal), "stock threshold unlocks ordinal " + ordinal);
        }
        check(!unlocked.contains(NativeAchievements.ordinal("REVIVE_SELF")), "a base-false type is never granted");
        check(
                !unlocked.contains(NativeAchievements.ordinal("CASINO_WHAMMIE")),
                "hidden casino types are never granted");
        System.out.println("PASS: native achievement predicates match the stock enum ordinals and thresholds");
    }
}
