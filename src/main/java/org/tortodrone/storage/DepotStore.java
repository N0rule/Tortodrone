package org.tortodrone.storage;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import org.tortodrone.character.ItemCatalog;
import org.tortodrone.town.TownCatalog;

/** Local catalogue management and stock-client quotes share the same transactional ledger. */
public final class DepotStore {
    public record Offer(int id,TownCatalog.Good good,boolean featured,boolean enabled) { }
    public record Snapshot(long revision,List<Offer> offers){public Snapshot{offers=List.copyOf(offers);}}
    public record Quote(long revision,Offer offer) { }
    private final Database database;
    private final ItemCatalog items;
    private final TownCatalog rules;
    public DepotStore(Database database,ItemCatalog items,TownCatalog rules){this.database=database;this.items=items;this.rules=rules;}
    static void initialize(Connection c)throws SQLException{
        try(var s=c.createStatement()){s.execute("CREATE TABLE town_depot_offers(id INTEGER PRIMARY KEY AUTOINCREMENT,item TEXT NOT NULL,count INTEGER NOT NULL CHECK(count BETWEEN 1 AND 9999),crowns INTEGER NOT NULL CHECK(crowns>=0),energy INTEGER NOT NULL CHECK(energy>=0),purchase_limit INTEGER NOT NULL CHECK(purchase_limit>=0),featured INTEGER NOT NULL CHECK(featured IN(0,1)),enabled INTEGER NOT NULL CHECK(enabled IN(0,1)))");}
    }
    public Snapshot snapshot()throws IOException{return database.read(c->{c.setAutoCommit(false);var result=load(c,rules);c.commit();return result;});}
    static Snapshot load(Connection c,TownCatalog rules)throws SQLException{
        long revision=0;try(var s=c.createStatement();var r=s.executeQuery("SELECT value FROM metadata WHERE key='supply-depot-revision'")){if(r.next())revision=r.getLong(1);}
        var offers=new ArrayList<Offer>();
        if(revision==0){for(int i=0;i<rules.depot().size();i++){var d=rules.depot().get(i);offers.add(new Offer(i,d.good(),d.featured(),true));}}
        else try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM town_depot_offers ORDER BY id")){while(r.next())offers.add(new Offer(r.getInt("id"),new TownCatalog.Good(r.getString("item"),r.getInt("count"),r.getInt("crowns"),r.getInt("energy"),r.getInt("purchase_limit"),"",0),r.getBoolean("featured"),r.getBoolean("enabled")));}
        return new Snapshot(revision,offers);
    }
    private void validate(Offer offer)throws IOException{
        var g=offer.good();var item=items.find(g.item());
        if(item==null||offer.enabled()&&!item.grantable()||g.count()<1||g.count()>(item.stackable()?9999:1)||g.price()<0||g.energy()<0||g.price()>0&&g.energy()>0||g.limit()<0||g.limit()>9999||!g.token().isEmpty())throw new IOException("Choose a grantable item, valid quantity, one currency and purchase limit 0–9999");
    }
    public Snapshot save(long expectedRevision,Offer offer)throws IOException{
        validate(offer);
        return database.write(c->{
            var before=load(c,rules);if(before.revision()!=expectedRevision)throw new IOException("Supply Depot changed; refresh before editing");
            if(offer.id()>=0&&before.offers().stream().noneMatch(o->o.id()==offer.id()))throw new IOException("Offer no longer exists");
            if(before.revision()==0){for(var original:before.offers())insert(c,original,true);}
            if(offer.id()<0)insert(c,offer,false);
            else try(var s=c.prepareStatement("UPDATE town_depot_offers SET item=?,count=?,crowns=?,energy=?,purchase_limit=?,featured=?,enabled=? WHERE id=?")){bind(s,offer);s.setInt(8,offer.id());if(s.executeUpdate()!=1)throw new IOException("Offer changed");}
            try(var s=c.prepareStatement("INSERT INTO metadata(key,value) VALUES('supply-depot-revision',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")){s.setLong(1,Math.addExact(before.revision(),1));s.executeUpdate();}
            return load(c,rules);
        });
    }
    private void insert(Connection c,Offer offer,boolean explicit)throws SQLException,IOException{
        var g=offer.good();int price=g.price();if(price<0){var item=items.find(g.item());price=g.energy()>0||item==null?0:rules.price(g.item(),item.rarity(),true);offer=new Offer(offer.id(),new TownCatalog.Good(g.item(),g.count(),price,g.energy(),g.limit(),"",0),offer.featured(),offer.enabled());}
        try(var s=c.prepareStatement("INSERT INTO town_depot_offers(item,count,crowns,energy,purchase_limit,featured,enabled"+(explicit?",id":"")+") VALUES(?,?,?,?,?,?,?"+(explicit?",?":"")+")")){bind(s,offer);if(explicit)s.setInt(8,offer.id());s.executeUpdate();}
    }
    private static void bind(PreparedStatement s,Offer offer)throws SQLException{var g=offer.good();s.setString(1,g.item());s.setInt(2,g.count());s.setInt(3,g.price());s.setInt(4,g.energy());s.setInt(5,g.limit());s.setBoolean(6,offer.featured());s.setBoolean(7,offer.enabled());}
}
