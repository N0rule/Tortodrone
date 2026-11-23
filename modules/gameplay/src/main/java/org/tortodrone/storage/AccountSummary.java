package org.tortodrone.storage;

/** One account listing row for local administration: identity, role and knight count. */
public record AccountSummary(int id, String username, boolean gameMaster, int knights, long createdAt) {}
