package org.tortodrone.storage;

import java.io.*;
import java.sql.*;
import java.util.*;
import org.tortodrone.character.*;
import org.tortodrone.core.Json;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.character.WorldProfile;
import org.tortodrone.core.town.*;
import org.tortodrone.core.town.TownCatalog;
import org.tortodrone.core.town.TownState;

/** Town inventory/progression mutations share the existing authenticated SQLite write transaction. */
public final class TownStore {
    private final Database database;
    private final ItemCatalog items;
    private final TownCatalog rules;
    private final SpriteStore sprites;
    private final CraftStore crafts;
    private final GearStore gears;
    private final ShopStore shops;
    private final GiftWrapStore gifts;
    private final CustomizeStore customize;
    private final UpgradeStore upgrades;
    private final UseStore uses;
    private final AdminStore admins;
    private final RenameStore renames;

    public TownStore(Database database, ItemCatalog items, TownCatalog rules) {
        this.database = database;
        this.items = items;
        this.rules = rules;
        this.sprites = new SpriteStore(items, rules);
        this.crafts = new CraftStore(items, rules);
        this.gears = new GearStore(items, rules);
        this.shops = new ShopStore(database, items, rules);
        this.gifts = new GiftWrapStore(items, rules);
        this.customize = new CustomizeStore(items, rules);
        this.upgrades = new UpgradeStore();
        this.uses = new UseStore(database, items);
        this.admins = new AdminStore(items, rules);
        this.renames = new RenameStore(database, items);
    }

    public static final long RENAME_INTERVAL = 30L * 24 * 60 * 60 * 1000;
    /** Stock canRename: the window only opens inside the thirty-day rename interval. */
    public void canRename(int account, int knight, long auth) throws IOException {
        renames.canRename(account, knight, auth);
    }
    /** The rename ticket and globally unique identity commit in the same authenticated write. */
    public Result rename(int account, int knight, long auth, String name) throws IOException {
        return renames.rename(account, knight, auth, name);
    }

    static TownState load(Connection c, int world) throws SQLException, IOException {
        var recipes = new ArrayList<TownState.LearnedRecipe>();
        var sprites = new ArrayList<TownState.Sprite>();
        var harnesses = new ArrayList<TownState.Harness>();
        int equipped = 0;
        try (var s = c.prepareStatement("SELECT * FROM town_recipes WHERE world_id=? ORDER BY id")) {
            s.setInt(1, world);
            try (var r = s.executeQuery()) {
                while (r.next())
                    recipes.add(new TownState.LearnedRecipe(
                            r.getLong("id"), r.getString("config"), r.getInt("crafted"), r.getLong("expires")));
            }
        }
        try (var s = c.prepareStatement("SELECT * FROM town_sprites WHERE world_id=? ORDER BY id")) {
            s.setInt(1, world);
            try (var r = s.executeQuery()) {
                while (r.next())
                    sprites.add(new TownState.Sprite(
                            r.getInt("id"),
                            r.getString("config"),
                            r.getString("name"),
                            r.getInt("level"),
                            r.getFloat("heat"),
                            r.getObject("last_full") == null ? null : r.getLong("last_full"),
                            r.getLong("full_stamp"),
                            r.getInt("perk_level"),
                            new HashSet<>(numbers(r.getString("perks"))),
                            (Integer) r.getObject("perk"),
                            numbers(r.getString("powers")),
                            numbers(r.getString("ultimates")),
                            r.getLong("harness")));
            }
        }
        try (var s = c.prepareStatement("SELECT * FROM town_harnesses WHERE world_id=? ORDER BY id")) {
            s.setInt(1, world);
            try (var r = s.executeQuery()) {
                while (r.next()) harnesses.add(new TownState.Harness(r.getLong("id"), r.getString("config")));
            }
        }
        try (var s = c.prepareStatement("SELECT sprite FROM town_equipped WHERE world_id=?")) {
            s.setInt(1, world);
            try (var r = s.executeQuery()) {
                if (r.next()) equipped = r.getInt(1);
            }
        }
        return new TownState(
                recipes,
                sprites,
                equipped,
                harnesses,
                TownMail.loadMail(c, world),
                NamedLoadouts.load(c, world),
                AuctionHouse.loadOutbids(c, world));
    }

