package org.tortodrone.core.wire;

import java.util.Set;

/** Decoded auction search request, independent of the live decoder. */
public record AuctionSearch(
        boolean featured, EnumValue group, Set<Integer> configs, int sort, boolean ascending, boolean all) {}
