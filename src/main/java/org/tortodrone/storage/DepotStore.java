package org.tortodrone.storage;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import org.tortodrone.character.ItemCatalog;
import org.tortodrone.town.TownCatalog;

/** SQLite is the sole catalog authority. Quotes and purchases use the same effective-offer resolver. */
public final class DepotStore {
    public record Offer(int id,TownCatalog.Good good,boolean featured,boolean enabled,boolean promotionOnly,
            int originalPrice,boolean sale,long promotionId,long promotionEnds) {
        public Offer(int id,TownCatalog.Good good,boolean featured,boolean enabled){this(id,good,featured,enabled,false,0,false,0,0);}
        public Offer(int id,TownCatalog.Good good,boolean featured,boolean enabled,boolean promotionOnly){this(id,good,featured,enabled,promotionOnly,0,false,0,0);}
    }
    public record Snapshot(long revision,List<Offer> offers){public Snapshot{offers=List.copyOf(offers);}}
    public record Quote(long revision,Offer offer) { }
    /** Unix milliseconds UTC; starts <= now < ends. Null reference price is a temporary direct price. */
    public record Promotion(long id,int offerId,long starts,long ends,int crowns,int energy,Integer referencePrice,boolean featured,boolean cancelled) {
        public String status(long now){return cancelled?"Cancelled":now<starts?"Scheduled":now>=ends?"Expired":"Active";}
    }
    private final Database database;
    private final ItemCatalog items;
    public DepotStore(Database database,ItemCatalog items,TownCatalog rules){this.database=database;this.items=items;}
    // Schema 11 base, also used by the explicit 9..14 candidate upgrade.
    static void initialize(Connection c)throws SQLException {
        try(var s=c.createStatement()){s.execute("CREATE TABLE town_depot_offers(id INTEGER PRIMARY KEY AUTOINCREMENT,item TEXT NOT NULL,count INTEGER NOT NULL CHECK(count BETWEEN 1 AND 9999),crowns INTEGER NOT NULL CHECK(crowns>=0),energy INTEGER NOT NULL CHECK(energy>=0),purchase_limit INTEGER NOT NULL CHECK(purchase_limit>=0),featured INTEGER NOT NULL CHECK(featured IN(0,1)),enabled INTEGER NOT NULL CHECK(enabled IN(0,1)))");}
    }
    /** Caller owns BEGIN IMMEDIATE. Never infer initialization from the number of offers. */
    static void upgrade(Connection c,DepotSeed.Data legacy)throws SQLException,IOException {
        try(var s=c.createStatement()) {
            s.execute("ALTER TABLE town_depot_offers ADD COLUMN promotion_only INTEGER NOT NULL DEFAULT 0 CHECK(promotion_only IN(0,1))");
            s.execute("CREATE TABLE town_depot_promotions(id INTEGER PRIMARY KEY AUTOINCREMENT,offer_id INTEGER NOT NULL REFERENCES town_depot_offers(id),starts INTEGER NOT NULL CHECK(starts>=0),ends INTEGER NOT NULL CHECK(ends>starts),crowns INTEGER NOT NULL CHECK(crowns>=0),energy INTEGER NOT NULL CHECK(energy>=0),reference_price INTEGER CHECK(reference_price>0),featured INTEGER NOT NULL CHECK(featured IN(0,1)),cancelled INTEGER NOT NULL DEFAULT 0 CHECK(cancelled IN(0,1)),CHECK(crowns=0 OR energy=0))");
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
    }
    private static long revision(Connection c)throws SQLException {
        try(var s=c.createStatement();var r=s.executeQuery("SELECT value FROM metadata WHERE key='supply-depot-revision'")){return r.next()?r.getLong(1):0;}
    }
    private static void metadata(Connection c,String key,long value)throws SQLException {
        try(var s=c.prepareStatement("INSERT INTO metadata(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")){s.setString(1,key);s.setLong(2,value);s.executeUpdate();}
    }
    public Snapshot snapshot()throws IOException {return database.read(c->{c.setAutoCommit(false);var result=load(c);c.commit();return result;});}
    public Snapshot catalog()throws IOException {return database.read(c->{c.setAutoCommit(false);var result=effective(c,System.currentTimeMillis());c.commit();return result;});}
    static Snapshot load(Connection c)throws SQLException,IOException {
        try(var s=c.createStatement();var r=s.executeQuery("SELECT value FROM metadata WHERE key='supply-depot-initialized'")){if(!r.next()||r.getInt(1)!=1)throw new IOException("Supply Depot has not been initialized");}
        var offers=new ArrayList<Offer>();
        try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM town_depot_offers ORDER BY id")) {
            while(r.next())offers.add(new Offer(r.getInt("id"),new TownCatalog.Good(r.getString("item"),r.getInt("count"),r.getInt("crowns"),r.getInt("energy"),r.getInt("purchase_limit"),"",0),r.getBoolean("featured"),r.getBoolean("enabled"),r.getBoolean("promotion_only")));
        }
        return new Snapshot(revision(c),offers);
    }
    static Snapshot effective(Connection c,long now)throws SQLException,IOException {
        var base=load(c);var active=new HashMap<Integer,Promotion>();
        try(var s=c.prepareStatement("SELECT * FROM town_depot_promotions WHERE cancelled=0 AND starts<=? AND ends>? ORDER BY id")) {
            s.setLong(1,now);s.setLong(2,now);try(var r=s.executeQuery()){while(r.next()){var p=promotion(r);if(active.putIfAbsent(p.offerId(),p)!=null)throw new IOException("Conflicting Supply Depot promotions");}}
        }
        var offers=new ArrayList<Offer>();for(var o:base.offers()) {
            var p=active.get(o.id());var g=o.good();boolean available=o.enabled()&&(!o.promotionOnly()||p!=null);
            offers.add(p==null?new Offer(o.id(),g,o.featured(),available,o.promotionOnly()):new Offer(o.id(),new TownCatalog.Good(g.item(),g.count(),p.crowns(),p.energy(),g.limit(),"",0),o.featured()||p.featured(),available,o.promotionOnly(),p.referencePrice()==null?0:p.referencePrice(),p.referencePrice()!=null,p.id(),p.ends()));
        }
        return new Snapshot(base.revision(),offers);
    }
    static void values(TownCatalog.Good g)throws IOException {
        if(g.item()==null||g.item().isBlank()||g.count()<1||g.count()>9999||g.price()<0||g.energy()<0||g.limit()<0||!g.token().isEmpty()||g.tokenCount()!=0)throw new IOException("Invalid Supply Depot item, quantity, price or limit");
    }
    private void validate(Offer offer)throws IOException {
        var g=offer.good();values(g);var item=items.find(g.item());
        if(item==null||offer.enabled()&&!item.grantable()||g.count()>(item.stackable()?9999:1)||g.price()>0&&g.energy()>0||offer.originalPrice()!=0||offer.sale()||offer.promotionId()!=0||offer.promotionEnds()!=0)throw new IOException("Choose a grantable item, valid quantity and one currency; edit promotions separately");
    }
    private static void expected(Connection c,long expected)throws SQLException,IOException {if(revision(c)!=expected)throw new IOException("Supply Depot changed; refresh before editing");}
    private static void advance(Connection c,long before)throws SQLException {metadata(c,"supply-depot-revision",Math.addExact(before,1));}
    public Snapshot save(long expectedRevision,Offer offer)throws IOException {
        validate(offer);
        return database.write(c->{expected(c,expectedRevision);
            if(offer.id()<0)insert(c,offer,false);
            else try(var s=c.prepareStatement("UPDATE town_depot_offers SET item=?,count=?,crowns=?,energy=?,purchase_limit=?,featured=?,enabled=?,promotion_only=? WHERE id=?")) {
                // Historical item-config purchase limits must never be reset by repointing an ID.
                var old=load(c).offers().stream().filter(o->o.id()==offer.id()).findFirst().orElseThrow(()->new IOException("Offer no longer exists"));
                if(!old.good().item().equals(offer.good().item()))throw new IOException("Create a new offer to change its item");
                bind(s,offer);s.setInt(9,offer.id());if(s.executeUpdate()!=1)throw new IOException("Offer changed");
            }
            advance(c,expectedRevision);return load(c);
        });
    }
    private static void insert(Connection c,Offer offer,boolean explicit)throws SQLException {
        try(var s=c.prepareStatement("INSERT INTO town_depot_offers(item,count,crowns,energy,purchase_limit,featured,enabled,promotion_only"+(explicit?",id":"")+") VALUES(?,?,?,?,?,?,?,?"+(explicit?",?":"")+")")) {
            bind(s,offer);if(explicit)s.setInt(9,offer.id());s.executeUpdate();
        }
    }
    private static void bind(PreparedStatement s,Offer offer)throws SQLException {var g=offer.good();s.setString(1,g.item());s.setInt(2,g.count());s.setInt(3,g.price());s.setInt(4,g.energy());s.setInt(5,g.limit());s.setBoolean(6,offer.featured());s.setBoolean(7,offer.enabled());s.setBoolean(8,offer.promotionOnly());}
    public List<Promotion> promotions()throws IOException {return database.read(DepotStore::promotions);}
    private static List<Promotion> promotions(Connection c)throws SQLException {
        var result=new ArrayList<Promotion>();try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM town_depot_promotions ORDER BY starts,id")){while(r.next())result.add(promotion(r));}return List.copyOf(result);
    }
    private static Promotion promotion(ResultSet r)throws SQLException {int reference=r.getInt("reference_price");boolean absent=r.wasNull();return new Promotion(r.getLong("id"),r.getInt("offer_id"),r.getLong("starts"),r.getLong("ends"),r.getInt("crowns"),r.getInt("energy"),absent?null:reference,r.getBoolean("featured"),r.getBoolean("cancelled"));}
    public Snapshot schedule(long expectedRevision,Promotion p)throws IOException {
        int price=p.energy()>0?p.energy():p.crowns();
        if(p.id()!=0||p.cancelled()||p.starts()<0||p.ends()<=p.starts()||p.crowns()<0||p.energy()<0||p.crowns()>0&&p.energy()>0||p.referencePrice()!=null&&p.referencePrice()<=price)throw new IOException("Choose an ordered UTC window, one currency and an optional regular price greater than the sale price");
        return database.write(c->{expected(c,expectedRevision);
            var offer=load(c).offers().stream().filter(o->o.id()==p.offerId()).findFirst().orElseThrow(()->new IOException("Unknown promotion offer"));
            var definition=items.find(offer.good().item());if(definition==null||!definition.grantable())throw new IOException("Promotion item cannot be granted");
            int regular=offer.good().energy()>0?offer.good().energy():offer.good().price();
            if(p.referencePrice()!=null&&(p.referencePrice()!=regular||(p.energy()>0)!=(offer.good().energy()>0)))throw new IOException("A discount comparison must match this offer's current regular price and currency");
            for(var other:promotions(c))if(!other.cancelled()&&other.offerId()==p.offerId()&&other.starts()<p.ends()&&p.starts()<other.ends())throw new IOException("Promotion windows for one offer must not overlap");
            try(var s=c.prepareStatement("INSERT INTO town_depot_promotions(offer_id,starts,ends,crowns,energy,reference_price,featured) VALUES(?,?,?,?,?,?,?)")) {
                s.setInt(1,p.offerId());s.setLong(2,p.starts());s.setLong(3,p.ends());s.setInt(4,p.crowns());s.setInt(5,p.energy());if(p.referencePrice()==null)s.setNull(6,Types.INTEGER);else s.setInt(6,p.referencePrice());s.setBoolean(7,p.featured());s.executeUpdate();
            }
            advance(c,expectedRevision);return load(c);
        });
    }
    public Snapshot cancelPromotion(long expectedRevision,long id)throws IOException {
        return database.write(c->{expected(c,expectedRevision);try(var s=c.prepareStatement("UPDATE town_depot_promotions SET cancelled=1 WHERE id=? AND cancelled=0")){s.setLong(1,id);if(s.executeUpdate()!=1)throw new IOException("Promotion no longer available");}advance(c,expectedRevision);return load(c);});
    }
}
