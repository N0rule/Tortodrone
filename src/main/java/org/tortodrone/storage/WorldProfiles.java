package org.tortodrone.storage;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import org.tortodrone.character.Knight;
import org.tortodrone.character.WorldProfile;
import org.tortodrone.character.WorldProfile.Slot;

/** Current character schema and owned starter-kit storage. Called inside the same transaction as its roster mutation. */
final class WorldProfiles {
    private WorldProfiles() { }

    static void initialize(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE world_characters(world_id INTEGER PRIMARY KEY CHECK(world_id>0 AND world_id<2147483647), account_id INTEGER NOT NULL, knight_id INTEGER NOT NULL, crowns INTEGER NOT NULL DEFAULT 0 CHECK(crowns BETWEEN 0 AND 2147483647), energy INTEGER NOT NULL DEFAULT 0 CHECK(energy BETWEEN 0 AND 2147483647), state_revision INTEGER NOT NULL DEFAULT 1 CHECK(state_revision>0), UNIQUE(account_id,knight_id), FOREIGN KEY(account_id,knight_id) REFERENCES knights(account_id,id) ON DELETE CASCADE)");
            s.execute("CREATE TABLE inventory_items(item_id INTEGER PRIMARY KEY CHECK(item_id>0),world_id INTEGER NOT NULL REFERENCES world_characters(world_id) ON DELETE CASCADE,slot TEXT NOT NULL CHECK(slot IN('HELM','ARMOR','SWORD','GUN','SHIELD','BOMB','HELM_COSTUME','ARMOR_COSTUME','SHIELD_COSTUME','TRINKET','OTHER')),config TEXT NOT NULL,accessory TEXT,heat INTEGER NOT NULL DEFAULT 0 CHECK(heat>=0),equipped_slot INTEGER CHECK(equipped_slot IN(0,1,2,3,4,6,7,10,11,15)),bound INTEGER NOT NULL DEFAULT 0 CHECK(bound IN(0,1)),bind_on_equip INTEGER NOT NULL DEFAULT 1 CHECK(bind_on_equip IN(0,1)),starter INTEGER NOT NULL DEFAULT 0 CHECK(starter IN(0,1)),count INTEGER NOT NULL DEFAULT 1 CHECK(count BETWEEN 1 AND 9999),permanent INTEGER NOT NULL DEFAULT 0 CHECK(permanent IN(0,1)),CHECK(slot!='OTHER' OR equipped_slot IS NULL),CHECK(slot='OTHER' OR count=1),CHECK(permanent=0 OR bound=1))");
            s.execute("CREATE UNIQUE INDEX equipped_items ON inventory_items(world_id,equipped_slot) WHERE equipped_slot IS NOT NULL");
            s.execute("CREATE UNIQUE INDEX starter_items ON inventory_items(world_id,slot) WHERE starter=1");
            s.execute("CREATE TABLE currency_events(id INTEGER PRIMARY KEY,world_id INTEGER NOT NULL,currency TEXT NOT NULL CHECK(currency IN('crowns','energy')),delta INTEGER NOT NULL,balance INTEGER NOT NULL,created_at INTEGER NOT NULL)");
            s.execute("CREATE TABLE item_events(id INTEGER PRIMARY KEY,world_id INTEGER NOT NULL REFERENCES world_characters(world_id) ON DELETE CASCADE,actor_account INTEGER,config TEXT NOT NULL,quantity INTEGER NOT NULL,first_item_id INTEGER NOT NULL,created_at INTEGER NOT NULL)");
            s.execute("INSERT INTO metadata VALUES('next-world-id',1),('next-item-id',1)");
        }
    }

    static void ensure(Connection c, int accountId, Knight knight) throws SQLException, IOException {
        try (PreparedStatement s = c.prepareStatement("SELECT world_id FROM world_characters WHERE account_id=? AND knight_id=?")) {
            s.setInt(1,accountId); s.setInt(2,knight.id());
            try (ResultSet r = s.executeQuery()) { if (r.next()) return; }
        }
        int worldId = Math.toIntExact(allocate(c,"next-world-id",Integer.MAX_VALUE));
        try (PreparedStatement s = c.prepareStatement("INSERT INTO world_characters(world_id,account_id,knight_id) VALUES(?,?,?)")) {
            s.setInt(1,worldId); s.setInt(2,accountId); s.setInt(3,knight.id()); s.executeUpdate();
        }
        add(c,worldId,Slot.HELM,knight.helm(),knight.helmAccessory());
        add(c,worldId,Slot.ARMOR,knight.armor(),knight.armorAccessory());
        add(c,worldId,Slot.SWORD,"Weapon/Sword/Proto Sword",null);
        add(c,worldId,Slot.GUN,"Weapon/Handgun/Proto Gun",null);
        add(c,worldId,Slot.SHIELD,"Gear/Shield/Proto Shield",null);
    }

    /** Creator edits replace chosen cosmetics, preserving IDs, heat and the rest of the kit. */
    static void appearance(Connection c,int accountId,Knight knight) throws SQLException {
        boolean changed=false;
        try (PreparedStatement s = c.prepareStatement("UPDATE inventory_items SET config=?,accessory=? WHERE world_id=(SELECT world_id FROM world_characters WHERE account_id=? AND knight_id=?) AND slot=? AND starter=1 AND (config IS NOT ? OR accessory IS NOT ?)")) {
            for (Slot slot : List.of(Slot.HELM,Slot.ARMOR)) {
                s.setString(1,slot==Slot.HELM ? knight.helm() : knight.armor());
                s.setString(2,slot==Slot.HELM ? knight.helmAccessory() : knight.armorAccessory());
                s.setInt(3,accountId); s.setInt(4,knight.id()); s.setString(5,slot.name());
                s.setString(6,slot==Slot.HELM?knight.helm():knight.armor());
                s.setString(7,slot==Slot.HELM?knight.helmAccessory():knight.armorAccessory());
                changed|=s.executeUpdate()>0;
            }
        }
        if(changed)try(var s=c.prepareStatement("UPDATE world_characters SET state_revision=state_revision+1 WHERE account_id=? AND knight_id=?")) {
            s.setInt(1,accountId);s.setInt(2,knight.id());s.executeUpdate();
        }
    }

    private static void add(Connection c,int worldId,Slot slot,String config,String accessory) throws SQLException, IOException {
        long itemId = allocate(c,"next-item-id",Long.MAX_VALUE);
        try (PreparedStatement s = c.prepareStatement("INSERT INTO inventory_items(item_id,world_id,slot,config,accessory,equipped_slot,bound,starter) VALUES(?,?,?,?,?,?,1,1)")) {
            s.setLong(1,itemId); s.setInt(2,worldId); s.setString(3,slot.name()); s.setString(4,config); s.setString(5,accessory); s.setInt(6,slot.initialIndex()); s.executeUpdate();
        }
    }

    static long allocate(Connection c,String key,long limit) throws SQLException, IOException {
        long id;
        try (PreparedStatement s = c.prepareStatement("SELECT value FROM metadata WHERE key=?")) {
            s.setString(1,key); try (ResultSet r = s.executeQuery()) {
                if (!r.next() || (id = r.getLong(1)) < 1 || id >= limit) throw new IOException("World identity space exhausted or invalid counter");
            }
        }
        try (PreparedStatement s = c.prepareStatement("UPDATE metadata SET value=? WHERE key=?")) { s.setLong(1,id+1); s.setString(2,key); s.executeUpdate(); }
        return id;
    }

    static WorldProfile load(Connection c,int accountId,int knightId,long authRevision) throws SQLException, IOException {
        return load(c,accountId,knightId,authRevision,null);
    }
    static WorldProfile load(Connection c,int accountId,int knightId,long authRevision,WorldProfile cached) throws SQLException, IOException {
        int worldId, crowns, energy; long revision;
        try (PreparedStatement s = c.prepareStatement("SELECT w.world_id,w.crowns,w.energy,w.state_revision FROM world_characters w JOIN accounts a ON a.id=w.account_id WHERE w.account_id=? AND w.knight_id=?" + (authRevision>=0 ? " AND a.auth_revision=?" : ""))) {
            s.setInt(1,accountId); s.setInt(2,knightId); if (authRevision>=0) s.setLong(3,authRevision);
            try (ResultSet r = s.executeQuery()) { if (!r.next()) throw new IOException("No such knight or account changed; reconnect"); worldId=r.getInt(1); crowns=r.getInt(2); energy=r.getInt(3); revision=r.getLong(4); }
        }
        // All supported inventory/balance writes increment state_revision in their transaction.
        // Still validate current ownership/authentication before reusing immutable inventory.
        if(cached!=null&&cached.accountId()==accountId&&cached.knightId()==knightId&&cached.worldId()==worldId&&cached.revision()==revision&&cached.items().stream().noneMatch(i->i.expired(System.currentTimeMillis())))return cached;
        List<WorldProfile.Item> items = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("SELECT * FROM inventory_items WHERE world_id=? ORDER BY item_id")) {
            s.setInt(1,worldId); try (ResultSet r = s.executeQuery()) {
                while (r.next()) items.add(new WorldProfile.Item(r.getLong("item_id"),Slot.valueOf(r.getString("slot")),r.getString("config"),r.getString("accessory"),r.getInt("heat"),(Integer)r.getObject("equipped_slot"),r.getBoolean("bound"),r.getBoolean("bind_on_equip"),r.getBoolean("starter"),r.getInt("count"),r.getBoolean("permanent"),r.getInt("level"),r.getFloat("heat_progress"),TownStore.variants(r.getString("variants")),TownStore.strings(r.getString("accessories"))));
            }
        }
        var expirations=InventoryExpiry.load(c,worldId);
        items.replaceAll(item->item.withExpiration(expirations.get(item.id())));
        items.removeIf(item->item.expired(System.currentTimeMillis()));
        // Bonus weapon slots use metadata so existing schema-15 databases need no migration.
        var bonusSlots=new HashMap<Long,Integer>();
        try(var s=c.prepareStatement("SELECT i.item_id,m.value FROM inventory_items i JOIN metadata m ON m.key='item.weapon-slot.'||i.item_id WHERE i.world_id=?")) {
            s.setInt(1,worldId);try(var r=s.executeQuery()){while(r.next()){int slot=r.getInt(2);if(slot!=8&&slot!=9)throw new IOException("Invalid bonus weapon slot");bonusSlots.put(r.getLong(1),slot);}}
        }
        items.replaceAll(item->bonusSlots.containsKey(item.id())?item.withEquippedSlot(bonusSlots.get(item.id())):item);
        var gifts=GiftStore.load(c,worldId);items.replaceAll(item->gifts.containsKey(item.id())?item.withGift(gifts.get(item.id())):item);
        int consumed;try(var s=c.prepareStatement("SELECT count(*) FROM town_consumed_starters WHERE world_id=?")){s.setInt(1,worldId);try(var r=s.executeQuery()){r.next();consumed=r.getInt(1);}}
        if(items.stream().filter(WorldProfile.Item::starter).count()+consumed!=5)throw new IOException("Incomplete starter inventory; restore the saved database");
        int rank=11,prestige=0;
        try(var s=c.prepareStatement("SELECT key,value FROM metadata WHERE key IN(?,?)")){
            s.setString(1,progressKey(worldId,"rank"));s.setString(2,progressKey(worldId,"prestige"));
            try(var r=s.executeQuery()){while(r.next()){long value=r.getLong(2);if(value<0||value>Integer.MAX_VALUE)throw new IOException("Invalid saved knight progress");if(r.getString(1).endsWith(".rank"))rank=(int)value;else prestige=(int)value;}}
        }
        try{
            var profile=new WorldProfile(worldId,accountId,knightId,crowns,energy,revision,items,new org.tortodrone.character.KnightProgress(rank/10,rank%10,prestige),TownStore.load(c,worldId));
            var visible=items.stream().map(item->item.equippedSlot()!=null&&!org.tortodrone.character.SlotUpgrades.unlocked(profile,item.equippedSlot())?item.withEquippedSlot(null):item).toList();
            return new WorldProfile(worldId,accountId,knightId,crowns,energy,revision,visible,profile.progress(),profile.town());
        }
        catch(IllegalArgumentException invalid){throw new IOException("Invalid saved knight progress",invalid);}
    }
    static String progressKey(int world,String field){return "knight.progress."+world+"."+field;}
    static void cleanProgress(Connection c)throws SQLException{
        try(var s=c.createStatement()){s.executeUpdate("DELETE FROM metadata WHERE key GLOB 'world.*.last-town-scene' AND key NOT IN (SELECT 'world.'||world_id||'.last-town-scene' FROM world_characters)");}
        FriendStore.clean(c);
        NamedLoadouts.clean(c);
        AuctionHouse.cleanOutbids(c);
        try(var s=c.createStatement()){s.executeUpdate("DELETE FROM metadata WHERE key LIKE 'knight.progress.%' AND key NOT IN (SELECT 'knight.progress.'||world_id||'.rank' FROM world_characters UNION ALL SELECT 'knight.progress.'||world_id||'.prestige' FROM world_characters)");}
    }
}
