# Current checkpoint and next milestones

This is the feature/acceptance handoff. Read `VERSION` and Git for the exact
checkout; update this file as completed milestones change behavior or limits.

## Checkpoint: 0.9.0, schema 4

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
Normal/think/emote/shout modes are supported; tells and broadcast remain unavailable.

The 0.7.1 movement fixes (`32629e2`) retain TCP actor/model baselines and synchronize
inventory activity timing. Fresh creation, stop/start/reverse, facing, inventory
closing and reconnect passed isolated graphical checks. Graphical two-window
animation and equip/unequip have not been independently accepted; networking
checks pass. Repeat relevant checks when changing scene/movement behavior. See
[entry evidence and desktop limitations](town-entry-investigation.md).

## Requested priorities

1. **Item binding**: persistent state, migration and real Arsenal RPC support.
2. **External scene framework**: map catalog, validated scene/collision data,
   multiple scene instances, pre-start archive preparation; migrate Haven.
3. **Single account session**: verified policy, reconnect/replacement and abrupt
   disconnect cleanup without permitting two knights from one account to play.
4. **Local knight deletion**: confirmation/identity checks, safe associated-data
   cleanup, live session/roster consistency.
5. **Scene chat**: original marshaller/messages, scene isolation and bounds,
   reusable routing for later channels.
6. **Game Master**: replace Admin terminology while retaining roles, basic
   permission checks, verified title and special icon. Document unsupported visuals.

Complete and commit these separately in this order; do not push. See the owner's
current instructions before choosing any additional milestone.

## Current limits and later work

Configured town maps/spawns; positions reset on entry. Reconnect to switch characters.
Static collision is sampled; no dynamic physics. Five starter items, no drops,
trade, crafting, loadouts or costume equipment. No combat, quests, shops, NPC
dialogue or portal gameplay. Currencies start at zero, with no economy/regeneration.
News/social feeds are empty. Administration is local; Admin does not enable in-game
commands. Binding does not implement an unbinding service.

After the requested foundations: server-owned position persistence, clean picker
return, one verified portal round trip, general owned items/weapon selection,
then one enemy and one persisted mission with idempotent rewards. Keep changes
small; do not attempt the full introduction at once.

Follow [AGENTS.md](../AGENTS.md), [development](development.md) and
[architecture](server-architecture.md). [Resource research](server-research.md)
provides primary networking/scene references. Default tests need no installed game;
optional installed-reader and graphical checks use disposable state.
