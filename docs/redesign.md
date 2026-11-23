# Redesign plan

Target: a modular monolith — one process, one executable JAR, explicit compile-time
boundaries, mission-owned content with shared assets in one place, and concurrency
that is measured rather than assumed.

This is a design and sequencing document. Current behaviour is defined by source and
tests, not by this file.

This is a **full redesign**, not a feature patch. Code moves into enforced modules,
the four god classes — `WorldSession`, the scene classes, `Database` and
`CharacterStore` — are split by responsibility, persistence is rebuilt around a new
baseline, content becomes mission-owned, and every unimplemented gameplay system gets
a reserved code package and content folder in this release — even when its
implementation is several releases away. The phases differ in risk and order, not in
ambition.

**Release target: `0.42.0`** — a behavioural release with a new database baseline
and content format. It is not `1.0.0`. Pre-`0.42.0` user data is not migrated in this
release. `VERSION` moves to `0.42.0` when the persistence reset lands (Phase 5). The
data guard is the baseline marker described under Database, not the version string:
an older build refuses the new baseline because it does not know the schema, and the
marker keeps a pre-`0.42.0` database from being mistaken for the new one.

## Fixed constraints

1. `./gradlew build` produces exactly one deployable archive: `build/tortodrone.jar`,
   beside `config/`, `content/`, `webui/` and `plugins/`.
2. Runtime state stays in `data/` beside the JAR.
3. Hosting stays headless. Administration stays on the local console and the
   optional WebUI under `/admin/`.
4. Native wire layouts, service ids, method ids and callbacks stay exact.
5. Authenticated ownership, leases, revisions and GM checks stay authoritative.
6. SQLite writes stay atomic. Durable state commits before world events publish.
7. Scene instances own simulation state. Client input is intent, not position.
8. No new network or database I/O inside a scene tick or a scene/storage lock. The
   commit-under-lock paths that exist today are named below; Phase 4 bounds or
   refactors them. The storage-reaching paths are bounded and tested; the landed
   outcome is recorded under Known commit-under-lock paths.
9. The runtime reads only project-owned formats. Installed client data, extracted
   assets and decompiled material never ship.
10. Verification stays focused: targeted `testUnit` programs and one graphical
    scenario only when the change is client-visible.

## Build and packaging

The project already packages with Shadow (`com.gradleup.shadow`), which merges
application classes and runtime dependencies into one executable archive with a
`Main-Class` manifest.

Benefits that matter here:

- **One file is the deployment contract.** Copy or replace one JAR; there is no
  `lib/` directory to drift, partially update or lose during backup.
- **Atomic update and rollback.** A failed release is undone by restoring one file.
- **Dependency versions are pinned in the artifact.** The server cannot accidentally
  run against a different SnakeYAML, JLine or SQLite JDBC version found on the host.
- **No launcher scripts or classpath files.** `java -jar tortodrone.jar` is the whole
  runtime contract on every platform.
- **Reproducible packaging is already configured.** Archive timestamps and file
  order are fixed, so identical inputs produce identical output bytes.

Costs and guardrails:

- **Resource and service merging.** Shading merges `META-INF/services` and can
  collide on duplicate resources. Keep the current dependency set small; add a
  dependency only with a concrete need.
- **No minimization.** `minimize()` is unsafe here because SQLite JDBC, JLine
  providers and reflective service loading are resolved at runtime. A smaller JAR
  is not worth a server that fails only in production.
- **One archive, not one class loader.** Shadow does not isolate dependencies. If
  two libraries ever need conflicting versions, relocate or remove one; do not add
  a second class loader.
- **Gradle modules are source boundaries, not JPMS modules.** The shaded JAR stays a
  plain classpath archive with one class loader; `module-info.class` is not added.
- **Licence packaging is out of scope and unchanged.** `build.gradle.kts` embeds
  `LICENSE`/`NOTICE` and `portableZip` ships `docs/LICENSE`, `docs/NOTICE`,
  `docs/THIRD-PARTY.md` and `docs/licenses/`. This redesign neither adds nor removes
  that work; bundled dependency terms remain their own regardless.

Alternatives rejected:

| Alternative | Why not |
| --- | --- |
| Application distribution with `lib/` | Violates the single-JAR contract and makes partial updates possible. |
| Bootstrap JAR that downloads libraries on first run | Adds a network dependency to startup, allows version drift, breaks air-gapped hosts and turns a supply-chain failure into a server that will not boot. The current archive is about 15 MiB with three runtime dependencies; there is nothing to gain. |
| `Class-Path` manifest entries | Still multiple files, with fragile relative paths. |
| `jlink` / `jpackage` | Bundles a platform-specific JDK, grows the download and complicates updates. |
| A framework boot loader | Adds a packaging framework to solve a problem Shadow already solves. |

Build assertion to keep:

```sh
./gradlew clean build
find build -maxdepth 1 -name '*.jar'
# exactly one: build/tortodrone.jar
```

Gradle scratch output stays under `.build/`; it is not a deployment artifact.

## Concurrency and runtime

### Current inventory

The old plan described a simpler runtime than the code actually has. This is the
real inventory that Phase 0 must baseline:

