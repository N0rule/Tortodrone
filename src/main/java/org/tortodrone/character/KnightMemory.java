package org.tortodrone.character;

/** An authored KNIGHT-persistent SetMemory operation; false represents native null/erase. */
public record KnightMemory(String name,boolean remember) {
    public KnightMemory {
        if(name==null||!name.matches("[A-Za-z0-9_.:/-]{1,128}"))throw new IllegalArgumentException("Invalid knight memory name");
    }
}
