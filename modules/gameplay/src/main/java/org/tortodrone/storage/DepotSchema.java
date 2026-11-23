package org.tortodrone.storage;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import org.tortodrone.core.town.TownCatalog;

/**
 * Supply Depot table and seed policy for the 0.42.0 baseline.
 *
 * <p>{@link DepotStore} keeps the runtime catalogue and its revision-guarded quote, purchase and
 * promotion paths. This class owns the managed tables, the legacy seed import, default alignment
 * and the revision metadata those paths share.
 */
final class DepotSchema {
    private DepotSchema() { }

    /** Seeded featured flags shipped before the default shop became plain; none were administrator promotions. */
    private record LegacyFeatured(int id,String item,int energy) { }
    private static final List<LegacyFeatured> LEGACY_SEEDED_FEATURED=List.of(
            new LegacyFeatured(0,"Upgrade/Heat Bonus (7 days)",1600),
            new LegacyFeatured(1,"Upgrade/Heat Bonus (30 days)",5000),
            new LegacyFeatured(2,"Upgrade/Trinket Slot",150),
            new LegacyFeatured(3,"Ticket/Keys/Silver Key",750),
            new LegacyFeatured(4,"Upgrade/Coin Bonus",300));
    /** Official offers earlier releases withheld because their native support was not yet verified. */
    private record SeededGrant(int id,String item,int count,int crowns,int energy) { }
    private static final List<SeededGrant> SEEDED_GRANTS=List.of(
            new SeededGrant(0,"Upgrade/Heat Bonus (7 days)",1,0,1600),
            new SeededGrant(1,"Upgrade/Heat Bonus (30 days)",1,0,5000),
            new SeededGrant(2,"Upgrade/Trinket Slot",1,0,150),
            new SeededGrant(4,"Upgrade/Coin Bonus",1,0,300),
            new SeededGrant(154,"Gear/Shield/Heater Shield",1,300000,3500),
            new SeededGrant(160,"Gear/Shield/Omega Shell",1,300000,3500),
            new SeededGrant(192,"Upgrade/Heat Bonus",1,0,800),
            new SeededGrant(196,"Upgrade/Weapon Slot",1,0,250),
            new SeededGrant(198,"Weapon/Bomb/Big Angry Bomb",1,300000,3500));

    // Managed Depot base table; the baseline adds promotions in the same transaction.
    static void initialize(Connection c)throws SQLException {
        try(var s=c.createStatement()){s.execute("CREATE TABLE town_depot_offers(id INTEGER PRIMARY KEY AUTOINCREMENT,item TEXT NOT NULL,count INTEGER NOT NULL CHECK(count BETWEEN 1 AND 9999),crowns INTEGER NOT NULL CHECK(crowns>=0),energy INTEGER NOT NULL CHECK(energy>=0),purchase_limit INTEGER NOT NULL CHECK(purchase_limit>=0),featured INTEGER NOT NULL CHECK(featured IN(0,1)),enabled INTEGER NOT NULL CHECK(enabled IN(0,1))) STRICT");}
    }

    /** Caller owns BEGIN IMMEDIATE. Never infer initialization from the number of offers. */
    static void upgrade(Connection c,DepotSeed.Data legacy)throws SQLException,IOException {
        try(var s=c.createStatement()) {
            s.execute("ALTER TABLE town_depot_offers ADD COLUMN promotion_only INTEGER NOT NULL DEFAULT 0 CHECK(promotion_only IN(0,1))");
            s.execute("CREATE TABLE town_depot_promotions(id INTEGER PRIMARY KEY AUTOINCREMENT,offer_id INTEGER NOT NULL REFERENCES town_depot_offers(id),starts INTEGER NOT NULL CHECK(starts>=0),ends INTEGER NOT NULL CHECK(ends>starts),crowns INTEGER NOT NULL CHECK(crowns>=0),energy INTEGER NOT NULL CHECK(energy>=0),reference_price INTEGER CHECK(reference_price>0),featured INTEGER NOT NULL CHECK(featured IN(0,1)),cancelled INTEGER NOT NULL DEFAULT 0 CHECK(cancelled IN(0,1)),CHECK(crowns=0 OR energy=0)) STRICT");
            s.execute("CREATE INDEX town_depot_windows ON town_depot_promotions(offer_id,cancelled,starts,ends)");
        }
        AuctionSchema.economyUpgrade(c);
        long revision=revision(c);
        if(revision==0) {
            if(legacy==null)throw new IOException("Legacy YAML-backed Depot requires an explicit import; no catalog was replaced");
            // A revision-zero database never had managed offers. Unexpected rows require investigation.
            try(var s=c.createStatement();var r=s.executeQuery("SELECT id FROM town_depot_offers LIMIT 1")){if(r.next())throw new IOException("Unmanaged Depot contains unexpected rows");}
            for(var offer:legacy.offers()){values(offer.good());insert(c,offer,true);}
            metadata(c,"supply-depot-revision",1);
        }
        metadata(c,"supply-depot-initialized",1);
        metadata(c,"supply-depot-seed-version",legacy==null?0:legacy.version());
        metadata(c,"supply-depot-defaults",3);
    }

