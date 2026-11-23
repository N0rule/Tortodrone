package org.tortodrone.storage;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import org.tortodrone.core.character.Currency;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.character.WorldProfile;

/** Local administration and inventory writes, split from the transaction/schema owner.
 *
 * <p>Every entry point still runs through {@link Database#read} or {@link Database#write};
 * target identity, the expected revision, the audit row and the mutation therefore share
 * one transaction, and the per-write expiry sweep stays in one place.
 */
final class AdministrationRepository {
    private final Database database;

    AdministrationRepository(Database database) { this.database = database; }

    private record Target(int account, int knight) { }

    private static Target target(Connection c, String username, String knightName) throws SQLException, IOException {
        try (var s=c.prepareStatement("SELECT k.account_id,k.id FROM knights k JOIN accounts a ON a.id=k.account_id WHERE k.name_key=?" + (username==null ? "" : " AND a.name_key=?"))) {
            s.setString(1,knightName.toLowerCase(Locale.ROOT));
            if(username!=null)s.setString(2,username.toLowerCase(Locale.ROOT));
            try(var r=s.executeQuery()){if(!r.next())throw new IOException("No such knight");return new Target(r.getInt(1),r.getInt(2));}
        }
    }

    private static WorldProfile administrativeProfile(Connection c, String username, String knightName,
            int expectedAccount, int expectedWorld) throws SQLException, IOException {
        var target=target(c,username,knightName);
        if(expectedAccount>=0&&target.account()!=expectedAccount)throw new IOException("Account changed; refresh");
        var profile=WorldProfiles.load(c,target.account(),target.knight(),-1);
        if(expectedWorld>=0&&profile.worldId()!=expectedWorld)throw new IOException("Knight changed; refresh");
        return profile;
    }

    WorldProfile inspectKnight(String username, String knightName) throws IOException {
        return database.read(c->{c.setAutoCommit(false);var result=administrativeProfile(c,username,knightName,-1,-1);c.commit();return result;});
    }

    /** Local administration only; target identity, revision and audit share one transaction. */
    WorldProfile progress(String username,String knightName,int expectedAccount,int expectedWorld,Integer rank,Integer subrank,Integer prestige)throws IOException{
        return progress(username,knightName,expectedAccount,expectedWorld,rank,subrank,prestige,null);
    }

    WorldProfile progress(String username,String knightName,int expectedAccount,int expectedWorld,Integer rank,Integer subrank,Integer prestige,String starterSprite)throws IOException{
        return progress(username,knightName,expectedAccount,expectedWorld,rank,subrank,prestige,starterSprite,-1);
    }

    WorldProfile progress(String username,String knightName,int expectedAccount,int expectedWorld,Integer rank,Integer subrank,Integer prestige,String starterSprite,long expectedRevision)throws IOException {
        return database.write(c->{var profile=administrativeProfile(c,username,knightName,expectedAccount,expectedWorld);
            if(expectedRevision>=0&&profile.revision()!=expectedRevision)throw new IOException("Knight changed; refresh before editing");
            var old=profile.progress();
            org.tortodrone.core.character.KnightProgress next;
            try{if((rank==null)!=(subrank==null))throw new IllegalArgumentException();next=new org.tortodrone.core.character.KnightProgress(rank==null?old.rank():rank,subrank==null?old.subrank():subrank,prestige==null?old.prestige():prestige);}
            catch(IllegalArgumentException invalid){throw new IOException("Rank must be 1-10, subrank 1-4 and prestige nonnegative",invalid);}
            boolean granted=starterSprite!=null&&TownStore.grantStarterSprite(c,profile,starterSprite);
            if(next.equals(old)&&!granted)return profile;
            try(var s=c.prepareStatement("INSERT INTO metadata(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")){
                s.setString(1,WorldProfiles.progressKey(profile.worldId(),"rank"));s.setInt(2,next.rank()*10+next.subrank());s.executeUpdate();
                s.setString(1,WorldProfiles.progressKey(profile.worldId(),"prestige"));s.setInt(2,next.prestige());s.executeUpdate();
            }
            try(var s=c.prepareStatement("UPDATE world_characters SET state_revision=state_revision+1 WHERE world_id=?")){s.setInt(1,profile.worldId());s.executeUpdate();}
            AccountRepository.event(c,profile.accountId(),"knight progress; world="+profile.worldId()+"; rank="+next.rankPair()+"; prestige="+next.prestige()+(granted?"; testing starter sprite="+starterSprite:""));
            return WorldProfiles.load(c,profile.accountId(),profile.knightId(),-1);
        });
    }

