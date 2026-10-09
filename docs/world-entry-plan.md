# Current checkpoint and next milestones

This is the feature/acceptance handoff. Read `VERSION` and Git for the exact
checkout; update this file as completed milestones change behavior or limits.

## Checkpoint: 0.15.0, schema 8

Normal login → saved knight creation/selection → Haven Town Square → movement,
mouse facing and multiplayer → durable bound starter equipment and per-knight
crowns/energy. Intro missions are skipped without invented completion flags/rewards.

0.7.2 fixes Arsenal re-equipping: its equipItem RPC is different from moveItem.
Both now use the same owned equipment transaction. Starter items are bound by
default; binding survives unequipping and database reopening.
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
animation remains unchecked; single-window item grant/equip and live unequip
acceptance is recorded below. Repeat relevant checks when changing scene/movement behavior. See
[entry evidence and desktop limitations](town-entry-investigation.md).
Accepted samples do not rule out recurrence of the previously intermittent
fresh-creation animation/stutter symptom; keep its graphical regression checklist.

## Completed requested priorities

1. **Item binding**: persistent state and real Arsenal RPC support.
2. **External scene framework**: map catalog, validated scene/collision data,
   multiple scene instances, pre-start archive preparation; external Haven.
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

The initial 0.10.0 stock exporter indexed all item definitions and enabled normal helm,
armor, shield, sword, gun and bomb grants with verified model arguments. External
`items/catalog.tsv` is loaded beside the JARs; absent catalogues retain the starter
fallback. The tested installation indexes 9,348 definitions, of which 883 support
equipment grants. Recipes, costumes, stackable and permanent items were index-only
in that release; the 0.11.0 expansion is described below.
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
Storage regression checks cover cross-account availability, concurrent claims,
rename collisions, rollback and deletion reuse. The stock
picker fixture checks actual validation and creation rejection across accounts.

## Player-item expansion (0.11.0)

`giveitem` accepts a unique nonzero stock catalogue ID or exact reference; `items`
displays IDs. Missing/ambiguous IDs and ID 0 fail without mutation. Catalogue v2
indexes 9,348 definitions and enables 8,454 verified player items: normal gear,
costumes, trinkets, materials, recipes, tickets, upgrades, boxes and consumables.
Stock `creatable` does not exclude unobtainable player gear. Internal/developer/test/
PvP definitions, expiring items, dungeon-only pickups and unsupported specialized
records remain index-only. Only v2 catalogues are supported.

The current inventory stores item identities/loadouts/audits and stack counts,
permanent binding and cosmetic/trinket slots. Stackable plain items use stock Item
records and merge atomically; equipment remains distinct LevelItems. Costumes replace
normal gear appearance, preserving its underlying equipment. The grant service
retains role/authentication/ownership checks and transactional audit/revision updates.
Grants implement possession and supported equipping; item-use actions remain unavailable.

Acceptance: unchanged stock readers validated every grantable definition's native
item class, count and binding flags, all model arguments at three colors, and
serialized translated-color costume composition. Actual network checks passed ID
grants, costume slots, material stack updates, recipe possession and reconnect.
An isolated graphical Arsenal rendered Steam Knight Mask and Cool Hexoskeleton
icons, accepted permanent and equip-time binding, displayed a live Brimstone stack
increase to 10, and rendered both costumes while moving after closing inventory.
These samples do not accept every item animation or resolve the earlier intermittent
fresh-creation issue. Test clients/servers were stopped.

## Peaceful town characters and live loadout (0.11.1)

Town Square and Bazaar actors have empty hands and carry their equipped shield or
shield costume on the back using the stock `Shield Away=True` model choice.
Weapons remain equipped/persistent and appear in the equipment preview. Town
combat inputs stay ignored; movement, facing and inventory activities are unchanged.

Equip/unequip and displacement publish individual equipment element events and
one current-loadout entry update, after item/binding events and before confirmation.
The open stock LoadoutPanel listens for these event types; whole-array/set
replacement left its slots/model stale. Inventory-only grants and stack changes
leave equipment/loadout observers and town actor models alone. No schema changes.

Acceptance: `make build test` passed all 33 unit and three integration checks.
Unchanged installed readers/builders matched peaceful town and costume composition;
network checks passed fresh entry/reconnect, equip/unequip, source/destination
displacement events, rejection without publication, current-loadout notifications
before confirmation, model retention and ignored combat inputs. The isolated
graphical client reproduced the previous stale open shield slot/preview, then
updated both immediately on unequip and re-equip with the fix. The town character
rendered empty hands and a shield on the back, and walked/stopped/reversed after
closing the panels. These samples do not accept every item animation or resolve
the earlier intermittent fresh-creation symptom. Test clients/servers were stopped.

## Java 25 and current formats only (0.12.0)

Builds, runtime tools and installed-client fixtures now require Java 25. Java tools
replace the shell download/preparation wrappers. Pinned SHA-256 verification and
preservation of edited deployment maps are retained.

Fresh databases are created directly as schema 8, with `game_master` and no unused
starter-version field. All schema upgrades, property-file readers/writers/imports,
v1 catalogue support, old role aliases and obsolete client research/probes were
removed. Unsupported database versions are refused unchanged; use a fresh data
directory for this release and retain old data/keys as backups. Existing real data
and keys were not changed during development.

Acceptance: all 32 unit and three packaged integration checks passed on Java 25,
including unsupported schema 0–7/future refusal with unchanged bytes/state, fresh
starter kits, live current-schema opening and no property imports. The unchanged
installed client passed world/account/GM/item checks and all 173 previews. Java
preparation tools passed fresh pinned downloads, retry/checksum/cache/cleanup
checks, edited-map preservation and a stock Bazaar scene export. Test processes
were stopped. No new graphical animation acceptance is claimed for this cleanup.