    /**
     * Existing databases only: retire untouched seeded featured flags and enable untouched
     * official rows that earlier releases withheld. Administrator edits are never replaced.
     */
    static void alignDefaults(Database database)throws IOException {
        int version=database.read(c->{try(var s=c.prepareStatement("SELECT value FROM metadata WHERE key='supply-depot-defaults'")){try(var r=s.executeQuery()){return r.next()?r.getInt(1):0;}}});
        if(version>=3)return;
        database.write(c->{
            boolean changed=false;
            if(version<2)for(var legacy:LEGACY_SEEDED_FEATURED)try(var s=c.prepareStatement("UPDATE town_depot_offers SET featured=0 WHERE id=? AND item=? AND energy=? AND featured=1 AND promotion_only=0")) {
                s.setInt(1,legacy.id());s.setString(2,legacy.item());s.setInt(3,legacy.energy());changed|=s.executeUpdate()==1;
            }
            if(version<3)for(var grant:SEEDED_GRANTS)try(var s=c.prepareStatement("UPDATE town_depot_offers SET enabled=1 WHERE id=? AND item=? AND count=? AND crowns=? AND energy=? AND enabled=0 AND promotion_only=0")) {
                s.setInt(1,grant.id());s.setString(2,grant.item());s.setInt(3,grant.count());s.setInt(4,grant.crowns());s.setInt(5,grant.energy());changed|=s.executeUpdate()==1;
            }
            metadata(c,"supply-depot-defaults",3);
            if(changed)advance(c,revision(c));
            return null;
        });
    }

    static long revision(Connection c)throws SQLException {
        try(var s=c.createStatement();var r=s.executeQuery("SELECT value FROM metadata WHERE key='supply-depot-revision'")){return r.next()?r.getLong(1):0;}
    }

    static void metadata(Connection c,String key,long value)throws SQLException {
        try(var s=c.prepareStatement("INSERT INTO metadata(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")){s.setString(1,key);s.setLong(2,value);s.executeUpdate();}
    }

    static void advance(Connection c,long before)throws SQLException {metadata(c,"supply-depot-revision",Math.addExact(before,1));}

    static void values(TownCatalog.Good g)throws IOException {
        if(g.item()==null||g.item().isBlank()||g.count()<1||g.count()>9999||g.price()<0||g.energy()<0||g.limit()<0||!g.token().isEmpty()||g.tokenCount()!=0)throw new IOException("Invalid Supply Depot item, quantity, price or limit");
    }

    static void insert(Connection c,DepotStore.Offer offer,boolean explicit)throws SQLException {
        try(var s=c.prepareStatement("INSERT INTO town_depot_offers(item,count,crowns,energy,purchase_limit,featured,enabled,promotion_only"+(explicit?",id":"")+") VALUES(?,?,?,?,?,?,?,?"+(explicit?",?":"")+")")) {
            bind(s,offer);if(explicit)s.setInt(9,offer.id());s.executeUpdate();
        }
    }

    static void bind(PreparedStatement s,DepotStore.Offer offer)throws SQLException {var g=offer.good();s.setString(1,g.item());s.setInt(2,g.count());s.setInt(3,g.price());s.setInt(4,g.energy());s.setInt(5,g.limit());s.setBoolean(6,offer.featured());s.setBoolean(7,offer.enabled());s.setBoolean(8,offer.promotionOnly());}
}
