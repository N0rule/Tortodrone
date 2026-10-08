package org.tortodrone.character;

import java.io.IOException;
import java.util.Locale;

public enum Currency {
    CROWNS, ENERGY;
    public static Currency parse(String value) throws IOException {
        try { return valueOf(value.toUpperCase(Locale.ROOT)); }
        catch(IllegalArgumentException failure){throw new IOException("Currency must be crowns or energy");}
    }
}
