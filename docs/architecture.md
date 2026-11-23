# Server architecture and invariants

Tortodrone is a **Java 25**, clean-room server for an unmodified Spiral Knights client. This describes the **implemented** design; configured content and its runtime limits are documented in [Content](content.md). Inspect source/tests and `VERSION` for the current baseline.

## Main components

| Responsibility | Current source |
| --- | --- |
| Listener, lifecycle, terminal console, metrics/logging | `server/{Server,ServerControl,ServerConsole,ServerOptions,ServerLog,ConsoleInput,ConsoleHelp}` and `server/network/NetworkMetrics` |
| Login/session orchestration | `server/network/{SecureHandshake,ClientSession,CharacterHandler,WorldSession,SessionRuntime,SessionDirectory,SessionInvocationRouter,SessionResponder,SessionOutput,SessionPresence,SessionPublications,SessionRefresh,SessionProfilePublisher,SessionLog,WorldStatePoller}` and the per-feature handlers |
| Client framing, class tables and serialization | `protocol/{HandshakeCodec,HandshakeWire,SessionDecoder,WireContext,WireWriter,SceneActorWire,ItemWire,GateWire,SessionCodec,SceneDeltaTemplate}` and per-feature codecs (`BootstrapCodec`, `SocialCodec`, `MissionCodec`, `KnightCodec`, `TradeCodec`, `TownCodec`, `PartyCodec`, `SceneCodec`, `GuildCodec`) |
| Authentication and account/knight services | `account/`, `character/` |
| Transactions, persisted identity, items, town services and trades | `storage/` (`Database`, `SchemaMigrations`, the `Account`/`Roster`/`World`/`Administration`/`Preference` repositories, `WorldProfiles`, `TradeTransfer` and the town domain stores) |
| Scene templates, instances, movement and tick | `world/{SceneRegistry,SceneSimulation,TownMovement,SceneEntities}`, `content/resources/{SceneCatalog,SceneCatalogLoader}` and `core/world/{CollisionMap,SceneContent,SceneContentLoader,SceneCollision,SceneCollisionCodec,ScenePortals,ScenePortalCodec,SceneActions,SceneActionRunner}` |
| Player combat / effects | `world/{SceneWeapons,SceneShots,Pose}` and `core/world/{KnightCombat,WeaponCatalog,EquipmentCatalog,FireCatalog}` |
| Atomic unified YAML resolution | `resources/{ContentManager,ContentRegistry}` |
| Browser administration | `web/{AdminHandler,AdminService,WebIdentityStore}` |

The executable JAR and packaged WebUI are independent of a running stock game installation. Source imports/compatibility fixtures can read installed client classes read-only in isolated test environments. The console and admin `status` view reports indexed map/item counts and live scene instances without opening unvisited map templates.

## Module graph

Seven Gradle projects are shaded into the same single executable JAR. The root
project is the packaging/composition `:app`; `implementation` dependencies stay
private, so javac rejects an undeclared edge as well as the explicit
`checkModuleGraph` verification.

```text
:app     -> :core :content :protocol :world :gameplay :server
:server  -> :gameplay :protocol :world :content :core
:gameplay-> :world :content :core
:world   -> :content :core
:protocol-> :core
:content -> :core
:core    -> none
```

Packages are assigned by extracted type, so `gameplay` contains `account`,
`character`, `storage` and `gameplay`; `server` contains `server` and `web` (news
serving is `server.NewsServer` over `core.NewsCatalog`); and shared content/view
models live in `core`, including `core.loot`, `core.town` and
`core.world.SceneClock`, which is used by both content actions and scene
simulation.

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

