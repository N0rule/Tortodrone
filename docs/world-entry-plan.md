# Current checkpoint and next milestones

This is the feature/acceptance handoff. Read `VERSION` and Git for the exact
checkout; update this file as completed milestones change behavior or limits.

## Checkpoint: 0.10.1, schema 6

Normal login → saved knight creation/selection → Haven Town Square → movement,
mouse facing and multiplayer → durable bound starter equipment and per-knight
crowns/energy. Intro missions are skipped without invented completion flags/rewards.

0.7.2 fixes Arsenal re-equipping: its equipItem RPC is different from moveItem.
Both now use the same owned equipment transaction. Starter items are bound by
default; binding survives unequipping and database reopening. Schema 3 migrates
transactionally without re-equipping items or changing identities/balances.
See [current feature research](server-milestones.md).

0.8.0 moves scene geometry/collision out of the JAR into validated external packs.
Town Square and Bazaar load through the same catalog, with distinct runtime
instances and isolated occupants. Original zone/scene RPC travel is supported;
portal interaction remains future work. [Map preparation](maps.md) requires no
manual scene drawing or runtime downloads.

0.8.1 enforces one authenticated connection per account. A successful password
login replaces the previous socket/controller; late cleanup cannot evict its
replacement. Unexpected disconnects release ownership; no reconnect credentials
or ghost-session grace period are introduced.

0.8.2 adds individual knight deletion to the manager, its CLI and server terminal.
Exact-name confirmation and account/world identity guards protect against stale
selection. Owned items/balances/audit rows are removed atomically; survivors and
allocation counters are preserved. The account reconnects with its refreshed roster.

0.9.0 adds scene-wide local chat through the stock SpeakMarshaller and Crowd message
events, with ready membership, authoritative knight names, instance isolation,
256-character limits and a five-message burst/one-per-second sustained rate.
Normal/think/emote/shout modes are supported; private tells remain unavailable.

0.9.1 renames the account role to Game Master, preserving existing stored flags.
Stock PlayerObject/occupant fields enable its title and support icon; prestige
rank is unchanged. `/broadcast` is restricted to current GM roles and reaches
ready players across maps/instances. Live role changes do not reset actor models.
The isolated stock graphical client rendered a peer's title/support icon and removed
them on live revocation. Its own nameplate follows the stock client's visibility rules.

The 0.7.1 movement fixes (`32629e2`) retain TCP actor/model baselines and synchronize
inventory activity timing. Fresh creation, stop/start/reverse, facing, inventory
closing and reconnect passed isolated graphical checks. Graphical two-window
animation and unequip remain unchecked; the single-window item grant/equip
acceptance is recorded below. Repeat relevant checks when changing scene/movement behavior. See
[entry evidence and desktop limitations](town-entry-investigation.md).
Accepted samples do not rule out recurrence of the previously intermittent
fresh-creation animation/stutter symptom; keep its graphical regression checklist.

## Completed requested priorities

1. **Item binding**: persistent state, migration and real Arsenal RPC support.
2. **External scene framework**: map catalog, validated scene/collision data,
   multiple scene instances, pre-start archive preparation; migrate Haven.
3. **Single account session**: verified policy, reconnect/replacement and abrupt
   disconnect cleanup without permitting two knights from one account to play.
4. **Local knight deletion**: confirmation/identity checks, safe associated-data
   cleanup, live session/roster consistency.
5. **Scene chat**: original marshaller/messages, scene isolation and bounds,
   reusable routing for later channels.
6. **Game Master**: role terminology/storage, current permissions, stock title/
   support icon and broadcasts. Item commands follow in 0.10.0 below.

All six are implemented in separate commits on main; nothing is pushed. Check
Git for exact commit IDs. See the owner's instructions before additional work.

## Inventory foundation and item grants (0.10.0)

The read-only stock exporter indexes all item definitions and enables normal helm,
armor, shield, sword, gun and bomb grants with verified model arguments. External
`items/catalog.tsv` is loaded beside the JARs; absent catalogues retain the starter
fallback. The tested installation indexes 9,348 definitions, of which 883 support
equipment grants. Recipes, costumes, stackable and permanent items remain index-only.
The separate file index lists physical game files and archive entries for later
research. Generated data stays local and contains no extracted assets.

Schema 5 preserves existing IDs/binding/loadouts and removes the one-item-per-type
restriction. Each knight retains its five marked starter instances; creator edits
only replace starter cosmetics. `!items` and `!giveitem` use the existing stock scene
speak RPC; console and local CLI share the grant service. Current role/authentication
checks, IDs, item rows, profile revision and audit commit in one transaction. Grants
are unequipped, unbound instances, with 1–20 per command and 256 total per knight.
Online updates use stock item entry events for Arsenal observers; external grants
do not reset the actor model. See [catalogue/commands/file reference](game-data.md).

Acceptance on the unchanged installed client: exhaustive metadata/model comparisons
at three player colors, actual private GM commands, live/offline grants, item-entry
observers, binding and reconnect persistence passed. A separate stock-reader fixture
applies a combined grant/equip update, including both additions and starter displacement.
The isolated graphical Arsenal rendered Calibur icons and distinct instances, received
two new swords live, showed the binding prompt and moved the equipped highlight to
the selected sword. Reconnect retained the bound loadout; inventory closing followed
by movement worked. These samples do not accept every item animation or the earlier
intermittent fresh-creation symptom. Temporary servers/clients were stopped.

## Global knight names (0.10.1)

Creation, picker name validation and renaming enforce case-insensitive uniqueness
across every account. Schema 6 adds a unique index on canonical knight names;
the transaction resolves competing requests and failed saves publish nothing or
consume IDs. Owner-preserving case changes remain allowed, and deleted names can
be claimed again. Account-local character IDs and global world/item IDs are unchanged.
Upgrades check existing names and refuse conflicts without renaming/removing knights
or replacing data. Resolve a conflicting name using the previous release, then retry.
Storage regression checks cover cross-account availability, concurrent claims,
rename collisions, rollback, deletion reuse and migration preservation. The stock
picker fixture checks actual validation and creation rejection across accounts.

## Current limits and later work

Configured town maps/spawns; positions reset on entry. Reconnect to switch characters.
Static collision is sampled; no dynamic physics. Five starter items plus supported equipment grants; no drops,
trade, crafting, loadouts or costume equipment. No combat, quests, shops, NPC
dialogue or portal gameplay. Currencies start at zero, with no economy/regeneration.
News/social feeds are empty. Account/character/currency management remains local;
in-game staff commands are broadcast, item search and item grants. Binding has no unbinding service.

After the requested foundations: server-owned position persistence, clean picker
return, one verified portal round trip, stackable owned items/weapon selection,
then one enemy and one persisted mission with idempotent rewards. Keep changes
small; do not attempt the full introduction at once.

Follow [AGENTS.md](../AGENTS.md), [development](development.md) and
[architecture](server-architecture.md). [Resource research](server-research.md)
provides primary networking/scene references. Default tests need no installed game;
optional installed-reader and graphical checks use disposable state.
