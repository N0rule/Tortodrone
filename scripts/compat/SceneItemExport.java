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
import com.threerings.config.ConfigReference;
import com.threerings.projectx.dungeon.config.DungeonActionConfig;
import com.threerings.projectx.dungeon.config.DungeonItemConfig;
import com.threerings.projectx.item.config.ItemConfig;
import com.threerings.tudey.config.ActionConfig;
import com.threerings.tudey.config.ActorConfig;
import com.threerings.tudey.config.TargetConfig;
import org.tortodrone.character.StarterCatalog;
import org.tortodrone.world.FireCatalog;

/** Exports numeric and typed action fields for native dungeon capsules and vials. */
public final class SceneItemExport {
    private SceneItemExport() { }

    /** Adds bounded native item data to the caller's deterministic properties map. */
    public static void export(ConfigManager cm, Map<String, String> out) throws Exception {
        if (cm == null || out == null) throw new NullPointerException();
        TreeMap<String, String> values = new TreeMap<>();
        List<ItemConfig> configs = new ArrayList<>();
        for (ItemConfig config : itemGroup(cm).kK()) configs.add(config);
        configs.sort(Comparator.comparingInt(ItemConfig::getConfigId).thenComparing(ItemConfig::getName));
        List<String> ids = new ArrayList<>();
        int exportId = 0;
        for (ItemConfig config : configs) {
            ItemConfig.Original original = config.w(cm);
            if (!(original instanceof DungeonItemConfig.Capsule || original instanceof DungeonItemConfig.Vial)) continue;
            int id = exportId++;
            String prefix = "item." + id + ".";
            ids.add(Integer.toString(id));
            put(values, prefix + "config", config.getName());
            if (original instanceof DungeonItemConfig.Capsule capsule) {
                put(values, prefix + "kind", "capsule");
                put(values, prefix + "delay", Integer.toString(capsule.actionDelay));
            } else {
                DungeonItemConfig.Vial vial = (DungeonItemConfig.Vial)original;
                put(values, prefix + "kind", "vial");
                put(values, prefix + "delay", "0");
                putModel(values, prefix + "vial-destruction-transient", FireCatalogExport.model(vial.destructionTransient));
                ConfigReference<ActorConfig> drop = new ConfigReference<>("Dynamic/Lift Objects/Throwing Vial",
                    "Colorizations", original.icon.colorizations,
                    "Destruction Transient", vial.destructionTransient,
                    "Icon File", original.icon.file,
                    "Item", new ConfigReference<ItemConfig>(config.getName()));
                put(values, prefix + "vial-config", drop.getName());
                ActorConfig actor = cm.a(ActorConfig.class, drop);
                if (actor == null || !(actor.ai(cm) instanceof com.threerings.projectx.dungeon.config.DungeonActorConfig.PickupLift lift))
                    throw new IOException("Unresolved native Throwing Vial actor config");
                if (lift.item == null || !config.getName().equals(lift.item.getName()))
                    throw new IOException("Throwing Vial actor is not bound to its source item " + config.getName());
                put(values, prefix + "vial-model", modelJson(FireCatalogExport.model(drop)));
                SceneContentExport.writeLift(cm,values,prefix+"lift.",drop,lift,true);
            }
            putIcon(values, prefix, original);
            writeAction(cm, prefix, ((ItemConfig.ActionItem)original).action, values);
        }
        if (ids.isEmpty()) throw new IOException("No native DungeonItemConfig Capsule/Vial entries found");
        put(values, "items", String.join(",", ids));
        for (var entry : values.entrySet()) {
            if (out.putIfAbsent(entry.getKey(), entry.getValue()) != null)
                throw new IOException("Scene item key already exists: " + entry.getKey());
        }
    }

