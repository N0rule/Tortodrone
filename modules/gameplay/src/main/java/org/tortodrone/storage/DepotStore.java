package org.tortodrone.storage;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.town.TownCatalog;

/** SQLite is the sole catalog authority. Quotes and purchases use the same effective-offer resolver. */
public final class DepotStore {
    public record Offer(
            int id,
            TownCatalog.Good good,
            boolean featured,
            boolean enabled,
            boolean promotionOnly,
            int originalPrice,
            boolean sale,
            long promotionId,
            long promotionEnds) {
        public Offer(int id, TownCatalog.Good good, boolean featured, boolean enabled) {
            this(id, good, featured, enabled, false, 0, false, 0, 0);
        }

        public Offer(int id, TownCatalog.Good good, boolean featured, boolean enabled, boolean promotionOnly) {
            this(id, good, featured, enabled, promotionOnly, 0, false, 0, 0);
        }
    }

    public record Snapshot(long revision, List<Offer> offers) {
        public Snapshot {
            offers = List.copyOf(offers);
        }
    }

    public record Quote(long revision, Offer offer) {}
    /** Unix milliseconds UTC; starts <= now < ends. Null reference price is a temporary direct price. */
    public record Promotion(
            long id,
            int offerId,
            long starts,
            long ends,
            int crowns,
            int energy,
            Integer referencePrice,
            boolean featured,
            boolean cancelled) {
        public String status(long now) {
            return cancelled ? "Cancelled" : now < starts ? "Scheduled" : now >= ends ? "Expired" : "Active";
        }
    }

    private final Database database;
    private final ItemCatalog items;

    public DepotStore(Database database, ItemCatalog items, TownCatalog rules) {
        this.database = database;
        this.items = items;
    }

    public Snapshot snapshot() throws IOException {
        return database.read(c -> {
            c.setAutoCommit(false);
            var result = load(c);
            c.commit();
            return result;
        });
    }

    public Snapshot catalog() throws IOException {
        return database.read(c -> {
            c.setAutoCommit(false);
            var result = effective(c, System.currentTimeMillis());
            c.commit();
            return result;
        });
    }

    static Snapshot load(Connection c) throws SQLException, IOException {
        try (var s = c.createStatement();
                var r = s.executeQuery("SELECT value FROM metadata WHERE key='supply-depot-initialized'")) {
            if (!r.next() || r.getInt(1) != 1) throw new IOException("Supply Depot has not been initialized");
        }
        var offers = new ArrayList<Offer>();
        try (var s = c.createStatement();
                var r = s.executeQuery("SELECT * FROM town_depot_offers ORDER BY id")) {
            while (r.next())
                offers.add(new Offer(
                        r.getInt("id"),
                        new TownCatalog.Good(
                                r.getString("item"),
                                r.getInt("count"),
                                r.getInt("crowns"),
                                r.getInt("energy"),
                                r.getInt("purchase_limit"),
                                "",
                                0),
                        r.getBoolean("featured"),
                        r.getBoolean("enabled"),
                        r.getBoolean("promotion_only")));
        }
        return new Snapshot(DepotSchema.revision(c), offers);
    }

