package org.tortodrone.core.character;

import java.util.Map;
import java.util.TreeMap;

/** The installed client's {@code com.threerings.projectx.data.StatType} enum.
 *
 * <p>Every constant is the client's own, in ordinal order. The realistic counters
 * are stored per account; owned counts are derived from the selected knight's
 * inventory so the values sent in {@code PlayerObject.stats} always match what
 * the client can see.
 */
public final class NativeStats {
    public static final int ITEMS_CRAFTED = 0;
    public static final int SWORDS_OWNED = 1;
    public static final int HANDGUNS_OWNED = 2;
    public static final int BOMBS_OWNED = 3;
    public static final int HELMS_OWNED = 4;
    public static final int ARMORS_OWNED = 5;
    public static final int SHIELDS_OWNED = 6;
    public static final int FIVE_STAR_SWORDS_OWNED = 7;
    public static final int FIVE_STAR_HANDGUNS_OWNED = 8;
    public static final int FIVE_STAR_BOMBS_OWNED = 9;
    public static final int FIVE_STAR_HELMS_OWNED = 10;
    public static final int FIVE_STAR_ARMORS_OWNED = 11;
    public static final int FIVE_STAR_SHIELDS_OWNED = 12;
    public static final int HEALTH_CAPSULES_USED = 13;
    public static final int REMEDY_CAPSULES_USED = 14;
    public static final int LUCK_POTIONS_USED = 15;
    public static final int LEVELS_COMPLETED = 16;
    public static final int CASINO_SPINS = 17;
    public static final int SLIME_LOCKBOXES_OPENED = 18;
    public static final int UNUSED = 19;

    private static final String[] NAMES = {
        "ITEMS_CRAFTED",
        "SWORDS_OWNED",
        "HANDGUNS_OWNED",
        "BOMBS_OWNED",
        "HELMS_OWNED",
        "ARMORS_OWNED",
        "SHIELDS_OWNED",
        "FIVE_STAR_SWORDS_OWNED",
        "FIVE_STAR_HANDGUNS_OWNED",
        "FIVE_STAR_BOMBS_OWNED",
        "FIVE_STAR_HELMS_OWNED",
        "FIVE_STAR_ARMORS_OWNED",
        "FIVE_STAR_SHIELDS_OWNED",
        "HEALTH_CAPSULES_USED",
        "REMEDY_CAPSULES_USED",
        "LUCK_POTIONS_USED",
        "LEVELS_COMPLETED",
        "CASINO_SPINS",
        "SLIME_LOCKBOXES_OPENED",
        "UNUSED"
    };

    private NativeStats() {}

    public static int count() {
        return NAMES.length;
    }

    public static String name(int ordinal) {
        if (ordinal < 0 || ordinal >= NAMES.length) throw new IllegalArgumentException("Unknown stat " + ordinal);
        return NAMES[ordinal];
    }

    public static int ordinal(String name) {
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(name)) return i;
        throw new IllegalArgumentException("Unknown stat " + name);
    }

    /** Non-zero values only: the client's {@code StatSet.a(type)} returns zero for absent entries. */
    public static Map<Integer, Integer> values(WorldProfile profile, ItemCatalog catalog, long now) {
        var values = new TreeMap<Integer, Integer>();
        for (var entry : profile.achievements().counters().entrySet())
            if (entry.getValue() > 0) values.put(entry.getKey(), entry.getValue());
        if (catalog == null) return Map.copyOf(values);
        int[] counts = new int[13];
        for (var item : profile.items()) {
            if (item.expired(now)) continue;
            int base = switch (item.slot()) {
                case SWORD -> SWORDS_OWNED;
                case GUN -> HANDGUNS_OWNED;
                case BOMB -> BOMBS_OWNED;
                case HELM -> HELMS_OWNED;
                case ARMOR -> ARMORS_OWNED;
                case SHIELD -> SHIELDS_OWNED;
                default -> -1;
            };
            if (base < 0) continue;
            counts[base - SWORDS_OWNED]++;
            var entry = catalog.find(item.config());
            if (entry != null && entry.rarity() == 5) counts[base - SWORDS_OWNED + 6]++;
        }
        for (int i = 0; i < counts.length; i++) if (counts[i] != 0) values.put(SWORDS_OWNED + i, counts[i]);
        return Map.copyOf(values);
    }
}
