package org.tortodrone.core.wire;

/** Steam friend hint streamed by the native create-knight call as (steam id, persona name). */
public record SteamFriend(Long id, String name) {}