The database uses the **0.42.0 baseline**: `PRAGMA application_id` carries the fixed project marker and `user_version` counts from schema 1. A fresh file is created and marked before the first table, and the baseline is recorded in `schema_migrations`. Later releases add ordered, versioned steps that run in one transaction after a consistent `VACUUM INTO` backup, with `foreign_key_check` before commit. This build is schema 4: step 2 adds indexes for foreign-key columns and the hot per-world reads (`inventory_items`, mail, sprites, harnesses, currency/item events and auction bids) that previously scanned their tables; step 3 rebuilds every non-`STRICT` table through SQLite's table-replacement procedure (copy into a `STRICT` twin, drop, rename) and restores its explicit indexes, triggers and AUTOINCREMENT high-water mark, keeping `metadata.value` as `ANY` because receipts and objectives are text alongside integer counters; step 4 adds the remaining child-side foreign-key indexes (auction seller/bidder and campaign deliveries). A database without the marker (pre-0.42.0 or unknown) or with an unknown or newer version is refused untouched; there is no automatic or offline conversion of earlier layouts. SQLite opens with WAL, `foreign_keys=ON`, `synchronous=NORMAL`, `busy_timeout=5000`, a 64 MiB `journal_size_limit`, a bounded cache and a 1000-page autocheckpoint. WebUI identities are a deliberately separate `data/webui.sqlite` database owned by the optional frontend: they carry no game account or GM inference, use their own connections and never enter the game database's writer queue. Login and setup attempts are throttled per remote address inside one bounded one-minute window, so a noisy client on a direct HTTP deployment cannot lock every administrator out. Behind the TLS proxy the peer is the loopback proxy, so all administrators share one bucket; a proxy deployment must rate-limit there as well. The storage maintenance watcher checkpoints a WAL that crosses 64 MiB and reports the observed size without blocking writes; shutdown stops writers, drains, checkpoints with `TRUNCATE` and only then closes. Inventory expiry runs on its own 30 s daemon outside every session and scene monitor: reads already hide an item whose deadline passed, the world-state pollers request a sweep when a deadline is due, and an ordinary write no longer pays a global sweep. The `backup` console command writes a consistent single-file snapshot with `VACUUM INTO` while the server keeps running. After confirming a rename, stop scene/session publishers before draining output and closing the socket; otherwise a late publication can abort the drain and lose the confirmation. The drain runs outside world/storage locks. Use identity checks and `BEGIN IMMEDIATE` for multi-owner item/currency transfers; commit durable data **before** publishing account/world events. Close handlers/sockets before tearing down database, logger and the deployment lock. Persistent keys survive every upgrade.

## Lock order and concurrency

Monitor acquisition follows one written order:

`ServerControl` (control) → `SceneRegistry` (registry) → scene simulation → `CharacterStore` (character) → `SessionOutput` (output) → storage.

`LockOrder` enforces it at runtime for the control, registry, character and output monitors. The scene and storage acquisitions follow the order by review: they are not wrapped in `LockOrder`, so a new inversion there is a review finding, not a development-build failure.

Storage is acquired outside every runtime monitor except the named scene commit paths below, which intentionally hold the scene through the durable commit so departure and competing mutations cannot race the payment. No other path may add storage work under the scene or party monitor.

Named commit-under-lock paths (the only allowed SCENE/party → storage edges):

- solo spark-revive payment;
- owned-item use;
- solo floor settlement;
- solo elevator settlement;
- party solo-gate and party revive transactions.

The storage-reaching transactions among those paths (`TownStore.revive`,
`TownStore.useItem` and `OpeningMissionStore.completeUnderLock`) use
`Database.writeBounded`: they wait at most one second for the dedicated writer to
start, refuse before opening a connection when the queue is still blocked, and never
abandon a transaction that has started. This bounds the monitor window without
weakening commit-before-publish; the other settlements listed above pass in-memory
callbacks and perform no storage under the lock.

`SceneSimulation` never runs a scene tick concurrently with itself. A bounded platform carrier pool drives one serial lane per scene at a fixed 50 ms cadence with no burst catch-up; a scene that overruns is counted and skipped, not restarted back-to-back. Admission is atomic, so concurrent starts cannot exceed the active-scene cap; a tick failure stops only that lane, recycles a private interior, and returns affected sessions through a safe close. Towns use the template's authored `instances` count as their shard policy: a world is assigned by `worldId` across those instances, so increasing the count shards Haven without changing isolated interiors.

