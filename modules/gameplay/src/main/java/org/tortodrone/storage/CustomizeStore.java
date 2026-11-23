package org.tortodrone.storage;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import org.tortodrone.core.Json;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.character.WorldProfile;
import org.tortodrone.core.town.TownCatalog;

/** Native variant rolls, unbinding and accessory editing. */
final class CustomizeStore {
    private static final List<String> ACCESSORY_SLOTS=List.of("HELM_TOP","HELM_BROW","HELM_FRONT","HELM_BACK","HELM_SIDE","ARMOR_FRONT","ARMOR_BACK","ARMOR_REAR","ARMOR_ARMS","ARMOR_ANKLE","ARMOR_AURA");
    private final ItemCatalog items;
    private final TownCatalog rules;
    CustomizeStore(ItemCatalog items,TownCatalog rules){this.items=items;this.rules=rules;}

    /** Native VariantMarshaller: custom ticket, or a selected random-variant count. */
    Object variant(Connection c,WorldProfile p,int count,long gearId,long ticketId,int keepMask,boolean custom)throws SQLException,IOException{
        var gear=TownLedger.owned(p,gearId);var def=items.require(gear.config());String group=CraftStore.group(def);
        if(!Set.of("SWORD","HANDGUN","BOMB","HELM","ARMOR","SHIELD","TRINKET").contains(group)||gear.variants().size()>3||keepMask<0||(keepMask>>>gear.variants().size())!=0)throw new IOException("e.wrong_item_group");
        var kept=new ArrayList<StarterCatalog.Model>();for(int i=0;i<gear.variants().size();i++)if((keepMask&(1<<i))!=0)kept.add(gear.variants().get(i));
        if(custom){
            var ticket=TownLedger.owned(p,ticketId);var metadata=rules.item(ticket.config());String variant=metadata.get("variant");
            if(variant==null||!Arrays.asList(metadata.getOrDefault("variantGroups","").split(",")).contains(group))throw new IOException("e.wrong_item_group");
            if(gear.variants().stream().anyMatch(v->v.name().equals(variant)))throw new IOException("e.already_have_variant");
            if(gear.variants().size()<3&&kept.size()!=gear.variants().size()||kept.size()>2)throw new IOException("Invalid retained variants");
            TownLedger.consume(c,p,ticketId,1);kept.add(new StarterCatalog.Model(variant,Map.of()));
        }else{
            if(count<1||count>3||kept.size()>=count)throw new IOException("Invalid variant count");
            if(ticketId==0)TownLedger.pay(c,p,new int[]{20000,75000,225000}[count-1],0);
            else{var ticket=TownLedger.owned(p,ticketId);if(!ticket.config().equals("Ticket/Variants/Variants "+count))throw new IOException("Invalid variant ticket");TownLedger.consume(c,p,ticketId,1);}
            var rolled=rules.roll("Shop/Variant "+count,group,kept);kept.clear();kept.addAll(rolled);
            if(kept.size()!=count)throw new IOException("Variant table did not supply the selected outcome");
        }
        try(var st=c.prepareStatement("UPDATE inventory_items SET variants=? WHERE world_id=? AND item_id=?")){st.setString(1,Json.stringify(kept.stream().map(StarterCatalog.Model::name).toList()));st.setInt(2,p.worldId());st.setLong(3,gearId);if(st.executeUpdate()!=1)throw new IOException("Item changed");}
        return gearId;
    }

    Object unbind(Connection c,WorldProfile p,long item,long ticket)throws SQLException,IOException{
        var i=TownLedger.owned(p,item);var def=items.require(i.config());int rarity=def.rarity();
        if(!i.bound())throw new IOException("e.not_bound");if(i.permanent()||i.starter()||def.variant()!=ItemCatalog.Variant.LEVEL_ITEM&&i.slot()==WorldProfile.Slot.OTHER||rarity<1||rarity>5)throw new IOException("e.cannot_unbind");if(i.equippedSlot()!=null)throw new IOException("e.equipped");
        if(ticket!=0){if(!TownLedger.owned(p,ticket).config().equals("Ticket/Unbind/Unbind "+rarity))throw new IOException("e.invalid_ticket");TownLedger.consume(c,p,ticket,1);}else TownLedger.pay(c,p,0,new int[]{0,100,200,600,1800,4000}[rarity]);
        try(var s=c.prepareStatement("UPDATE inventory_items SET bound=0,heat=0,level=0,heat_progress=0 WHERE item_id=? AND world_id=?")){s.setLong(1,item);s.setInt(2,p.worldId());s.executeUpdate();}return item;
    }

