package org.tortodrone.world;

/** Wire bit layout of the stock DungeonInputFrame._flags, as built by the native
 * client controller (com.threerings.tudey.a.a, specialized by projectx.client.dv and
 * dungeon.client.m; verified against the installed jars — see
 * local/client-decompile/DECOMPILE.md §6 "input frames").
 *
 * Movement direction is NOT in these bits: the frame carries rotation/direction as
 * floats. Bits 0-11 (mask 4095) are the complete defined layout; anything else on
 * the wire is not input this server interprets. */
public final class InputBits {
    /** Movement magnitude present (analogstick/keys above threshold). */
    public static final int MOVING = 1;
    /** A UI window (inventory, dialogs, modal) currently owns the input focus. */
    public static final int UI_WINDOW = 2;
    /** Attack / use weapon; the client latches its transitions into actionChanged. */
    public static final int ACTION = 4;
    /** Selected weapon slot 0..3: (flags & WEAPON_SLOT_MASK) >>> WEAPON_SLOT_SHIFT. */
    public static final int WEAPON_SLOT_MASK = 56, WEAPON_SLOT_SHIFT = 3;
    /** Shield held up (defend). */
    public static final int DEFEND = 64;
    /** Battle-sprite skill: 0 = none, 1..3 = SPRITE_ACTION_1..3. */
    public static final int SPRITE_SKILL_MASK = 384, SPRITE_SKILL_SHIFT = 7;
    /** Auto-targeting toggle (client preference; also forced on by a pref). */
    public static final int AUTO_TARGET = 512;
    public static final int DODGE = 1024;
    public static final int SHIELD_BASH = 2048;
    /** Bits 0-11: the complete defined input layout. */
    public static final int DEFINED_MASK = 4095;

    private InputBits() {}
}