`SessionOutput` is bounded by both frames and bytes. Reliable state changes are never dropped; optional scene/effect frames are skipped before encoding when a reader is behind, and a reader that stays over budget for the sustained window is disconnected. The budget decision and encoding share one lock, so a frame that begins encoding is queued rather than dropped after it may have registered codec mappings; one such frame can leave the queue a single frame past the byte budget. Encoding runs on the session's own delivery worker and socket writes on its output thread, never on the scene lane.

SQLite runs on a single dedicated platform writer and a small platform reader pool; JDBC only enters through the storage repositories. A delivery worker may wait on a repository commit (bounded by the storage queue) but never runs JDBC or holds a scene lock while waiting; a slow commit delays only its own session's frames, which the mailbox coalesces. Shutdown stops accept, stops scene lanes, waits for connection handlers and the delivery worker against deadlines, checkpoints the WAL, shuts the storage pools down and closes the database, and only then closes the logger.

## Wire correctness

`SessionCodec` is a facade over per-feature codecs that share one `WireContext` per connection, so class and string-intern ordering stays single and connection-global. `SessionWireGoldenTest` pins a synthetic cross-feature stream byte-for-byte, and `SessionCodecFeaturesTest` pins each per-feature codec's own frame directly. `SessionInvocationRouter` keeps the ordered claim chain, and feature handlers write only through `SessionResponder`; neither touches the socket queue directly.

The stock client expects exact Narya/Presents serialization, not Java serialization or approximate messages:

- Incoming and outgoing mappings for classes, enums and interned strings are **per connection and directional**. Preserve definitions, indices, event order, listener/request IDs and bounded frame parsing.
- Incoming frames are bounded to 64 KiB; outbound frames to 1 MiB. Apply depth/count/whitelist limits to decoded input; never load peer-specified classes or accept unknown enum ordinals as trusted data.
- Invocation **service handles** can be assigned during bootstrap and vary by session; stock **method IDs, type layouts and callback contracts** are not interchangeable. Validate against the installed client rather than historical tables. The F3 `ActorSpawner` is admin marshaller method 49 with a `ConfigReference` and `ConfirmMarshaller`; re-verify it against the installed client after an update instead of guessing.
- Preserve the installed reader's expected authentication identity shape (including `ProjectXAuthResponseData.ident = null` for normal login). The client adopts the response's `authName` into its credentials after every successful login; a Steam-initialized client whose adopted name is not a `steam:` alias is offered native account conversion, and its SteamDirector resolves the partner steam service from the bootstrap. Tortodrone therefore reports Steam clients under the server-chosen alias `steam:<accountId>` (the account itself stays a normal username/password account), registers the steam service inertly, and answers its unacknowledged reports (`[int]`, `[boolean]`) while purchase, profile and invite requests receive native failure callbacks. A platform-ticket (Steam account) login is a valid stock mode but unsupported authentication: it receives a normal rejected `AuthResponse` whose code carries a user-visible message asking for the regular account name and password, instead of a protocol error and silent disconnect.
- A client-visible UI control or successfully decoded request **does not prove the corresponding gameplay feature works**. Unsupported operations should deliver native failure callbacks instead of disconnecting when the request is validly parsed.

Proof requires stock-class/reader checks and often a real isolated stock graphical/two-client scenario. See [Development](development.md).

## World and simulation

`SceneCatalog` serves the lazy immutable map/body lookup and `SceneCatalogLoader` ingests and validates external map/collision/portal descriptors and authored content/collision definitions. `SceneRegistry` gives each runtime place a distinct OID and mutable `SceneSimulation`. Towns are shared; supported interiors are private by world ID or shared with the existing party instance. Client scene revision is a cache/version argument, **not** authorization or instance selection.
Training uses the stock level controller and `LevelPartyObject`. Emergency Revive has one shared charge per membership/level; automatic and manual revival consume the same charge. Subsequent deaths expose the native Spark of Life flow. Cooperative Spark payment pins party and scene membership through the authenticated storage commit, then revives the target; concurrent or stale attempts cannot pay twice. Party chat and NPC conversations share service number 21 but route by their owning object. Player damage publishes the native Dungeoneer hit timestamp for impact visuals; activity 274 lasts the current stock client’s 500 ms and permits movement and turning without resetting queued input. The historical 1000 ms protection remains. Passive status resistance excludes the shield slot; blocking handles shields separately. Successful pad heals reuse the stock health-capsule sound/visual, and paid revives use the current client’s `Player/Spark Rez` effect.

