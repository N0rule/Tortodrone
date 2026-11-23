package org.tortodrone.server;

/**
 * Process-wide feature switches read once from {@code config/server.yml}.
 *
 * <p>The server process is one deployment, so the values are installed at startup instead of being
 * threaded through every session; the defaults stay enabled so isolated tests and tools that never
 * install a policy keep the stock behaviour.
 */
public final class ServerPolicy {
    private static volatile boolean trades = true, chat = true;

    private ServerPolicy() {}

    public static void install(boolean tradesEnabled, boolean chatEnabled) {
        trades = tradesEnabled;
        chat = chatEnabled;
    }

    /** False refuses new trade invitations; an open trade is unaffected. */
    public static boolean tradesEnabled() {
        return trades;
    }

    /** False refuses player-authored text: scene, party, guild, private and emote lines. */
    public static boolean chatEnabled() {
        return chat;
    }
}
