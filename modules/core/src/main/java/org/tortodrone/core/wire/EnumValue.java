package org.tortodrone.core.wire;

/** Wire enum value: an interned type name plus its ordinal. */
public record EnumValue(String type, int ordinal) {}
