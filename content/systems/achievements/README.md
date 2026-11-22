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
| 50, 51 | capsule use counts | `HEALTH/REMEDY_CAPSULES_USED >= 10`; native capsule graphs credit the counter when the capsule is consumed |
| 60, 62, 63 | casino spins | `CASINO_SPINS >= 100/10000/1000000` (no casino is implemented) |

## Capsule counters

The installed client attaches `ProjectXActionConfig$IncrementStat` to the same
capsule graph that heals or clears status. Its `type` is the client `StatType`,
its `delta` is 1 and its target is `TargetConfig.Activator`, so the using knight's
account receives the increment:

| Capsule config | Counter |
| --- | --- |
| `Pickup/Capsule/Health Capsule`, `.../Super Health Capsule`, `.../Ultra Health Capsule` | `HEALTH_CAPSULES_USED` |
| `Pickup/Capsule/Remedy Capsule` | `REMEDY_CAPSULES_USED` |

`SceneItemExport` records those counters under `stats` beside the executable
`actions` graph in `content/_internal/item-actions.json`; the gameplay graph still
drops them, and `MapYaml` carries `item.<n>.stat`/`stat-delta` on the prepared item
spec. `WorldSession` credits the counters through one `TownStore.stats` transaction
after the scene consumed the capsule, then publishes `achievementChanges`. A refused
receipt is logged and does not change the already-applied scene effect. Counties
without a client predicate (for example `LUCK_POTIONS_USED`) are stored but never
invent an achievement.

## Persistence

Rows live in the existing `metadata` table as `account.achievement.<account>.<ordinal>`
and `account.stat.<account>.<ordinal>`, so the 0.42.0 schema baseline is unchanged
and a receipt commits in the same SQLite transaction as the event that produced it.
Deleting an account removes both prefixes.

## Predicate audit

`Achievement$Type` declares 66 constants but only the inner classes listed above
override `qualifies(PlayerObject)`. Every other constant inherits the base
implementation, whose body is the literal `return false`, so no server-computed
predicate can unlock it; the stock server must grant it directly or through the
`ProjectXActionConfig$UnlockAchievement` action attached to content. A search of
`rsrc`, `scenes` and `unpacked.dat` found no shipped use of that action, and no
other grant evidence exists in the client archive, so these types stay locked
rather than being guessed:

- `REACHED_HAVEN`, `REACHED_TERMINAL_1`, `REACHED_SUBTOWN_1`,
  `REACHED_SUBTOWN_2`, `REACHED_CORE_TERMINAL`
- `DEFEATED_ROYAL_JELLY`, `DEFEATED_VANADUKE`, `DEFEATED_SNARBOLAX`,
  `DEFEATED_ROARMULUS`
- `CRAFTED_RARITY_1`–`CRAFTED_RARITY_5`, `LEVELLED_ITEM_1`
- `MINERALS_DEPOSITED_1`–`MINERALS_DEPOSITED_5`
- `REVIVE_SELF`, `REVIVE_FRIEND`, `REVIVE_FRIEND_MINIMUM`
- `MECHA_KNIGHT_ACTIVATED`
- `REVIVELESS_COMPLETE_TIER_1`–`REVIVELESS_COMPLETE_TIER_3`, `FULL_RUN`,
  `REVIVELESS_FULL_RUN`
- `FIRECRACKER_BOMB_OWNED`, `LOCKDOWN_SNOWBALL`, `UNUSED`

`CASINO_WHAMMIE` and `CASINO_VIP` override only `isHidden()`; their `qualifies`
inherits the same base `false`.

## EARNED_TIER_2 / EARNED_TIER_3 blocker

Their stock predicates are exactly `PlayerObject.dX(1)` and `dX(2)`, which test
progress flags `0x1` and `0x2`. `progressFlags` is a public `PlayerObject` field
that the client only reads (`dX`); it never writes it, so the value arrives from the
stock server as a `PlayerObject` field update, and the trigger and threshold are not
present in the client archive. No shipped content references `SetProgress`, so the
server keeps both bits clear and both achievements locked instead of deriving a
tier rule from rank, slots or mission completion.

## Still blocked

Lifetime counters without a server event: casino spins, boss defeats, minerals
deposited, item levelling, revives and full runs. The objective/progress
achievements remain part of the mission-objective work.
