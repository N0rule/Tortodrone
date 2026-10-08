package org.tortodrone.character;

import java.util.List;

/** Server-owned character state persisted across sessions. */
public record WorldProfile(int worldId, int accountId, int knightId, int crowns, int energy,
        long revision, List<Item> items) {
    public WorldProfile { items = List.copyOf(items); }
    public enum Slot {
        HELM, ARMOR, SWORD, GUN, SHIELD;
        public boolean accepts(int index) {
            return switch(this) {
                case HELM -> index==0;
                case ARMOR -> index==1;
                case SHIELD -> index==2;
                case SWORD, GUN -> index==6 || index==7;
            };
        }
        public int initialIndex() { return switch(this) {case HELM->0;case ARMOR->1;case SHIELD->2;case SWORD->6;case GUN->7;}; }
    }
    public record Item(long id, Slot slot, String config, String accessory, int heat, Integer equippedSlot, boolean bound) {
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
