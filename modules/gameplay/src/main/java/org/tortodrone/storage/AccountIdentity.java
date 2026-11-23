package org.tortodrone.storage;

/** One account identity row, including the stored password verifier and its revision.
 *
 *  <p>{@link #toString()} is redacted: the record is passed to the authentication path and
 *  may be logged, so the salt and verifier never reach a log line. */
public record AccountIdentity(int id, String username, boolean gameMaster, long authRevision, byte[] salt, byte[] verifier) {
    @Override public String toString() { return "AccountIdentity[redacted]"; }
}