    private static List<Integer> numbers(String json) throws IOException {
        return ((List<?>) Json.parse(json))
                .stream().map(v -> ((Number) v).intValue()).toList();
    }

    static List<String> strings(String json) throws IOException {
        return ((List<?>) Json.parse(json)).stream().map(v -> (String) v).toList();
    }

    static List<StarterCatalog.Model> variants(String json) throws IOException {
        var result = new ArrayList<StarterCatalog.Model>();
        for (var v : (List<?>) Json.parse(json)) result.add(new StarterCatalog.Model((String) v, Map.of()));
        return List.copyOf(result);
    }

    public record Result(WorldProfile profile, Object result) {}
    /** Scene-issued receipt, never accepted from a client invocation. Commit before actor removal.
     * The receipt is global to the pickup, so a reconnect/competing account cannot claim it twice. */
    public Result pickup(int account, int knight, long auth, String receipt, String config) throws IOException {
        return uses.pickup(account, knight, auth, receipt, config);
    }

    public static final int SPARK_COST = org.tortodrone.core.economy.Fees.SPARK_COST;
    /** Official ItemMarshaller.deleteItem: one owned, non-permanent, unequipped item goes whole. */
    public Result trash(int account, int knight, long auth, long item) throws IOException {
        return change(account, knight, auth, (c, p) -> uses.trash(c, p, item));
    }
    /** Only verified cosmetic AnimatedAction items; revision/ownership checked in the same write. */
    public Result confetti(int account, int knight, long auth, long revision, long item) throws IOException {
        return animatedItem(account, knight, auth, revision, item, true);
    }

    public Result animatedItem(int account, int knight, long auth, long revision, long item) throws IOException {
        return animatedItem(account, knight, auth, revision, item, false);
    }

    private Result animatedItem(int account, int knight, long auth, long revision, long item, boolean confetti)
            throws IOException {
        return change(account, knight, auth, (c, p) -> uses.animated(c, p, revision, item, confetti));
    }
    /** Consume one owned scene-usable item; the scene applies its prepared effect under
     *  the same lock, so a refused revision or item leaves both sides unchanged. */
    public Result useItem(int account, int knight, long auth, long revision, long item) throws IOException {
        return changeBounded(account, knight, auth, (c, p) -> uses.use(c, p, revision, item));
    }
    /**
     * Credit the account-scoped lifetime counters that native scene actions attach to a
     * consumed capsule. The scene effect has already run; the receipt is one transaction
     * and reconciles achievements before the caller publishes the updated profile.
     */
    public Result stats(int account, int knight, long auth, Map<String, Integer> counters) throws IOException {
        return change(account, knight, auth, (c, p) -> uses.stats(c, p, counters));
    }
    /** Native PartyMarshaller.revive: -1 spends one owned Spark; a positive approval buys its use. */
    public Result revive(int account, int knight, long auth, int approvedCost) throws IOException {
        if (approvedCost < -1) throw new IOException("m.costupdate");
        return changeBounded(account, knight, auth, (c, p) -> uses.revive(c, p, approvedCost));
    }

    private Result change(int account, int knight, long auth, TownMutations.Mutation mutation) throws IOException {
        return TownMutations.change(database, items, account, knight, auth, mutation);
    }
    /** Scene-locked item use and revive: refuse before opening a connection when the
     *  shared writer is still busy after the queue budget. */
    private Result changeBounded(int account, int knight, long auth, TownMutations.Mutation mutation)
            throws IOException {
        return TownMutations.changeBounded(database, items, account, knight, auth, mutation);
    }

    long create(
            Connection c,
            WorldProfile p,
            String config,
            int count,
            boolean locked,
            int level,
            float heat,
            List<StarterCatalog.Model> variants)
            throws SQLException, IOException {
        return TownLedger.create(c, p, items, config, count, locked, level, heat, variants);
    }

    public List<Integer> availability(int account, int knight, long auth, TownCatalog.Station station, long cycle)
            throws IOException {
        return shops.availability(account, knight, auth, station, cycle);
    }