| Path | Today |
| --- | --- |
| Client accept | Main thread accepts; one platform thread per connection; a semaphore bounds connections. |
| Session output | One writer platform thread per session; 64-frame queue with a 4 MiB byte budget and a 5 s sustained-overflow disconnect; a single frame may be up to 1 MiB. |
| Session delivery | Per-session `SceneDelivery` virtual thread plus two additional queues. |
| Scene tick | A per-scene task on the platform `SceneScheduler` (cap 256 active scenes), re-armed per idle transition; fixed 50 ms delay; the whole tick and snapshot capture run under the scene monitor. |
| Parallelism | Across scenes only. A single scene is never ticked concurrently. |
| Storage | A fresh SQLite connection per transaction; WAL; `busy_timeout=5000`; one `Database` with a platform writer and four readers is shared by game and admin paths. |
| Account watcher | A database read per session every 250 ms. |
| World-state poller | Batched reads every 250 ms. |
| Auction expiry | A write every 30 s. |
| Metrics | Sampled every 5 s; the path takes the control monitor and then every scene monitor. |
| Character roster | One `CharacterStore` monitor serialises roster I/O and listener encodes. |
| HTTP | Platform pool of 4–16 threads, 64-slot queue, `AbortPolicy`; not virtual-thread request handling. |

### Known commit-under-lock paths

These are real today and deliberately documented in the code:

- solo revive payment commits under the scene monitor;
- owned-item use commits under the scene monitor;
- solo floor settlement commits under the scene monitor;
- solo elevator settlement commits under the scene monitor;
- party solo-gate and revive transactions execute scene/storage work under the
  party monitor.

A SQLite stall is therefore a scene stall and, for the party paths, a party-wide
stall. The plan does not claim otherwise. Each path gets an owner and one of two
outcomes before Phase 4 exits:

1. **Bounded under the lock** — commit runs on the dedicated storage executor with a
   measured deadline, and the window is covered by a regression test; or
2. **Reserve → release → commit → revalidate** — the scene/party state is reserved,
   the lock is released, storage commits, and the result is revalidated against the
   generation before publication.

No new commit-under-lock path may be added.

**Landed outcome 1:** the three transactions that actually reach storage under a
scene or party monitor — `TownStore.revive`, `TownStore.useItem` and
`OpeningMissionStore.completeUnderLock` — use `Database.writeBounded` with a 1 s
queue-wait budget. If the dedicated writer has not started the transaction when the
budget expires, the queued task is removed and refused before it opens a connection,
so nothing commits behind the caller. Once started, the task is never abandoned; the
caller waits for the real outcome, preserving commit-before-publish.
`BoundedWriteTest` covers both sides, and `SparkRevivalTest`,
`SceneItemUseTest` and `MissionFloorSessionTest` exercise the callers. Floor/elevator
settlements whose callback is in-memory (`() -> Boolean.TRUE`) hold no storage under
the lock.

### Target model

- **Storage:** one writer and a small number of readers on dedicated **platform**
  threads. JDBC never runs on a virtual thread; sqlite-jdbc uses native frames and
  can pin carriers. Game and admin share one writer service, not two `Database`
  instances on the same file.
- **Connections:** virtual threads for socket reads and session work, with a bounded
  connection limit that also bounds concurrent handshakes. Any JDBC call from a
  session is dispatched to the storage executor; it never runs on a carrier.
- **Output:** bounded by **bytes and frames** per session. Policy: when a session
  exceeds its byte budget, drop optional update frames (snapshots beyond the newest,
  effect chatter) rather than reliable state changes; a client that stays over budget
  for a sustained window is disconnected. The scene never waits on a slow client and
  the slow client never causes another client to lose state. Encoding stays off the
  scene thread where possible.
- **Scenes:** an explicit scheduler with a bounded carrier pool and one serial
  execution lane per scene. Fixed 50 ms cadence, no burst catch-up, active-scene
  cap, and an overrun policy (log, skip, or evict) chosen from measurement.
- **Tick contents:** fixed system order (movement, status, combat, portals,
  elevators, capture), no parallel streams, no blocking calls, no storage or
  network access, reusable scratch buffers.
- **Publication:** build an immutable snapshot after mutation, release the scene
  lock, then deliver. Recipients never observe a partially updated scene.
- **Town sharding:** a single Haven instance is a hot monitor. The plan must state
  an instance-count or sharding policy for towns, not only “parallel across scenes”.
- **Shutdown:** stop accept → stop scene ticks → drain deliveries with a deadline →
  checkpoint WAL → close database → exit. SIGTERM is bounded; no write or log after
  closure.
- **Scene failure:** a tick failure logs template/instance, marks the instance
  failed, evicts members with a safe return and recycles the instance. A frozen
  scene is not an acceptable end state.
- **Clock:** `SceneClock` is a 64-bit millisecond counter with wrap-safe helpers, so
  long soaks do not reuse a stale stamp.

### Lock order

The working order is:

```text
session registry -> party -> scene -> entity collections
```

This is a target, not a description of every current call path. The party-to-scene
nesting on the input path and the commit-under-lock paths are known exceptions with
owners. `LockOrder` currently enforces the order for the control, registry, character
and output monitors; the scene and storage acquisitions follow it by review and are
not instrumented. Storage is acquired outside all of them except the named commit
paths.

### Metrics required before the scheduler rewrite

