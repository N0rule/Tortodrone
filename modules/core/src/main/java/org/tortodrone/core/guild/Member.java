package org.tortodrone.core.guild;

/** Guild roster row. */
public record Member(int worldId, String name, byte rank, int commandOrder, long joined,
        long lastSession, long lastWithdrawal) { }
