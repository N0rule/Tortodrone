package org.tortodrone.core.style;

import java.util.List;

/** Saved cosmetic selection, independent of the store that commits it. */
public record Saved(List<Integer> colors,List<Integer> eyes,List<Integer> heights) {
    public Saved { colors=List.copyOf(colors);eyes=List.copyOf(eyes);heights=List.copyOf(heights); }
}