1. Per scene: lock wait, tick work, publication and delivery, and cadence lateness —
   p50/p99/max, sampled outside the scene monitor.
2. A defined load, recorded with the measurement host (CPU model, cores, RAM, JDK
   build): 16 clients at the shipped default, one 8-player interior, one town
   instance, plus the workload mix (idle, full interior, revive, owned-item use,
   elevator settlement), at `-Xmx512m` and `-Xmx1g`. Instrumentation and the recorded
   values live in the Phase 4 measurements section of `docs/baseline.md`.
3. Tick gap and input latency while a revive/settlement transaction runs against a
   contended database.
4. Database wait, `SQLITE_BUSY` count, transaction duration, WAL bytes, checkpoint
   timing and the cost of the per-write expiry sweep.
5. Per-session queue depth in frames **and** bytes, overflow/throttle/disconnect
   counts, largest frame, retained snapshots, heap per session with a slow reader.
6. Carrier and thread accounting: pool parallelism, active carriers, virtual and
   platform thread counts, and evidence that concurrent JDBC calls do not starve
   ticks.
7. GC and allocation, including per-tick allocation from snapshot capture.
8. Shutdown drain time under load and whether the WAL checkpoint completes.

## Scope and evidence

At the time of writing:

- 273 catalogue missions: 108 rank, 147 prestige, 3 expansion, 15 hidden.
- 53 imported gate routes; one fully playable route beyond the pinned opening gate:
  `shadow_beast` (scenes 351, 380, 382, 384).
- 17 scene definitions under `content/maps`.
- 271 `*Test.java` programs under `src/test`; `testUnit` selects 265 of them (six
  integration tests are excluded). Two further programs live under `scripts`. Three
  files declare `main()` as benchmarks or the harness rather than tests:
  `SceneBenchmark`, `LoadBenchmark`, `StandaloneTestSuite`.
- `build/tortodrone.jar` is about 16 MB (16,190,646 bytes in the current build); the
  exact size changes with content and dependencies.

**108 rank missions is not 108 playable routes.** 53 routes are imported. Five have
every floor present under `content/maps`, and only one of those also has a complete
lobby-to-level wedge chain:

- `shadow_beast` — lobby 351, floors 380, 382, 384;
- `ancient_generator` — route wants lobby 351 then 337, but the shipped opening gate
  goes directly to 337;
- `choose_your_sprite` and `laboratory_visit` — lobby-only wedge (scene 30893);
- `hall_of_heroes_4` — lobby-only wedge (scene 318).

The 0.42.0 runtime must play every route that is startable from shipped content, and
hide the rest with a recorded reason. It must not claim all 108 are playable.

Evidence rules:

- Layouts and behaviour come from observed stock-client behaviour and verified
  captures/configuration, never from invented packets or rewards.
- Captures are used to derive layouts. Test fixtures are synthetic and sanitized;
  client captures are never committed.
- A missing layout is a Phase 2 blocker, not a reason to guess.

## Target code architecture

### Current graph

The source is a module graph under `modules/`. The root packaging/composition
project is named `tortodrone`; `core`, `content`, `protocol`, `world`, `gameplay`
and `server` are included projects, and `checkModuleGraph` enforces the allowed
edges between them.

The earlier `protocol → world/storage/town`, `storage → protocol`,
`resources → world` and `world → protocol` edges are gone, and `MapYaml` no longer
calls into `SceneEntities`. The former `server ↔ web` package cycle is gone too:
`web` still reads `ServerControl`, `ServerLog` and the other server types, but the
server no longer imports `web`. It composes the optional WebUI through the
server-owned `WebFrontend` seam registered in `META-INF/services`, and reaches
identity recovery through `WebIdentities`.

### Target modules

Seven Gradle projects, shaded into the existing single JAR (the root project is
named `tortodrone` and carries the `:app` role):

```text
:app          packaging, composition root, release tasks
:core         kernel + pure domain models and view contracts
:content      YAML/JSON schemas, loaders, validation, catalog snapshots
:protocol     handshake, decoder, per-feature wire codecs
:world        scene templates, instances, simulation, collision, portals
:gameplay     persistence, transactions and application services
:server       process lifecycle, sessions, console, WebUI, News, artwork
```

Allowed edges:

```text
:app      -> all
:server   -> gameplay, protocol, world, content, core
:gameplay -> world, content, core
:world    -> content, core
:protocol -> core
:content  -> core
:core     -> none
```

`news`, `config` and `web` are server-owned, but the **settings value type** and the
**news read model** are read by loaders, so those types move to `:core` and are
passed in rather than fetched from statics. `account`, `character` storage,
`storage`, `loot` opening and `town` services live in `:gameplay`; character, town
and loot **models** move to `:core`. `resources` becomes `:content`. Split packages
are expected: a package is assigned by extracted type, not by its current name.

### Cycle-breaking order

1. Move shared model types out of `world`, `storage` and `town` into `:core`: scene
   content specs, fire graphs, mission gates/comlinks/conversations, item and
   character read views.
2. Move the `ServerSettings` value type and the `NewsCatalog` read model into `:core`,
   then pass them into `:content` loaders instead of reading `config`/`news` statics
   from `resources` and `world`.