    public Result shop(
            int account,
            int knight,
            long auth,
            TownCatalog.Station station,
            long cycle,
            Map<Integer, Integer> buys,
            Map<Long, Integer> sells)
            throws IOException {
        return change(account, knight, auth, (c, p) -> shops.shop(c, p, station, cycle, buys, sells));
    }

    public Result depot(int account, int knight, long auth, int index, int quotedPrice) throws IOException {
        return depot(account, knight, auth, index, quotedPrice, null);
    }

    public Result depot(int account, int knight, long auth, int index, int quotedPrice, DepotStore.Quote quote)
            throws IOException {
        return change(account, knight, auth, (c, p) -> shops.depot(c, p, index, quotedPrice, quote));
    }

    public Result forge(int account, int knight, long auth, long itemId, int crystals, int expectedLevel)
            throws IOException {
        return change(account, knight, auth, (c, p) -> gears.forge(c, p, itemId, crystals, expectedLevel));
    }
    /** Trusted heat awards split only across equipped gear still able to receive heat. */
    public Result addHeat(int account, int knight, long auth, float amount) throws IOException {
        if (!Float.isFinite(amount) || amount <= 0 || amount > 1_000_000) throw new IOException("Invalid heat award");
        return change(account, knight, auth, (c, p) -> gears.addHeat(c, p, amount));
    }

    static void applyHeat(Connection c, WorldProfile p, TownCatalog rules, float amount)
            throws SQLException, IOException {
        GearStore.applyHeat(c, p, rules, amount);
    }

    static void requireGameMaster(Connection c, int account, long auth) throws SQLException, IOException {
        try (var st = c.prepareStatement("SELECT game_master FROM accounts WHERE id=? AND auth_revision=?")) {
            st.setInt(1, account);
            st.setLong(2, auth);
            try (var r = st.executeQuery()) {
                if (!r.next() || !r.getBoolean(1)) throw new IOException("Game Master permission required");
            }
        }
    }
    /** Read-only recheck for tools whose effect is not a town mutation. */
    public void requireGameMaster(int account, long auth) throws IOException {
        database.read(c -> {
            requireGameMaster(c, account, auth);
            return null;
        });
    }

    public Result loadout(int account, int knight, long auth, String name, boolean save) throws IOException {
        return change(account, knight, auth, (c, p) -> {
            if (save) NamedLoadouts.save(c, p, name);
            else NamedLoadouts.equip(c, p, name, rules);
            return null;
        });
    }

    public Result loadoutPvp(int account, int knight, long auth, String name, boolean enabled) throws IOException {
        return change(account, knight, auth, (c, p) -> {
            NamedLoadouts.pvp(c, p, name, enabled);
            return null;
        });
    }

    public Result removeLoadout(int account, int knight, long auth, String name) throws IOException {
        return change(account, knight, auth, (c, p) -> {
            NamedLoadouts.remove(c, p, name);
            return null;
        });
    }

    public Result adminAccessory(int account, int knight, long auth, int slot, String name, String costume)
            throws IOException {
        return change(
                account, knight, auth, (c, p) -> customize.adminAccessory(c, p, account, auth, slot, name, costume));
    }

    static void editAccessory(
            Connection c, WorldProfile p, WorldProfile.Item gear, String name, String costume, TownCatalog rules)
            throws IOException, SQLException {
        CustomizeStore.editAccessory(c, p, gear, name, costume, rules);
    }
    /** Native /grant and /set_rank: role and ownership checked inside the write transaction. */
    /** Native /grant and /set_rank: role and ownership checked inside the write transaction. */
    public Result adminProgress(int account, int knight, long auth, int method, List<Object> args) throws IOException {
        return change(account, knight, auth, (c, p) -> admins.progress(c, p, account, auth, method, args));
    }
    /** Current stock /heat, /level and /forge commands; setters never grant normal-player authority. */
    public Result adminGear(int account, int knight, long auth, int method, List<Object> args) throws IOException {
        return change(account, knight, auth, (c, p) -> admins.gear(c, p, account, auth, method, args));
    }
    /** Native extendUpgrade(active item, unused item), ItemMarshaller method five. */
    public Result extendUpgrade(int account, int knight, long auth, long active, long unused) throws IOException {
        return change(account, knight, auth, (c, p) -> upgrades.extend(c, p, active, unused));
    }

