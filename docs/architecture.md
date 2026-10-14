# Server architecture and invariants

Tortodrone is a **Java 25**, clean-room server for an unmodified Spiral Knights client. This describes the **implemented** design; possible future content packs are identified separately in [Content](content.md). Inspect source/tests and `VERSION` for the current baseline.

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
| External shipped content and account-manager GUI | `resources/DefaultResources`, `admin/` |

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

The database currently uses **SQLite schema 8**, WAL, foreign keys, a bounded busy timeout and transactional updates. Reject unsupported schemas without silently migrating or deleting state. Use identity checks and `BEGIN IMMEDIATE` for multi-owner item/currency transfers; commit durable data **before** publishing account/world events. Close handlers/sockets before tearing down database, logger and the deployment lock. Persistent keys must survive upgrades.

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

Clients send facing/activity/input intent, **not authoritative coordinates**. Movement is validated against server collision/portals. Native portal travel uses verified destination scene+entrance data and resets old-scene subscriptions/baselines; delayed input and events from the former scene must not leak into the destination. Server-authored stock actor configs and visual state must agree with client expectations.

`WorldStatePoller` batches identity/role/revision inspection outside session/simulation locks and publishes changed saved inventory/balances; do not use log scraping as a state source. Chat/tells and inspection are scoped by authenticated identities and current ready/owned scenes. Trading requires synchronous revision/owner checks and atomic bilateral transfer.

## Implemented versus missing gameplay

The server currently supports Haven towns, solo interiors, persistent inventories, a portion of authored equipment/combat controls, scene chat, emotes, trading, inspection/privacy and local account/GM management. **It is not a complete vanilla server.** Training Hall now has a bounded implementation of prepared actors, interactive props, traps, pickups, damage, status and conversation. Do not infer general NPC service logic, arbitrary monster behavior/drops, crafting, missions, arbitrary dungeons, full party matchmaking or guilds from those mechanics or client data.

A future content model should keep **definitions** (scene/NPC/item/mission/dungeon parameters) outside the executable and **state** (accounts, saved progression, ownership) in persistent storage, while Java remains authoritative for mechanics, transactions, security and wire protocol. Incremental migration is safer than wholesale refactoring during feature work.

## Source-of-truth and dependency hygiene

`build.gradle.kts` configures Java 25 toolchains and packaging; verified plugin/dependency digests are pinned under `gradle/verification-metadata.xml`. Required redistribution notices live in `src/main/resources/THIRD-PARTY.md`, JAR resources and `licenses/`. Keep private captures, stock game binaries/assets, `data/`, `.build/` and temporary test deployments out of Git.

Use focused regression tests before broad stock-client checks. Avoid expanding this document with one-off milestone reports; Git history retains past investigations.