3. Replace `protocol → world/storage/town` with `:core` view contracts. `SessionCodec`
   keeps a facade and receives views, not live scenes or profiles. Move the
   `WireTypes` constants to `:core` so `world` stops importing `protocol`.
4. Remove `storage → protocol` by moving request/decoder-owned types to `:core` or
   handling them in `:gameplay`.
5. Remove the `MapYaml` call into `SceneEntities.validateItemUse`. Validation stays in
   `:world`; the loader receives a validator function. (Landed: the loader no longer
   calls the entity model, and the now-unused entity static is gone.)
6. Break `server ↔ web` by making the WebUI depend on server-owned interfaces, or by
   moving `AdminService` construction behind a server boundary. (Landed: the server
   owns `WebFrontend`/`WebIdentities`; `web.AdminFrontend` is the single provider.)
7. Only then create Gradle projects and the dependency check. Use `jdeps`/bytecode as
   well as imports; fully qualified references hide edges. (Landed: `checkModuleGraph`
   also rejects a direct `org.tortodrone.server` → `org.tortodrone.web` reference, and
   `checkBytecodeDependencies` validates the packaged bytecode against the documented
   module graph.)

### Codec split

`SessionCodec` was 1,980 lines with connection-global class and string interning.
It is now a 423-line facade over per-feature codecs that share one `WireContext`
holding the class table, intern table and connection-local state. The split proved
identical bytes for a synthetic cross-feature stream (no client captures), not only
for per-feature frames; `SessionWireGoldenTest` pins that stream, and
`SessionCodecFeaturesTest` constructs each per-feature codec directly and pins its
own representative frame.

### God-class splits

- `WorldSession` → `SessionRuntime`, `SessionInvocationRouter`, `SessionResponder`,
  `SessionDirectory` and feature handlers. Handlers send only through the responder
  and never reach the session's output queue or socket; they encode through the
  responder's codec.
- `SceneSimulation`/`SceneEntities` → one shared mutable entity model plus
  `MovementSystem`, `CombatSystem`, `StatusSystem`, `PortalSystem`,
  `ElevatorSystem`, `SnapshotBuilder` and an explicit callback contract. The
  callback sinks, including elevator authority, live in `SceneCallbacks` and are
  part of that contract, not incidental wiring.
- `Database` → transaction/schema owner plus repositories. The split must preserve
  or deliberately replace the write-path expiry sweep.
- `CharacterStore` is a storage facade returning `storage.*` types. Split the model
  from the store before claiming a `character`/`storage` boundary.

#### Status after `0.42.0`

The codec split, the session handler extraction, the scene system extraction and the
god-class splits are landed and tested. The classes the table named are now thin
owners that compose named collaborators instead of mixing responsibilities:

| Class | Lines | Composition |
| --- | --- | --- |
| `WorldSession` | 601 | protocol facade over `SessionRuntime`, the feature handlers, `SessionResponder`, `SessionPresence`, `SessionLog`, `SessionPublications`, `SessionRefresh`, `SessionProfilePublisher` and `ClientDiagnostics`; forced travel uses `SessionRuntime.retireScene` |
| `SceneEntities` | 516 | shared entity model over `SceneCollisions` (spatial index), `SceneCallbacks` (status/dialog/effect contract) and the system classes |
| `SceneSimulation` | 399 | process-wide `SceneClock`, membership map and publications; the entity/weapon callback contract is wired once in its constructor; per-tick observations live in `SceneTickMetrics` |
| `SceneMembership` | 282 | per-knight scene state (movement, health, floor/elevator/dialog ownership); inventory and equipment publication live in `MemberLoadout` |
| `Database` | 505 | transaction/schema owner over `AccountRepository`, `RosterRepository`, `WorldRepository`, `AdministrationRepository` and `PreferenceRepository`, returning storage records or core character models; process counters live in `StorageTelemetry` |
| `CharacterStore` | 351 | roster facade returning core character models; the storage-service facade is `CharacterServices`, per-knight storage reads/writes are in `KnightStorage`, knight identity policy is in `KnightIdentity`, and the roster monitor enters the `CHARACTER` rank at every synchronized entry point |

The line counts are the size of each facade, not a target: a facade gains a line
whenever a protocol entry point or a new collaborator is added. The two largest
remaining stores were split the same way:

- `TownStore` keeps the authenticated mutation entry points and composes
  `SpriteStore` for sprites and harnesses, `CraftStore` for recipes and crafting,
  `GearStore` for forge and heat, `ShopStore` for storefront and Depot purchases,
  `GiftWrapStore` for gift boxes, `CustomizeStore` for variants, unbinding and
  accessories, `UpgradeStore` for Arsenal upgrades, `UseStore` for scene item use
  and revive payment, `AdminStore` for the GM setters and `RenameStore` for the
  knight rename window and ticket. It delegates to the static `TownLedger` for
  inventory and currency primitives and `TownSchema` for the town tables.
- `GuildStore` composes `GuildHallStore` for the treasury well, hall state and
  expansion tickets, `GuildMembershipStore` for formation, invitations and roster
  commands, and `GuildSettingsStore` for name checks, the rename window and the
  message of the day. It delegates to the static `GuildLogStore` for the guild
  record and `GuildSchema` for the guild tables.
