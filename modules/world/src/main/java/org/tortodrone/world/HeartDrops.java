package org.tortodrone.world;

import java.util.*;
import org.tortodrone.core.character.StarterCatalog;

/** Native treasure-health loot: the tutorial depth table's value draw and heart-tier spend.
 *  Spawns only prepared Pickup actors through the entity model. */
final class HeartDrops {
    private final SceneEntities scene;

    HeartDrops(SceneEntities scene) {
        this.scene = scene;
    }

    /** Native Treasure Health block: run the block's official heart table (Treasure/Block/
     *  Treasure, Health (Tutorial) → Treasure/Health/Treasure Health, Hearts → Parts/Monster
     *  Health → Treasure/Health/Value/Hearts) exactly like the TableSpawnPerMember its
     *  destruction action carries: one value draw from the tutorial's depth entries (the
     *  native ValueDepthListEntry rule - bounds interpolated between bands, drawn gaussian
     *  at the spread's mean 0.43/dev 0.25, clamped, then the table's -1 value offset), spent
     *  largest heart tier first, each tier composing its actors from its own depth band.
     *  The tutorial floor runs at depth 0, where the bounds are both 2 and the draw is
     *  deterministically one tier-1 Heart. */
    void drop(SceneEntity e, int stamp) {
        int value = value(0);
        for (int tier = HEART_TIERS; tier >= 1; tier--)
            while (value >= tier) {
                value -= tier;
                for (String config : heartTierActors(tier, 0)) spawnHeart(e, stamp, config);
            }
    }
    /** The tutorial table's ValueDepthEntry bands {depth, min, max}. */
    private static final int[][] HEART_DEPTH = {{0, 2, 2}, {8, 2, 5}, {9, 3, 4}, {18, 3, 6}, {19, 3, 7}, {30, 4, 9}};

    private static final int HEART_TIERS = 3;
    /** ValueDepthListEntry.b: pick the band at the scene depth, interpolate its bounds toward
     *  the next band, draw a gaussian at the spread (mean 0.43, dev 0.25 of max-min), clamp,
     *  then apply the table's -1 value offset. */
    int value(int depth) {
        float min = 0, max = 0;
        for (int i = HEART_DEPTH.length - 1; i >= 0; i--) {
            if (HEART_DEPTH[i][0] > depth) continue;
            min = HEART_DEPTH[i][1];
            max = HEART_DEPTH[i][2];
            if (i + 1 >= HEART_DEPTH.length || depth == HEART_DEPTH[i][0]) break;
            float portion = (float) (depth - HEART_DEPTH[i][0]) / (HEART_DEPTH[i + 1][0] - HEART_DEPTH[i][0]);
            min += portion * (HEART_DEPTH[i + 1][1] - min);
            max += portion * (HEART_DEPTH[i + 1][2] - max);
            break;
        }
        float spread = max - min;
        int value = Math.round((float) scene.random().nextGaussian(spread * 0.43f + min, spread * 0.25f));
        value = Math.max((int) min, Math.min((int) max, value));
        return value - 1;
    }
    /** The tier spend's actor composition per depth band, from Treasure/Health/Value/Heart 1-3:
     *  tier 2 and 3 tables compose plain Hearts below depth 9, the larger Heart actor from
     *  9 to 18, and at depth 19+ tier 3 is one Heart 3 while tier 2 returns to two Hearts. */
    private static String[] heartTierActors(int tier, int depth) {
        return switch (tier) {
            case 1 -> new String[] {"Item/Consumable/Hearts/Heart"};
            case 2 ->
                depth < 9 || depth >= 19
                        ? new String[] {"Item/Consumable/Hearts/Heart", "Item/Consumable/Hearts/Heart"}
                        : new String[] {"Item/Consumable/Hearts/Heart 2"};
            default ->
                depth < 9
                        ? new String[] {
                            "Item/Consumable/Hearts/Heart",
                            "Item/Consumable/Hearts/Heart",
                            "Item/Consumable/Hearts/Heart"
                        }
                        : depth < 19
                                ? new String[] {"Item/Consumable/Hearts/Heart 2", "Item/Consumable/Hearts/Heart"}
                                : new String[] {"Item/Consumable/Hearts/Heart 3"};
        };
    }
    /** The native heart pickups: Item/Consumable/Hearts/Heart tiers heal 40/80/120 on contact. */
    private void spawnHeart(SceneEntity e, int stamp, String config) {
        int heal = config.endsWith("Heart 2") ? 80 : config.endsWith("Heart 3") ? 120 : 40;
        var fields = new TreeMap<String, String>();
        fields.put("config", config);
        fields.put("native-type", "com.threerings.projectx.dungeon.data.actor.Pickup");
        fields.put("shape", "Circle");
        fields.put("radius", "0.4");
        fields.put("pickup-heal", Integer.toString(heal));
        fields.put("vacuum-expansion", "0.6");
        fields.put("vacuum-speed", "8");
        fields.put("lifespan", "120000");
        try {
            var json = org.tortodrone.core.Json.stringify(fields);
            scene.spawnPickup(new StarterCatalog.Model("Pickup", Map.of("fields", json)), e.x, e.y, e.rotation, stamp);
        } catch (java.io.IOException invalid) {
            throw new IllegalStateException("Invalid prepared heart pickup", invalid);
        }
    }
}
