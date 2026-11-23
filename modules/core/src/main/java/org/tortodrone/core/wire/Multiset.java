package org.tortodrone.core.wire;

import java.util.Map;

/** Wire-side counted set shared by decoder, services and codec. */
public record Multiset(Map<Object, Integer> counts) {
    public Multiset {
        counts = Map.copyOf(counts);
    }
}