    static Snapshot effective(Connection c, long now) throws SQLException, IOException {
        var base = load(c);
        var active = new HashMap<Integer, Promotion>();
        try (var s = c.prepareStatement(
                "SELECT * FROM town_depot_promotions WHERE cancelled=0 AND starts<=? AND ends>? ORDER BY id")) {
            s.setLong(1, now);
            s.setLong(2, now);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    var p = promotion(r);
                    if (active.putIfAbsent(p.offerId(), p) != null)
                        throw new IOException("Conflicting Supply Depot promotions");
                }
            }
        }
        var offers = new ArrayList<Offer>();
        for (var o : base.offers()) {
            var p = active.get(o.id());
            var g = o.good();
            boolean available = o.enabled() && (!o.promotionOnly() || p != null);
            offers.add(
                    p == null
                            ? new Offer(o.id(), g, o.featured(), available, o.promotionOnly())
                            : new Offer(
                                    o.id(),
                                    new TownCatalog.Good(g.item(), g.count(), p.crowns(), p.energy(), g.limit(), "", 0),
                                    o.featured() || p.featured(),
                                    available,
                                    o.promotionOnly(),
                                    p.referencePrice() == null ? 0 : p.referencePrice(),
                                    p.referencePrice() != null,
                                    p.id(),
                                    p.ends()));
        }
        return new Snapshot(base.revision(), offers);
    }

    private void validate(Offer offer) throws IOException {
        var g = offer.good();
        DepotSchema.values(g);
        var item = items.find(g.item());
        if (item == null
                || offer.enabled() && !item.grantable()
                || g.count() > (item.stackable() ? 9999 : 1)
                || g.price() > 0 && g.energy() > 0
                || offer.originalPrice() != 0
                || offer.sale()
                || offer.promotionId() != 0
                || offer.promotionEnds() != 0)
            throw new IOException(
                    "Choose a grantable item, valid quantity and one currency; edit promotions separately");
    }

    private static void expected(Connection c, long expected) throws SQLException, IOException {
        if (DepotSchema.revision(c) != expected) throw new IOException("Supply Depot changed; refresh before editing");
    }

    public Snapshot save(long expectedRevision, Offer offer) throws IOException {
        validate(offer);
        return database.write(c -> {
            expected(c, expectedRevision);
            if (offer.id() < 0) DepotSchema.insert(c, offer, false);
            else
                try (var s = c.prepareStatement(
                        "UPDATE town_depot_offers SET item=?,count=?,crowns=?,energy=?,purchase_limit=?,featured=?,enabled=?,promotion_only=? WHERE id=?")) {
                    // Historical item-config purchase limits must never be reset by repointing an ID.
                    var old = load(c).offers().stream()
                            .filter(o -> o.id() == offer.id())
                            .findFirst()
                            .orElseThrow(() -> new IOException("Offer no longer exists"));
                    if (!old.good().item().equals(offer.good().item()))
                        throw new IOException("Create a new offer to change its item");
                    DepotSchema.bind(s, offer);
                    s.setInt(9, offer.id());
                    if (s.executeUpdate() != 1) throw new IOException("Offer changed");
                }
            DepotSchema.advance(c, expectedRevision);
            return load(c);
        });
    }

    public List<Promotion> promotions() throws IOException {
        return database.read(DepotStore::promotions);
    }

    private static List<Promotion> promotions(Connection c) throws SQLException {
        var result = new ArrayList<Promotion>();
        try (var s = c.createStatement();
                var r = s.executeQuery("SELECT * FROM town_depot_promotions ORDER BY starts,id")) {
            while (r.next()) result.add(promotion(r));
        }
        return List.copyOf(result);
    }

    private static Promotion promotion(ResultSet r) throws SQLException {
        int reference = r.getInt("reference_price");
        boolean absent = r.wasNull();
        return new Promotion(
                r.getLong("id"),
                r.getInt("offer_id"),
                r.getLong("starts"),
                r.getLong("ends"),
                r.getInt("crowns"),
                r.getInt("energy"),
                absent ? null : reference,
                r.getBoolean("featured"),
                r.getBoolean("cancelled"));
    }

    public Snapshot schedule(long expectedRevision, Promotion p) throws IOException {
        int price = p.energy() > 0 ? p.energy() : p.crowns();
        if (p.id() != 0
                || p.cancelled()
                || p.starts() < 0
                || p.ends() <= p.starts()
                || p.crowns() < 0
                || p.energy() < 0
                || p.crowns() > 0 && p.energy() > 0
                || p.referencePrice() != null && p.referencePrice() <= price)
            throw new IOException(
                    "Choose an ordered UTC window, one currency and an optional regular price greater than the sale price");
        return database.write(c -> {
            expected(c, expectedRevision);
            var offer = load(c).offers().stream()
                    .filter(o -> o.id() == p.offerId())
                    .findFirst()
                    .orElseThrow(() -> new IOException("Unknown promotion offer"));
            var definition = items.find(offer.good().item());
            if (definition == null || !definition.grantable())
                throw new IOException("Promotion item cannot be granted");
            int regular = offer.good().energy() > 0
                    ? offer.good().energy()
                    : offer.good().price();
            if (p.referencePrice() != null
                    && (p.referencePrice() != regular
                            || (p.energy() > 0) != (offer.good().energy() > 0)))
                throw new IOException(
                        "A discount comparison must match this offer's current regular price and currency");
            for (var other : promotions(c))
                if (!other.cancelled()
                        && other.offerId() == p.offerId()
                        && other.starts() < p.ends()
                        && p.starts() < other.ends())
                    throw new IOException("Promotion windows for one offer must not overlap");
            try (var s = c.prepareStatement(
                    "INSERT INTO town_depot_promotions(offer_id,starts,ends,crowns,energy,reference_price,featured) VALUES(?,?,?,?,?,?,?)")) {
                s.setInt(1, p.offerId());
                s.setLong(2, p.starts());
                s.setLong(3, p.ends());
                s.setInt(4, p.crowns());
                s.setInt(5, p.energy());
                if (p.referencePrice() == null) s.setNull(6, Types.INTEGER);
                else s.setInt(6, p.referencePrice());
                s.setBoolean(7, p.featured());
                s.executeUpdate();
            }
            DepotSchema.advance(c, expectedRevision);
            return load(c);
        });
    }

    public Snapshot cancelPromotion(long expectedRevision, long id) throws IOException {
        return database.write(c -> {
            expected(c, expectedRevision);
            try (var s =
                    c.prepareStatement("UPDATE town_depot_promotions SET cancelled=1 WHERE id=? AND cancelled=0")) {
                s.setLong(1, id);
                if (s.executeUpdate() != 1) throw new IOException("Promotion no longer available");
            }
            DepotSchema.advance(c, expectedRevision);
            return load(c);
        });
    }
}
