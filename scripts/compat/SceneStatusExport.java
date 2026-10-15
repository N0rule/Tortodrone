import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.threerings.config.ConfigManager;
import com.threerings.projectx.dungeon.config.StatusConditionConfig;
import org.tortodrone.character.StarterCatalog;
import org.tortodrone.world.FireCatalog;

/** Exports bounded native status defaults and modifier fields for scene combat. */
public final class SceneStatusExport {
    private static final Set<String> TYPES = Set.of(
        "Fire", "Shock", "Freeze", "Poison", "Stun", "Sleep", "Curse", "DepthScaled", "Resist", "Buff", "Cloak", "Grab", "Stop", "Shield", "Expires");

    private SceneStatusExport() { }

    /** Adds deterministic status config rows and exact native default data to {@code out}. */
    public static void export(ConfigManager cm, Map<String, String> out) throws Exception {
        if (cm == null || out == null) throw new NullPointerException();
        List<StatusConditionConfig> configs = new ArrayList<>();
        for (StatusConditionConfig config : cm.bd(StatusConditionConfig.class).kK())
            if (TYPES.contains(config.getClass().getSimpleName())) configs.add(config);
        configs.sort(Comparator.comparing(StatusConditionConfig::getName));
        if (configs.isEmpty()) throw new IOException("Native status config group has no supported status types");

        TreeMap<String, String> values = new TreeMap<>();
        for(var power:com.threerings.projectx.dungeon.data.StatusCodes.StatusPower.values())values.put("status-power."+power.name(),Integer.toString(power.power(0)));
        for(var chance:com.threerings.projectx.dungeon.data.StatusCodes.StatusChance.values())values.put("status-chance."+chance.name(),Float.toString(chance.chance(0)));
        List<String> ids = new ArrayList<>();
        for (int id = 0; id < configs.size(); id++) {
            StatusConditionConfig config = configs.get(id);
            String prefix = "status." + id + ".";
            ids.add(Integer.toString(id));
            put(values, prefix + "config", config.getName());
            put(values, prefix + "class", config.getClass().getSimpleName());
            putCommon(values, prefix, config);
            put(values, prefix + "data", statusData(config));
            for(String key:List.of("signal","finishedAction","damageAction"))if(field(config,key) instanceof com.threerings.tudey.config.ActionConfig action){
                String output=key.equals("signal")?"signal-action":key.equals("finishedAction")?"finished-action":"damage-action";
                put(values,prefix+output,FireCatalog.actionJson(FireCatalogExport.readAction(cm,action)));
            }
            for(String key:List.of("damageStart","damageEnd","wakeDamageStart","wakeDamageEnd","healthStart","healthEnd","health","healScale"))if(field(config,key) instanceof com.threerings.config.ConfigReference<?> ref){
                @SuppressWarnings({"unchecked","rawtypes"}) var scale=cm.a(com.threerings.projectx.dungeon.config.DepthScaleConfig.class,(com.threerings.config.ConfigReference)ref);
                if(scale!=null)putFinite(values,prefix+"scale-"+key,scale.a(cm,0,0,com.threerings.projectx.dungeon.data.DungeonCodes.Difficulty.MEDIUM));
            }
        }
        put(values, "statuses", String.join(",", ids));
        for (var entry : values.entrySet()) {
            if (out.containsKey(entry.getKey())) throw new IOException("Scene status key already exists: " + entry.getKey());
        }
        out.putAll(values);
    }

    private static void putCommon(Map<String, String> out, String prefix, StatusConditionConfig config)
        throws IOException {
        Object flags = field(config, "flags");
        Object mask = field(config, "mask");
        Object power = field(config, "power");
        Object chance = field(config, "chance");
        Object minDuration = field(config, "minDuration");
        Object maxDuration = field(config, "maxDuration");
        Object monsterDuration = field(config, "monsterDurationMultiplier");
        if (flags instanceof Number n) put(out, prefix + "flags", n.toString());
        if (mask instanceof Number n) put(out, prefix + "mask", n.toString());
        if (power instanceof Number n) put(out, prefix + "power", n.toString());
        if (chance instanceof Number n) putFinite(out, prefix + "chance", n.doubleValue());
        if (minDuration instanceof Number n) put(out, prefix + "min-duration", n.toString());
        if (maxDuration instanceof Number n) put(out, prefix + "max-duration", n.toString());
        if (monsterDuration instanceof Number n) putFinite(out, prefix + "monster-duration-multiplier", n.doubleValue());
        if (config instanceof StatusConditionConfig.Default defaults) {
            putFinite(out, prefix + "effective-chance", defaults.statusChance.chance(defaults.chance));
            put(out, prefix + "effective-power", Integer.toString(defaults.statusPower.power(defaults.power)));
        }
    }

    /** Serializes only fields declared by the native status implementation hierarchy. */
    static String statusData(StatusConditionConfig config) throws Exception {
        List<FireCatalog.FieldValue> fields = new ArrayList<>();
        for (Class<?> type = config.getClass(); type != null && type != StatusConditionConfig.class;
             type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic() || Modifier.isTransient(field.getModifiers())) continue;
                if (fields.size() >= 128) throw new IOException("Status config has too many native fields: " + config.getName());
                Object value;
                try {
                    field.setAccessible(true);
                    value = field.get(config);
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    throw new IOException("Cannot read native status field " + field, failure);
                }
                fields.add(new FireCatalog.FieldValue(field.getName(), field.getType().getName(),
                    FireCatalogExport.argumentValue(value)));
            }
        }
        return FireCatalog.modelJson(new StarterCatalog.Model("status", Map.of(
            "config", config.getName(),
            "data", new FireCatalog.DataValue(config.getClass().getName(), fields))));
    }

    private static Object field(Object target, String name) throws IOException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // Continue through the native subclass hierarchy.
            } catch (ReflectiveOperationException | RuntimeException failure) {
                throw new IOException("Cannot read native status field " + name, failure);
            }
        }
        return null;
    }

    private static void putFinite(Map<String, String> out, String key, double value) throws IOException {
        if (!Double.isFinite(value)) throw new IOException("Non-finite native status number " + key);
        put(out, key, Double.toString(value));
    }

    private static void put(Map<String, String> out, String key, String value) throws IOException {
        if (value == null || value.length() > 1_000_000 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
            throw new IOException("Invalid native status value: " + key);
        if (out.putIfAbsent(key, value) != null) throw new IOException("Duplicate native status key: " + key);
    }
}
