package org.tortodrone.storage;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.character.SlotUpgrades;
import org.tortodrone.core.character.WorldProfile;
import org.tortodrone.core.town.GearRules;
import org.tortodrone.core.town.TownCatalog;

/** Forge attempts and heat awards, including the fire-crystal and equipped-gear rules. */
final class GearStore {
    private static final String[] FIRE_CRYSTALS={"Cracked","Dim","Warm","Glowing","Shining","Radiant"};
    private final ItemCatalog items;
    private final TownCatalog rules;
    GearStore(ItemCatalog items,TownCatalog rules){this.items=items;this.rules=rules;}

    /** Spend fire crystals on one forge attempt; a failed attempt still spends them. */
    Object forge(Connection c,WorldProfile p,long itemId,int crystals,int expectedLevel)throws SQLException,IOException{
        var item=TownLedger.owned(p,itemId);var definition=items.require(item.config());var gear=rules.gear().gear(item.config());
        if(gear==null||gear.maximum(item.level()))throw new IOException("m.forge_already");
        if(item.level()!=expectedLevel)throw new IOException("m.costupdate");
        if(item.heatProgress()!=1)throw new IOException("m.forge_locked");
        var props=rules.gear().forge(gear,item.level());if(props==null)throw new IOException("m.forge_already");
        float chance=props.chance(crystals,true);int rarity=definition.rarity();if(rarity<0||rarity>=FIRE_CRYSTALS.length)throw new IOException("m.internal_error");
        int remaining=crystals;var consumes=new LinkedHashMap<Long,Integer>();String config="Rarity/Fire Crystal/"+FIRE_CRYSTALS[rarity];
        for(var held:p.items())if(held.config().equals(config)&&held.equippedSlot()==null&&!held.permanent()&&remaining>0){int take=Math.min(held.count(),remaining);consumes.put(held.id(),take);remaining-=take;}
        if(remaining!=0)throw new Database.Refused("m.insufficient_crystals");
        for(var consume:consumes.entrySet())TownLedger.consume(c,p,consume.getKey(),consume.getValue());
        // Like the archive, failed attempts spend crystals but preserve the full heat bar.
        if(java.util.concurrent.ThreadLocalRandom.current().nextFloat()>=chance)return null;
        // Live ForgeAnswerProbe: a qualified localization key followed by a tainted
        // displayed level (stored levels are zero based), in this exact order.
        setGearProgress(c,p.worldId(),item.id(),item.level()+1,0);
        return GearRules.forgeMessage(definition.nameKey(),item.level()+1);
    }
    /** Trusted heat awards split only across equipped gear still able to receive heat. */
    Object addHeat(Connection c,WorldProfile p,float amount)throws SQLException,IOException{
        applyHeat(c,p,rules,amount);return null;
    }
    static void applyHeat(Connection c,WorldProfile p,TownCatalog rules,float amount)throws SQLException,IOException {
        float awarded=SlotUpgrades.heatBonusActive(p)?amount*2:amount;
        var eligible=p.items().stream().filter(i->i.equippedSlot()!=null&&Set.of(0,1,2,6,7,8,9).contains(i.equippedSlot())).filter(i->{var g=rules.gear().gear(i.config());return g!=null&&!g.maximum(i.level())&&i.heatProgress()<1;}).toList();
        for(var item:eligible){var g=rules.gear().gear(item.config());setGearProgress(c,p.worldId(),item.id(),item.level(),g.addHeat(item.level(),item.heatProgress(),awarded/eligible.size()));}
    }
    static void setGearProgress(Connection c,int world,long item,int level,float heat)throws SQLException,IOException{
        try(var st=c.prepareStatement("UPDATE inventory_items SET level=?,heat_progress=? WHERE world_id=? AND item_id=?")){st.setInt(1,level);st.setFloat(2,heat);st.setInt(3,world);st.setLong(4,item);if(st.executeUpdate()!=1)throw new IOException("Item changed");}
    }
}
