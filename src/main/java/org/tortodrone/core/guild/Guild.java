package org.tortodrone.core.guild;

/** Guild row published by GuildObject. */
public record Guild(long id, String name, long formed, String motd, boolean hallOpen,
        int energy, int upkeepCost, long upkeepTime, boolean inDefault, int memberLimit,
        String theme, String environment, long hallOpenAt, long renamed) { }