    WorldProfile currency(String username,String knightName,int expectedAccount,int expectedWorld,
            Currency currency,long delta) throws IOException {
        return balance(username,knightName,expectedAccount,expectedWorld,currency,delta,false);
    }

    WorldProfile setCurrency(String username,String knightName,int expectedAccount,int expectedWorld,
            Currency currency,long amount) throws IOException {
        return balance(username,knightName,expectedAccount,expectedWorld,currency,amount,true);
    }

    private WorldProfile balance(String username,String knightName,int expectedAccount,int expectedWorld,
            Currency currency,long amount,boolean absolute) throws IOException {
        return administrativeCurrency(username,knightName,expectedAccount,expectedWorld,currency,amount,absolute,-1);
    }

    WorldProfile administrativeCurrency(String username,String knightName,int expectedAccount,int expectedWorld,
            Currency currency,long amount,boolean absolute,long expectedRevision)throws IOException {
        Objects.requireNonNull(currency);
        if(absolute ? amount<0||amount>Integer.MAX_VALUE : amount==0||amount<-Integer.MAX_VALUE||amount>Integer.MAX_VALUE)
            throw new IOException(absolute ? "Balance must be between 0 and 2147483647" : "Amount must be between 1 and 2147483647");
        return database.write(c -> {
            var profile=administrativeProfile(c,username,knightName,expectedAccount,expectedWorld);
            if(expectedRevision>=0&&profile.revision()!=expectedRevision)throw new IOException("Balance changed; refresh before editing");
            long previous=currency==Currency.CROWNS?profile.crowns():profile.energy();
            long balance=absolute?amount:previous+amount, delta=balance-previous;
            if(balance<0)throw new IOException("Insufficient "+currency.name().toLowerCase(Locale.ROOT));
            if(balance>Integer.MAX_VALUE)throw new IOException("Balance exceeds the supported limit");
            String column=currency==Currency.CROWNS?"crowns":"energy";
            try(var s=c.prepareStatement("UPDATE world_characters SET "+column+"=?,state_revision=state_revision+1 WHERE world_id=?")) {
                s.setLong(1,balance);s.setInt(2,profile.worldId());s.executeUpdate();
            }
            try(var s=c.prepareStatement("INSERT INTO currency_events(world_id,currency,delta,balance,created_at) VALUES(?,?,?,?,?)")) {
                s.setInt(1,profile.worldId());s.setString(2,column);s.setLong(3,delta);s.setLong(4,balance);s.setLong(5,System.currentTimeMillis());s.executeUpdate();
            }
            AccountRepository.event(c,profile.accountId(),"currency "+(absolute?"set":"grant")+"; world="+profile.worldId()+"; "+column+"="+balance+"; delta="+delta);
            return WorldProfiles.load(c,profile.accountId(),profile.knightId(),-1);
        });
    }

    /** Local administrator quantity/removal, audited and published by the existing revision poller. */
    WorldProfile itemQuantity(String username,String knightName,int expectedAccount,int expectedWorld,
            long itemId,int quantity,ItemCatalog catalog) throws IOException {
        return itemQuantity(username,knightName,expectedAccount,expectedWorld,itemId,quantity,catalog,-1);
    }

