package org.tortodrone.character;

import java.util.List;

/** Server-owned character state persisted across sessions. */
public record WorldProfile(int worldId, int accountId, int knightId, int crowns, int energy,
        long revision, List<Item> items,KnightProgress progress,org.tortodrone.town.TownState town) {
    public WorldProfile(int worldId,int accountId,int knightId,int crowns,int energy,long revision,List<Item> items,KnightProgress progress){this(worldId,accountId,knightId,crowns,energy,revision,items,progress,org.tortodrone.town.TownState.EMPTY);}
    public WorldProfile(int worldId,int accountId,int knightId,int crowns,int energy,long revision,List<Item> items){this(worldId,accountId,knightId,crowns,energy,revision,items,KnightProgress.RECRUIT);}
    public WorldProfile { items = List.copyOf(items);java.util.Objects.requireNonNull(progress);java.util.Objects.requireNonNull(town); }
    public enum Slot {
        HELM, ARMOR, SWORD, GUN, SHIELD, BOMB, HELM_COSTUME, ARMOR_COSTUME, SHIELD_COSTUME, TRINKET, OTHER;
        public boolean weapon() { return this==SWORD || this==GUN || this==BOMB; }
        public boolean accepts(int index) {
            return switch(this) {
                case HELM -> index==0||index==3;
                case ARMOR -> index==1||index==4;
                case SHIELD -> index==2||index==15;
                case SWORD, GUN, BOMB -> index==6 || index==7;
                case HELM_COSTUME -> index==3;
                case ARMOR_COSTUME -> index==4;
                case SHIELD_COSTUME -> index==15;
                case TRINKET -> index==10 || index==11;
                case OTHER -> false;
            };
        }
        public int initialIndex() { return switch(this) {case HELM->0;case ARMOR->1;case SHIELD->2;case SWORD,BOMB->6;case GUN->7;case HELM_COSTUME->3;case ARMOR_COSTUME->4;case SHIELD_COSTUME->15;case TRINKET->10;case OTHER->-1;}; }
    }
    public record Item(long id, Slot slot, String config, String accessory, int heat, Integer equippedSlot, boolean bound, boolean bindOnEquip, boolean starter, int count, boolean permanent,int level,float heatProgress,List<StarterCatalog.Model> variants,List<String> additionalAccessories,boolean cursed) {
        public Item(long id,Slot slot,String config,String accessory,int heat,Integer equippedSlot,boolean bound,boolean bindOnEquip,boolean starter,int count,boolean permanent,int level,float heatProgress,List<StarterCatalog.Model> variants,List<String> additionalAccessories){this(id,slot,config,accessory,heat,equippedSlot,bound,bindOnEquip,starter,count,permanent,level,heatProgress,variants,additionalAccessories,false);}
        public Item curse(boolean value){return cursed==value?this:new Item(id,slot,config,accessory,heat,equippedSlot,bound,bindOnEquip,starter,count,permanent,level,heatProgress,variants,additionalAccessories,value);}
        public Item(long id,Slot slot,String config,String accessory,int heat,Integer equippedSlot,boolean bound,boolean bindOnEquip,boolean starter,int count,boolean permanent,int level,float heatProgress,List<StarterCatalog.Model> variants){this(id,slot,config,accessory,heat,equippedSlot,bound,bindOnEquip,starter,count,permanent,level,heatProgress,variants,List.of());}
        public List<String> nativeAccessories(String costume){var all=new java.util.ArrayList<String>();if(accessory!=null)all.add(accessory+", "+costume);all.addAll(additionalAccessories);return List.copyOf(all);}
        public Item{variants=List.copyOf(variants);additionalAccessories=List.copyOf(additionalAccessories);}
        public Item(long id,Slot slot,String config,String accessory,int heat,Integer equippedSlot,boolean bound,boolean bindOnEquip,boolean starter,int count,boolean permanent){this(id,slot,config,accessory,heat,equippedSlot,bound,bindOnEquip,starter,count,permanent,0,0,List.of());}
        public Item(long id, Slot slot, String config, String accessory, int heat, Integer equippedSlot, boolean bound, boolean bindOnEquip, boolean starter) {
            this(id,slot,config,accessory,heat,equippedSlot,bound,bindOnEquip,starter,1,false);
        }
        public Item(long id, Slot slot, String config, String accessory, int heat, Integer equippedSlot, boolean bound) {
            this(id,slot,config,accessory,heat,equippedSlot,bound,true,true);
        }
        public Item(long id, Slot slot, String config, String accessory, int heat, Integer equippedSlot) {
            this(id, slot, config, accessory, heat, equippedSlot, true);
        }
    }
    public long[] equipment() {
        long[] result=new long[16];
        for(var item:items)if(item.equippedSlot()!=null)result[item.equippedSlot()]=item.id();
        return result;
    }
}
