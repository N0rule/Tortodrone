/** Achievements for the predicates the stock client verifies.

Contract: achievement definitions, progress and account-scoped grants. Achievements
are read models plus account state; they never mutate scene simulation directly.
`AccountAchievements` backs the `PlayerObject.achievements` and `PlayerObject.stats`
DSets with account receipts, and `content/systems/achievements/README.md` lists the
predicates that are honoured.

Evidence still required: lifetime counters with no server event (casino spins, boss
defeats, minerals deposited, item levelling, revives, full runs) and the
`EARNED_TIER_2`/`EARNED_TIER_3` progress flags. Those types stay locked rather than
being guessed. */
package org.tortodrone.gameplay.achievements;
