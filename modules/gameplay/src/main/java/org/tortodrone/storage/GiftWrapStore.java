package org.tortodrone.storage;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import org.tortodrone.core.Json;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.character.WorldProfile;
import org.tortodrone.core.town.TownCatalog;

/** Native gift-box wrapping and unwrapping, including the escrowed contents. */
final class GiftWrapStore {
    private final ItemCatalog items;
    private final TownCatalog rules;
    GiftWrapStore(ItemCatalog items,TownCatalog rules){this.items=items;this.rules=rules;}

    Object wrap(Connection c,WorldProfile p,int account,int knight,long paperId,Map<Long,Integer> selected,String message,String costume)throws SQLException,IOException{
        var paper=TownLedger.owned(p,paperId);String box=rules.item(paper.config()).get("giftBox");
        if(box==null||!items.require(paper.config()).type().equals("WrappingPaper")||!items.require(box).type().equals("GiftBox"))throw new IOException("e.not_wrapable");
        var gifts=new ArrayList<WorldProfile.Item>();int total=0;
        for(var entry:selected.entrySet()){
            var item=TownLedger.owned(p,entry.getKey());var def=items.require(item.config());int count=entry.getValue();
            if(item.id()==paperId||item.bound()||def.locked()||item.permanent()||item.starter()||item.cursed()||item.expired(System.currentTimeMillis())||item.equippedSlot()!=null||def.type().equals("GiftBox")||count<1||count>item.count()||(total+=count)>9999)throw new IOException("e.not_wrapable");
            gifts.add(new WorldProfile.Item(0,item.slot(),item.config(),null,item.heat(),null,false,item.bindOnEquip(),false,count,false,item.level(),item.heatProgress(),item.variants(),item.nativeAccessories(costume)).withExpiration(item.expiresAt()));
        }
        String name;try(var st=c.prepareStatement("SELECT name FROM knights WHERE account_id=? AND id=?")){st.setInt(1,account);st.setInt(2,knight);try(var row=st.executeQuery()){if(!row.next())throw new IOException("m.internal_error");name=row.getString(1);}}
        TownLedger.consume(c,p,paperId,1);for(var entry:selected.entrySet())TownLedger.consume(c,p,entry.getKey(),entry.getValue());
        long id=TownLedger.create(c,p,items,box,1,false,0,0,List.of());GiftStore.save(c,id,new WorldProfile.Gift(p.worldId(),name,message,gifts));return null;
    }
    Object unwrap(Connection c,WorldProfile p,long boxId)throws SQLException,IOException{
        var box=TownLedger.owned(p,boxId);if(!items.require(box.config()).type().equals("GiftBox")||box.gift()==null)throw new IOException("m.internal_error");
        var contents=box.gift().contents();TownLedger.consume(c,p,boxId,1);var result=new LinkedHashMap<Object,Integer>();
        for(var item:contents){
            if(item.expired(System.currentTimeMillis()))continue;
            boolean plain=item.expiresAt()==null&&item.heat()==0&&item.accessory()==null&&item.additionalAccessories().isEmpty()
                &&item.bindOnEquip()==items.require(item.config()).bindOnEquip();
            long id=TownLedger.create(c,p,items,item.config(),item.count(),item.bound(),item.level(),item.heatProgress(),item.variants(),plain);
            InventoryExpiry.save(c,id,item.expiresAt());
            try(var st=c.prepareStatement("UPDATE inventory_items SET heat=?,accessory=?,accessories=?,bind_on_equip=? WHERE world_id=? AND item_id=?")){st.setInt(1,item.heat());st.setString(2,item.accessory());st.setString(3,Json.stringify(item.additionalAccessories()));st.setBoolean(4,item.bindOnEquip());st.setInt(5,p.worldId());st.setLong(6,id);st.executeUpdate();}
            result.merge(id,item.count(),Math::addExact);
        }
        return new org.tortodrone.core.wire.Multiset(result);
    }
}
