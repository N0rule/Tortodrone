package org.tortodrone.storage;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.character.WorldProfile;
import org.tortodrone.core.town.TownCatalog;

/** Storefront availability, purchases and Supply Depot redemption. */
final class ShopStore {
    private final Database database;
    private final ItemCatalog items;
    private final TownCatalog rules;

    ShopStore(Database database, ItemCatalog items, TownCatalog rules) {
        this.database = database;
        this.items = items;
        this.rules = rules;
    }

    List<Integer> availability(int account, int knight, long auth, TownCatalog.Station station, long cycle)
            throws IOException {
        return database.read(c -> {
            c.setAutoCommit(false);
            var p = WorldProfiles.load(c, account, knight, auth);
            var result = availability(c, p, station, cycle);
            c.commit();
            return result;
        });
    }

    private List<Integer> availability(Connection c, WorldProfile p, TownCatalog.Station station, long cycle)
            throws SQLException {
        var result = new ArrayList<Integer>();
        for (var good : rules.goods(station, cycle, p.worldId())) {
            int available = station.kind().equals("OpenHarnessInstaller")
                    ? (rules.harnessAvailable(p, rules.item(good.item()).getOrDefault("harness", "")) ? 1 : 0)
                    : -1;
            if (good.limit() > 0) {
                int purchased = 0;
                try (var s = c.prepareStatement(
                        "SELECT purchased FROM town_purchases WHERE world_id=? AND catalog=? AND item=? AND cycle=?")) {
                    s.setInt(1, p.worldId());
                    s.setString(2, station.shop().catalog());
                    s.setString(3, good.item());
                    s.setLong(4, cycle);
                    try (var r = s.executeQuery()) {
                        if (r.next()) purchased = r.getInt(1);
                    }
                }
                available = Math.max(0, good.limit() - purchased);
            }
            result.add(available);
        }
        return List.copyOf(result);
    }

    Object shop(
            Connection c,
            WorldProfile p,
            TownCatalog.Station station,
            long cycle,
            Map<Integer, Integer> buys,
            Map<Long, Integer> sells)
            throws SQLException, IOException {
        if (cycle != 0 && cycle <= System.currentTimeMillis())
            throw new IOException("Shop catalogue changed; reopen the shop");
        var goods = rules.goods(station, cycle, p.worldId());
        var available = availability(c, p, station, cycle);
        var consume = new HashMap<Long, Integer>(sells);
        var tokens = new HashMap<String, Integer>();
        var purchased = new HashMap<String, Integer>();
        var limits = new HashMap<String, Integer>();
        for (int i = 0; i < goods.size(); i++)
            if (available.get(i) >= 0) limits.merge(goods.get(i).item(), available.get(i), Math::min);
        long crowns = 0, energy = 0;
        for (var sale : sells.entrySet()) {
            var item = TownLedger.owned(p, sale.getKey());
            var def = items.require(item.config());
            int price = rules.price(item.config(), def.rarity(), false);
            if (sale.getValue() < 1
                    || sale.getValue() > item.count()
                    || item.equippedSlot() != null
                    || item.permanent()
                    || price <= 0) throw new IOException("Item cannot be sold");
            crowns += (long) price * sale.getValue();
        }
        for (var buy : buys.entrySet()) {
            int index = buy.getKey(), count = buy.getValue();
            if (index < 0
                    || index >= goods.size()
                    || count < 1
                    || count > 9999
                    || available.get(index) >= 0 && count > available.get(index))
                throw new IOException("This good is not available");
            var good = goods.get(index);
            var def = items.require(good.item());
            int price = good.price() >= 0 ? good.price() : rules.price(good.item(), def.rarity(), true);
            purchased.merge(good.item(), count, Math::addExact);
            if (!good.token().isEmpty()) {
                long tokenCount = (long) good.tokenCount() * count;
                if (tokenCount > 9999) throw new IOException("Token requirement exceeds limit");
                tokens.merge(good.token(), (int) tokenCount, Math::addExact);
            } else {
                crowns -= (long) price * count;
                energy += (long) good.energy() * count;
            }
        }
        for (var entry : purchased.entrySet())
            if (limits.containsKey(entry.getKey()) && entry.getValue() > limits.get(entry.getKey()))
                throw new IOException("This good is not available");
        for (var required : tokens.entrySet()) {
            int needed = required.getValue();
            for (var held : p.items())
                if (held.config().equals(required.getKey()) && held.equippedSlot() == null && !held.permanent()) {
                    int free = held.count() - consume.getOrDefault(held.id(), 0),
                            used = Math.min(Math.max(0, free), needed);
                    if (used > 0) {
                        consume.merge(held.id(), used, Math::addExact);
                        needed -= used;
                    }
                    if (needed == 0) break;
                }
            if (needed != 0) throw new Database.Refused("m.nsf_tokens");
        }
        if (crowns + p.crowns() < 0) throw new Database.Refused("m.nsf_crowns");
        if (crowns + p.crowns() > Integer.MAX_VALUE) throw new IOException("Currency balance exceeds supported limits");
        if (energy < 0 || energy > p.energy()) throw new Database.Refused("m.nsf_energy");
        TownLedger.credit(c, p.worldId(), Math.toIntExact(crowns), -(int) energy);
        for (var use : consume.entrySet()) TownLedger.consume(c, p, use.getKey(), use.getValue());
        for (var buy : buys.entrySet()) {
            var good = goods.get(buy.getKey());
            long quantity = (long) good.count() * buy.getValue();
            if (quantity > 9999) throw new IOException("Purchase count exceeds limit");
            var d = items.require(good.item());
            boolean locked = station.shop().preparer().equals("LOCKED")
                    || station.shop().preparer().equals("LOCK_NON_STACKABLE") && !d.stackable();
            TownLedger.create(c, p, items, good.item(), (int) quantity, locked, 0, 0, List.of());
            if (limits.containsKey(good.item()))
                try (var s = c.prepareStatement(
                        "INSERT INTO town_purchases(world_id,catalog,item,cycle,purchased) VALUES(?,?,?,?,?) ON CONFLICT(world_id,catalog,item,cycle) DO UPDATE SET purchased=purchased+excluded.purchased")) {
                    s.setInt(1, p.worldId());
                    s.setString(2, station.shop().catalog());
                    s.setString(3, good.item());
                    s.setLong(4, cycle);
                    s.setInt(5, buy.getValue());
                    s.executeUpdate();
                }
        }
        return null;
    }