    @SuppressWarnings("unchecked")
    private static ConfigGroup<ItemConfig> itemGroup(ConfigManager cm) throws Exception {
        List<Method> methods = Arrays.stream(cm.getClass().getMethods())
            .filter(m -> m.getParameterCount() == 1 && m.getParameterTypes()[0] == Class.class)
            .filter(m -> ConfigGroup.class.isAssignableFrom(m.getReturnType()))
            .filter(m -> !Modifier.isStatic(m.getModifiers())).toList();
        if (methods.size() != 1) throw new IOException("Cannot identify native ItemConfig group accessor");
        Object result = methods.getFirst().invoke(cm, ItemConfig.class);
        if (!(result instanceof ConfigGroup<?> group)) throw new IOException("Native item group unavailable");
        return (ConfigGroup<ItemConfig>)group;
    }

    private static void putIcon(Map<String, String> out, String prefix, ItemConfig.Original original)
        throws IOException {
        put(out, prefix + "icon-file", original.icon.file);
        if (original.icon.colorizations == null) {
            put(out, prefix + "icon-colorizations", "[]");
            return;
        }
        var colors = new ArrayList<Object>();
        for (var color : original.icon.colorizations) {
            if (color instanceof com.threerings.opengl.renderer.config.ColorizationConfig.Normal normal)
                colors.add(new StarterCatalog.Colorization(normal.colorization));
            else if (color instanceof com.threerings.opengl.renderer.config.ColorizationConfig.TransNormal trans)
                colors.add(new StarterCatalog.TransColorization(trans.clazz, trans.colorization));
            else if (color instanceof com.threerings.opengl.renderer.config.ColorizationConfig.CustomOffsets custom)
                colors.add(new FireCatalog.DataValue(color.getClass().getName(), List.of(
                    new FireCatalog.FieldValue("clazz", "int", custom.clazz),
                    new FireCatalog.FieldValue("offsets", custom.offsets.getClass().getName(), FireCatalogExport.argumentValue(custom.offsets)))));
            else colors.add(FireCatalogExport.argumentValue(color));
        }
        put(out, prefix + "icon-colorizations", FireCatalog.modelJson(new StarterCatalog.Model("metadata", Map.of("colorizations", new FireCatalog.ListValue("colorization", colors)))));
    }

    private static void writeAction(ConfigManager cm, String prefix, ActionConfig action,
        Map<String, String> out) throws Exception {
        put(out, prefix + "use-action", FireCatalog.actionJson(readAction(cm, action)));
        put(out, prefix + "use-effect", modelJson(findFireEffect(action)));
        int healAmount = findHeal(action);
        if (healAmount >= 0) put(out, prefix + "heal", Integer.toString(healAmount));
    }

    private static StarterCatalog.Model findFireEffect(ActionConfig action) throws IOException {
        if (action instanceof ActionConfig.FireEffect effect) {
            try { return FireCatalogExport.model(effect.effect); }
            catch (IOException failure) { throw failure; }
        }
        if (action instanceof ActionConfig.Compound compound && compound.actions != null) {
            for (ActionConfig child : compound.actions) {
                StarterCatalog.Model found = findFireEffect(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static FireCatalog.Action readAction(ConfigManager cm, ActionConfig action) throws Exception {
        return FireCatalogExport.readAction(cm,action);
    }

    private static int findHeal(ActionConfig action) throws IOException {
        if (action instanceof DungeonActionConfig.Heal heal) return heal.current;
        if (action instanceof ActionConfig.Compound compound && compound.actions != null) {
            int found = -1;
            for (ActionConfig child : compound.actions) {
                int amount = findHeal(child);
                if (amount >= 0) {
                    if (found >= 0) throw new IOException("Multiple heal steps in native capsule action");
                    found = amount;
                }
            }
            return found;
        }
        return -1;
    }

    private static void putModel(Map<String, String> out, String key, StarterCatalog.Model model)
        throws IOException {
        if (model != null) put(out, key, modelJson(model));
    }

    private static String modelJson(StarterCatalog.Model model) throws IOException {
        if (model == null) return "null";
        return FireCatalog.modelJson(model);
    }

    private static void put(Map<String, String> out, String key, String value) throws IOException {
        if (value == null || value.length() > 1_000_000 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
            throw new IOException("Invalid scene item value for " + key);
        if (out.putIfAbsent(key, value) != null) throw new IOException("Duplicate scene item key: " + key);
    }
}
