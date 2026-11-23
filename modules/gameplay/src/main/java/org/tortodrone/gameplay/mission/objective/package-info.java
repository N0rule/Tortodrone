/** Reserved objective progress.
 *
 * Contract: a mission objective records measurable progress for one mission run and is
 * settled by the owning scene instance. It does not own rewards, rank or gates.
 *
 * Evidence still required: the stock client's objective progress and completion
 * packets, the objective definition schema, per-type argument mappings, and the
 * settlement order relative to mission rewards. No packet or reward layout is defined
 * until that evidence exists. */
package org.tortodrone.gameplay.mission.objective;
