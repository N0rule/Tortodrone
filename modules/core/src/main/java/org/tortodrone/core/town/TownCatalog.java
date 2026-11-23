package org.tortodrone.core.town;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Immutable town rules; no executable client code or assets. */
public final class TownCatalog {
    public record Ingredient(
            String kind,
            String item,
            int count,
            int rarity,
            String group,
            int level,
            String upgrade,
            boolean keepHeat,
            List<String> options) {}

    public record Recipe(
            String config,
            String item,
            int count,
            boolean locked,
            List<Integer> crowns,
            List<Boolean> useOrbs,
            String variantTable,
            List<Ingredient> ingredients,
            boolean oneTime,
            int maxHours) {
        public int cost(int rarity) {
            return rarity >= 0 && rarity < crowns.size() ? crowns.get(rarity) : 0;
        }

        public boolean orbs(int rarity) {
            return rarity >= 0 && rarity < useOrbs.size() && useOrbs.get(rarity);
        }
    }

    public record Skill(List<String> attacks, boolean follow, List<Integer> recharge, List<Integer> minimum) {}

    public record Sprite(String config, List<String> preferredFood, String restrictedDamage, List<Skill> skills) {
        public Sprite(String config, List<String> preferredFood, String restrictedDamage) {
            this(config, preferredFood, restrictedDamage, List.of());
        }
    }

    public record Accessory(String config, String location, Set<String> blocks, boolean remove, boolean destroy) {}

    public record Perk(String name, int tier, String variant, String previous) {}

    public record Shop(String catalog, int level, String preparer, String cycle, boolean perKnight) {
        public static final Shop EMPTY = new Shop("", 10, "BASIC", "CONSTANT", false);
    }

    public record Bonus(String weapon, int health, float charge, float speed, float attack) {}

    public record Good(String item, int count, int price, int energy, int limit, String token, int tokenCount) {}

    public record DepotGood(
            Good good, boolean featured, int originalPrice, boolean sale, String promoHeader, String promoDetail) {
        public DepotGood(Good good, boolean featured, int originalPrice, boolean sale) {
            this(good, featured, originalPrice, sale, null, null);
        }
        /** Native DEFAULT countdown; archive DepotTimedGoods documents the wire strings. */
        public static DepotGood timed(
                Good good, boolean featured, int originalPrice, boolean sale, long ends, long now) {
            if (ends <= now) return new DepotGood(good, featured, originalPrice, sale);
            long remaining = ends - now, hours = remaining / 3_600_000;
            String detail =
                    hours < 36 ? "m.promo_hours_left|~" + hours : "m.promo_days_left|~" + (remaining / 86_400_000 + 1);
            return new DepotGood(good, true, originalPrice, sale, "~", detail);
        }

        public DepotGood(Good good, boolean featured) {
            this(good, featured, 0, false);
        }
    }

    public record DepotOffers(Map<Integer, DepotGood> goods) {
        public DepotOffers {
            goods = Map.copyOf(goods);
        }
    }

    public record Catalog(
            String name, String kind, List<String> includes, List<Good> goods, int min, int max, float chance) {}

    /** Every station {@code kind} the town handler and the town codec dispatch. A kind outside
     *  this set would load as a station whose dialog renders and whose operation is always
     *  refused, so content validation rejects it instead. */
    public static final Set<String> STATION_KINDS = Set.of(
            "OpenShop",
            "OpenDepot",
            "OpenAuction",
            "OpenCrafter",
            "OpenCatalogCrafter",
            "OpenGuildCatalogCrafter",
            "OpenUnbinder",
            "OpenAccessorizer",
            "OpenVariantMaker",
            "OpenHarnessInstaller",
            "OpenArsenal");

    public record Station(
            String actor,
            String kind,
            String type,
            String group,
            String name,
            String title,
            String model,
            String description,
            List<String> recipes,
            Shop shop) {
        public Station(
                String actor,
                String kind,
                String type,
                String group,
                String name,
                String title,
                String model,
                String description,
                List<String> recipes) {
            this(actor, kind, type, group, name, title, model, description, recipes, Shop.EMPTY);
        }
    }

    private final Map<String, Recipe> recipes;
    private final Map<String, Map<String, String>> items;
    private final Map<String, Sprite> sprites;
    private final List<Perk> perks;
    private final Map<String, Station> stations;
    Map<String, Map<String, Object>> variantTables = Map.of();
    GearRules gear = GearRules.empty();

