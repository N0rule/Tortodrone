package org.tortodrone.character;

/** Consistent per-knight metadata published to active world observers.
 *
 * <p>Domain-side view of one roster row: it names only character concepts, so the
 * session poller does not depend on the storage package's records.
 */
public record WorldSummary(int accountId,int knightId,int worldId,String name,long revision,long authRevision,boolean gameMaster) { }