    WorldProfile itemQuantity(String username,String knightName,int expectedAccount,int expectedWorld,long itemId,int quantity,ItemCatalog catalog,long expectedRevision)throws IOException {
        if(quantity<0||quantity>9999)throw new IOException("Quantity must be between 0 and 9999");
        return database.write(c->{
            var profile=administrativeProfile(c,username,knightName,expectedAccount,expectedWorld);
            if(expectedRevision>=0&&profile.revision()!=expectedRevision)throw new IOException("Inventory changed; refresh before editing");
            var item=profile.items().stream().filter(i->i.id()==itemId).findFirst().orElseThrow(()->new IOException("No such owned item"));
            if(item.starter()||item.permanent()||item.equippedSlot()!=null)
                throw new IOException("Starter, permanent or equipped items cannot be removed or adjusted; unequip ordinary items in the client first");
            var definition=catalog.find(item.config());
            if(quantity>1&&(definition==null||!definition.stackable()))throw new IOException("This item is not stackable");
            if(quantity==0)try(var s=c.prepareStatement("DELETE FROM inventory_items WHERE world_id=? AND item_id=?")){s.setInt(1,profile.worldId());s.setLong(2,itemId);s.executeUpdate();}
            else try(var s=c.prepareStatement("UPDATE inventory_items SET count=? WHERE world_id=? AND item_id=?")){s.setInt(1,quantity);s.setInt(2,profile.worldId());s.setLong(3,itemId);s.executeUpdate();}
            try(var s=c.prepareStatement("UPDATE world_characters SET state_revision=state_revision+1 WHERE world_id=?")){s.setInt(1,profile.worldId());s.executeUpdate();}
            try(var s=c.prepareStatement("INSERT INTO item_events(world_id,actor_account,config,quantity,first_item_id,created_at) VALUES(?,NULL,?,?,?,?)")){
                s.setInt(1,profile.worldId());s.setString(2,item.config());s.setInt(3,quantity-item.count());s.setLong(4,itemId);s.setLong(5,System.currentTimeMillis());s.executeUpdate();
            }
            AccountRepository.event(c,profile.accountId(),"inventory quantity; world="+profile.worldId()+"; item="+itemId+"; count="+quantity);
            return WorldProfiles.load(c,profile.accountId(),profile.knightId(),-1);
        });
    }

    WorldProfile equip(int accountId,int knightId,long revision,long itemId,Integer slot) throws IOException {
        return database.write(c -> {
            var profile=WorldProfiles.load(c,accountId,knightId,revision);
            equip(c,profile,itemId,slot);
            return WorldProfiles.load(c,accountId,knightId,revision);
        });
    }

    private static void equip(Connection c,WorldProfile profile,long itemId,Integer slot)throws SQLException,IOException {
            var item=profile.items().stream().filter(i->i.id()==itemId).findFirst().orElseThrow(()->new IOException("No such owned item"));
            if(slot!=null && !item.slot().accepts(slot))throw new IOException("Item cannot be equipped in that slot");
            if(slot!=null&&!org.tortodrone.core.character.SlotUpgrades.unlocked(profile,slot))throw new IOException("Activate a slot upgrade first");
            try(var st=c.prepareStatement("DELETE FROM metadata WHERE key IN (SELECT 'item.weapon-slot.'||item_id FROM inventory_items WHERE world_id=?) AND (key=? OR value=?)")) {
                st.setInt(1,profile.worldId());st.setString(2,"item.weapon-slot."+itemId);if(slot==null)st.setNull(3,Types.INTEGER);else st.setInt(3,slot);st.executeUpdate();
            }
            // Slot 14 is metadata-backed because the schema-17 equipped_slot CHECK predates
            // the client's PvP shield slot. Moving the item elsewhere clears its old value.
            if(slot==null||slot!=14)try(var st=c.prepareStatement("DELETE FROM metadata WHERE key=? AND value=?")) {
                st.setString(1,"item.pvp-shield."+profile.worldId());st.setLong(2,itemId);st.executeUpdate();
            }
            // Slot 12 is the dungeon gear slot. The native client refuses a Vitapod whose
            // capsule tier is not better than the equipped one, and replaces (removes) the old pod.
            if(slot!=null&&slot==12&&item.slot()==WorldProfile.Slot.DUNGEON) {
                int nextTier=org.tortodrone.core.character.DungeonGear.vitapodTier(item.config());
                if(nextTier>0) {
                    var current=profile.items().stream().filter(i->i.id()!=itemId&&Integer.valueOf(12).equals(i.equippedSlot())).findFirst().orElse(null);
                    if(current!=null&&org.tortodrone.core.character.DungeonGear.vitapodTier(current.config())>=nextTier)
                        throw new IOException("A better Vitapod is already equipped");
                    if(current!=null&&org.tortodrone.core.character.DungeonGear.vitapodTier(current.config())>0)
                        try(var st=c.prepareStatement("DELETE FROM inventory_items WHERE world_id=? AND item_id=?")){st.setInt(1,profile.worldId());st.setLong(2,current.id());st.executeUpdate();}
                }
            }
            if(slot==null||slot!=12)try(var st=c.prepareStatement("DELETE FROM metadata WHERE key=? AND value=?")) {
                st.setString(1,"item.dungeon."+profile.worldId());st.setLong(2,itemId);st.executeUpdate();
            }

            if(slot!=null)try(PreparedStatement s=c.prepareStatement("UPDATE inventory_items SET equipped_slot=NULL WHERE world_id=? AND equipped_slot=?")){s.setInt(1,profile.worldId());s.setInt(2,slot);s.executeUpdate();}
            try(PreparedStatement s=c.prepareStatement("UPDATE inventory_items SET equipped_slot=?,bound=CASE WHEN ? IS NOT NULL AND bind_on_equip=1 THEN 1 ELSE bound END WHERE world_id=? AND item_id=?")){
                if(slot==null||slot==8||slot==9||slot==12||slot==14)s.setNull(1,Types.INTEGER);else s.setInt(1,slot);
                if(slot==null)s.setNull(2,Types.INTEGER);else s.setInt(2,slot);
                s.setInt(3,profile.worldId());s.setLong(4,itemId);s.executeUpdate();
            }
            if(slot!=null&&(slot==8||slot==9))try(var st=c.prepareStatement("INSERT INTO metadata(key,value) VALUES(?,?)")){st.setString(1,"item.weapon-slot."+itemId);st.setInt(2,slot);st.executeUpdate();}
            if(slot!=null&&slot==14)try(var st=c.prepareStatement("INSERT INTO metadata(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")){st.setString(1,"item.pvp-shield."+profile.worldId());st.setLong(2,itemId);st.executeUpdate();}
            if(slot!=null&&slot==12)try(var st=c.prepareStatement("INSERT INTO metadata(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")){st.setString(1,"item.dungeon."+profile.worldId());st.setLong(2,itemId);st.executeUpdate();}
            try(PreparedStatement s=c.prepareStatement("UPDATE world_characters SET state_revision=state_revision+1 WHERE world_id=?")){s.setInt(1,profile.worldId());s.executeUpdate();}
    }