    public GearRules gear() {
        return gear;
    }

    public static String itemGroup(org.tortodrone.core.character.ItemCatalog.Entry item) {
        return switch (item.type()) {
            case "Sword" -> "SWORD";
            case "Gun", "Handgun", "SwingingHandgun" -> "HANDGUN";
            case "Bomb", "BlastNetworkBomb" -> "BOMB";
            case "HelmCostume" -> "COSTUME_HELM";
            case "ArmorCostume" -> "COSTUME_ARMOR";
            case "ShieldCostume" -> "COSTUME_SHIELD";
            case "Material", "Craft" -> "CRAFT";
            default -> item.type().toUpperCase(Locale.ROOT);
        };
    }

    public List<String> availableVariants(String group) {
        if (!Set.of("SWORD", "HANDGUN", "BOMB", "HELM", "ARMOR", "SHIELD", "TRINKET")
                .contains(group)) return List.of();
        var result = new TreeSet<String>();
        collectVariants(variantTables.get("Shop/Variant 3"), group, result, 0);
        return List.copyOf(result);
    }

    private void collectVariants(Map<String, Object> node, String group, Set<String> result, int depth) {
        if (node == null || depth > 32) return;
        String kind = string(node, "kind");
        if (kind.equals("GroupEntry") && !group.equals(string(node, "group"))) return;
        if (kind.equals("VariantEntry")) {
            result.add(string(node, "variant"));
            return;
        }
        var entries = list(node, "entries");
        if ((kind.equals("TableEntry") || kind.equals("Derived")) && entries.isEmpty())
            collectVariants(variantTables.get(string(node, "table")), group, result, depth + 1);
        for (var e : entries) collectVariants(object(object(e).get("node")), group, result, depth + 1);
    }

    public void validateVariants(String group, List<String> selected) throws IOException {
        if (selected.size() > 3
                || new HashSet<>(selected).size() != selected.size()
                || !availableVariants(group).containsAll(selected))
            throw new IOException("Choose up to three distinct UVs valid for this item");
        validateVariantFamilies(variantTables.get("Shop/Variant 3"), group, new HashSet<>(selected), 0);
    }

    private void validateVariantFamilies(Map<String, Object> node, String group, Set<String> selected, int depth)
            throws IOException {
        if (node == null || depth > 32) return;
        String kind = string(node, "kind");
        if (kind.equals("GroupEntry") && !group.equals(string(node, "group"))) return;
        var entries = list(node, "entries");
        if ((kind.equals("TableEntry") || kind.equals("Derived")) && entries.isEmpty())
            validateVariantFamilies(variantTables.get(string(node, "table")), group, selected, depth + 1);
        for (var e : entries) {
            var child = object(object(e).get("node"));
            if (Boolean.TRUE.equals(node.get("exclude"))) {
                var family = new HashSet<String>();
                collectVariants(child, group, family, depth + 1);
                family.retainAll(selected);
                if (family.size() > 1) throw new IOException("Choose only one UV from each variant family");
            }
            validateVariantFamilies(child, group, selected, depth + 1);
        }
    }

    Map<String, Accessory> accessories = Map.of();
    Map<String, Set<String>> blockers = Map.of();
    Map<String, String> harnesses = Map.of();
    final Map<String, Float> harnessRecharge = new HashMap<>();
    final Map<String, String> harnessPrerequisites = new HashMap<>();
    final Map<String, Integer> harnessPrices = new HashMap<>();

    public String harnessPrerequisite(String config) {
        return harnessPrerequisites.getOrDefault(config, "");
    }

    public boolean harnessAvailable(org.tortodrone.core.character.WorldProfile profile, String config) {
        var owned = new HashSet<String>();
        profile.town().harnesses().forEach(h -> owned.add(h.config()));
        if (!profile.town().sprites().isEmpty()) owned.add("Iron Harness");
        for (String existing : owned) {
            var seen = new HashSet<String>();
            while (!existing.isEmpty() && seen.add(existing)) {
                if (existing.equals(config)) return false;
                existing = harnessPrerequisite(existing);
            }
        }
        String required = harnessPrerequisite(config);
        return !profile.town().sprites().isEmpty() && (required.isEmpty() || owned.contains(required));
    }

    public float harnessRecharge(String name) {
        return harnessRecharge.getOrDefault(name, 1f);
    }