    public Result activateUpgrade(int account, int knight, long auth, long id) throws IOException {
        return change(account, knight, auth, (c, p) -> upgrades.activate(c, p, id));
    }

    public Result learn(int account, int knight, long auth, long item) throws IOException {
        return change(account, knight, auth, (c, p) -> crafts.learn(c, p, item));
    }

    public Result craft(
            int account, int knight, long auth, String recipe, long[] selected, boolean upgrade, boolean catalogue)
            throws IOException {
        return change(account, knight, auth, (c, p) -> crafts.craft(c, p, recipe, selected, upgrade, catalogue));
    }

    public Result wrap(
            int account,
            int knight,
            long auth,
            long paperId,
            Map<Long, Integer> contents,
            String message,
            String costume)
            throws IOException {
        if (message == null
                || message.length() > 300
                || message.chars().anyMatch(ch -> ch == 0)
                || contents.isEmpty()
                || contents.size() > 256) throw new IOException("e.not_wrapable");
        var selected = Map.copyOf(contents);
        return change(
                account,
                knight,
                auth,
                (c, p) -> gifts.wrap(c, p, account, knight, paperId, selected, message, costume));
    }

    public Result unwrap(int account, int knight, long auth, long boxId) throws IOException {
        return change(account, knight, auth, (c, p) -> gifts.unwrap(c, p, boxId));
    }
    /** Native VariantMarshaller: custom ticket, or a selected random-variant count. */
    public Result variant(
            int account, int knight, long auth, int count, long gearId, long ticketId, int keepMask, boolean custom)
            throws IOException {
        return change(
                account, knight, auth, (c, p) -> customize.variant(c, p, count, gearId, ticketId, keepMask, custom));
    }

    public Result unbind(int account, int knight, long auth, long item, long ticket) throws IOException {
        return change(account, knight, auth, (c, p) -> customize.unbind(c, p, item, ticket));
    }

    public Result accessorize(
            int account, int knight, long auth, int method, long gearId, Object argument, String costume)
            throws IOException {
        return change(account, knight, auth, (c, p) -> customize.accessorize(c, p, method, gearId, argument, costume));
    }

    /** Archive grantStarterSprite: the pod gate checks sprite count, not mission completion. */
    static boolean grantStarterSprite(Connection c, WorldProfile p, String config) throws SQLException, IOException {
        return SpriteStore.grantStarterSprite(c, p, config);
    }
    /** Stock ProjectXAdminMarshaller IDs; GM setters bypass normal point/catalyst requirements. */
    public Result adminSprite(int account, int knight, long auth, int method, List<Object> args) throws IOException {
        return change(account, knight, auth, (c, p) -> sprites.adminSprite(c, p, account, auth, method, args));
    }

    public Result hatch(int account, int knight, long auth, long egg) throws IOException {
        return change(account, knight, auth, (c, p) -> sprites.hatch(c, p, egg));
    }

    public Result installHarnesses(
            int account, int knight, long auth, TownCatalog.Station station, long cycle, Set<Integer> selected)
            throws IOException {
        return change(account, knight, auth, (c, p) -> sprites.installHarnesses(c, p, station, cycle, selected));
    }
    /** Current archive harness installation consumes one kit and persists one owned harness. */
    public Result installHarness(int account, int knight, long auth, long kit) throws IOException {
        return change(account, knight, auth, (c, p) -> sprites.installHarness(c, p, kit));
    }
    /** Sprite service IDs and argument order are the current stock BattleSpriteMarshaller's. */
    public Result sprite(int account, int knight, long auth, int method, List<Object> args, boolean staff)
            throws IOException {
        return change(account, knight, auth, (c, p) -> sprites.sprite(c, p, method, args, staff));
    }

    static Set<Integer> unlocked(TownCatalog rules, int level, Set<Integer> perks) {
        var result = new HashSet<Integer>();
        for (int i = 0; i < rules.perks().size(); i++)
            if (rules.perks().get(i).tier() < level || perks.contains(i)) result.add(i);
        var remove = new HashSet<Integer>();
        for (int i : result) {
            String replaced = rules.perks().get(i).previous();
            for (int j : result) if (rules.perks().get(j).name().equals(replaced)) remove.add(j);
        }
        result.removeAll(remove);
        return result;
    }
}
