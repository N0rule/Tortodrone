package org.tortodrone.server.network;

import java.util.LinkedHashMap;
import java.util.Map;

/** Read-only view of the bounded per-session output counters for operator status. */
public final class NetworkMetrics {
    private NetworkMetrics() { }

    /** Process-wide counters since startup, including running maxima for the peak gauges. */
    public static Map<String,Long> snapshot() {
        var value=SessionOutput.Metrics.snapshot();
        var result=new LinkedHashMap<String,Long>();
        result.put("frames",value.frames());
        result.put("bytes",value.bytes());
        result.put("largestFrame",value.largestFrame());
        result.put("droppedOptional",value.droppedOptional());
        result.put("reliableOverflows",value.reliableOverflows());
        result.put("budgetDisconnects",value.budgetDisconnects());
        result.put("peakPendingFrames",value.peakPendingFrames());
        result.put("peakPendingBytes",value.peakPendingBytes());
        return Map.copyOf(result);
    }
}
