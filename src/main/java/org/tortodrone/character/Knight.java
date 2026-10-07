package org.tortodrone.character;

/** Server-owned character identity and the choices sent by the character creator. */
public record Knight(int id, String name, String helm, String armor, String helmAccessory,
        String armorAccessory, String costume, int color, int height, int eye, long createdAt) { }
