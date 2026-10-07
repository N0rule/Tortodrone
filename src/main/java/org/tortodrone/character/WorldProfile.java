package org.tortodrone.character;

import java.util.List;

/** Durable character and item identities for future selection and shared scenes. No client state. */
public record WorldProfile(int worldId, int accountId, int knightId, List<Item> items) {
    public WorldProfile { items = List.copyOf(items); }
    public enum Slot { HELM, ARMOR, SWORD, GUN, SHIELD }
    public record Item(long id, Slot slot, String config, String accessory, int heat) { }
}
