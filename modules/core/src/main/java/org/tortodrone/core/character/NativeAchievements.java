package org.tortodrone.core.character;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** The installed client's {@code Achievement.Type} enum and its exact unlock predicates.
 *
 * <p>Ordinals are the client's enum ordinals and are the only identity written on the
 * wire. Conditions are transcribed from each generated {@code qualifies(PlayerObject)}
 * method: flag masks, progress-flag masks and {@code StatSet} thresholds. Types whose
 * stock predicate is the base class' constant {@code false} (or whose condition needs
 * an unsupported system such as the casino) are deliberately never granted from an
 * invented rule.
 */
public final class NativeAchievements {
    private static final String[] NAMES = {
        "REACHED_RESCUE_CAMP", "REACHED_HAVEN", "REACHED_TERMINAL_1", "REACHED_SUBTOWN_1",
        "EARNED_TIER_2", "REACHED_SUBTOWN_2", "EARNED_TIER_3", "REACHED_CORE_TERMINAL",
        "DEFEATED_ROYAL_JELLY", "DEFEATED_VANADUKE", "DEFEATED_SNARBOLAX", "DEFEATED_ROARMULUS",
        "CRAFTED_RARITY_1", "CRAFTED_RARITY_2", "CRAFTED_RARITY_3", "CRAFTED_RARITY_4",
        "CRAFTED_RARITY_5", "ITEMS_CRAFTED_1", "ITEMS_CRAFTED_2", "ITEMS_CRAFTED_3",
        "ITEMS_CRAFTED_4", "SWORDS_OWNED_1", "SWORDS_OWNED_2", "SWORDS_OWNED_3",
        "HANDGUNS_OWNED_1", "HANDGUNS_OWNED_2", "HANDGUNS_OWNED_3", "BOMBS_OWNED_1",
        "BOMBS_OWNED_2", "BOMBS_OWNED_3", "HELMS_OWNED_1", "HELMS_OWNED_2",
        "HELMS_OWNED_3", "ARMORS_OWNED_1", "ARMORS_OWNED_2", "ARMORS_OWNED_3",
        "SHIELDS_OWNED_1", "SHIELDS_OWNED_2", "SHIELDS_OWNED_3", "RARITY_SET_1",
        "LEVELLED_ITEM_1", "MINERALS_DEPOSITED_1", "MINERALS_DEPOSITED_2", "MINERALS_DEPOSITED_3",
        "MINERALS_DEPOSITED_4", "MINERALS_DEPOSITED_5", "SLOTS_FILLED_1", "REVIVE_SELF",
        "REVIVE_FRIEND", "REVIVE_FRIEND_MINIMUM", "HEALTH_CAPSULES_USED_1",
        "REMEDY_CAPSULES_USED_1", "MECHA_KNIGHT_ACTIVATED", "REVIVELESS_COMPLETE_TIER_1",
        "REVIVELESS_COMPLETE_TIER_2", "REVIVELESS_COMPLETE_TIER_3", "FULL_RUN",
        "REVIVELESS_FULL_RUN", "FIRECRACKER_BOMB_OWNED", "LOCKDOWN_SNOWBALL",
        "CASINO_SPIN_1", "CASINO_WHAMMIE", "CASINO_SPIN_2", "CASINO_SPIN_3",
        "CASINO_VIP", "UNUSED"
    };

    private static final int MASK_FLAG = 0;
    private static final int MASK_PROGRESS = 1;
    private static final int STAT = 2;
    private static final int RARITY_SET = 3;
    private static final int EQUIPMENT = 4;

    /** The exact equipment indices required by {@code SLOTS_FILLED_1}: Four weapon
     *  slots (6–9), both trinkets (10–11), shield (2) and the three costume slots
     *  (3, 4, 15). Helm and armor (0, 1) are deliberately not part of the predicate. */
    private static final int[] SLOTS_FILLED_INDICES = {2, 3, 4, 6, 7, 8, 9, 10, 11, 15};

    private record Rule(int kind, int argument, int threshold) { }