    Object accessorize(Connection c,WorldProfile p,int method,long gearId,Object argument,String costume)throws SQLException,IOException{
        var gear=TownLedger.owned(p,gearId);if(gear.slot()!=WorldProfile.Slot.HELM&&gear.slot()!=WorldProfile.Slot.HELM_COSTUME&&gear.slot()!=WorldProfile.Slot.ARMOR&&gear.slot()!=WorldProfile.Slot.ARMOR_COSTUME)throw new IOException("e.cant_use_accessory");
        var accessories=new ArrayList<>(gear.nativeAccessories(costume));long result=0;
        if(method==1){long ticket=(Long)argument;var held=TownLedger.owned(p,ticket);String name=rules.item(held.config()).get("accessory");var accessory=name==null?null:rules.accessory(name);
            if(accessory==null||accessory.remove())throw new IOException("e.cant_use_accessory");
            var blocked=new HashSet<>(rules.blockers(gear.config()));boolean helm=gear.slot()==WorldProfile.Slot.HELM||gear.slot()==WorldProfile.Slot.HELM_COSTUME;
            for(String slot:ACCESSORY_SLOTS)if(helm?slot.startsWith("ARMOR_"):slot.startsWith("HELM_"))blocked.add(slot);
            if(accessory.blocks().stream().anyMatch(blocked::contains))throw new IOException("e.cant_use_accessory");
            for(String existing:accessories){var worn=rules.accessory(existing);if(worn==null)continue;if(worn.location().equals(accessory.location()))throw new IOException("e.existing_accessory");if(worn.blocks().stream().anyMatch(accessory.blocks()::contains))throw new IOException("e.existing_multislot");}
            TownLedger.consume(c,p,ticket,1);accessories.add(name);
        }else{
            String slot;TownCatalog.Accessory tool=null;long toolId=0;
            if(method==2){int ordinal=((org.tortodrone.core.wire.EnumValue)argument).ordinal();if(ordinal<0||ordinal>=ACCESSORY_SLOTS.size())throw new IOException("Invalid accessory slot");slot=ACCESSORY_SLOTS.get(ordinal);}
            else if(method==3){toolId=(Long)argument;String name=rules.item(TownLedger.owned(p,toolId).config()).get("accessory");tool=name==null?null:rules.accessory(name);if(tool==null||!tool.remove())throw new IOException("e.cant_use_accessory");slot=tool.location();}else throw new IOException("Invalid accessory operation");
            String worn=null;for(String existing:accessories){var a=rules.accessory(existing);if(a!=null&&a.location().equals(slot)){worn=existing;break;}}if(worn==null)throw new IOException("e.no_accessory");
            if(tool!=null){TownLedger.consume(c,p,toolId,1);if(!tool.destroy()){String recovered=rules.accessoryTicket(worn);if(recovered==null)throw new IOException("Accessory has no recoverable ticket");result=TownLedger.create(c,p,items,recovered,1,false,0,0,List.of());}}
            accessories.remove(worn);
        }
        try(var s=c.prepareStatement("UPDATE inventory_items SET accessory=NULL,accessories=? WHERE item_id=? AND world_id=?")){s.setString(1,Json.stringify(accessories));s.setLong(2,gearId);s.setInt(3,p.worldId());s.executeUpdate();}return result;
    }

    Object adminAccessory(Connection c,WorldProfile p,int account,long auth,int slot,String name,String costume)throws SQLException,IOException{
        TownStore.requireGameMaster(c,account,auth);
        var gear=p.items().stream().filter(i->i.equippedSlot()!=null&&i.equippedSlot()==slot).findFirst().orElseThrow(()->new IOException("Equip an item in that slot first"));
        editAccessory(c,p,gear,name,costume,rules);
        return null;
    }

    static void editAccessory(Connection c,WorldProfile p,WorldProfile.Item gear,String name,String costume,TownCatalog rules)throws IOException,SQLException {
        boolean helm=gear.slot()==WorldProfile.Slot.HELM||gear.slot()==WorldProfile.Slot.HELM_COSTUME;
        if(!helm&&gear.slot()!=WorldProfile.Slot.ARMOR&&gear.slot()!=WorldProfile.Slot.ARMOR_COSTUME)throw new IOException("e.cant_use_accessory");
        var accessories=new ArrayList<>(gear.nativeAccessories(costume));
        if(name==null)accessories.clear();
        else {
            var accessory=rules.accessory(name);
            if(accessory==null||accessory.remove()||rules.accessoryModel(name)==null)throw new IOException("Unknown or unsupported accessory config");
            var blocked=new HashSet<>(rules.blockers(gear.config()));
            for(String location:ACCESSORY_SLOTS)if(helm?location.startsWith("ARMOR_"):location.startsWith("HELM_"))blocked.add(location);
            if(accessory.blocks().stream().anyMatch(blocked::contains))throw new IOException("e.cant_use_accessory");
            for(String existing:accessories){var worn=rules.accessory(existing);if(worn!=null&&(worn.location().equals(accessory.location())||worn.blocks().stream().anyMatch(accessory.blocks()::contains)))throw new IOException("e.existing_accessory");}
            accessories.add(name);
        }
        try(var st=c.prepareStatement("UPDATE inventory_items SET accessory=NULL,accessories=? WHERE world_id=? AND item_id=?")) {
            st.setString(1,Json.stringify(accessories));st.setInt(2,p.worldId());st.setLong(3,gear.id());if(st.executeUpdate()!=1)throw new IOException("Item changed");
        }
    }
}
