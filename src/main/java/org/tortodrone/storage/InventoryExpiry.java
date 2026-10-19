package org.tortodrone.storage;

import java.sql.*;
import java.util.*;

/** Absolute native item deadlines, stored in the existing authoritative metadata table. */
final class InventoryExpiry {
    private static final String PREFIX="item.expires.";
    private InventoryExpiry() { }
    static Long deadline(int days){return days==0?null:Math.addExact(System.currentTimeMillis(),Math.multiplyExact((long)days,86_400_000L));}
    static void save(Connection c,long item,Long deadline)throws SQLException {
        if(deadline==null)return;
        if(item<1||deadline<1)throw new IllegalArgumentException("Invalid item deadline");
        try(var s=c.prepareStatement("INSERT INTO metadata(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")){
            s.setString(1,PREFIX+item);s.setLong(2,deadline);s.executeUpdate();
        }
    }
    static Map<Long,Long> load(Connection c,int world)throws SQLException {
        var result=new HashMap<Long,Long>();
        try(var s=c.prepareStatement("SELECT i.item_id,m.value FROM inventory_items i JOIN metadata m ON m.key=?||i.item_id WHERE i.world_id=?")) {
            s.setString(1,PREFIX);s.setInt(2,world);try(var r=s.executeQuery()){while(r.next())result.put(r.getLong(1),r.getLong(2));}
        }return result;
    }
    static boolean due(Connection c,long now)throws SQLException {
        try(var s=c.prepareStatement("SELECT 1 FROM metadata m JOIN inventory_items i ON m.key=?||i.item_id WHERE m.key>=? AND m.key<? AND m.value<=? LIMIT 1")){
            s.setString(1,PREFIX);s.setString(2,PREFIX);s.setString(3,"item.expires/");s.setLong(4,now);try(var r=s.executeQuery()){return r.next();}
        }
    }
    /** Caller owns BEGIN IMMEDIATE. Each affected world publishes exactly one new revision. */
    static void prune(Connection c,long now)throws SQLException {
        var worlds=new HashSet<Integer>();var ids=new ArrayList<Long>();
        try(var s=c.prepareStatement("SELECT i.world_id,i.item_id FROM inventory_items i JOIN metadata m ON m.key=?||i.item_id WHERE m.key>='item.expires.' AND m.key<'item.expires/' AND m.value<=?")) {
            s.setString(1,PREFIX);s.setLong(2,now);try(var r=s.executeQuery()){while(r.next()){worlds.add(r.getInt(1));ids.add(r.getLong(2));}}
        }
        for(long id:ids) {
            try(var s=c.prepareStatement("DELETE FROM inventory_items WHERE item_id=?")){s.setLong(1,id);s.executeUpdate();}
            try(var s=c.prepareStatement("DELETE FROM metadata WHERE key=?")){s.setString(1,PREFIX+id);s.executeUpdate();}
        }
        for(int world:worlds)TownStore.touch(c,world);
    }
}
