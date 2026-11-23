package org.tortodrone.storage;

import java.util.List;
import org.tortodrone.core.character.Knight;

/** One consistent roster snapshot: the knights, the next allocation id and the compare-and-swap revision. */
public record RosterState(List<Knight> knights, int next, long revision) { }
