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
        // Losing a timed upgrade returns equipment to the bag; equipment itself never expires with its slot.
        for(int world:worlds){reconcile(c,world,now);TownStore.touch(c,world);}
    }
    static void reconcile(Connection c,int world,long now)throws SQLException {
        int weapons=0,trinkets=0;
        try(var s=c.prepareStatement("SELECT i.config FROM inventory_items i JOIN metadata m ON m.key='item.expires.'||i.item_id WHERE i.world_id=? AND m.value>?")) {
            s.setInt(1,world);s.setLong(2,now);try(var r=s.executeQuery()){while(r.next()){var def=org.tortodrone.character.SlotUpgrades.find(r.getString(1));if(def!=null){weapons+=def.weapons();trinkets+=def.trinkets();}}}
        }
        try(var s=c.prepareStatement("DELETE FROM metadata WHERE key IN (SELECT 'item.weapon-slot.'||item_id FROM inventory_items WHERE world_id=?) AND value>=?")) {
            s.setInt(1,world);s.setInt(2,8+Math.min(2,weapons));s.executeUpdate();
        }
        try(var s=c.prepareStatement("UPDATE inventory_items SET equipped_slot=NULL WHERE world_id=? AND equipped_slot>=? AND equipped_slot IN(10,11)")) {
            s.setInt(1,world);s.setInt(2,10+Math.min(2,trinkets));s.executeUpdate();
        }

        try(var s=c.createStatement()){s.executeUpdate("DELETE FROM metadata WHERE key LIKE 'item.weapon-slot.%' AND key NOT IN(SELECT 'item.weapon-slot.'||item_id FROM inventory_items)");}
    }

}