## Completed external-map distribution (0.13.0)

0.13.0 retains complete default Town Square and Bazaar scene packs in the checkout
and switches Haven to its authentic regular 2017 export, with a freshly derived
stock collision mask. There are no snow tiles or Winterfest decorations in this
export. Default preparation is offline and preserves edited deployment files.
The initial ZIP packaging and optional maintainer downloader added here were
removed in 0.13.1 below. Database schema remains 8.

Acceptance: `make build test` passed all 32 unit and four packaged integration
checks on Java 25. The unchanged installed reader/network harness passed both maps,
round-trip travel, matching collision, movement, peaceful equipment and observer
updates, accounts, GM roles and item grants. An isolated stock graphical client
loaded the regular grass/flower/fountain scene and rendered walking in both
directions. Both pinned maintainer downloads matched the retained exports. The release
check passed extraction/startup with no game or download cache, maps outside both
JARs, offline preparation, edited-pack preservation and corrupt-source refusal.
All test processes were stopped; real data, keys,
preferences and edited maps were preserved.

## Map-tool cleanup (0.13.1)

Removed the map source properties file, optional map downloader and premature
release ZIP tooling. Map preparation still verifies and copies the complete local
default packs, preserving existing deployment files. Provenance remains documented
in dependency notices. Tests check offline map preparation directly; they no longer
build release archives. Only missing SQLite/JLine library caches can cause Make
to download build inputs. Regular Town Square and schema 8 are unchanged.

Acceptance: all 32 unit and four integration checks passed on Java 25. Offline
map preparation passed with no URL metadata or download cache, preserved edited
packs and refused corrupt sources. Both JARs were rebuilt; test processes stopped.
The owner's running server, data, keys and preferences were left untouched.

## Character select renders the current outfit (0.15.0)

The stock picker renders each `AvailableKnight` model verbatim, so the preview is
entirely server-composed. Roster publications previously composed it from the saved
creation choices: the picker showed the outfit the knight was created with and ignored
everything equipped afterwards. `CharacterStore.available` now composes each published
preview from the knight's currently equipped items (helm/armor, costumes, trinket,
shield, the lowest occupied weapon slot and item accessories) under the roster
publication lock, using the same verified composition as the world actor. Creation gear
still seeds the starter kit and creator edits still replace the marked cosmetics.

Schema remains 8. A profile read for publication uses no authentication revision; an
unreadable profile falls back to the creation outfit instead of failing the roster.

Acceptance: `make build test` passed all unit and integration checks, including the new
picker composition regression. The unchanged installed client, after equipment changes
and a reconnect, received a picker model equal to the stock held-model builder applied
to the knight's current equipment; every earlier roster/equipment/reconnect check still
passed. An isolated graphical stock client confirmed current red helm/Wolver Coat
and Calibur sword on hover, with an unequipped armor slot rendering without the
saved blue creation armor. Test processes were stopped.

## Startup defaults and JSON definitions (0.15.0)

Default resources are organized as `defaults/maps/` and `defaults/items/` and stay
outside the JARs. Preparation discovers map folders and copies future default
directories without hardcoded names. All 9,348 item definitions are retained in
version 1 JSON, preserving supported models/colors, signed IDs, counts, binding,
costumes/trinkets, grants and equipment behavior. There are no new dependencies.

Startup attempts a bounded root-defaults download from the hardcoded placeholder
repository when required files are missing. The complete archive is validated
before installation, existing files are preserved, and failures warn and continue.
Local custom resources take precedence. Without usable maps, login and local
administration continue and world entry fails clearly; absent/unreadable items use
the existing starter fallback. Fresh RSA generation logs only the copyable public
key, once. Schema 8 data and existing keys are preserved without migrations.

At review, `step` and `main` both pointed to `d35b844`; `step` had no unique commits.
Useful unfinished
picker composition changes were already in the working tree and were retained:
fresh login/subscription previews current equipment, including weapons, rather than
the saved creation outfit. The read-only profile uses a consistent SQL snapshot.

Acceptance: all 34 unit and four integration checks passed on Java 25. Local HTTP
checks covered successful recovery, warm/offline startup, stalled-body deadlines,
future directories, path validation and preservation of existing files. Packaged
checks verified missing-default startup and copyable public-key logging without
logging private material. All 9,348 shipped JSON entries exactly matched a fresh
stock export; the exhaustive stock item/wire checks and 173 starter previews passed.
The graphical picker checks above passed. All test clients and servers were stopped.

## Current limits and later work

Configured town maps/spawns; positions reset on entry. Reconnect to switch characters.
Static collision is sampled; no dynamic physics. Five starter items plus supported player-item grants, stacks and costume/trinket
equipment; no drops, trade, crafting, item-use actions or loadout switching. No combat, quests, shops, NPC
dialogue or portal gameplay. Currencies start at zero, with no economy/regeneration.
News/social feeds are empty. Account/character/currency management remains local;
in-game staff commands are broadcast, item search and item grants. Binding has no unbinding service.

After the requested foundations: server-owned position persistence, clean picker
return, one verified portal round trip, weapon selection,
then one enemy and one persisted mission with idempotent rewards. Keep changes
small; do not attempt the full introduction at once.

Follow [AGENTS.md](../AGENTS.md), [development](development.md) and
[architecture](server-architecture.md). [Resource research](server-research.md)
provides primary networking/scene references. Default tests need no installed game;
optional installed-reader and graphical checks use disposable state.