    Map<String, Catalog> catalogs = Map.of();
    Map<String, Bonus> bonuses = Map.of();

    public List<String> spriteVariants(org.tortodrone.core.character.WorldProfile profile) {
        var sprite = profile.town().sprites().stream()
                .filter(s -> s.id() == profile.town().equippedSprite())
                .findFirst()
                .orElse(null);
        return sprite != null && sprite.perk() != null && sprite.perk() >= 0 && sprite.perk() < perks.size()
                ? List.of("Sprite/" + perks.get(sprite.perk()).variant())
                : List.of();
    }

    public Bonus equipmentBonuses(org.tortodrone.core.character.WorldProfile profile, String weapon) {
        var refs = new ArrayList<String>();
        for (var item : profile.items())
            if (item.equippedSlot() != null && Set.of(0, 1, 2, 5, 10, 11, 12).contains(item.equippedSlot()))
                for (var variant : item.variants()) refs.add(variant.name());
        var sprite = profile.town().sprites().stream()
                .filter(s -> s.id() == profile.town().equippedSprite())
                .findFirst()
                .orElse(null);
        if (sprite != null && sprite.perk() != null && sprite.perk() >= 0 && sprite.perk() < perks.size())
            refs.add("Sprite/" + perks.get(sprite.perk()).variant());
        return bonus(refs, weapon);
    }

    public Bonus weaponBonuses(org.tortodrone.core.character.WorldProfile.Item item, String weapon) {
        return bonus(
                item == null
                        ? List.of()
                        : item.variants().stream()
                                .map(org.tortodrone.core.character.StarterCatalog.Model::name)
                                .toList(),
                weapon);
    }

    private Bonus bonus(List<String> refs, String weapon) {
        int health = 0;
        float charge = 0, speed = 0, attack = 0;
        for (String ref : refs) {
            var b = bonuses.get(ref);
            if (b == null || !b.weapon().isEmpty() && !b.weapon().equals(weapon)) continue;
            health += b.health();
            charge += b.charge();
            speed += b.speed();
            attack += b.attack();
        }
        return new Bonus("", health, charge, speed, attack);
    }

    Map<String, org.tortodrone.core.character.StarterCatalog.Model> accessoryModels = Map.of();
    Map<String, String> accessoryParameters = Map.of();

    public org.tortodrone.core.character.StarterCatalog.Model accessoryModel(String name) {
        return accessoryModels.get(name);
    }

    public String accessoryParameter(String name) {
        return accessoryParameters.get(name);
    }

    public int price(String name, int rarity, boolean buy) {
        String table = item(name).getOrDefault(buy ? "buyPrices" : "sellPrices", "");
        if (table.isEmpty()) return 0;
        String[] rows = table.split(",");
        int index = rows.length == 1 ? 0 : rarity;
        return index >= 0 && index < rows.length ? Integer.parseInt(rows[index]) : 0;
    }