- `AuctionHouse` keeps auction listings, bids, fees, escrow and settlement;
  `TownMail` owns the inbox, uplink letters, attachments, retention and the
  shared escrow item envelope. Settlement writes a letter through `TownMail`
  inside the same auction transaction, preserving refund/claim ordering.
- `SceneActions` keeps the sealed action vocabulary, JSON/wire decoding and
  validation; `SceneActionRunner` owns the tick-budgeted execution engine,
  timer queue, ordering counter and signal-lifetime latches. All action
  ordering and timing are unchanged.
- `MonsterCatalog` keeps the immutable type/overlay maps and runtime
  resolution; `MonsterCatalogReader` owns file/fingerprint ingestion, parsing,
  overlay validation and the bounded scripted-boss check.
- `MailCampaignStore` keeps campaign and welcome policy; `StaffMail` owns the
  single staff-letter writer (sender negation, literal/tainted text, SYSTEM
  type and gift materialisation) used by campaigns, the welcome letter and
  one-off sends inside the caller's transaction.
- `WireWriter` keeps shared framing, symbol and event primitives; `ItemWire`
  owns the item-instance, gift and loadout records (`KnightCodec`,
  `TradeCodec` and `SceneActorWire` compose it) and `GateWire` owns the
  bootstrap-only gate summaries, including the fixed tutorial/opening capture
  data. Byte layout is unchanged and the cross-feature golden still matches.
- `SessionCodec` keeps the protocol constants, shared view records and facade
  delegations; `SceneDeltaTemplate` owns the reusable scene-delta fan-out
  frame, its recorded offsets and the +4 shift. Recipients still materialise
  their own symbols from the same `WireContext`.
- `MapCollision` keeps the immutable compiled tiles/shapes, bounds and query
  kernel; `MapCollisionCodec` owns the build-time properties parser and the
  versioned gzip read/write, including sorted tile keys, derived bounds and
  stable shape order. The runtime value carries no file-format policy.
- `FireCatalog` keeps the immutable attack/detonation indexes and runtime
  lookup; `FireCatalogCodec` owns the prepared catalogue file format, the
  tagged JSON graph/value shape and every size and depth limit applied to
  imported graphs. Prepared scene metadata and the catalogue share the same
  codec rather than duplicating graph policy.
- `WeaponCatalog` keeps the immutable weapon/shield lookups and chain policy;
  `WeaponCatalogCodec` owns the exported TSV format, row assembly and the
  duplicate, contiguity and field-count rules. An unused private builder that
  no code referenced was removed with the split.
- `EquipmentCatalog` keeps the immutable per-item modifier lookups and stock
  modifier arithmetic; `EquipmentCatalogCodec` owns the exported TSV format,
  the weapon-class column order and the size/row/field rules.
- `SceneCatalog` keeps the lazy id/body map and lookup; `SceneCatalogLoader`
  owns prepared-map descriptor ingestion: path containment, pinned checksums,
  the archived header, compiled collision hash, spawn clearance and the
  portal/collision metadata. Cross-scene portal-graph validation stays with
  the catalog in `of`.
- `SceneContent` keeps the immutable actor/item/status tables and `Spec`
  lookups; `SceneContentLoader` owns the prepared properties format, sorted-key
  range scans, table limits and the pinned layout-hash check.
- `TownCatalog` keeps the immutable town tables, lookups and variant rolling;
  `TownCatalogReader` owns the exported JSON catalogue, its format version, row
  shapes and size limit.
- `ScenePortals` keeps the immutable entrance/trigger tables, polygon
  intersection and pawn radius; `ScenePortalCodec` owns the exported properties
  format, entrance clearance, trigger-shape parsing and convex-polygon rules.
- `BoxCatalog` keeps the immutable key bindings, lookup and spawn tables;
  `BoxCatalogReader` owns the exported binding document, the readable loot YAML,
  key/type/evidence checks and the reward-graph validation.
- `SceneCollision` keeps the immutable tile/floor/shape tables and the
  penetration/clearance kernel; `SceneCollisionCodec` owns the exported
  properties format, layout-hash check, bounded row counts and geometry grammar.

Phase 9 stays evidence-gated and is not part of this table; it needs the per-epic
stock evidence listed in `docs/baseline.md`.

Reserved packages and their content folders are created in Phase 1, each with a
`package-info.java` (and a `README.md` under the matching content folder) describing
the contract and the evidence it still needs. Phase 9 fills these; it does not create
them:
`gameplay/mission/{objective,prestige,gateconversation,missiontest}`,
`world/arcade`, `gameplay/guildhall`, `gameplay/pvp`, `gameplay/events`,
`gameplay/lairs`. `gameplay/achievements` already implements the predicates the
client verifies; its content README records which types remain evidence-gated.

## Target content architecture

### Layout principle

The existing roots are **already the shared area**:

```text
content/items, content/maps, content/monsters, content/town, content/loot,
content/shared (including shared/news), content/_internal
```

They stay where they are in 0.42.0. Moving them under a second `content/shared/`
root would break `ContentManager`, `MapYaml`, `ItemYaml`, `TownYaml`, `BoxCatalog`,
`MapImport`, `ItemMigration`, `ContentPresentation` and `BuildTools`, and would make
`maps.getParent().resolve("shared")` resolve to `content/shared/shared`.

