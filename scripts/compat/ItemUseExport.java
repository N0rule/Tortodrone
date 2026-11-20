import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.threerings.config.ConfigGroup;
import com.threerings.config.ConfigManager;
import com.threerings.projectx.dungeon.config.DungeonActionConfig;
import com.threerings.projectx.dungeon.config.DungeonItemConfig;
import com.threerings.projectx.item.config.ItemConfig;
import com.threerings.tudey.config.ActionConfig;
import com.threerings.tudey.config.TargetConfig;
import org.tortodrone.resources.Json;

/** Exports instant heals, self-damage, spawn-actor props and carryable lifts for owned bag items. */
public final class ItemUseExport {
    private ItemUseExport() { }

    /** Writes the global item-use catalogue consumed by {@code MapYaml.prepare}. */
    public static void main(String[] args) throws Exception {
        Path game = Path.of(args[0]).toRealPath(), out = Path.of(args[1]).toAbsolutePath();
        if (out.startsWith(game) || Files.exists(out)) throw new IOException("Choose a fresh output outside the client");
        var resource = Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resource.getClass().getMethod("gf", String.class).invoke(resource, game.resolve("rsrc").toString());
        var manager = (ConfigManager) ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),
            com.threerings.util.R.class, String.class).newInstance(resource, null, "config/");
        manager.init();
        var uses = new TreeMap<String, Map<String, Object>>();
        for (var config : itemGroup(manager).kR()) {
            var original = config.w(manager);
            if (original instanceof DungeonItemConfig.InstantAction instant) {
                var use = instant(manager, instant);
                if (use != null) uses.put(config.getName(), use);
            } else if (original instanceof ItemConfig.SpawnActor spawn) {
                if (spawn.actor == null) continue;
                var values = new TreeMap<String, String>();
                values.put("actor.config", spawn.actor.getName());
                SceneContentExport.writeActor(manager, values, "actor.", spawn.actor);
                uses.put(config.getName(), fields("spawn-actor", values));
            } else if (original instanceof ItemConfig.Lift lift) {
                if (lift.lift == null) continue;
                var actor = manager.a(com.threerings.tudey.config.ActorConfig.class, lift.lift);
                if (actor == null || !(actor.al(manager) instanceof com.threerings.projectx.config.ProjectXActorConfig.Lift nativeLift))
                    throw new IOException("Unresolved lift actor " + lift.lift.getName());
                var values = new TreeMap<String, String>();
                SceneContentExport.writeLift(manager, values, "lift.", lift.lift, nativeLift, true);
                uses.put(config.getName(), fields("lift", values));
            }
        }
        Files.writeString(out, Json.stringify(Map.of("format", 1, "items", uses)),
            StandardOpenOption.CREATE_NEW);
        System.out.println("Exported " + uses.size() + " item-use definitions to " + out);
    }

    private static Map<String, Object> itemUse(String kind) {
        var use = new TreeMap<String, Object>();
        use.put("kind", kind);
        return use;
    }

    private static Map<String, Object> fields(String kind, Map<String, String> values) {
        var use = itemUse(kind);
        use.putAll(values);
        return use;
    }

    /** Native InstantAction is usable only when its action targets the activator itself. */
    private static Map<String, Object> instant(ConfigManager manager, DungeonItemConfig.InstantAction instant) throws Exception {
        var action = instant.action;
        if (action instanceof DungeonActionConfig.Heal heal && action.getClass() == DungeonActionConfig.Heal.class
            && heal.maximum == 0 && heal.current > 0 && heal.target instanceof TargetConfig.Activator) {
            var use = itemUse("heal");
            use.put("heal", heal.current);
            return use;
        }
        var damage = damageTarget(action);
        if (damage == null) return null;
        var use = itemUse("lethal");
        use.put("damage", org.tortodrone.world.FireCatalog.valueJson(FireCatalogExport.argumentValue(damage)));
        return use;
    }

    private static Object damageTarget(ActionConfig action) throws Exception {
        if (action instanceof DungeonActionConfig.DamageTarget hit && hit.target instanceof TargetConfig.Activator) return hit.damage;
        if (action instanceof ActionConfig.Compound compound) {
            Object found = null;
            for (var child : compound.actions) {
                var damage = damageTarget(child);
                if (damage == null) continue;
                if (found != null) throw new IOException("Multiple self-damage steps in one instant action");
                found = damage;
            }
            return found;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static ConfigGroup<ItemConfig> itemGroup(ConfigManager cm) throws Exception {
        List<Method> methods = new ArrayList<>(Arrays.stream(cm.getClass().getMethods())
            .filter(m -> m.getParameterCount() == 1 && m.getParameterTypes()[0] == Class.class)
            .filter(m -> ConfigGroup.class.isAssignableFrom(m.getReturnType()))
            .filter(m -> !Modifier.isStatic(m.getModifiers())).toList());
        if (methods.size() != 1) throw new IOException("Cannot identify native ItemConfig group accessor");
        Object result = methods.getFirst().invoke(cm, ItemConfig.class);
        if (!(result instanceof ConfigGroup<?> group)) throw new IOException("Native item group unavailable");
        return (ConfigGroup<ItemConfig>) group;
    }
}
