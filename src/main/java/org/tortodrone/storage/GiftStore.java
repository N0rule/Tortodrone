package org.tortodrone.storage;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import org.tortodrone.character.WorldProfile;
import org.tortodrone.resources.Json;

/** Durable contents for native GiftBoxItem records; ownership follows the box's inventory row. */
final class GiftStore {
    private GiftStore() { }
    static void initialize(Connection c)throws SQLException {
        try(var s=c.createStatement()){s.execute("CREATE TABLE town_gifts(item_id INTEGER PRIMARY KEY REFERENCES inventory_items(item_id) ON DELETE CASCADE,record TEXT NOT NULL)");}
    }
    static Map<Long,WorldProfile.Gift> load(Connection c,int world)throws SQLException,IOException {
        var gifts=new HashMap<Long,WorldProfile.Gift>();
        try(var s=c.prepareStatement("SELECT g.item_id,g.record FROM town_gifts g JOIN inventory_items i ON i.item_id=g.item_id WHERE i.world_id=?")){
            s.setInt(1,world);try(var r=s.executeQuery()){while(r.next())gifts.put(r.getLong(1),decode(r.getString(2)));}
        }
        return gifts;
    }
    static void save(Connection c,long id,WorldProfile.Gift gift)throws SQLException,IOException {
        if(gift==null)return;
        try(var s=c.prepareStatement("INSERT INTO town_gifts(item_id,record) VALUES(?,?)")){s.setLong(1,id);s.setString(2,encode(gift));s.executeUpdate();}
    }
    static String encode(WorldProfile.Gift gift)throws IOException {
        var contents=new ArrayList<String>();for(var item:gift.contents()){if(item.gift()!=null)throw new IOException("Nested gifts are not allowed");contents.add(AuctionHouse.encode(item));}
        var row=new LinkedHashMap<String,Object>();row.put("wrapperId",gift.wrapperId());row.put("wrapperName",gift.wrapperName());row.put("message",gift.message());row.put("contents",contents);return Json.stringify(row);
    }
    static WorldProfile.Gift decode(String json)throws IOException {
        try{
            if(json.length()>1_000_000)throw new IOException("Gift record exceeds limit");
            var row=(Map<?,?>)Json.parse(json);var contents=new ArrayList<WorldProfile.Item>();
            int total=0;
            for(var raw:(List<?>)row.get("contents")){
                if(contents.size()>=256)throw new IOException("Gift contents exceed limit");
                var item=AuctionHouse.decode((String)raw);
                if(item.gift()!=null||item.count()<1||(total+=item.count())>9999||item.slot()!=WorldProfile.Slot.OTHER&&item.count()!=1)throw new IOException("Invalid saved gift contents");
                contents.add(item);
            }
            if(contents.isEmpty())throw new IOException("Empty saved gift");
            return new WorldProfile.Gift(((Number)row.get("wrapperId")).intValue(),(String)row.get("wrapperName"),(String)row.get("message"),contents);
        }catch(RuntimeException bad){throw new IOException("Invalid saved gift",bad);}
    }
}
