package org.tortodrone.storage;

import java.io.IOException;
import java.sql.*;
import org.tortodrone.core.character.SlotUpgrades;
import org.tortodrone.core.character.WorldProfile;

/** Arsenal upgrade activation and duration extension. */
final class UpgradeStore {
    Object extend(Connection c,WorldProfile p,long active,long unused)throws SQLException,IOException{
        var target=TownLedger.owned(p,active);var source=TownLedger.owned(p,unused);var a=SlotUpgrades.find(target.config());var b=SlotUpgrades.find(source.config());
        if(active==unused||a==null||b==null||a.weapons()!=b.weapons()||a.trinkets()!=b.trinkets()
                ||a.heatBonus()!=b.heatBonus()||a.pvpTokens()!=b.pvpTokens()||target.expiresAt()==null||source.expiresAt()!=null||source.count()!=1)throw new IOException("These upgrades cannot be combined");
        long deadline;
        try{deadline=Math.addExact(target.expiresAt(),Math.multiplyExact((long)b.days(),86_400_000L));}
        catch(ArithmeticException overflow){throw new IOException("Upgrade duration exceeds supported limits");}
        TownLedger.consume(c,p,unused,1);InventoryExpiry.save(c,active,deadline);return null;
    }

    Object activate(Connection c,WorldProfile p,long id)throws SQLException,IOException{
        var item=TownLedger.owned(p,id);var def=SlotUpgrades.find(item.config());
        if(def==null||item.expiresAt()!=null||item.count()!=1||item.equippedSlot()!=null||item.gift()!=null)throw new IOException("This upgrade cannot be activated");
        if(def.weapons()>0&&SlotUpgrades.weapons(p)-2>=2||def.trinkets()>0&&SlotUpgrades.trinkets(p)>=2)throw new IOException("All slots of this type are already unlocked");
        if(def.heatBonus()&&SlotUpgrades.heatBonusActive(p))throw new IOException("A Heat Bonus is already active");
        if(def.pvpTokens()>0&&SlotUpgrades.pvpTokenBonus(p)+def.pvpTokens()>6)throw new IOException("The Krogmo Coin Bonus is already at its maximum");
        InventoryExpiry.save(c,id,InventoryExpiry.deadline(def.days()));
        try(var st=c.prepareStatement("UPDATE inventory_items SET bound=1 WHERE world_id=? AND item_id=?")){st.setInt(1,p.worldId());st.setLong(2,id);st.executeUpdate();}
        return null;
    }
}
