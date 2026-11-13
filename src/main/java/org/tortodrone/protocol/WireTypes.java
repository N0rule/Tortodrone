package org.tortodrone.protocol;

/** Wire class-record type names of the stock client, in one place.
 *
 * Every value below is a fully-qualified type name exactly as the installed client
 * writes and reads it on the wire (see local/client-decompile/DECOMPILE.md §6).
 * A client build that renames a class only needs this registry updated; the
 * SessionDecoder switch labels and SessionCodec emitters reference these
 * constants, so a mismatch fails compilation here instead of silently
 * desyncing an invocation. Local single-use names may stay inline; anything
 * shared across compilation units must live here. */
public final class WireTypes {
    // presents packages the wire prefixes live in
    public static final String NET_PREFIX = "com.threerings.presents.net.";
    public static final String DOBJ_PREFIX = "com.threerings.presents.dobj.";
    // presents streaming machinery
    public static final String ARRAY_LIST = "com.threerings.util.StreamableArrayList";
    public static final String NAME = "com.threerings.util.Name";
    public static final String NAME_ARRAY = "[Lcom.threerings.util.Name;";
    public static final String LISTENER_PREFIX = "com.threerings.presents.data.InvocationMarshaller$";
    public static final String CONFIRM_LISTENER = LISTENER_PREFIX + "ConfirmMarshaller";
    public static final String RESULT_LISTENER = LISTENER_PREFIX + "ResultMarshaller";
    public static final String DSET = "com.threerings.presents.dobj.DSet";
    public static final String ENTRY_UPDATED_EVENT = "com.threerings.presents.dobj.EntryUpdatedEvent";
    public static final String CONFIG_REFERENCE = "com.threerings.config.ConfigReference";
    public static final String VECTOR2F = "com.threerings.math.Vector2f";
    public static final String COLORIZATION = "com.threerings.opengl.renderer.config.ColorizationConfig";
    // tudey scene and actor records
    public static final String ACTOR = "com.threerings.tudey.data.actor.Actor";
    public static final String ACTOR_ARRAY = "[Lcom.threerings.tudey.data.actor.Actor;";
    public static final String SHAPE_CIRCLE = "com.threerings.tudey.shape.config.ShapeConfig$Circle";
    public static final String REGION_DEFAULT = "com.threerings.tudey.config.RegionConfig$Default";
    // projectx enums the client verifies by exact enum class
    public static final String DIFFICULTY = "com.threerings.projectx.dungeon.data.DungeonCodes$Difficulty";
    public static final String FLOOR_TYPE = "com.threerings.projectx.dungeon.data.LevelPartyObject$FloorType";
    public static final String KNIGHT_HEIGHT = "com.threerings.projectx.data.ProjectXCodes$KnightHeight";
    public static final String EYE_TYPE = "com.threerings.projectx.data.ProjectXCodes$EyeType";
    public static final String SPRITE_PERK = "com.threerings.projectx.sprites.data.SpritePerk";
    public static final String SPRITE_ULTIMATE = "com.threerings.projectx.sprites.data.SpriteCodes$Ultimate";
    public static final String ITEM_LOCATION = "com.threerings.projectx.item.data.ItemCodes$Location";
    public static final String DAMAGE_STATUS = "com.threerings.projectx.dungeon.config.DamageConfig$Status";
    public static final String STATUS_POWER = "com.threerings.projectx.dungeon.data.StatusCodes$StatusPower";
    public static final String STATUS_CHANCE = "com.threerings.projectx.dungeon.data.StatusCodes$StatusChance";

    private WireTypes() {}
}
