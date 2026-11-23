package org.tortodrone.storage;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import org.tortodrone.core.character.AnimatedItems;
import org.tortodrone.core.character.Confetti;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.character.NativeStats;
import org.tortodrone.core.character.WorldProfile;

/** Scene-issued pickups, consumable use, cosmetic actions, lifetime stats and revive payment. */
final class UseStore {
    private final Database database;
    private final ItemCatalog items;
    UseStore(Database database,ItemCatalog items){this.database=database;this.items=items;}

    /** Scene-issued receipt, never accepted from a client invocation. Commit before actor removal.
     * The receipt is global to the pickup, so a reconnect/competing account cannot claim it twice. */
    TownStore.Result pickup(int account,int knight,long auth,String receipt,String config)throws IOException {
        if(receipt==null||!receipt.matches("[0-9a-f-]{36}:[0-9]{1,10}"))throw new IOException("Invalid scene reward receipt");
        return database.write(c->{
            var profile=WorldProfiles.load(c,account,knight,auth);String key="scene-pickup:"+receipt;
            try(var st=c.prepareStatement("SELECT value FROM metadata WHERE key=?")){
                st.setString(1,key);try(var row=st.executeQuery()){if(row.next()){
                    if(!row.getString(1).equals(Integer.toString(profile.worldId())))throw new IOException("Pickup already claimed");
                    return new TownStore.Result(profile,null);
                }}
            }
            int crowns=switch(config){
                // Original Treasure/Crowns/Value/Base denominations, also item.xml's Crowns argument.
                case "Instant/Crown/Copper Crown"->1;case "Instant/Crown/Copper-Silver Crown"->5;
                case "Instant/Crown/Silver Crown"->10;case "Instant/Crown/Silver-Gold Crown"->25;
                case "Instant/Crown/Gold Crown"->50;default->0;
            };
            if(crowns>0)TownLedger.credit(c,profile.worldId(),crowns,0);
            else{
                var definition=items.require(config);
                if(!definition.grantable()||definition.permanent()||definition.type().equals("InstantAction"))throw new IOException("Unsupported persistent scene reward");
                TownLedger.create(c,profile,items,config,1,false,0,0,List.of());
            }
            try(var st=c.prepareStatement("INSERT INTO metadata(key,value) VALUES(?,?)")){st.setString(1,key);st.setInt(2,profile.worldId());st.executeUpdate();}
            TownLedger.touch(c,profile.worldId());return new TownStore.Result(WorldProfiles.load(c,account,knight,auth),null);
        });
    }

    Object trash(Connection c,WorldProfile p,long item)throws SQLException,IOException{
        var held=TownLedger.owned(p,item);
        if(held.permanent()||held.equippedSlot()!=null)throw new IOException("m.internal_error");
        TownLedger.consume(c,p,item,held.count());
        return null;
    }

    Object animated(Connection c,WorldProfile p,long revision,long item,boolean confetti)throws SQLException,IOException{
        if(p.revision()!=revision)throw new IOException("Your inventory changed. Please try again.");
        var held=TownLedger.owned(p,item);
        if(!(confetti?Confetti.supports(held.config()):AnimatedItems.supports(held.config()))||!items.require(held.config()).type().equals("AnimatedAction")
                ||held.gift()!=null||held.expired(System.currentTimeMillis()))throw new IOException("m.internal_error");
        TownLedger.consume(c,p,item,1);return held.config();
    }

    Object use(Connection c,WorldProfile p,long revision,long item)throws SQLException,IOException{
        if(p.revision()!=revision)throw new IOException("Your inventory changed. Please try again.");
        var held=TownLedger.owned(p,item);
        var definition=items.find(held.config());
        if(definition==null||!Set.of("InstantAction","SpawnActor","Lift").contains(definition.type())
                ||held.gift()!=null||held.expired(System.currentTimeMillis()))throw new IOException("m.internal_error");
        TownLedger.consume(c,p,item,1);return held.config();
    }

    Object stats(Connection c,WorldProfile p,Map<String,Integer> counters)throws SQLException,IOException{
        if(counters==null||counters.isEmpty())throw new IOException("m.internal_error");
        var ordinals=new TreeMap<Integer,Integer>();
        for(var entry:counters.entrySet()){
            int ordinal;
            try{ordinal=NativeStats.ordinal(entry.getKey());}
            catch(IllegalArgumentException invalid){throw new IOException("Unknown lifetime stat "+entry.getKey(),invalid);}
            int delta=entry.getValue()==null?0:entry.getValue();
            if(delta<1||delta>1_000_000)throw new IOException("Invalid lifetime stat increment");
            ordinals.merge(ordinal,delta,Integer::sum);
            if(ordinals.get(ordinal)>1_000_000)throw new IOException("Invalid lifetime stat increment");
        }
        for(var entry:ordinals.entrySet())AccountAchievements.increment(c,p.accountId(),entry.getKey(),entry.getValue());
        return null;
    }

    Object revive(Connection c,WorldProfile p,int approvedCost)throws SQLException,IOException{
        if(approvedCost<0){
            var spark=p.items().stream().filter(i->i.config().equals("Rarity/Spark of Life")&&i.count()>0).findFirst().orElseThrow(()->new IOException("m.costupdate"));
            TownLedger.consume(c,p,spark.id(),1);
        }else{
            if(approvedCost<TownStore.SPARK_COST)throw new IOException("m.costupdate");
            TownLedger.pay(c,p,0,TownStore.SPARK_COST);
        }
        return null;
    }
}