    WorldProfile administrativeEquipment(String username,String knight,int account,int world,long revision,long itemId,Integer slot,String grant,ItemCatalog catalog,org.tortodrone.core.town.TownCatalog rules)throws IOException {
        return database.write(c->{
            var profile=administrativeProfile(c,username,knight,account,world);
            if(profile.revision()!=revision)throw new IOException("Inventory changed; refresh before editing");
            long owned=itemId;
            if(!grant.isEmpty()) {
                var entry=catalog.require(grant);
                if(!entry.grantable()||slot==null||!entry.slot().accepts(slot))throw new IOException("Item cannot be equipped in that slot");
                org.tortodrone.character.ItemModifiers.NONE.validate(entry,rules);
                owned=new TownStore(database,catalog,rules).create(c,profile,grant,1,false,0,0,List.of());
                profile=WorldProfiles.load(c,account,profile.knightId(),-1);
            }
            equip(c,profile,owned,slot);
            AccountRepository.event(c,account,"equipment; world="+world+"; item="+owned+"; slot="+slot);
            return WorldProfiles.load(c,account,profile.knightId(),-1);
        });
    }

    /** Permission and target identity are checked under the same write lock as allocation. */
    WorldProfile grantItems(String username,String knightName,int expectedAccount,int expectedWorld,
            ItemCatalog.Entry entry,int quantity,int actorAccount,long actorRevision)throws IOException {
        return grantItems(username,knightName,expectedAccount,expectedWorld,entry,quantity,actorAccount,actorRevision,org.tortodrone.character.ItemModifiers.NONE,org.tortodrone.core.town.TownCatalog.empty());
    }

    WorldProfile grantItems(String username,String knightName,int expectedAccount,int expectedWorld,
            ItemCatalog.Entry entry,int quantity,int actorAccount,long actorRevision,
            org.tortodrone.character.ItemModifiers modifiers,org.tortodrone.core.town.TownCatalog rules)throws IOException {
        return grantItems(username,knightName,expectedAccount,expectedWorld,entry,quantity,actorAccount,actorRevision,modifiers,rules,false,entry.expireDays());
    }