    public long shopCycle(Shop shop) {
        var now = java.time.ZonedDateTime.now();
        return switch (shop.cycle()) {
            case "DAY", "INSTANCE" ->
                now.toLocalDate()
                        .plusDays(1)
                        .atStartOfDay(now.getZone())
                        .toInstant()
                        .toEpochMilli();
            case "WEEK" ->
                now.toLocalDate()
                        .with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.SUNDAY))
                        .atStartOfDay(now.getZone())
                        .toInstant()
                        .toEpochMilli();
            case "MONTH" ->
                now.toLocalDate()
                        .withDayOfMonth(1)
                        .plusMonths(1)
                        .atStartOfDay(now.getZone())
                        .toInstant()
                        .toEpochMilli();
            case "YEAR" ->
                now.toLocalDate()
                        .withDayOfYear(1)
                        .plusYears(1)
                        .atStartOfDay(now.getZone())
                        .toInstant()
                        .toEpochMilli();
            default -> 0;
        };
    }

    public List<Good> goods(Shop shop, long cycle, int world) {
        var result = new ArrayList<Good>();
        goods(shop.catalog(), new Random(cycle + (shop.perKnight() ? world : 0)), result, 0);
        return List.copyOf(result);
    }

    public List<Good> goods(Station station, long cycle, int world) {
        if (!station.kind().equals("OpenHarnessInstaller")
                || !station.shop().catalog().isEmpty()) return goods(station.shop(), cycle, world);
        return items.entrySet().stream()
                .filter(e -> harnessPrices.containsKey(e.getValue().get("harness")))
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new Good(e.getKey(), 1, harnessPrices.get(e.getValue().get("harness")), 0, 0, "", 0))
                .toList();
    }

    private void goods(String name, Random random, List<Good> output, int depth) {
        if (depth > 16) return;
        var catalog = catalogs.get(name);
        if (catalog == null || catalog.kind().equals("Optional") && random.nextFloat() >= catalog.chance()) return;
        var goods = new ArrayList<Good>();
        for (String include : catalog.includes()) goods(include, random, goods, depth + 1);
        goods.addAll(catalog.goods());
        if (catalog.kind().equals("Subset")) {
            if (catalog.max() < catalog.min()) return;
            int count = Math.min(goods.size(), random.nextInt(catalog.max() - catalog.min() + 1) + catalog.min() + 1);
            for (int i = 0; i < count; i++) output.add(goods.remove(random.nextInt(goods.size())));
        } else output.addAll(goods);
    }

    public Accessory accessory(String name) {
        return accessories.get(name);
    }

    public Set<String> blockers(String name) {
        return blockers.getOrDefault(name, Set.of());
    }

    public String harnessDamage(String name) {
        return harnesses.get(name);
    }

    public String accessoryTicket(String accessory) {
        var grants = items.entrySet().stream()
                .filter(e -> accessory.equals(e.getValue().get("accessory")))
                .map(Map.Entry::getKey)
                .filter(k -> !k.contains("/Parts/"))
                .sorted()
                .toList();
        return grants.size() == 1 ? grants.getFirst() : null;
    }

    public List<org.tortodrone.core.character.StarterCatalog.Model> roll(String table, String group)
            throws IOException {
        return roll(table, group, List.of());
    }
    /** Retained UVs exclude their entire weighted branch, including other tiers of that UV. */
    public List<org.tortodrone.core.character.StarterCatalog.Model> roll(
            String table, String group, List<org.tortodrone.core.character.StarterCatalog.Model> retained)
            throws IOException {
        var result = new ArrayList<org.tortodrone.core.character.StarterCatalog.Model>();
        var excluded = retained.stream()
                .map(org.tortodrone.core.character.StarterCatalog.Model::name)
                .collect(java.util.stream.Collectors.toSet());
        if (!table.isEmpty()) {
            var root = variantTables.get(table);
            if (root == null) throw new IOException("Missing variant table: " + table);
            int limit = integer(root, "maxResults") - retained.size();
            if (limit > 0) {
                rollNode(root, group, result, excluded, 0);
                trim(result, limit);
            }
        }
        result.addAll(retained);
        return List.copyOf(result);
    }

    private static void trim(List<?> values, int maximum) {
        while (values.size() > maximum)
            values.remove(java.util.concurrent.ThreadLocalRandom.current().nextInt(values.size()));
    }

    private void rollNode(
            Map<String, Object> node,
            String group,
            List<org.tortodrone.core.character.StarterCatalog.Model> result,
            Set<String> excluded,
            int depth)
            throws IOException {
        if (depth > 32) throw new IOException("Variant table recursion limit");
        String kind = string(node, "kind");
        var entries = list(node, "entries");
        var random = java.util.concurrent.ThreadLocalRandom.current();
        switch (kind) {
            case "VariantEntry" -> {
                String name = string(node, "variant");
                if (!name.isEmpty()) result.add(new org.tortodrone.core.character.StarterCatalog.Model(name, Map.of()));
            }
            case "TableEntry", "Derived" -> {
                Map<String, Object> root = entries.isEmpty()
                        ? variantTables.get(string(node, "table"))
                        : object(object(entries.getFirst()).get("node"));
                if (root == null) throw new IOException("Missing variant table: " + string(node, "table"));
                rollNode(root, group, result, excluded, depth + 1);
            }
            case "GroupEntry" -> {
                if (group.equals(string(node, "group")) && !entries.isEmpty())
                    rollNode(object(object(entries.getFirst()).get("node")), group, result, excluded, depth + 1);
            }
            case "OptionalEntry" -> {
                if (random.nextFloat() < ((Number) node.get("probability")).floatValue() && !entries.isEmpty())
                    rollNode(object(object(entries.getFirst()).get("node")), group, result, excluded, depth + 1);
            }
            case "RandomEntry", "SelectionEntry", "RandomSelectionEntry" -> {
                int selections = kind.equals("RandomEntry")
                        ? 1
                        : kind.equals("SelectionEntry")
                                ? integer(node, "selections")
                                : weighted(list(node, "selectionWeights").stream()
                                        .mapToDouble(v -> ((Number) v).doubleValue())
                                        .toArray());
                double[] weights = new double[entries.size()];
                for (int i = 0; i < weights.length; i++) {
                    var entry = object(entries.get(i));
                    weights[i] = Boolean.TRUE.equals(node.get("exclude"))
                                    && containsVariant(object(entry.get("node")), group, excluded, depth + 1)
                            ? 0
                            : ((Number) entry.get("weight")).doubleValue();
                }
                for (int i = 0; i < selections; i++) {
                    int selected = weighted(weights);
                    if (selected < 0) break;
                    rollNode(object(object(entries.get(selected)).get("node")), group, result, excluded, depth + 1);
                    weights[selected] = 0;
                }
            }
            case "CompoundEntry" -> {
                for (var e : entries) rollNode(object(object(e).get("node")), group, result, excluded, depth + 1);
            }
            case "Original", "VariantTableConfig" -> {
                var local = new ArrayList<org.tortodrone.core.character.StarterCatalog.Model>();
                for (var e : entries) rollNode(object(object(e).get("node")), group, local, excluded, depth + 1);
                trim(local, integer(node, "maxResults"));
                result.addAll(local);
            }
            default -> throw new IOException("Unsupported variant table node: " + kind);
        }
    }

    private boolean containsVariant(Map<String, Object> node, String group, Set<String> variants, int depth)
            throws IOException {
        if (depth > 32) throw new IOException("Variant table recursion limit");
        String kind = string(node, "kind");
        if (kind.equals("VariantEntry")) return variants.contains(string(node, "variant"));
        if (kind.equals("GroupEntry") && !group.equals(string(node, "group"))) return false;
        var entries = list(node, "entries");
        if ((kind.equals("TableEntry") || kind.equals("Derived")) && entries.isEmpty()) {
            var root = variantTables.get(string(node, "table"));
            return root != null && containsVariant(root, group, variants, depth + 1);
        }
        for (var entry : entries)
            if (containsVariant(object(object(entry).get("node")), group, variants, depth + 1)) return true;
        return false;
    }

    private static int weighted(double[] weights) {
        double total = 0;
        for (double weight : weights) total += Math.max(0, weight);
        if (total <= 0) return -1;
        double pick = java.util.concurrent.ThreadLocalRandom.current().nextDouble(total);
        for (int i = 0; i < weights.length; i++) {
            pick -= Math.max(0, weights[i]);
            if (pick < 0) return i;
        }
        return -1;
    }

    TownCatalog(
            Map<String, Recipe> recipes,
            Map<String, Map<String, String>> items,
            Map<String, Sprite> sprites,
            List<Perk> perks,
            Map<String, Station> stations) {
        this.recipes = Map.copyOf(recipes);
        this.items = Map.copyOf(items);
        this.sprites = Map.copyOf(sprites);
        this.perks = List.copyOf(perks);
        this.stations = Map.copyOf(stations);
    }

    public static TownCatalog empty() {
        return new TownCatalog(Map.of(), Map.of(), Map.of(), List.of(), Map.of());
    }

    public Recipe recipe(String name) {
        return recipes.get(name);
    }

    public Collection<Recipe> recipes() {
        return recipes.values();
    }

    public Map<String, String> item(String name) {
        return items.getOrDefault(name, Map.of());
    }

    public Sprite sprite(String name) {
        return sprites.get(name);
    }

    public List<Perk> perks() {
        return perks;
    }

    public Station station(String actor) {
        return stations.get(actor);
    }

    public Collection<Station> stations() {
        return stations.values();
    }
    /** Read the exported Town catalogue; the reader owns format, row shapes and limits. */
    public static TownCatalog load(Path path) throws IOException {
        return TownCatalogReader.load(path);
    }

    public static TownCatalog fromData(Object data) throws IOException {
        return TownCatalogReader.fromData(data);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private static List<?> list(Map<String, Object> object, String key) {
        return (List<?>) object.get(key);
    }

    private static String string(Map<String, Object> object, String key) {
        return (String) object.get(key);
    }

    private static int integer(Map<String, Object> object, String key) {
        return ((Number) object.get(key)).intValue();
    }
}
