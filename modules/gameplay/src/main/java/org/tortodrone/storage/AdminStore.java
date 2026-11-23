package org.tortodrone.storage;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.character.KnightProgress;
import org.tortodrone.core.character.WorldProfile;
import org.tortodrone.core.town.TownCatalog;

/** Verified GM setters for progress and gear; they never grant normal-player authority. */
final class AdminStore {
    private final ItemCatalog items;
    private final TownCatalog rules;
    AdminStore(ItemCatalog items,TownCatalog rules){this.items=items;this.rules=rules;}

    /** Native /grant and /set_rank: role and ownership checked inside the write transaction. */
    Object progress(Connection c,WorldProfile p,int account,long auth,int method,List<Object> args)throws SQLException,IOException{
        TownStore.requireGameMaster(c,account,auth);
        if(method==17) {
            int amount=(Integer)args.getFirst();
            if(amount<0)throw new IOException("Crown grant must be nonnegative");
            TownLedger.credit(c,p.worldId(),amount,0);
        }else if(method==44) {
            KnightProgress progress;
            try{progress=new KnightProgress((Integer)args.get(0),(Integer)args.get(1),p.progress().prestige());}
            catch(IllegalArgumentException invalid){throw new IOException("Rank must be 1–10 and subrank 1–4");}
            try(var st=c.prepareStatement("INSERT INTO metadata(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
                st.setString(1,WorldProfiles.progressKey(p.worldId(),"rank"));st.setInt(2,progress.rank()*10+progress.subrank());st.executeUpdate();
            }
        }else throw new IOException("Unknown progress admin operation");
        return null;
    }

    /** Current stock /heat, /level and /forge commands; setters never grant normal-player authority. */
    Object gear(Connection c,WorldProfile p,int account,long auth,int method,List<Object> args)throws SQLException,IOException{
        TownStore.requireGameMaster(c,account,auth);int slot=(Integer)args.get(0);
        if(method==41&&slot>=6&&slot<=9){
            // The stock /heat command exposes only a "weapon" group and always
            // sends the first weapon slot. Apply the value to every equipped
            // weapon so slots 7-9 are reachable through the same command.
            float percent=(Float)args.get(1);if(!Float.isFinite(percent)||percent<0||percent>100)throw new IOException("Heat progress must be between 0 and 100");
            var weapons=p.items().stream().filter(i->i.equippedSlot()!=null&&i.equippedSlot()>=6&&i.equippedSlot()<=9).toList();
            if(weapons.isEmpty())throw new IOException("Equip a weapon first");
            boolean applied=false;
            for(var weapon:weapons){var weaponGear=rules.gear().gear(weapon.config());if(weaponGear==null||weaponGear.levels()<1)continue;GearStore.setGearProgress(c,p.worldId(),weapon.id(),weapon.level(),percent/100f);applied=true;}
            if(!applied)throw new IOException("This item has no heat progression");
            return null;
        }
        var item=p.items().stream().filter(i->i.equippedSlot()!=null&&i.equippedSlot()==slot).findFirst().orElseThrow(()->new IOException("Equip an item in that slot first"));
        var gear=rules.gear().gear(item.config());if(gear==null||gear.levels()<1)throw new IOException("This item has no heat progression");
        if(method==41){float percent=(Float)args.get(1);if(!Float.isFinite(percent)||percent<0||percent>100)throw new IOException("Heat progress must be between 0 and 100");GearStore.setGearProgress(c,p.worldId(),item.id(),item.level(),percent/100f);return null;}
        if(method==42){int level=(Integer)args.get(1);if(level<0||level>=gear.levels())throw new IOException("Item level is outside its progression table");GearStore.setGearProgress(c,p.worldId(),item.id(),level,0);return null;}
        if(method!=23)throw new IOException("Unknown gear admin operation");
        if(gear.maximum(item.level()))return null;var props=rules.gear().forge(gear,item.level());if(props==null)return null;
        int crystals=(Integer)args.get(1);if(crystals<0||crystals>9999)throw new IOException("Invalid forge crystal tier");
        float chance=props.chance(crystals,false);if(java.util.concurrent.ThreadLocalRandom.current().nextFloat()>=chance)return null;
        GearStore.setGearProgress(c,p.worldId(),item.id(),item.level()+1,0);return item.level()+1;
    }
}