For missions, the redesign adds:

- `content/missions/<mission-id>/` for mission-owned records;
- `content/shared/missions/` for records shared by the catalogue.

```text
content/
  format.txt
  items/
  loot/
  maps/{level,town,training}/<scene>/
  monsters/
  town/
  shared/
    combat.yml
    statuses.yml
    trap-cycles.yml
    presentation.yml
    news/
    missions/
      groups.yml
      properties.yml
  missions/
    shadow_beast/
      mission.yml        required; one `missions:` row, id must match folder
      grants.yml         optional; one `grants:` section
      comlink.yml        optional; one `comlinks:` section
      route.yml          optional; one `routes:` section
      objectives.yml     optional; new schema, only when the subsystem exists
    ancient_generator/
      mission.yml
      route.yml
  systems/
    arcade/              README only
    guildhall/           README only
    pvp/                 README only
    events/              README only
    lairs/               README only
    achievements/        README (verified predicates; counters blocked)
  _internal/
    default-source.properties
    fires.json
    pet-fires.json
    item-bindings.json
    item-actions.json
    item-use.json
    item-arguments.json
    statuses.json
    town-definitions.json
    box-bindings.json
    box-expectations.json
    localization-report.txt
    provenance/
```

Rules:

- A mission folder owns only mission-specific records. Reused maps, items, monsters,
  towns and loot stay in the existing roots. Shared mission `groups.yml` and
  `properties.yml` move from `content/missions/` to `content/shared/missions/`.
- A shared scene is never copied into a mission folder. Mission folders reference
  scenes by level or id; the lobby scene 351 stays shared by every route that uses it.
- `mission.yml` contains the complete row, including `requirement` and `rewards`
  references. There is no separate `rewards.yml`, `exchange.yml`,
  `gate-conversation.yml` or `tests.yml`; those are row fields or requirement types,
  and splitting them would duplicate the row or change the schema.
- `grants.yml`, `comlink.yml`, `route.yml` keep their existing section schemas.
  `objectives.yml` is new and gets its schema in Phase 2, backed by evidence.
- Folder names use the mission-id regex `[a-z][a-z0-9_]{0,127}`.
- Unknown files are rejected. Allowed files per mission folder are exactly the five
  above (the last optional and only when supported). Reserved `systems/` folders
  contain a `README.md`, not empty YAML with invented fields.
- Rank, prestige, expansion and hidden remain mission families in `mission.yml`.
- **v1 stays readable for one release.** `0.42.0` ships and validates v2, but the v1
  reader remains so an existing tree still boots; it is deleted in `0.43.0`. The v2
  reader is recursive, removes the 16-file cap and reads
  `content/shared/missions/groups.yml` and `properties.yml`.
- `_internal/` does not move. It holds loader inputs and provenance metadata. Only
  `default-source.properties` and `provenance/` are touched, and only for path
  regeneration.

### `format.txt`

`content/format.txt` is a one-line layout marker:

```text
2
```

Truth table:

| `format.txt` | Meaning |
| --- | --- |
| absent | v1 (current split-file layout) |
| `1` | v1 |
| `2` | v2 (per-mission folders) |
| anything else | refuse startup |

Encoding is UTF-8, exactly one line, trailing newline, no trimming beyond the line
ending. A tree containing both flat mission files and `missions/<id>/` folders is
refused even if `format.txt` is valid. The converter writes the marker; startup only
reads it. A missing marker means v1, not “unknown”, so existing deployments keep
working.

### Converter, importers and manifest

- Add a deterministic, idempotent offline v1→v2 converter. It reads only the
  checked-in v1 tree, writes a temporary v2 tree, validates it, then swaps
  atomically. It does not depend on external captures.
- Add `--check` to all four mission importers, not just `import_missions.py`, and
  give all four temporary-file/directory writes with atomic replacement. The four
  importers now share `scripts/content/mission_layout.py` (`write_all`/`atomic_write`)
  and expose `--check`. The converter writes into a disposable staging tree and swaps
  directories only after validation, so a failed run leaves the live tree untouched;
  the residual window is a crash between those two directory renames, recoverable
  from the `content.v1-backup` sibling.
- The manifest is already per-file: 1011 `owned.*` and 847 `source.*` keys in
  `_internal/default-source.properties`. Keep per-file keys and regenerate mission
  paths from the converted tree. A folder digest may exist as an optional summary,
  but it is not the conflict unit; one edited mission must yield one file conflict.
- Customized deployments: map each old path to its new path, preserve owner edits,
  and fail with an actionable list when a mapping is ambiguous. “Rebuild by hand” is
  a documented fallback, not the default.

### Equivalence gate

“Catalogues identical” is too narrow. The v1/v2 gate requires:

- per-domain semantic dumps (missions, groups, properties, grants, comlinks, gates);
- unchanged asset domains (items, maps, monsters, town, presentation, shared news);
- byte equality for `_internal/` **excluding** `default-source.properties`, which is
  regenerated; the manifest is compared semantically instead (same file set and
  digests after path mapping);
- unknown/mixed/reserved-file rejection tests;
- converter idempotence and a boot test on the converted deployment;
- an ownership-placement diff for every moved mission record.

## Database