Town services use native dialogs with generation-bound close handling, authored conversation actions and placed interaction shapes. Fixed minimap entries retain stock icons and world positions. Arsenal zones publish the native equipment policy; ordinary level equipment changes require a zone, while Town and Training retain ordinary changes. Inventory Use is ItemMarshaller method 14; wrapping/unwrapping use methods 15/13 and native `GiftBoxItem` records. Durable gift contents attach to the box inventory row, including transfer and auction escrow/mail preservation. From the 0.42.0 baseline, schema changes use ordered versioned migrations; unknown or newer versions are refused untouched. Read-only `StockTools town-services` and the restoration scripts reproduce supported service bindings and documented vendor stock. Random vendor rotations and Forge bonus/prize probabilities remain unavailable in the stripped reference data; no replacement probabilities are supplied.

Inventory Trash (ItemMarshaller 1) removes an entire owned stack, refusing permanent or equipped items in both persistent and temporary inventories. Recipe Use removes one item, then publishes one `EntryAddedEvent` inside `EventNotification`, before the confirmation. It must not replace the recipes set first: the native `DSet` would reject the addition as a duplicate and suppress observers. `RecipeCompatibility` checks real Use/replay publications with the installed reader.

Featured Depot offers stream their flag as native `RankGood.special`; clearing it hides Specials and can redirect Kozma's default view to the purchase tab. The Supply Depot constructor resolves BillingMarshaller while building its purchase panel, so an inert registration is required even for ordinary Depot shopping. There is no payment backend: native buy methods 1/2 return `BillingResponseCode.ERROR` (-1); account-info method 3 and all other calls use native failure callbacks when addressable. The removed `BillingInfo` success payload omitted the installed client's `stripeEnabled` boolean and failed native decoding. Current installed methods 6/7/8 have `[int,String,Confirm]`, `[int,String,Result]`, `[long,boolean,Confirm]` shapes respectively; older layouts put the last operation at 7. No billing request grants currency/items or reports successful payment. `BillingCompatibility` verifies the installed wire shapes; `CheckCombatRendering --depot-only` uses an isolated stock client to approach Kozma, open the Depot, load offers, purchase with in-game currency and verify the visible purchase tab survives refused billing calls. The native purchase panel displays its failed-load state and disables purchase.

Fire Pot torches retain their served 6-second cleanup fuse and streamed `Lifetime = "4 Seconds"`. New damage/status region applications are conservatively limited to the first 4 seconds; expiry is checked before any region application. The installed config confirms cleanup and visual parameters, but stripped handler conditions do not establish the original server's damage cutoff. Fire/Oil ground effects use native `Bomb` state (the Bomb sprite casts to this class), including unspecified floor Z. Repeated pot throws, visible fire/oil effects and expiry have an isolated stock-client rendering check; exact native damage timing and lift-barrier visual prediction remain unverified; authoritative movement tests cover closed/open barriers, separate carriers, drop/throw, high speed and diagonal approaches.

Shard-family catalogue repair restores retained delayed child actions, preserves prepared headings, and bounds each primary/secondary detonation to one execution. Primary fuse and secondary cleanup follow the retained bomb-wrapper fuse and shard visual lifespan. Retail strips spread angles and signal/timer fields: existing Rock Salt headings are preserved, other official spread patterns and exact secondary trigger timing remain unverified. Missing headings remain at their retained defaults and may overlap; native fragmentation parity is incomplete. No common fan or random distribution is substituted.

Each occupied instance ticks once per **50 ms**; movement, combat and portal checks run under scene ownership, then capture a shared immutable snapshot. Each recipient receives a delta relative to its **last successfully queued** snapshot. A dedicated bounded output path serializes per-connection class mapping and frames, keeping network writes outside scene/store locks. Class and interned-string codes are per connection, so a town's actor records are encoded once per baseline into a template with symbol placeholders and materialized with each recipient's own codes; a connection that has not yet seen a symbol encodes that frame directly, which also registers it.

