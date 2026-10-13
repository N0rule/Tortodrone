import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.threerings.config.ConfigGroup;
import com.threerings.config.ConfigManager;
import com.threerings.projectx.dungeon.config.DefenseConfig;
import com.threerings.projectx.dungeon.util.DamageSummary;
import com.threerings.projectx.item.config.ItemConfig;

/** Exports stock Training depth-zero player base-health and unvarianted gear stats. */
public final class ScenePlayerStatsExport {
    private static final String[] DAMAGE_TYPES = {"normal", "piercing", "elemental", "shadow"};

    private ScenePlayerStatsExport() { }

    /** Adds deterministic numeric values sourced from the native item and defense configs. */
    public static void export(ConfigManager cm, Map<String, String> out) throws Exception {
        if (cm == null || out == null) throw new NullPointerException();
        TreeMap<String, String> values = new TreeMap<>();
        put(values, "player.base-health", "200");
        var pc=cm.d(com.threerings.tudey.config.ActorConfig.class,"Character/PC/Default").ai(cm);
        put(values,"player.collision-mask",Integer.toString(pc.collisionMask));
        var interaction=((com.threerings.projectx.config.ProjectXActorConfig.Knight)pc).interactionShape.acP().aeu();
        put(values,"player.interaction-offset-x",Float.toString((interaction.tt().x+interaction.tu().x)/2));
        put(values,"player.interaction-offset-y",Float.toString((interaction.tt().y+interaction.tu().y)/2));
        put(values,"player.interaction-radius",Float.toString(Math.min(interaction.tu().x-interaction.tt().x,interaction.tu().y-interaction.tt().y)/2));
        var dungeon=(com.threerings.projectx.dungeon.config.DungeonActorConfig.Dungeoneer)cm.d(com.threerings.tudey.config.ActorConfig.class,"Character/PC/Level").ai(cm);
        put(values,"player.damage-mask",Integer.toString(dungeon.damageMask));
        // Server-only field: original Dungeoneer initializer and modern Training
        // thrown-vial deltas (source 144059) both establish a one-unit launch Z.
        put(values,"player.lift-height","1.0");

        ConfigGroup<ItemConfig> group = configGroup(cm);
        List<ItemConfig> configs = new ArrayList<>();
        for (ItemConfig config : group.kK()) configs.add(config);
        configs.sort(Comparator.comparing(ItemConfig::getName));
        for (ItemConfig config : configs) {
            ItemConfig.Original original = config.w(cm);
            if (!(original instanceof ItemConfig.Gear gear)) continue;
            String name = config.getName();
            var level = gear.c(cm, 0);
            put(values, "gear." + name + ".health-bonus", Integer.toString(level.fr(0)));
            for (String damageType : DAMAGE_TYPES) {
                DamageSummary.DamageType type = DamageSummary.DamageType.valueOf(damageType.toUpperCase());
                int base = gear.defense.a(cm, type, 0, gear.rarity);
                float defense = level.a((float)base, type, cm, 0, gear.rarity);
                if (!Float.isFinite(defense)) throw new IOException("Non-finite gear defense for " + name);
                put(values, "gear." + name + ".defense." + damageType,
                    Float.toString(defense));
            }
        }
        for (var entry : values.entrySet()) {
            if (out.putIfAbsent(entry.getKey(), entry.getValue()) != null)
                throw new IOException("Player stats key already exists: " + entry.getKey());
        }
    }

    @SuppressWarnings("unchecked")
    private static ConfigGroup<ItemConfig> configGroup(ConfigManager cm) throws Exception {
        List<Method> methods = Arrays.stream(cm.getClass().getMethods())
            .filter(m -> m.getParameterCount() == 1 && m.getParameterTypes()[0] == Class.class)
            .filter(m -> ConfigGroup.class.isAssignableFrom(m.getReturnType()))
            .filter(m -> !Modifier.isStatic(m.getModifiers())).toList();
        if (methods.size() != 1) throw new IOException("Cannot identify native item ConfigGroup accessor");
        Object result = methods.getFirst().invoke(cm, ItemConfig.class);
        if (!(result instanceof ConfigGroup<?> group)) throw new IOException("Native item ConfigGroup unavailable");
        return (ConfigGroup<ItemConfig>)group;
    }

    private static void put(Map<String, String> out, String key, String value) throws IOException {
        if (value == null || value.isBlank() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
            throw new IOException("Invalid player stat value for " + key);
        if (out.putIfAbsent(key, value) != null) throw new IOException("Duplicate player stat key: " + key);
    }
}