SQLite remains the correct choice: single node, no external service, ACID economy,
WAL readers, one-file backup and a mature JDBC driver already inside the JAR.

### Baseline marker

`SCHEMA_VERSION=1` cannot identify the new baseline on its own. Earlier releases used
schema versions up to 18, and `DatabaseSchemaTest` already treats `user_version=1` as
a refused case. A pre-`0.42.0` database must stay distinguishable from the new
baseline.

Use `PRAGMA application_id` with a fixed project value as the marker, plus
`user_version` as the schema version. `application_id` is written before any table,
and the schema version counts from 1 at the baseline.

Open rules:

| State | Behaviour |
| --- | --- |
| Empty file, no marker | Initialize the new baseline (`application_id` and `user_version=1`). |
| Marker present, `user_version` = 1 | Open. |
| Marker present, `user_version` = 2..N where N is this build's version | Run ordered migrations from the stored version to N. |
| Marker present, `user_version` > this build's version | Refuse: downgrade. |
| Marker present, unparseable/zero schema version | Refuse. |
| No project marker, non-empty database | Refuse as pre-`0.42.0` or unknown; do not modify. |
| Unknown `application_id` | Refuse. |

`DatabaseSchemaTest` must be rewritten to assert every row, including that a refused
database is byte-identical afterwards and that a real fixture for each refused
version exists instead of a bare `user_version` stamp.

### Migrations

- `0.42.0` is a clean baseline; no pre-`0.42.0` conversion.
- From the baseline onward, schema changes use ordered, versioned steps in one
  transaction, recorded in a migration table. A known version below the build's
  version migrates forward; a version above it is refused.
- Take a backup before an upgrade under the deployment lock.
- Use `PRAGMA foreign_keys=OFF`, run the steps, run `foreign_key_check`, then commit;
  roll back on any failure.
- Refuse unknown versions. Never auto-migrate or guess.
- The migration runner exists from the first release even though this release has
  nothing to migrate.

### Schema targets

- Game tables are `STRICT`, so a wrong type fails instead of being silently coerced.
  The separate `data/webui.sqlite` identity store keeps its own schema.
- Foreign keys are declared and enforced (`PRAGMA foreign_keys=ON` per connection).
- Item instances are one row per instance; no relational state is stored as JSON.
- Every foreign-key column and every lookup a store performs has an index.
- The write path does not run a global expiry sweep on every transaction; expiry
  runs on its own schedule and its cost is measured.

**Landed:** the baseline creates every game table `STRICT`. Step 3 rebuilds a
non-`STRICT` file with SQLite's table-replacement procedure, preserving rows,
explicit and partial indexes and the AUTOINCREMENT high-water mark; `metadata.value`
is `ANY` because mission receipts and objective payloads are text alongside integer
counters. The caller still runs `foreign_key_check` before commit and the step
refuses to run unless foreign keys are disabled. Step 4 adds the remaining
child-side foreign-key indexes. `inventory_items` is one row per instance; the JSON
columns that remain carry value lists or message payloads, not relational rows.
Inventory expiry already runs on its own 30 s daemon outside the write path.
`DatabaseSchemaTest` upgrades a real non-`STRICT` schema-2 fixture with text
metadata and checks the rebuilt schema, rows and indexes, while a synthetic fixture
covers trigger and sequence preservation.

### SQLite operations

- Choose and document `synchronous`, `wal_autocheckpoint`, `journal_size_limit`,
  `cache_size` and `busy_timeout`; do not inherit defaults silently.
- Add a checkpoint scheduler and a WAL-size alarm; checkpoint before shutdown.
- Replace “online backup” with a concrete implementation (`VACUUM INTO` or the
  backup API) and update `docs/operations.md`, which currently says to stop the
  server and copy `data/`.
- One writer service for game and admin. Remove the second `Database` instance on
  the same file.
- Batch related writes; never hold a scene or party lock across a new transaction
  except the named, bounded commit paths.

## Verification

- Unit programs stay standalone `main` programs under the existing runner. Do not
  migrate the 265 programs selected by `testUnit` to JUnit as part of this work.
- Selected runs: `./gradlew testUnit -PunitTests=A,B`.
- Compile/package check: `./gradlew build`.
- Wire changes: structural decoding plus **synthetic** per-codec and cross-feature
  golden fixtures.
  Do not commit client captures.
- Storage changes: ownership, rollback, escrow, concurrent trade/auction/mail, and
  refusal of historical schemas without modification.
- Content changes: per-domain equivalence, converter idempotence, mixed-tree refusal
  and a converted-deployment boot test.
- Architecture: `jdeps`/bytecode dependency check, not import scanning alone.
- Concurrency: the eight measurements above; lock-order tests; no new
  commit-under-lock paths.
- Client-visible changes: one relevant graphical scenario, never routine. Phase 2
  adds a `mission` scenario; `explore` only drives free movement.
- `testAll` only with explicit owner approval.

## Phases

