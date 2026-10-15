# Server architecture and invariants

Tortodrone is a **Java 25**, clean-room server for an unmodified Spiral Knights client. This describes the **implemented** design; selected content and its runtime limits are documented in [Content](content.md). Inspect source/tests and `VERSION` for the current baseline.

## Main components

| Responsibility | Current source |
| --- | --- |
| Listener, lifecycle, local UI, metrics/logging | `server/{Server,ServerControl,ServerWindow,ServerConsole,ServerLog}` |
| Login/session orchestration | `server/network/{SecureHandshake,ClientSession,WorldSession,SessionOutput,WorldStatePoller}` |
| Narya/Presents framing, class tables and serialization | `protocol/{HandshakeCodec,SessionDecoder,SessionCodec}` |
| Authentication and account/knight services | `account/`, `character/` |
| Transactions, persisted identity, items and trades | `storage/{Database,WorldProfiles,TradeTransfer}` |
| Scene templates, instances, movement and tick | `world/{SceneCatalog,SceneRegistry,SceneSimulation,TownMovement,CollisionMap,SceneContent,SceneEntities,SceneCollision}` |
| Player combat / effects | `world/{KnightCombat,SceneWeapons,WeaponCatalog,EquipmentCatalog,FireCatalog}` |
| Versioned content resolution and account-manager GUI | `resources/{ContentManager,ContentRegistry,ContentMigration}`, `admin/` |

Both executable JARs are independent of a running stock game installation. Source imports/compatibility fixtures can read installed client classes read-only in isolated test environments.

## Critical identities and security

| ID | Scope |
| --- | --- |
| Account | Persistent login and permissions owner |
| Account-local roster ID | Knight slot/roster, not globally unique |
| Global world/knight ID | Persistent character identity and actor ownership |
| Stock item definition ID/config | Catalog identity, different from saved item-instance ID |
| Scene template ID | Authored layout and metadata |
| Scene runtime instance/distributed-object OID | Live place/object identity, **not** persistent scene ID |
| Connection/session ID | Transient network identity |

One authenticated connection owns the current account lease; a successful login can take over and invalidate an earlier session, but a failed login must not. Recheck auth revisions and GM role at the operation/transaction boundary. Mutating another knight's inventory or reading its private data must never follow from a caller-provided ID alone.

The database currently uses **SQLite schema 9**, WAL, foreign keys, a bounded busy timeout and transactional updates. Reject unsupported schemas without silently migrating or deleting state. Use identity checks and `BEGIN IMMEDIATE` for multi-owner item/currency transfers; commit durable data **before** publishing account/world events. Close handlers/sockets before tearing down database, logger and the deployment lock. Persistent keys must survive upgrades.

## Wire correctness

The stock client expects exact Narya/Presents serialization, not Java serialization or approximate messages:

- Incoming and outgoing mappings for classes, enums and interned strings are **per connection and directional**. Preserve definitions, indices, event order, listener/request IDs and bounded frame parsing.
- Incoming frames are bounded to 64 KiB; outbound frames to 1 MiB. Apply depth/count/whitelist limits to decoded input; never load peer-specified classes or accept unknown enum ordinals as trusted data.
- Invocation **service handles** can be assigned during bootstrap and vary by session; stock **method IDs, type layouts and callback contracts** are not interchangeable. Validate against the installed client rather than historical tables.
- Preserve the installed reader's expected authentication identity shape (including `ProjectXAuthResponseData.ident = null` for normal login).
- A client-visible UI control or successfully decoded request **does not prove the corresponding gameplay feature works**. Unsupported operations should deliver native failure callbacks instead of disconnecting when the request is validly parsed.

Proof requires stock-class/reader checks and often a real isolated stock graphical/two-client scenario. See [Development](development.md) and [Research](research.md).

## World and simulation

`SceneCatalog` loads immutable external map/collision/portal templates and optional authored content/collision definitions (currently prepared for Training Hall). `SceneRegistry` gives each runtime place a distinct OID and mutable `SceneSimulation`. Towns are shared; current non-town interiors are private by world ID. Client scene revision is a cache/version argument, **not** authorization or instance selection.

Each occupied instance ticks once per **50 ms**; movement, combat and portal checks run under scene ownership, then capture a shared immutable snapshot. Each recipient receives a delta relative to its **last successfully queued** snapshot. A dedicated bounded output path serializes per-connection class mapping and frames, keeping network writes outside scene/store locks.

