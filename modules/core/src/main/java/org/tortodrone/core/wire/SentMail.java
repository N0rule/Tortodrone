package org.tortodrone.core.wire;

import java.util.Map;

/** Player mail as the stock Uplink.deliver call streams it: item id repeated per unit. */
public record SentMail(
        String recipient, String subject, String body, Map<Long, Integer> items, int crowns, int energy) {
    public SentMail {
        items = Map.copyOf(items);
    }
}