    WorldProfile grantItems(String username,String knightName,int expectedAccount,int expectedWorld,
            ItemCatalog.Entry entry,int quantity,int actorAccount,long actorRevision,
            org.tortodrone.character.ItemModifiers modifiers,org.tortodrone.core.town.TownCatalog rules,boolean locked,int expireDays)throws IOException {
        if(expireDays<0||expireDays>3650||expireDays>0&&entry.permanent())throw new IOException("Expiry must be 0–3650 days and cannot apply to permanent items");
        if(quantity<1||quantity>ItemCatalog.GRANT_LIMIT)throw new IOException("Quantity must be between 1 and 20");
        if(!entry.grantable())throw new IOException("Unsupported item for grants");
        modifiers.validate(entry,rules);
        return database.write(c->{
            if(actorAccount>=0)try(var s=c.prepareStatement("SELECT game_master FROM accounts WHERE id=? AND auth_revision=?")) {
                s.setInt(1,actorAccount);s.setLong(2,actorRevision);
                try(var r=s.executeQuery()){if(!r.next()||!r.getBoolean(1))throw new IOException("Game Master permission required");}
            }
            int account,knight;
            if (username == null) {
                // Global name lookup shares the grant transaction and permission check.
                try (var s=c.prepareStatement("SELECT account_id FROM knights WHERE name_key=?")) {
                    s.setString(1,knightName.toLowerCase(Locale.ROOT));
                    try (var r=s.executeQuery()) { if(!r.next())throw new IOException("No such knight");account=r.getInt(1); }
                }
            } else account=AccountRepository.requireId(c,username);
            if(expectedAccount>=0&&account!=expectedAccount)throw new IOException("Account changed; refresh");
            try(var s=c.prepareStatement("SELECT id FROM knights WHERE account_id=? AND name_key=?")) {
                s.setInt(1,account);s.setString(2,knightName.toLowerCase(Locale.ROOT));
                try(var r=s.executeQuery()){if(!r.next())throw new IOException("No such knight");knight=r.getInt(1);}
            }
            var profile=WorldProfiles.load(c,account,knight,-1);
            if(expectedWorld>=0&&profile.worldId()!=expectedWorld)throw new IOException("Knight changed; refresh");
            // Stack only plain inventory items with identical grant flags; equipment stays distinct.
            var stack=expireDays==0&&entry.stackable()&&modifiers.equals(org.tortodrone.character.ItemModifiers.NONE)?profile.items().stream().filter(i->i.config().equals(entry.config())
                &&i.slot()==WorldProfile.Slot.OTHER&&i.expiresAt()==null&&i.bound()==(locked||entry.locked()||entry.permanent())&&i.permanent()==entry.permanent()
                &&i.equippedSlot()==null&&i.level()==0&&i.heatProgress()==0&&i.variants().isEmpty()&&i.count()+quantity<=9999).findFirst().orElse(null):null;
            int instances=stack!=null?0:entry.stackable()?1:quantity;
            long first=0;
            if(stack!=null) {
                first=stack.id();
                try(var s=c.prepareStatement("UPDATE inventory_items SET count=count+? WHERE item_id=? AND world_id=?")) {
                    s.setInt(1,quantity);s.setLong(2,first);s.setInt(3,profile.worldId());s.executeUpdate();
                }
            }else try(var s=c.prepareStatement("INSERT INTO inventory_items(item_id,world_id,slot,config,bind_on_equip,count,permanent,bound,level,heat_progress,variants) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
                for(int i=0;i<instances;i++) {
                    long id=WorldProfiles.allocate(c,"next-item-id",Long.MAX_VALUE);if(i==0)first=id;
                    s.setLong(1,id);s.setInt(2,profile.worldId());s.setString(3,entry.slot().persistedName());s.setString(4,entry.config());s.setBoolean(5,entry.bindOnEquip());
                    s.setInt(6,entry.stackable()?quantity:1);s.setBoolean(7,entry.permanent());s.setBoolean(8,locked||entry.locked()||entry.permanent());
                    s.setInt(9,modifiers.level());s.setFloat(10,modifiers.heat());s.setString(11,org.tortodrone.core.Json.stringify(modifiers.variants()));s.executeUpdate();
                    InventoryExpiry.save(c,id,InventoryExpiry.deadline(expireDays));
                }
            }
            try(var s=c.prepareStatement("UPDATE world_characters SET state_revision=state_revision+1 WHERE world_id=?")){s.setInt(1,profile.worldId());s.executeUpdate();}
            try(var s=c.prepareStatement("INSERT INTO item_events(world_id,actor_account,config,quantity,first_item_id,created_at) VALUES(?,?,?,?,?,?)")) {
                s.setInt(1,profile.worldId());if(actorAccount<0)s.setNull(2,Types.INTEGER);else s.setInt(2,actorAccount);
                s.setString(3,entry.config());s.setInt(4,quantity);s.setLong(5,first);s.setLong(6,System.currentTimeMillis());s.executeUpdate();
            }
            return WorldProfiles.load(c,account,knight,-1);
        });
    }

    /** Local administration only; a stale inspector cannot overwrite newly forged or traded gear. */
    WorldProfile itemModifiers(String username,String knightName,int expectedAccount,int expectedWorld,long expectedRevision,
            long itemId,org.tortodrone.character.ItemModifiers modifiers,ItemCatalog catalog,org.tortodrone.core.town.TownCatalog rules)throws IOException{
        return database.write(c->{
            var profile=administrativeProfile(c,username,knightName,expectedAccount,expectedWorld);if(profile.revision()!=expectedRevision)throw new IOException("Inventory changed; refresh before editing");
            var item=profile.items().stream().filter(i->i.id()==itemId).findFirst().orElseThrow(()->new IOException("Item is not owned by this knight"));
            modifiers.validate(catalog.require(item.config()),rules);
            try(var s=c.prepareStatement("UPDATE inventory_items SET level=?,heat_progress=?,variants=? WHERE world_id=? AND item_id=?")){s.setInt(1,modifiers.level());s.setFloat(2,modifiers.heat());s.setString(3,org.tortodrone.core.Json.stringify(modifiers.variants()));s.setInt(4,profile.worldId());s.setLong(5,itemId);if(s.executeUpdate()!=1)throw new IOException("Item changed");}
            TownLedger.touch(c,profile.worldId());AccountRepository.event(c,profile.accountId(),"item modifiers; world="+profile.worldId()+"; item="+itemId);
            return WorldProfiles.load(c,profile.accountId(),profile.knightId(),-1);
        });
    }

    /** Web/local administration; expected identity and revision are checked inside BEGIN IMMEDIATE. */
    WorldProfile itemAccessory(String username,String knightName,int expectedAccount,int expectedWorld,long expectedRevision,long itemId,String accessory,org.tortodrone.core.town.TownCatalog rules)throws IOException {
        return database.write(c->{
            var profile=administrativeProfile(c,username,knightName,expectedAccount,expectedWorld);
            if(profile.revision()!=expectedRevision)throw new IOException("Inventory changed; refresh before editing");
            var item=profile.items().stream().filter(i->i.id()==itemId).findFirst().orElseThrow(()->new IOException("Item is not owned by this knight"));
            String costume;
            try(var s=c.prepareStatement("SELECT costume FROM knights WHERE account_id=? AND id=?")){s.setInt(1,profile.accountId());s.setInt(2,profile.knightId());try(var r=s.executeQuery()){if(!r.next())throw new IOException("Knight changed");costume=r.getString(1);}}
            TownStore.editAccessory(c,profile,item,accessory,costume,rules);
            TownLedger.touch(c,profile.worldId());AccountRepository.event(c,profile.accountId(),"item accessory; world="+profile.worldId()+"; item="+itemId);
            return WorldProfiles.load(c,profile.accountId(),profile.knightId(),-1);
        });
    }

    List<WorldProfile> trade(WorldProfile first,long firstAuth,Map<Long,Integer> firstItems,int firstCrowns,int firstEnergy,
            WorldProfile second,long secondAuth,Map<Long,Integer> secondItems,int secondCrowns,int secondEnergy)throws IOException {
        return trade(first,firstAuth,firstItems,firstCrowns,firstEnergy,second,secondAuth,secondItems,secondCrowns,secondEnergy,ItemCatalog.starter());
    }

    List<WorldProfile> trade(WorldProfile first,long firstAuth,Map<Long,Integer> firstItems,int firstCrowns,int firstEnergy,
            WorldProfile second,long secondAuth,Map<Long,Integer> secondItems,int secondCrowns,int secondEnergy,ItemCatalog catalog)throws IOException {
        var a=Map.copyOf(firstItems);var b=Map.copyOf(secondItems);
        return database.write(c->TradeTransfer.commit(c,first,firstAuth,a,firstCrowns,firstEnergy,second,secondAuth,b,secondCrowns,secondEnergy,catalog));
    }
}