| Phase | Work | Exit gate |
| --- | --- | --- |
| 0. Baseline | Record the `sha256` of `content/` and of `build/tortodrone.jar`, the JAR size, and tick/DB/queue baselines using existing tools; write the current thread/lock inventory; list the required wire evidence with its evidence source; add synthetic fixture scaffolding | `build` and selected tests green; the baseline file and evidence list exist and are committed |
| 1. Content v2 | Mission folders, `shared/missions/`, `format.txt`, recursive v2 reader, converter, per-file manifest, `--check` and atomic writes on all four importers; create reserved `systems/` folders and `package-info.java` skeletons | v1/v2 mission domains identical and `_internal` equal excluding the manifest; converted deployment boots; mixed/unknown trees refused; reserved paths exist |
| 2. Mission runtime | Wire evidence for MissionObject/objectives, mission bar, gate map, party config, reward wheel and lobby; objective model and `objectives.yml`; room progress; exchange draw; settle-once; add a `mission` graphical scenario | `shadow_beast` runs floor-by-floor with live objectives, bar and room progress; the four other bundled routes are either playable or recorded as blocked with the exact missing wedge/floor; no invented layouts |
| 3. Cycle breaking | Move shared models, settings/news types and `WireTypes` to `:core`; replace protocol→world/storage/town with view contracts; remove storage→protocol, resources→world, resources/world→config/news, world→protocol, server↔web | No forbidden **package** edge under the Phase 3 rules, checked with `jdeps`/bytecode; no behaviour change. Gradle enforcement is Phase 6 |
| 4. Concurrency contract | Split current/target lock order and add `CharacterStore`, `SessionOutput`, `ServerControl` and the registry; add the eight measurements; bound or refactor every commit-under-lock path; platform-thread storage executor and single writer service; byte-based output budget and overflow policy; scene scheduler with active-scene cap, town sharding, failure/recycle and shutdown contracts; clock fix | The eight measurements exist on the recorded load; budgets defined; lock-order, commit-path, overflow, active-scene/town-sharding, scene-failure and shutdown tests green |
| 5. Persistence reset | `VERSION` to `0.42.0`; baseline marker, schema 1, repositories, migration runner, backup, SQLite settings, remove pre-`0.42.0` upgrade paths, real fixtures for every refused version | Historical versions refused untouched; marker/migration/backup tests green; a 0.41.x build refuses the new baseline |
| 6. Module extraction | Create the seven Gradle projects and enforcement; keep one shaded JAR; preserve `testUnit`/packaging paths | Exactly one JAR; dependency check green; packaging integration green |
| 7. Session and codec split | `WireContext`, cross-feature synthetic goldens, router and feature handlers, `WorldSession` facade, per-feature codecs | Per-feature session and wire tests green |
| 8. Cutover | Ship v2 content; delete the compatibility paths that the new modules supersede, keeping the v1 mission reader; update `docs/architecture.md`, `docs/content.md`, `docs/operations.md`; tag `0.42.0` | Release build uses v2 content and the new baseline; no canonical doc still describes schema 18 or the pre-`0.42.0` mission layout except the one-release compatibility note; v1 still boots read-only |
| 9. Post-0.42.0 systems | Arcade, guild hall, PvP, events and Shadow Lairs — each its own epic filling the reserved packages and folders; achievements already covers the verified predicates and keeps the remaining counters evidence-gated | Per-epic acceptance; no shared abstraction invented ahead of two real users |

Phases 3–7 are separate changes. Combining them hides correctness and performance
regressions. Phase 9 is explicitly outside the 0.42.0 definition of done.

## Definition of done for `0.42.0`

- `./gradlew build` yields exactly one `tortodrone.jar`.
- Content is v2 for missions, with the existing shared roots unchanged, v1 still
  readable for one release, and `systems/` reserved folders and packages present and
  documented.
- Every route that is startable from shipped content is playable with objectives,
  mission bar, room progress and one settlement — at minimum `shadow_beast`. The other
  four bundled routes are either playable or recorded as blocked with the exact
  missing wedge or floor. “108 playable” is not claimed. Live objective progress and
  `objectives.yml` remain blocked on the stock progress/completion packets: the bar
  and room progress are live, the objective line is static.
- Database uses the new baseline marker, schema 1, no pre-`0.42.0` upgrade, and a
  versioned migration runner for future releases.
- Seven modules with no forbidden dependency edge.
- `WorldSession` is a facade; feature handlers have their own session tests, the
  facade has a byte-for-byte cross-feature golden, and each per-feature codec pins
  its own frame directly.
- Wire layouts are byte-verified with synthetic fixtures.
- Scene ownership, written lock order, commit-before-publish and one-writer storage
  hold; the control, registry, character and output monitors are instrumented and the
  named commit-under-lock paths are bounded and tested.
- Tick, queue, storage and shutdown budgets are measured and reported.
- Headless startup, console, WebUI, News and single-JAR deployment still work.
- No pre-`0.42.0` user data is migrated or silently changed.
- No client assets, captures, decompiled code or proprietary tables are shipped.

## What this plan deliberately does not do

- Rewrite the simulation before profiling proves it necessary.
- Split one scene tick across threads, or add a thread per entity.
- Migrate the standalone test runner to a framework for its own sake.
- Introduce a second database, a message broker, a service mesh or a desktop admin
  application.
- Move the existing shared content roots in this release.
- Invent quests, rewards, dialogue, packet layouts or mechanics to fill a folder.
- Copy another server's implementation. Public captures, stock-client behaviour and
  the wiki are evidence for interoperability, not source material.
