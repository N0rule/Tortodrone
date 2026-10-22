package org.tortodrone.storage;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import org.tortodrone.character.WorldProfile;
import org.tortodrone.character.ItemCatalog;

/** Two-owner exchange, entirely inside the deployment's BEGIN IMMEDIATE transaction. */
final class TradeTransfer {
    private TradeTransfer() { }
    static List<WorldProfile> commit(Connection c,WorldProfile first,long firstAuth,Map<Long,Integer> firstItems,
            int firstCrowns,int firstEnergy,WorldProfile second,long secondAuth,Map<Long,Integer> secondItems,
            int secondCrowns,int secondEnergy,ItemCatalog catalog)throws SQLException,IOException {
        if(first.accountId()==second.accountId()||first.worldId()==second.worldId())throw new IOException("Cannot trade with yourself");
        var a=current(c,first,firstAuth);var b=current(c,second,secondAuth);
        validate(a,firstItems,firstCrowns,firstEnergy,catalog);validate(b,secondItems,secondCrowns,secondEnergy,catalog);
        int ac=balance(a.crowns(),firstCrowns,secondCrowns),ae=balance(a.energy(),firstEnergy,secondEnergy);
        int bc=balance(b.crowns(),secondCrowns,firstCrowns),be=balance(b.energy(),secondEnergy,firstEnergy);
        transfer(c,a,b,firstItems);transfer(c,b,a,secondItems);
        update(c,a,ac,ae);update(c,b,bc,be);
        return List.of(WorldProfiles.load(c,a.accountId(),a.knightId(),firstAuth),
            WorldProfiles.load(c,b.accountId(),b.knightId(),secondAuth));
    }
    private static WorldProfile current(Connection c,WorldProfile expected,long auth)throws SQLException,IOException {
        var actual=WorldProfiles.load(c,expected.accountId(),expected.knightId(),auth);
        if(auth<0||actual.worldId()!=expected.worldId()||actual.revision()!=expected.revision())
            throw new IOException("Inventory or account changed; start a new trade");
        return actual;
    }
    static void validate(WorldProfile owner,Map<Long,Integer> offered,int crowns,int energy,ItemCatalog catalog)throws IOException {
        if(offered.size()>15)throw new IOException("A trade can offer at most 15 item stacks");
        if(crowns<0||crowns>owner.crowns()||energy<0||energy>owner.energy())throw new IOException("Insufficient trade currency");
        for(var entry:offered.entrySet()) {
            var item=owner.items().stream().filter(i->i.id()==entry.getKey()).findFirst().orElseThrow(()->new IOException("Item is not owned"));
            var definition=catalog.find(item.config());
            if(definition!=null&&(definition.locked()||definition.permanent())||item.bound()||item.permanent()||item.starter()||item.equippedSlot()!=null)
                throw new IOException("Bound, starter and equipped items cannot be traded");
            if(entry.getValue()==null||entry.getValue()<1||entry.getValue()>item.count())throw new IOException("Invalid trade item quantity");
        }
    }
    private static int balance(int original,int sent,int received)throws IOException {
        long result=(long)original-sent+received;
        if(result<0||result>Integer.MAX_VALUE)throw new IOException("Trade currency balance exceeds its limit");
        return (int)result;
    }
    private static void transfer(Connection c,WorldProfile from,WorldProfile to,Map<Long,Integer> offered)throws SQLException,IOException {
        for(var entry:offered.entrySet()) {
            var item=from.items().stream().filter(i->i.id()==entry.getKey()).findFirst().orElseThrow();int count=entry.getValue();
            long receivedId=item.id();
            if(count==item.count()) {
                try(var s=c.prepareStatement("UPDATE inventory_items SET world_id=? WHERE item_id=? AND world_id=?")) {
                    s.setInt(1,to.worldId());s.setLong(2,item.id());s.setInt(3,from.worldId());
                    if(s.executeUpdate()!=1)throw new IOException("Trade item changed");
                }
            } else {
                long id=WorldProfiles.allocate(c,"next-item-id",Long.MAX_VALUE);
                receivedId=id;
                InventoryExpiry.save(c,id,item.expiresAt());
                try(var s=c.prepareStatement("INSERT INTO inventory_items(item_id,world_id,slot,config,accessory,heat,equipped_slot,bound,bind_on_equip,starter,count,permanent,level,heat_progress,variants,accessories) SELECT ?,?,slot,config,accessory,heat,NULL,bound,bind_on_equip,0,?,permanent,level,heat_progress,variants,accessories FROM inventory_items WHERE item_id=? AND world_id=?")) {
                    s.setLong(1,id);s.setInt(2,to.worldId());s.setInt(3,count);s.setLong(4,item.id());s.setInt(5,from.worldId());
                    if(s.executeUpdate()!=1)throw new IOException("Trade item changed");
                }
                try(var s=c.prepareStatement("UPDATE inventory_items SET count=count-? WHERE item_id=?")) {s.setInt(1,count);s.setLong(2,item.id());s.executeUpdate();}
            }
            try(var s=c.prepareStatement("INSERT INTO item_events(world_id,actor_account,config,quantity,first_item_id,created_at) VALUES(?,?,?,?,?,?)")) {
                for(boolean incoming:List.of(false,true)) {
                    s.setInt(1,incoming?to.worldId():from.worldId());s.setInt(2,from.accountId());s.setString(3,item.config());
                    s.setInt(4,incoming?count:-count);s.setLong(5,incoming?receivedId:item.id());s.setLong(6,System.currentTimeMillis());s.executeUpdate();
                }
            }
        }
    }
    private static void update(Connection c,WorldProfile before,int crowns,int energy)throws SQLException {
        try(var s=c.prepareStatement("UPDATE world_characters SET crowns=?,energy=?,state_revision=state_revision+1 WHERE world_id=?")) {
            s.setInt(1,crowns);s.setInt(2,energy);s.setInt(3,before.worldId());s.executeUpdate();
        }
        try(var s=c.prepareStatement("INSERT INTO currency_events(world_id,currency,delta,balance,created_at) VALUES(?,?,?,?,?)")) {
            for(String currency:List.of("crowns","energy")) {
                int old=currency.equals("crowns")?before.crowns():before.energy(),value=currency.equals("crowns")?crowns:energy;
                if(old==value)continue;s.setInt(1,before.worldId());s.setString(2,currency);s.setLong(3,(long)value-old);
                s.setInt(4,value);s.setLong(5,System.currentTimeMillis());s.executeUpdate();
            }
        }
    }
}
