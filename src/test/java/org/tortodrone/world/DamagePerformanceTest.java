package org.tortodrone.world;

import static org.tortodrone.support.TestWire.check;

import java.util.HashMap;
import org.tortodrone.core.world.FireCatalog;
import org.tortodrone.core.world.SceneContent;
import org.tortodrone.core.world.SceneDamage;
import org.tortodrone.resources.SceneCatalog;
import org.tortodrone.support.ContentFixture;

/** The damage summary's performance must classify natively (DamageSummary.getPerformance over
 *  applyDefense's ratio): each dealt part is compared with the same part mitigated by the
 *  NORMAL defense alone, so an attack into defenses weaker than the target's NORMAL defense
 *  rates STRONG (ratio over 1.15), into stronger defenses WEAK (under 0.9), else NORMAL.
 *  MonsterLogic then promotes the base by the dealt fraction before posting feedback. */
public final class DamagePerformanceTest {
    public static void main(String[] args) throws Exception {
        var map = SceneCatalog.load(ContentFixture.path("maps")).scene(294);
        var content = map.content();
        var piercing = (Object) new FireCatalog.DataValue(
                "com.threerings.projectx.dungeon.config.DamageConfig$Piercing", java.util.Map.of("amount", 100f));
        var sword = (Object) new FireCatalog.DataValue(
                "com.threerings.projectx.dungeon.config.DamageConfig$Normal", java.util.Map.of("amount", 100f));
        var vial = (Object) new FireCatalog.DataValue(
                "com.threerings.projectx.dungeon.config.DamageConfig$Unblockable", java.util.Map.of("amount", 100f));
        check(
                SceneDamage.performance(sword, content, defense("100", "100")).equals("NORMAL"),
                "an attack into matching defenses rates NORMAL (ratio exactly 1)");
        check(
                SceneDamage.performance(piercing, content, defense("100", "100"))
                        .equals("NORMAL"),
                "a typed attack into its equal defense rates NORMAL");
        check(
                SceneDamage.performance(piercing, content, defense("100", "90")).equals("NORMAL"),
                "an attack marginally stronger than the target's defense stays inside the NORMAL band");
        check(
                SceneDamage.performance(piercing, content, defense("10", "1000"))
                        .equals("WEAK"),
                "an attack into a type defense far stronger than the normal defense rates WEAK");
        check(
                SceneDamage.performance(piercing, content, defense("1000", "10"))
                        .equals("STRONG"),
                "an attack into a type defense far weaker than the normal defense rates STRONG");
        check(
                SceneDamage.performance(piercing, content, defense("100", "130"))
                        .equals("WEAK"),
                "an attack falling under 0.9 of the normal baseline rates WEAK");
        check(
                SceneDamage.performance(vial, content, defense("1000", "1000")).equals("NORMAL"),
                "unblockable damage ignores defenses and rates NORMAL");
        // The promoted tiers keep the plain names' alphabets: every base the hit site can
        // produce must stream as a native performance ordinal.
        for (String base : java.util.List.of("WEAK", "NORMAL", "STRONG"))
            for (String size : java.util.List.of("LARGE", "MEDIUM", "SMALL"))
                check(
                        DAMAGE_PERFORMANCE.contains(base + "_" + size),
                        "promoted performance " + base + "_" + size + " exists natively");
        System.out.println("PASS: native damage performance classification and its promoted tier names");
    }

    private static SceneContent.Spec defense(String normal, String piercing) {
        var fields = new HashMap<String, String>();
        fields.put("defense.normal", normal);
        fields.put("defense.piercing", piercing);
        return new SceneContent.Spec(fields);
    }

    private static final java.util.List<String> DAMAGE_PERFORMANCE = java.util.List.of(
            "WEAK",
            "NORMAL",
            "STRONG",
            "WEAK_SMALL",
            "WEAK_MEDIUM",
            "WEAK_LARGE",
            "NORMAL_SMALL",
            "NORMAL_MEDIUM",
            "NORMAL_LARGE",
            "STRONG_SMALL",
            "STRONG_MEDIUM",
            "STRONG_LARGE");
}
