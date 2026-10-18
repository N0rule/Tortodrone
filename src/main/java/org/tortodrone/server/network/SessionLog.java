package org.tortodrone.server.network;

/** Protocol diagnostics plus the operational events needed in normal logs. */
@FunctionalInterface
public interface SessionLog {
    void debug(String message);
    default boolean debugEnabled() { return true; }

    default void info(String message) { debug(message); }
    default void warn(String message) { debug(message); }
    default void authenticating(String clientVersion) { }
    default void authenticated(int accountId, String username) { }
    default void selected(int rosterId, int worldId, String knight) { }
    default void scene(int templateId, int instanceId, String name) { }
    default void activity() { }
}
