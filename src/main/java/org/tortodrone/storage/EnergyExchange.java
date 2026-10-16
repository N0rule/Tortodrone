package org.tortodrone.storage;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import org.tortodrone.town.ExchangeState;

/** Player-funded price/time order book. Every debit, match, refund and delivery commits together. */
public final class EnergyExchange {
    public static final int LOT=100;
    private final Database database;
    public EnergyExchange(Database database){this.database=database;}
    static void initialize(Connection c)throws SQLException{
        try(var s=c.createStatement()){
            s.execute("CREATE TABLE town_energy_offers(id INTEGER PRIMARY KEY AUTOINCREMENT CHECK(id BETWEEN 1 AND 2147483647),world_id INTEGER NOT NULL REFERENCES world_characters(world_id) ON DELETE CASCADE,buy INTEGER NOT NULL CHECK(buy IN(0,1)),price INTEGER NOT NULL CHECK(price>0),volume INTEGER NOT NULL CHECK(volume BETWEEN 0 AND 999),created INTEGER NOT NULL)");
            s.execute("CREATE INDEX town_energy_book ON town_energy_offers(buy,price,id) WHERE volume>0");
            s.execute("CREATE INDEX town_energy_owner ON town_energy_offers(world_id) WHERE volume>0");
        }
    }
    private static long metadata(Connection c,String key)throws SQLException{try(var s=c.prepareStatement("SELECT value FROM metadata WHERE key=?")){s.setString(1,key);try(var r=s.executeQuery()){return r.next()?r.getLong(1):0;}}}
    private static void put(Connection c,String key,long value)throws SQLException{try(var s=c.prepareStatement("INSERT INTO metadata(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")){s.setString(1,key);s.setLong(2,value);s.executeUpdate();}}
    public ExchangeState.Book book()throws IOException{return database.read(c->{c.setAutoCommit(false);var book=new ExchangeState.Book(metadata(c,"energy-exchange-revision"),(int)metadata(c,"energy-exchange-last-price"),levels(c,true),levels(c,false));c.commit();return book;});}
    private static List<ExchangeState.Level> levels(Connection c,boolean buy)throws SQLException{
        var result=new ArrayList<ExchangeState.Level>();try(var s=c.prepareStatement("SELECT price,SUM(volume) AS volume FROM town_energy_offers WHERE buy=? AND volume>0 GROUP BY price ORDER BY price "+(buy?"DESC":"ASC")+" LIMIT 20")){s.setBoolean(1,buy);try(var r=s.executeQuery()){while(r.next())result.add(new ExchangeState.Level((int)Math.min(Integer.MAX_VALUE,r.getLong("volume")),r.getInt("price")));}}return result;
    }
    public TownStore.Result offers(int account,int knight,long auth)throws IOException{return database.read(c->{c.setAutoCommit(false);var p=WorldProfiles.load(c,account,knight,auth);var buys=new ArrayList<ExchangeState.Offer>();var sells=new ArrayList<ExchangeState.Offer>();try(var s=c.prepareStatement("SELECT id,buy,price,volume FROM town_energy_offers WHERE world_id=? AND volume>0 ORDER BY id")){s.setInt(1,p.worldId());try(var r=s.executeQuery()){while(r.next()){var offer=new ExchangeState.Offer(r.getInt(1),r.getBoolean(2),r.getInt(3),r.getInt(4));(offer.buy()?buys:sells).add(offer);}}}c.commit();return new TownStore.Result(p,List.of(List.copyOf(buys),List.copyOf(sells)));});}
    public TownStore.Result cancel(int account,int knight,long auth,int offerId)throws IOException{
        return database.write(c->{var p=WorldProfiles.load(c,account,knight,auth);boolean buy;int price,volume;
            try(var s=c.prepareStatement("SELECT buy,price,volume FROM town_energy_offers WHERE id=? AND world_id=? AND volume>0")){s.setInt(1,offerId);s.setInt(2,p.worldId());try(var r=s.executeQuery()){if(!r.next())throw new IOException("This Energy offer is no longer available");buy=r.getBoolean(1);price=r.getInt(2);volume=r.getInt(3);}}
            TownStore.credit(c,p.worldId(),buy?Math.multiplyExact(price,volume):0,buy?0:volume*LOT);
            try(var s=c.prepareStatement("UPDATE town_energy_offers SET volume=0 WHERE id=? AND world_id=?")){s.setInt(1,offerId);s.setInt(2,p.worldId());s.executeUpdate();}
            changed(c);return new TownStore.Result(WorldProfiles.load(c,account,knight,auth),null);
        });
    }
    private record Maker(int id,int world,int price,int volume) { }
    public TownStore.Result trade(int account,int knight,long auth,int volume,int price,boolean buy,boolean immediate)throws IOException{
        if(volume<1||volume>999||price<1||(long)volume*price>Integer.MAX_VALUE)throw new IOException("Enter 1–999 lots and a positive Crown price within the balance limit");
        return database.write(c->{
            var p=WorldProfiles.load(c,account,knight,auth);var matches=new ArrayList<Maker>();
            try(var s=c.prepareStatement("SELECT id,world_id,price,volume FROM town_energy_offers WHERE buy=? AND volume>0 AND world_id<>? AND price"+(buy?"<=":" >=")+"? ORDER BY price "+(buy?"ASC":"DESC")+",id LIMIT 1000")){
                s.setBoolean(1,!buy);s.setInt(2,p.worldId());s.setInt(3,price);try(var r=s.executeQuery()){while(r.next())matches.add(new Maker(r.getInt(1),r.getInt(2),r.getInt(3),r.getInt(4)));}
            }
            if(immediate&&matches.stream().mapToLong(Maker::volume).sum()<volume)throw new IOException("The Energy price changed or there are not enough matching offers");
            if(!immediate){try(var s=c.prepareStatement("SELECT count(*) FROM town_energy_offers WHERE world_id=? AND volume>0")){s.setInt(1,p.worldId());try(var r=s.executeQuery()){r.next();if(r.getInt(1)>=64)throw new IOException("Cancel an existing Energy offer before posting another");}}}
            TownStore.pay(c,p,buy?price*volume:0,buy?0:volume*LOT);
            int remaining=volume;
            for(var maker:matches){
                if(remaining==0)break;int filled=Math.min(remaining,maker.volume()),crowns=maker.price()*filled,energy=filled*LOT;
                if(buy){TownStore.credit(c,p.worldId(),(price-maker.price())*filled,energy);TownStore.credit(c,maker.world(),crowns,0);}
                else{TownStore.credit(c,p.worldId(),crowns,0);TownStore.credit(c,maker.world(),0,energy);}
                try(var s=c.prepareStatement("UPDATE town_energy_offers SET volume=volume-? WHERE id=? AND volume=?")){s.setInt(1,filled);s.setInt(2,maker.id());s.setInt(3,maker.volume());if(s.executeUpdate()!=1)throw new IOException("Energy offer changed; retry");}
                put(c,"energy-exchange-last-price",maker.price());remaining-=filled;
            }
            ExchangeState.Offer offer=null;
            if(remaining>0){try(var s=c.prepareStatement("INSERT INTO town_energy_offers(world_id,buy,price,volume,created) VALUES(?,?,?,?,?) RETURNING id")){s.setInt(1,p.worldId());s.setBoolean(2,buy);s.setInt(3,price);s.setInt(4,remaining);s.setLong(5,System.currentTimeMillis());try(var r=s.executeQuery()){r.next();offer=new ExchangeState.Offer(r.getInt(1),buy,price,remaining);}}}
            changed(c);return new TownStore.Result(WorldProfiles.load(c,account,knight,auth),offer);
        });
    }
    private static void changed(Connection c)throws SQLException{put(c,"energy-exchange-revision",metadata(c,"energy-exchange-revision")+1);}
}
