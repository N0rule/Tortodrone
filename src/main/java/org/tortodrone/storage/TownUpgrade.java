package org.tortodrone.storage;

import java.io.IOException;

/** Retired predecessor tool; schema 8 has no supported economy migration. */
public final class TownUpgrade {
    private TownUpgrade() { }
    public static void main(String[] args)throws IOException {
        throw new IOException("Schema 8 is unsupported; saved data was not changed. EconomyUpgrade exports only known schemas 9–14 to a separate candidate.");
    }
}
