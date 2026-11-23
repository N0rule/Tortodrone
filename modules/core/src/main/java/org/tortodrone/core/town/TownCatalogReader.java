package org.tortodrone.core.town;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.tortodrone.core.Json;

/**
 * JSON ingestion for {@link TownCatalog}.
 *
 * <p>The runtime value keeps only the immutable town tables, lookups and variant rolling. This
 * class owns the exported catalogue file, its format version, every row-shape check and the
 * size limit applied to untrusted data.
 */
final class TownCatalogReader {
    private TownCatalogReader() {}

    public static TownCatalog load(Path path) throws IOException {
        if (Files.size(path) > 8_000_000) throw new IOException("Town catalogue too large");
        return fromData(Json.parse(Files.readString(path)));
    }

    public static TownCatalog fromData(Object data) throws IOException {
        try {
            var root = object(data);
            if (integer(root, "format") != 1) throw new IOException("Unsupported Town catalogue");
            var recipes = new LinkedHashMap<String, TownCatalog.Recipe>();
            var items = new HashMap<String, Map<String, String>>();
            var sprites = new HashMap<String, TownCatalog.Sprite>();
            var perks = new ArrayList<TownCatalog.Perk>();
            var stations = new HashMap<String, TownCatalog.Station>();
            for (var value : list(root, "recipes")) {
                var r = object(value);
                var ingredients = new ArrayList<TownCatalog.Ingredient>();
                for (var iv : list(r, "ingredients")) {
                    var i = object(iv);
                    ingredients.add(new TownCatalog.Ingredient(
                            string(i, "kind"),
                            string(i, "item"),
                            integer(i, "count"),
                            integer(i, "rarity"),
                            string(i, "group"),
                            integer(i, "level"),
                            string(i, "upgrade"),
                            bool(i, "keepHeat"),
                            strings(i, "options")));
                }
                var recipe = new TownCatalog.Recipe(
                        string(r, "config"),
                        string(r, "item"),
                        integer(r, "count"),
                        bool(r, "locked"),
                        list(r, "crowns").stream()
                                .map(v -> ((Number) v).intValue())
                                .toList(),
                        list(r, "useOrbs").stream().map(v -> (Boolean) v).toList(),
                        string(r, "variantTable"),
                        List.copyOf(ingredients),
                        bool(r, "oneTime"),
                        integer(r, "maxHours"));
                if (recipe.count() < 1
                        || recipe.count() > 9999
                        || recipe.ingredients().stream().anyMatch(i -> i.count() < 1 || i.count() > 9999)
                        || recipes.put(recipe.config(), recipe) != null) throw new IOException("Invalid Town recipe");
            }
            for (var value : list(root, "items")) {
                var i = object(value);
                var fields = new HashMap<String, String>();
                i.forEach((k, v) -> fields.put(k, (String) v));
                items.put(string(i, "config"), Map.copyOf(fields));
            }
            for (var value : list(root, "sprites")) {
                var s = object(value);
                var skills = new ArrayList<TownCatalog.Skill>();
                for (var raw : (List<?>) s.getOrDefault("skills", List.of())) {
                    var skill = object(raw);
                    skills.add(new TownCatalog.Skill(
                            strings(skill, "attacks"),
                            bool(skill, "follow"),
                            list(skill, "recharge").stream()
                                    .map(v -> ((Number) v).intValue())
                                    .toList(),
                            list(skill, "minimum").stream()
                                    .map(v -> ((Number) v).intValue())
                                    .toList()));
                }
                sprites.put(
                        string(s, "config"),
                        new TownCatalog.Sprite(
                                string(s, "config"),
                                strings(s, "preferredFood"),
                                (String) s.getOrDefault("restrictDamageType", "UNBLOCKABLE"),
                                List.copyOf(skills)));
            }
            for (var value : list(root, "perks")) {
                var p = object(value);
                perks.add(new TownCatalog.Perk(
                        string(p, "name"), integer(p, "tier"), string(p, "variant"), string(p, "previous")));
            }
            for (var value : list(root, "stations")) {
                var s = object(value);
                var station = new TownCatalog.Station(
                        string(s, "actor"),
                        string(s, "kind"),
                        string(s, "type"),
                        string(s, "group"),
                        string(s, "name"),
                        string(s, "title"),
                        string(s, "model"),
                        string(s, "description"),
                        strings(s, "recipes"),
                        new TownCatalog.Shop(
                                (String) s.getOrDefault("catalog", ""),
                                ((Number) s.getOrDefault("level", 10)).intValue(),
                                (String) s.getOrDefault("preparer", "BASIC"),
                                (String) s.getOrDefault("cycle", "CONSTANT"),
                                (Boolean) s.getOrDefault("perKnight", false)));
                stations.put(station.actor(), station);
            }
            var result = new TownCatalog(recipes, items, sprites, perks, stations);
            result.gear = GearRules.load(root);
            var tables = new HashMap<String, Map<String, Object>>();
            for (var value : list(root, "variantTables")) {
                var t = object(value);
                tables.put(string(t, "config"), object(t.get("root")));
            }
            result.variantTables = Map.copyOf(tables);
            var accessories = new HashMap<String, TownCatalog.Accessory>();
            for (var value : list(root, "accessories")) {
                var v = object(value);
                accessories.put(
                        string(v, "config"),
                        new TownCatalog.Accessory(
                                string(v, "config"),
                                string(v, "location"),
                                Set.copyOf(strings(v, "blocks")),
                                bool(v, "remove"),
                                bool(v, "destroy")));
            }
            result.accessories = Map.copyOf(accessories);
            var blockers = new HashMap<String, Set<String>>();
            for (var value : list(root, "blockers")) {
                var v = object(value);
                blockers.put(string(v, "config"), Set.copyOf(strings(v, "slots")));
            }
            result.blockers = Map.copyOf(blockers);
            var harnesses = new HashMap<String, String>();
            for (var value : list(root, "harnesses")) {
                var v = object(value);
                harnesses.put(string(v, "config"), string(v, "damageType"));
                result.harnessPrerequisites.put(string(v, "config"), (String) v.getOrDefault("prerequisite", ""));
                if (v.containsKey("crowns")) {
                    int price = ((Number) v.get("crowns")).intValue();
                    if (price < 1) throw new IOException("Invalid harness price");
                    result.harnessPrices.put(string(v, "config"), price);
                }
                result.harnessRecharge.put(
                        string(v, "config"), ((Number) v.getOrDefault("rechargeModifier", 1)).floatValue());
            }
            result.harnesses = Map.copyOf(harnesses);
            var catalogs = new HashMap<String, TownCatalog.Catalog>();
            for (var value : list(root, "catalogs")) {
                var v = object(value);
                var goods = new ArrayList<TownCatalog.Good>();
                for (var raw : list(v, "goods")) {
                    var g = object(raw);
                    goods.add(new TownCatalog.Good(
                            string(g, "item"),
                            integer(g, "count"),
                            integer(g, "price"),
                            integer(g, "energy"),
                            integer(g, "limit"),
                            string(g, "token"),
                            integer(g, "tokenCount")));
                }
                catalogs.put(
                        string(v, "config"),
                        new TownCatalog.Catalog(
                                string(v, "config"),
                                string(v, "kind"),
                                strings(v, "includes"),
                                List.copyOf(goods),
                                integer(v, "min"),
                                integer(v, "max"),
                                ((Number) v.get("chance")).floatValue()));
            }
            result.catalogs = Map.copyOf(catalogs);
            var bonuses = new HashMap<String, TownCatalog.Bonus>();
            for (var value : list(root, "bonuses")) {
                var v = object(value);
                bonuses.put(
                        string(v, "config"),
                        new TownCatalog.Bonus(
                                string(v, "weapon"),
                                integer(v, "health"),
                                ((Number) v.get("charge")).floatValue(),
                                ((Number) v.get("speed")).floatValue(),
                                ((Number) v.get("attack")).floatValue()));
            }
            result.bonuses = Map.copyOf(bonuses);
            var models = new HashMap<String, org.tortodrone.core.character.StarterCatalog.Model>();
            var parameters = new HashMap<String, String>();
            for (var value : list(root, "accessories")) {
                var v = object(value);
                if (v.containsKey("model")) {
                    models.put(
                            string(v, "config"), org.tortodrone.core.world.FireCatalog.modelJson(string(v, "model")));
                    parameters.put(string(v, "config"), string(v, "parameter"));
                }
            }
            result.accessoryModels = Map.copyOf(models);
            result.accessoryParameters = Map.copyOf(parameters);
            return result;
        } catch (IllegalArgumentException | ClassCastException | NullPointerException bad) {
            throw new IOException("Invalid Town catalogue", bad);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private static List<?> list(Map<String, Object> object, String key) {
        return (List<?>) object.get(key);
    }

    private static List<String> strings(Map<String, Object> object, String key) {
        return list(object, key).stream().map(v -> (String) v).toList();
    }

    private static String string(Map<String, Object> object, String key) {
        return (String) object.get(key);
    }

    private static int integer(Map<String, Object> object, String key) {
        return ((Number) object.get(key)).intValue();
    }

    private static boolean bool(Map<String, Object> object, String key) {
        return (Boolean) object.get(key);
    }
}