    Object depot(Connection c, WorldProfile p, int index, int quotedPrice, DepotStore.Quote quote)
            throws SQLException, IOException {
        var snapshot = DepotStore.effective(c, System.currentTimeMillis());
        var offer = snapshot.offers().stream()
                .filter(o -> o.id() == index && o.enabled())
                .findFirst()
                .orElseThrow(() -> new IOException("e.good_unavailable"));
        if (quote != null && (quote.revision() != snapshot.revision() || !offer.equals(quote.offer())))
            throw new IOException("e.good_unavailable");
        var good = offer.good();
        var def = items.require(good.item());
        int cost = good.energy() > 0 ? good.energy() : good.price();
        if (cost < 0 || quotedPrice != cost || !def.grantable()) throw new IOException("e.good_unavailable");
        if (good.limit() > 0) {
            try (var st = c.prepareStatement(
                    "SELECT purchased FROM town_purchases WHERE world_id=? AND catalog='Supply Depot' AND item=? AND cycle=0")) {
                st.setInt(1, p.worldId());
                st.setString(2, good.item());
                try (var r = st.executeQuery()) {
                    if (r.next() && r.getInt(1) >= good.limit()) throw new IOException("e.good_unavailable");
                }
            }
        }
        TownLedger.pay(c, p, good.energy() > 0 ? 0 : cost, good.energy() > 0 ? cost : 0);
        boolean locked =
                def.variant() == ItemCatalog.Variant.LEVEL_ITEM && !def.type().endsWith("Costume");
        TownLedger.create(c, p, items, good.item(), good.count(), locked, 0, 0, List.of());
        try (var st = c.prepareStatement(
                "INSERT INTO town_purchases(world_id,catalog,item,cycle,purchased) VALUES(?,'Supply Depot',?,0,1) ON CONFLICT(world_id,catalog,item,cycle) DO UPDATE SET purchased=purchased+1")) {
            st.setInt(1, p.worldId());
            st.setString(2, good.item());
            st.executeUpdate();
        }
        return null;
    }
}
