package org.tortodrone.core.character;

/** Roster row as the wire object is built from: knight, preview model and rank. */
public record RosterEntry(Knight knight, StarterCatalog.Model preview, int rank) {
    public RosterEntry(Knight knight, StarterCatalog.Model preview) {
        this(knight, preview, 1);
    }
}
