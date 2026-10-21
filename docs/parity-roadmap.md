# Parity roadmap: what to do now, what to defer

This is the actionable companion to [Official parity](parity.md). It ranks the
remaining in-scope work (missions and Arcade excluded) by player value, risk and
whether the evidence needed to do it correctly actually exists.

Sizes are rough: **S** = a focused change, **M** = a subsystem increment,
**L** = a multi-increment subsystem, **XL** = a project.

## Ground rules

1. **Evidence before packets.** Verify class/method/argument layouts against the
   installed client. A decoded request is not proof that a feature works.
2. **Do not invent content or probabilities.** Missing official RNG, timings and
   mission definitions stay missing until evidence exists.
3. **Prefer the player loop.** A working shop, forge, trade, auction or fight is
   worth more than another catalog row.
4. **Verification is work.** A feature without an isolated stock-client check is
   "implemented, unverified", not "done".

## Do now (next few increments)

These are the highest-value, best-evidenced changes. Ordered by suggested
sequence.

> Documentation truth-up (the `/heat` 0–100 scale and the removed GM chat
> extensions in `commands.md`) was completed alongside this page.

| # | Work | Why now | Size | Risk | Start here |
|---|---|---|---|---|---|
| 1 | **Core-loop acceptance sweep** | Only 5/250 box pools are client-verified and 508 item configs are unusable; hidden rendering/logic bugs are the biggest parity risk | M | Low | Existing isolated scenarios in `docs/development.md`; run one per core loop (shop, forge, auction, trade, sprite) and record results |
| 2 | **Monster loot drops + loot tables** | Combat currently has no reward loop; general drops are absent from `SceneEntities`/`SceneSimulation` | M | Medium | `world/SceneSimulation`, `world/SceneEntities`, `loot/`, `storage/` |
| 3 | **Enemy AI basics: line-of-sight, leashing, obstacle avoidance** | Acquisition/chase exists but no LOS or pathfinding; encounters feel wrong in every non-trivial room | M–L | Medium | `world/SceneEntities` (`acquire-distance`, `acquisition-interval`, `move`) |
| 4 | **Combat fidelity: item-level damage bonuses and status resistance** | The docs list these as incomplete; they affect every fight and every gear choice | M | Medium | `world/SceneDamage`, `world/SceneHealth`, `town/GearRules`, fire catalogs |
| 5 | **Party persistence + join a friend's scene** | Done: friends travel by relationship or native secret, and player parties survive a brief reconnect | S | Low | `world/PartyRegistry`, `storage/FriendStore`, `WorldSession` presence |
| 6 | **Vendor/catalog reachability audit** | 99 stations, 59 `OpenShop`, but only 20 curated catalogs; some vendors are known to be thin | S–M | Low | `content/town/stations.yml`, `content/town/shops.yml`, `town/TownCatalog` |
| 7 | **Forge bonus/prize policy** | Forge chances are data-driven but official bonus/prize rules are not; decide "verified table" vs "explicit uniform policy" | S–M | Low | `town/GearRules`, `content/town/progression.yml` |
| 8 | **Fill service 2/7/8 only where observed** | Generic failures are harmless until the client exercises them; instrument first, then implement | S–M | Low | `server/network/ClientSession`, `protocol/SessionCodec` |

### Notes on the "now" list

- **The first item is not a feature.** It is the cheapest way to find out which
  of the other items are actually player-visible. Do it before large rewrites.
- **#2 and #3 are the real combat-value pair.** Drops without AI feel hollow;
  AI without drops still feels like a game.
- **#5 unlocks the social loop without touching guilds/PvP.** It reuses
  existing friend presence and party objects.
- **General player-to-player mail is implemented.** The stock Uplink compose/fee/
  attachment flow, claim/delete paths, GM bounce and the schema-16 upgrade are in
  place; only an isolated stock-client acceptance check remains.
- **#6 is deliberately cheap.** It is mostly auditing content references and
  adding captured catalogs, not new server logic.

## Do later (valuable, but larger or dependency-bound)

| Work | Why later | Size | Notes |
|---|---|---|---|
| **Guilds** (records, membership, ranks, chat, halls) | Large and standalone; no player-visible prerequisite is blocking the core loop | L | Protocol fields already reserved; start with persistence + chat, defer halls |
| **PvP** (arenas, teams, scoring, matchmaking) | XL effort; needs instance/matchmaking design and verified client flows | XL | Do not fake with scripted NPCs |
| **Events / seasonal / daily rewards** | Event vendor definitions exist, but activation windows, calendars and reward rules do not | M | Add a scheduler only after deciding the authoritative calendar source |
| **Battle sprite depth** | The core is already solid; remaining work is table completeness and verification | M | Verify skills/ultimates against client data before adding more |
| **Persistent scene pickups** | Currently refused; matters mainly for dungeon-like content | S–M | Interacts with scene ownership and expiry |
| **World-position persistence** | Nice-to-have; the official client already handles town entry well | S | Decide the exact restore semantics first |
| **Achievements** | Mission-adjacent; needs definitions and persistence | M | Pair with a future mission system, not before |
| **Dungeon generation / bosses** | Mission-scoped and evidence-heavy | XL | Out of the current scope; do not half-build |
| **Full admin backend / remote admin** | Local GUI/CLI already covers operations; remote admin is a security decision | L | Keep local-only unless the owner requests otherwise |

## Do not do (or only with an explicit owner decision)

- **Missions, quests, Clockworks progression, mission rewards.** Out of scope by
  request, and the largest evidence gap.
- **Arcade mode and clearances.** Out of scope by request; the Arcade district is
  only a travel destination here.
- **Real-money billing / pay-to-win.** Deliberately disabled; enabling it is a
  product and policy decision, not a parity task.
- **Steam/guest authentication.** Needs platform integration and secrets that
  this project intentionally avoids.
- **Invented RNG, timings, loot tables or NPC services.** If the official rule is
  unknown, keep the explicit limitation instead of a plausible guess.
- **Chasing exact box distributions without evidence.** 243 boxes are already
  "supported with estimated distribution"; more precision needs verification,
  not more constants.

## Suggested sequencing

1. **Milestone A — truth and verification.** Documentation fixes, core-loop
   acceptance sweep, vendor reachability audit. Cheap, reduces risk.
2. **Milestone B — combat loop.** Loot drops, AI basics, damage/status fidelity.
   This is the biggest player-visible gain in scope.
3. **Milestone C — social/economy depth.** Party matchmaking/ready room and
   the party-finder matcher. Friend travel, secret join and party reconnect
   are already in place.
4. **Milestone D — optional large systems.** Guilds, events, PvP, sprite depth,
   persistence. Only after A–C are stable and verified.

## How to measure progress

- Update the scorecard in [parity.md](parity.md) when a row materially changes;
  do not move a row for a partial increment.
- Add hard evidence where it exists: item/box rows to `docs/item-support.csv`
  and `docs/box-support.csv`, focused tests for new invariants, and one isolated
  stock-client check for anything client-visible.
- Treat "implemented but unverified" as its own state in reports. The project's
  existing convention (source/tests over docs) should stay.

## Related documents

- [Official parity](parity.md) — the scorecard and per-area assessment.
- [Architecture](architecture.md) — invariants and implemented design.
- [Content](content.md) — maps, items, shops, boxes and their limits.
- [Operations](operations.md) — deployment, accounts and administration.
- [Research](research.md) — evidence standards and the economy baseline.
- [Commands](../commands.md) — player and GM command surface.