    private static final Map<Integer, Rule> RULES = Map.ofEntries(
        Map.entry(0, new Rule(MASK_FLAG, 32, 32)),
        Map.entry(4, new Rule(MASK_PROGRESS, 1, 1)),
        Map.entry(6, new Rule(MASK_PROGRESS, 2, 2)),
        Map.entry(17, new Rule(STAT, NativeStats.ITEMS_CRAFTED, 5)),
        Map.entry(18, new Rule(STAT, NativeStats.ITEMS_CRAFTED, 10)),
        Map.entry(19, new Rule(STAT, NativeStats.ITEMS_CRAFTED, 25)),
        Map.entry(20, new Rule(STAT, NativeStats.ITEMS_CRAFTED, 50)),
        Map.entry(21, new Rule(STAT, NativeStats.SWORDS_OWNED, 3)),
        Map.entry(22, new Rule(STAT, NativeStats.SWORDS_OWNED, 5)),
        Map.entry(23, new Rule(STAT, NativeStats.SWORDS_OWNED, 10)),
        Map.entry(24, new Rule(STAT, NativeStats.HANDGUNS_OWNED, 3)),
        Map.entry(25, new Rule(STAT, NativeStats.HANDGUNS_OWNED, 5)),
        Map.entry(26, new Rule(STAT, NativeStats.HANDGUNS_OWNED, 10)),
        Map.entry(27, new Rule(STAT, NativeStats.BOMBS_OWNED, 3)),
        Map.entry(28, new Rule(STAT, NativeStats.BOMBS_OWNED, 5)),
        Map.entry(29, new Rule(STAT, NativeStats.BOMBS_OWNED, 10)),
        Map.entry(30, new Rule(STAT, NativeStats.HELMS_OWNED, 3)),
        Map.entry(31, new Rule(STAT, NativeStats.HELMS_OWNED, 5)),
        Map.entry(32, new Rule(STAT, NativeStats.HELMS_OWNED, 10)),
        Map.entry(33, new Rule(STAT, NativeStats.ARMORS_OWNED, 3)),
        Map.entry(34, new Rule(STAT, NativeStats.ARMORS_OWNED, 5)),
        Map.entry(35, new Rule(STAT, NativeStats.ARMORS_OWNED, 10)),
        Map.entry(36, new Rule(STAT, NativeStats.SHIELDS_OWNED, 3)),
        Map.entry(37, new Rule(STAT, NativeStats.SHIELDS_OWNED, 5)),
        Map.entry(38, new Rule(STAT, NativeStats.SHIELDS_OWNED, 10)),
        Map.entry(39, new Rule(RARITY_SET, 0, 1)),
        Map.entry(46, new Rule(EQUIPMENT, 0, 1)),
        Map.entry(50, new Rule(STAT, NativeStats.HEALTH_CAPSULES_USED, 10)),
        Map.entry(51, new Rule(STAT, NativeStats.REMEDY_CAPSULES_USED, 10)),
        Map.entry(60, new Rule(STAT, NativeStats.CASINO_SPINS, 100)),
        Map.entry(62, new Rule(STAT, NativeStats.CASINO_SPINS, 10_000)),
        Map.entry(63, new Rule(STAT, NativeStats.CASINO_SPINS, 1_000_000))
    );

    private NativeAchievements() { }

    public static int count() { return NAMES.length; }

    public static String name(int ordinal) {
        if (ordinal < 0 || ordinal >= NAMES.length) throw new IllegalArgumentException("Unknown achievement " + ordinal);
        return NAMES[ordinal];
    }

    public static int ordinal(String name) {
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(name)) return i;
        throw new IllegalArgumentException("Unknown achievement " + name);
    }

    /** Exact {@code Achievement.Type.qualifies(PlayerObject)} result for every supported rule. */
    public static Set<Integer> qualifying(Map<Integer, Integer> stats, int flags, int progressFlags,
            long[] equipment) {
        var result = new LinkedHashSet<Integer>();
        for (var entry : RULES.entrySet()) {
            var rule = entry.getValue();
            boolean qualifies = switch (rule.kind()) {
                case MASK_FLAG -> (flags & rule.argument()) == rule.threshold();
                case MASK_PROGRESS -> (progressFlags & rule.argument()) != 0;
                case STAT -> stats.getOrDefault(rule.argument(), 0) >= rule.threshold();
                case RARITY_SET -> {
                    // The stock RARITY_SET_1 predicate checks helms, armors, shields,
                    // handguns and bombs; it deliberately excludes five-star swords.
                    boolean all = true;
                    for (int stat : new int[]{NativeStats.FIVE_STAR_HANDGUNS_OWNED,
                            NativeStats.FIVE_STAR_BOMBS_OWNED, NativeStats.FIVE_STAR_HELMS_OWNED,
                            NativeStats.FIVE_STAR_ARMORS_OWNED, NativeStats.FIVE_STAR_SHIELDS_OWNED})
                        all &= stats.getOrDefault(stat, 0) >= rule.threshold();
                    yield all;
                }
                case EQUIPMENT -> {
                    // ItemCodes.bxm={6,7,8,9}, bxn={10,11}, then {2,3,4,15}: every
                    // listed slot id must hold an item; helm and armor are excluded.
                    boolean all = equipment != null;
                    if (all) for (int slot : SLOTS_FILLED_INDICES)
                        all &= slot < equipment.length && equipment[slot] > 0;
                    yield all;
                }
                default -> false;
            };
            if (qualifies) result.add(entry.getKey());
        }
        return Set.copyOf(result);
    }
}
