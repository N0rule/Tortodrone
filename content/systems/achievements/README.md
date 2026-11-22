# Achievements

The stock `PlayerObject.achievements` DSet is now backed by the installed client's
own `com.threerings.projectx.data.Achievement$Type` enum and `StatType` enum.

## Verified contract

- 66 `Achievement$Type` constants, ordinals 0–65. The enum ordinal is the wire
  identity and the DSet key; `Achievement.b(type)` is the CRC32 of the enum name
  and rejects collisions.
- An entry is `type("...Achievement")`, `type("...Achievement$Type")`,
  `writeByte(ordinal)`, `writeLong(_unlocked)`.
- `PlayerObject.stats` is a `com.threerings.stats.data.StatSet` DSet of
  `IntStat` entries. Each entry is `type("...IntStat")`,
  `type("...projectx.data.StatType")`, `writeByte(ordinal)`,
  `writeBoolean(_modified)`, `writeByte(_modCount)`, `writeInt(_value)`.
- `StatType` has 20 constants; every stock prototype is an `IntStat`. `StatSet`
  is keyed by `StatType.name()`, so server output is sorted by the enum name,
  not the ordinal.
- `AchievementState` keeps receipts and counters by enum ordinal. Account scope
  matches `PlayerObject`, which exists before a knight is selected.

## Implemented predicates

Only predicates the server can already compute are enabled; every other base
type returns the stock constant `false` and is never granted.

| Ordinal | Type | Stock predicate now honoured |
| --- | --- | --- |
| 0 | `REACHED_RESCUE_CAMP` | `PlayerObject.flags & 32` |
| 4, 6 | `EARNED_TIER_2`, `EARNED_TIER_3` | `progressFlags & 1/2` (server does not yet set these bits) |
| 17–20 | `ITEMS_CRAFTED_1..4` | `ITEMS_CRAFTED >= 5/10/25/50` |
| 21–38 | owned weapon/armor counts | inventory counts per category at `>= 3/5/10` |
| 39 | `RARITY_SET_1` | a five-star item in the five categories the client checks: handguns, bombs, helms, armors and shields (swords are excluded) |
| 46 | `SLOTS_FILLED_1` | equipment slots `2,3,4,6,7,8,9,10,11,15` (shield, three costumes, four weapon slots, two trinkets); helm/armor are not checked |
| 50, 51 | capsule use counts | `HEALTH/REMEDY_CAPSULES_USED >= 10` (no capsule use is recorded yet) |
| 60, 62, 63 | casino spins | `CASINO_SPINS >= 100/10000/1000000` (no casino is implemented) |

## Persistence

Rows live in the existing `metadata` table as `account.achievement.<account>.<ordinal>`
and `account.stat.<account>.<ordinal>`, so the 0.42.0 schema baseline is unchanged
and a receipt commits in the same SQLite transaction as the event that produced it.
Deleting an account removes both prefixes.

## Still blocked

Lifetime counters without a server event: capsule use, casino spins, boss defeats,
minerals deposited, item levelling, revives, full runs and `SLOTS_FILLED_1`
(exact `ItemCodes` arrays not extracted). The objective/progress achievements
remain part of the mission-objective work.