Weapon projectiles own their simulation clocks, bounded timers and shutdown actions. Fuse expiry still executes authored detonation actions (including Tortofist missiles and Alchemer children); the native fizzle flag selects the destruction visual. Child actors retain the attacker's faction but have no client prediction owner. Burst sword charges emit the configured number of trail bombs at 100 ms intervals, based on local official captures, retaining the weapon's color, player damage and explosion size. Pulsars replace their first stage with the authored explosive second stage. Exported detonation radii are restored only from surviving stock choice bindings.

Secondary Alchemer bounce directions use a capture-derived relative fan. The stripped Pulsar maturation timer currently uses the stock class's one-second default, not verified official timing. Exact charged Alchemer fans, shard-bomb timers and arbitrary orbital/contact/signal graphs remain incomplete. These mechanics do not establish parity for every weapon.

Clients send facing/activity/input intent, **not authoritative coordinates**. Movement is validated against server collision/portals. Native portal travel uses verified destination scene+entrance data and resets old-scene subscriptions/baselines; delayed input and events from the former scene must not leak into the destination. Server-authored stock actor configs and visual state must agree with client expectations.

`WorldStatePoller` batches identity/role/revision inspection outside session/simulation locks and publishes changed saved inventory/balances; do not use log scraping as a state source. Chat/tells and inspection are scoped by authenticated identities and current ready/owned scenes. Trading requires synchronous revision/owner checks and atomic bilateral transfer.

Knights retain mission rank/subrank and prestige in namespaced rows of schema 9's existing
integer metadata table. Missing values mean Recruit 1-1 and zero prestige; invalid saved
values fail closed. Local console commands `rank <knight> <rank> <subrank>` and
`prestige <knight> <total>` (or admin CLI `--set-rank` / `--set-prestige`) update these
transactionally with the world revision and audit. Selection, inspection, picker rank and
occupant rank/prestige badges use saved values; connected selected knights receive updates.
Rank grants the verified gear eligibility bits, without inventing mission completions,
prestige awards or Arcade clearances. Ordinary helms, armor and shields can occupy native
costume slots 3, 4 and 15; those slots change appearance without adding combat stats.

Party identity is announced only when `partyOid` changes, before interior travel completes.
Location and profile refreshes must not repeat that attribute: the stock PartyDirector
resubscribes even for an unchanged value, replacing the object while the HUD still listens
to the old instance. HP and status updates then reach a different object from the HUD.

Fire catalog v4 resolves full damage-scale references, including arguments and weapon
rarity, at depth zero/MEDIUM during stock export. Same-type damage is accumulated before
defense mitigation. Shield defense executes the named push-back graph with its rarity,
nearby-monster and 1500-ms cooldown guards; missing bindings/guards are restored from the
local archive and its surviving power choices. Bash uses the archive's capture-calibrated
forward sweep (0.60 ahead, radius 0.67, armed at 550/attackSpeed ms), once per target per
bash, and applies native impulses. Status hits consume shield health with the native
per-status one-second cooloff. The bash region and missing shield binding are labelled
reconstructions, not recovered retail server code.

These paths have focused Java and stock-reader checks, without new graphical validation.
Arbitrary stripped conditions, weapon self-status actions, delayed developer shield actions,
Sealed Sword table spawns, gear/variant status resistance and full item-level damage bonuses
remain incomplete. The exporter retains unsupported-action diagnostics; v1-v3 fire packs
remain readable but require re-export for resolved damage values and shield action graphs.

## Implemented versus missing gameplay

The server currently supports Haven towns, solo interiors, persistent inventories, a portion of authored equipment/combat controls, scene chat, emotes, trading, inspection/privacy and local account/GM management. **It is not a complete vanilla server.** Training Hall now has a bounded implementation of prepared actors, interactive props, traps, pickups, damage, status and conversation. Do not infer general NPC service logic, arbitrary monster behavior/drops, crafting, missions, arbitrary dungeons, full party matchmaking or guilds from those mechanics or client data.

The versioned content registry keeps supported scene/catalog definitions outside the executable and persistent **state** (accounts, saved progression, ownership) in storage, while Java remains authoritative for mechanics, transactions, security and wire protocol. Content selection, validation, dependency declarations and complete-resource overrides are implemented; arbitrary missions/dungeons and hot reload are not.

## Source-of-truth and dependency hygiene

`build.gradle.kts` configures Java 25 toolchains and packaging; verified plugin/dependency digests are pinned under `gradle/verification-metadata.xml`. Required redistribution notices live in `src/main/resources/THIRD-PARTY.md`, JAR resources and `licenses/`. Keep private captures, stock game binaries/assets, `data/`, `.build/` and temporary test deployments out of Git.

Use focused regression tests before broad stock-client checks. Avoid expanding this document with one-off milestone reports; Git history retains past investigations.