Weapon projectiles own their simulation clocks, bounded timers and shutdown actions. Fuse expiry still executes authored detonation actions (including Tortofist missiles and Alchemer children); the native fizzle flag selects the destruction visual. Child actors retain the attacker's faction but have no client prediction owner. Burst sword charges emit the configured number of trail bombs at 100 ms intervals, based on observed stock behavior, retaining the weapon's color, player damage and explosion size. Pulsars replace their first stage with the authored explosive second stage. Exported detonation radii are restored only from surviving stock choice bindings.

Secondary Alchemer bounce directions use an observed relative fan. The stripped Pulsar maturation timer currently uses the stock class's one-second default, not verified official timing. Exact charged Alchemer fans, shard-bomb timers and arbitrary orbital/contact/signal graphs remain incomplete. These mechanics do not establish parity for every weapon.

Clients send facing/activity/input intent, **not authoritative coordinates**. Movement is validated against server collision/portals. Native portal travel uses verified destination scene+entrance data and resets old-scene subscriptions/baselines; delayed input and events from the former scene must not leak into the destination. Server-authored stock actor configs and visual state must agree with client expectations.

`WorldStatePoller` batches identity/role/revision inspection outside session/simulation locks and publishes changed saved inventory/balances; do not use log scraping as a state source. Chat/tells and inspection are scoped by authenticated identities and current ready/owned scenes. Trading requires synchronous revision/owner checks and atomic bilateral transfer.

Knights retain mission rank/subrank and prestige in namespaced rows of the baseline's
integer metadata table. Missing values mean Recruit 1-1 and zero prestige; invalid saved
values fail closed. Local console commands `rank <knight> <rank> <subrank>` and
`prestige <knight> <total>` update these transactionally with the world revision
and audit. Selection, inspection, picker rank and
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
stock configuration and its surviving power choices. Bash uses the verified
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

The server currently supports Haven towns and supported interiors, persistent inventories, owned consumable/instant/spawn-actor/lift item use, authored equipment and combat controls, shared Training parties and friend travel, town services (shops, crafting/forge, Depot, auction, exchange), player mail, trading, inspection/privacy, scene chat/emotes, the stock mission catalogue with the native mission bar and gate map, the verified Shadow Beast gate route with floor settlement, guild formation/roster/invites/ranks/record/energy well and local account/GM management. Guild hall scenes, expansions, themes and the guild shop are not served. **It is not a complete vanilla server.** Training Hall has a bounded implementation of prepared actors, interactive props, traps, pickups, damage, status and conversation; its public/party instances do not imply general dungeon or matchmaking support. Do not infer general NPC service logic, arbitrary monster behavior or dungeon loot tables, complete crafting coverage, general mission objective progress, arbitrary dungeons, party matchmaking or guilds from those mechanics or client data.

The versioned content registry keeps supported scene/catalog definitions outside the executable and persistent **state** (accounts, saved progression, ownership) in storage, while Java remains authoritative for mechanics, transactions, security and wire protocol. Content selection, validation, dependency declarations and complete-resource overrides are implemented; arbitrary missions/dungeons and hot reload are not.

## Source-of-truth and dependency hygiene

`build.gradle.kts` configures Java 25 toolchains and packaging; verified plugin/dependency digests are pinned under `gradle/verification-metadata.xml`. Required redistribution notices live in `src/main/resources/THIRD-PARTY.md`, JAR resources and `src/main/resources/licenses/`; releases assemble them under `licenses/`. Keep private captures, stock game binaries/assets, `data/`, `.build/` and temporary test deployments out of Git.

Use focused regression tests before broad stock-client checks. Avoid expanding this document with one-off milestone reports; Git history retains past investigations.

Content compilation resolves map-local YAML and pinned native bindings into the existing catalog types before publication. Town and pet graphs are retained in that snapshot; scene creation and account-store initialization do not reopen catalog files. Pack dependency resolution is confined to the explicit import boundary. See [Content](content.md) for the administration contract.
