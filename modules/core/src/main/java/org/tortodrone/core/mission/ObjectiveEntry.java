package org.tortodrone.core.mission;

/**
 * One entry of the native {@code PlayerObject.objectives} set.
 *
 * <p>The stock HUD renders every entry whose {@code ObjectiveConfig.getText()} key is non-empty,
 * so knight-persistent and mission-party objectives share the one set. The entry index is the
 * native set key; it only has to be unique within the set. Persistence is the native
 * {@code Persistence} ordinal ({@code KNIGHT=3}, {@code PARTY=5}), verified against the installed
 * client's enum order.
 */
public record ObjectiveEntry(int index, String name, String text, int persistence) {
    public static final int KNIGHT = 3;
    public static final int PARTY = 5;

    public ObjectiveEntry {
        if (index < 0) throw new IllegalArgumentException("Invalid objective index");
        if (name == null || !name.matches("[A-Za-z0-9_.:-]{1,128}"))
            throw new IllegalArgumentException("Invalid objective name");
        if (text == null || !text.matches("[a-z]\\.[A-Za-z0-9_.:-]{1,200}"))
            throw new IllegalArgumentException("Invalid objective message key");
        if (persistence != KNIGHT && persistence != PARTY)
            throw new IllegalArgumentException("Unsupported objective persistence");
    }
}
