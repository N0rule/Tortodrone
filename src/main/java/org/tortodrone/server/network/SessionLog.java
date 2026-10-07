package org.tortodrone.server.network;

/** Protocol diagnostics plus the operational events needed in normal logs. */
@FunctionalInterface
public interface SessionLog {
    void debug(String message);

    default void info(String message) { debug(message); }
    default void warn(String message) { debug(message); }
}
