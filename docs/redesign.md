# Redesign plan

Target: a modular monolith — one process, one executable JAR, explicit compile-time
boundaries, mission-owned content with shared assets in one place, and concurrency
that is measured rather than assumed.

This is a design and sequencing document. Current behaviour is defined by source and
tests, not by this file.

This is a **full redesign**, not a feature patch. Code moves into enforced modules,
the three god classes are split by responsibility, persistence is rebuilt around a
new baseline, content becomes mission-owned, and every unimplemented gameplay system
gets a reserved code package and content folder now — even when its implementation
is several releases away. The phases differ in risk and order, not in ambition.

**Release target: `0.42.0`** — a behavioural release with a new database baseline
and content format. It is not `1.0.0`. Pre-`0.42.0` user data is not migrated in this
release. `VERSION` moves to `0.42.0` at Phase 0, before any schema or data-format
change, so a `0.41.x` build can never open the new baseline.

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
   existing commit-under-lock paths are named, bounded and tested; they are not
   claimed away.
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
- **Licence packaging is unchanged until the cutover.** `build.gradle.kts` embeds
  `LICENSE`/`NOTICE` and `portableZip` ships them today. The cutover removes that
  project packaging step as a build-only change; until then the plan does not
  pretend it is already gone.

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
| Session output | One writer platform thread per session; bounded queue of 64 **frames**; frames may be up to 1 MiB. |
| Session delivery | Per-session `SceneDelivery` virtual thread plus two additional queues. |
| Scene tick | One virtual thread per active scene; fixed 50 ms delay; the whole tick and snapshot capture run under the scene monitor. |
| Parallelism | Across scenes only. A single scene is never ticked concurrently. |
| Storage | A fresh SQLite connection per transaction; WAL; `busy_timeout=5000`; game and admin paths use **two `Database` instances on the same file**. |
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

### Target model

- **Storage:** one writer and a small number of readers on dedicated **platform**
  threads. JDBC never runs on a virtual thread; sqlite-jdbc uses native frames and
  can pin carriers. Game and admin share one writer service, not two `Database`
  instances on the same file.
- **Connections:** virtual threads for blocking reads, with a bounded connection
  limit that also bounds concurrent handshakes.
- **Output:** bounded by **bytes and frames** per session, not frames alone. Define
  the overflow policy explicitly: throttle, drop to a lower update rate, or
  disconnect. Encoding stays off the scene thread where possible.
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
- **Clock:** `SceneClock` is currently an `int` millisecond counter that wraps after
  roughly 24.8 days. Replace it with a 64-bit or wrap-safe clock before any soak or
  “measured” claim.

### Lock order

The working order is:

```text
session registry -> party -> scene -> entity collections
```

This is a target, not a description of every current call path. The party-to-scene
nesting on the input path and the commit-under-lock paths are known exceptions with
owners. `CharacterStore`, `SessionOutput`, `ServerControl` and the scene registry
must be added to the written order before the scheduler rewrite. Storage is acquired
outside all of them except the named commit paths.

### Metrics required before the scheduler rewrite

1. Per scene: lock wait, tick work, publication and delivery, and cadence lateness —
   p50/p99/max, sampled outside the scene monitor.
2. A defined load: clients, scenes, actors per scene, party sizes, and the workload
   mix (idle town, full interior, revive, owned-item use, elevator settlement) on a
   stated hardware and heap size.
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
- 17 map folders under `content/maps`.
- 251 `*Test.java` programs; 254 test files declare `main()` and run through the
  standalone runner.
- `build/tortodrone.jar` is 15,780,467 bytes (about 15 MiB).

**108 rank missions is not 108 playable routes.** Many routes still resolve to
unbundled scenes or live-only packs. The 0.42.0 runtime must make every route whose
floors are bundled playable, and keep unresolved routes hidden with an explicit
reason. It must not claim all 108 are playable.

Evidence rules:

- Layouts and behaviour come from observed stock-client behaviour and verified
  captures/configuration, never from invented packets or rewards.
- Captures are used to derive layouts. Test fixtures are synthetic and sanitized;
  client captures are never committed.
- A missing layout is a Phase 2 blocker, not a reason to guess.

## Target code architecture

### Current graph

The current code is not a six-module graph. The package graph contains a strongly
connected component spanning:

```text
account, character, loot, protocol, resources, storage, town, world
```

Known concrete cycles:

- `protocol` imports `world`, `storage`, `town` and `character` (47 world imports in
  `SessionCodec` alone);
- `storage` imports `protocol` (`AuctionHouse`, `BoxOpeningService`);
- `resources` imports `world` (`MissionComlinks`, `MissionGates`, `SceneConversation`,
  `FireCatalog`, and `SceneEntities.validateItemUse`);
- `server` imports `web`, while the WebUI is wired back into `Server`.

Therefore **module extraction is not the first step**. Phases 0–3 break these edges
in-package; only then can Gradle enforce them.

### Target modules

Seven modules, shaded into the existing single JAR:

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

`news`, `config` and `web` live in `:server`; `account`, `character` storage,
`storage`, `loot` opening and `town` services live in `:gameplay`; character, town
and loot **models** move to `:core`. `resources` becomes `:content`. Split packages
are expected: a package is assigned by extracted type, not by its current name.

### Cycle-breaking order

1. Move shared model types out of `world`, `storage` and `town` into `:core`: scene
   content specs, fire graphs, mission gates/comlinks/conversations, item and
   character read views.
2. Replace `protocol → world/storage/town` with `:core` view contracts. `SessionCodec`
   keeps a facade and receives views, not live scenes or profiles.
3. Remove `storage → protocol` by moving request/decoder-owned types to `:core` or
   handling them in `:gameplay`.
4. Move `SceneEntities.validateItemUse` out of the content path into `:world`
   validation.
5. Break `server ↔ web` by making the WebUI depend on server-owned interfaces, or by
   moving `AdminService` construction behind a server boundary.
6. Only then create Gradle projects and the dependency check. Use `jdeps`/bytecode as
   well as imports; fully qualified references hide edges.

### Codec split

`SessionCodec` is 1,980 lines with connection-global class and string interning.
Per-feature codecs cannot be independent without a shared `WireContext` holding the
class table, intern table and connection-local state. Before splitting:

- define `WireContext`;
- capture a synthetic cross-feature golden stream (no client captures);
- prove identical bytes for the combined stream, not only per-feature frames.

### God-class splits

- `WorldSession` → `SessionRuntime`, `InvocationRouter`, `SessionResponder` and
  feature handlers. Handlers receive a narrow context and never write sockets.
- `SceneSimulation`/`SceneEntities` → one shared mutable entity model plus
  `MovementSystem`, `CombatSystem`, `StatusSystem`, `PortalSystem`,
  `ElevatorSystem`, `SnapshotBuilder` and an explicit callback contract. The
  current ten callback sinks are part of that contract, not incidental wiring.
- `Database` → transaction/schema owner plus repositories. Every write currently
  runs an expiry sweep; the split must preserve or deliberately replace it.
- `CharacterStore` is a storage facade returning `storage.*` types. Split the model
  from the store before claiming a `character`/`storage` boundary.

Reserved packages contain `package-info.java` describing the contract:
`gameplay/mission/{objective,prestige,gateconversation,missiontest}`,
`world/arcade`, `gameplay/guildhall`, `gameplay/pvp`, `gameplay/events`,
`gameplay/lairs`, `gameplay/achievements`.

## Target content architecture

### Layout principle

The existing roots are **already the shared area**:

```text
content/items, content/maps, content/monsters, content/town, content/loot,
content/news, content/shared, content/_internal
```

They stay where they are in 0.42.0. Moving them under a second `content/shared/`
root would break `ContentManager`, `MapYaml`, `ItemYaml`, `TownYaml`, `BoxCatalog`,
`MapImport`, `ItemMigration`, `ContentPresentation` and `BuildTools`, and would make
`maps.getParent().resolve("shared")` resolve to `content/shared/shared`.

The redesign adds two things:

- `content/missions/<mission-id>/` for mission-owned records;
- `content/shared/missions/` for records shared by the catalogue.

```text
content/
  format.txt
  items/
  loot/
  maps/{level,town,training}/<scene>/
  monsters/
  news/
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
    achievements/        README only
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
  towns, loot and shared mission groups/properties stay in the existing roots.
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
- The v1 reader stays for one release. The v2 reader is recursive, removes the
  16-file cap and reads `content/shared/missions/groups.yml` and `properties.yml`.
- `_internal/` does not move. Its files are loader inputs, not provenance.

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
- Add `--check` to all four mission importers, not just `import_missions.py`. All
  writers use a temporary file/directory and atomic replacement; a partial write
  must not create a mixed tree.
- The manifest moves to v2 **per-file** `owned.*` and `source.*` keys, regenerated
  from the converted tree. A folder digest may exist as an optional summary, but it
  is not the conflict unit; one edited mission must yield one file conflict.
- Customized deployments: map each old path to its new path, preserve owner edits,
  and fail with an actionable list when a mapping is ambiguous. “Rebuild by hand” is
  a documented fallback, not the default.

### Equivalence gate

“Catalogues identical” is too narrow. The v1/v2 gate requires:

- per-domain semantic dumps (missions, groups, properties, grants, comlinks, gates);
- unchanged asset domains (items, maps, monsters, town, news, presentation);
- byte equality for `_internal/`;
- unknown/mixed/reserved-file rejection tests;
- converter idempotence and a boot test on the converted deployment;
- an ownership-placement diff for every moved mission record.

## Database

SQLite remains the correct choice: single node, no external service, ACID economy,
WAL readers, one-file backup and a mature JDBC driver already inside the JAR.

### Baseline marker

`SCHEMA_VERSION=1` cannot be used alone: historical schema 1 exists and is currently
refused by `DatabaseSchemaTest`. A pre-0.42.0 database must remain distinguishable
from the new baseline even if its `user_version` is 1.

Use a separate marker:

- `PRAGMA application_id` with a fixed project value, plus `user_version=1`; or
- a `meta(baseline=42)` row written before any other table.

Open rules:

| State | Behaviour |
| --- | --- |
| Marker present, schema 1 | Open. |
| No marker, `user_version` 1–18 | Refuse as pre-0.42.0; do not modify. |
| Marker present, unknown version | Refuse; do not guess. |
| Marker absent, empty file | Initialize the new baseline. |
| Unknown `application_id` | Refuse. |

`DatabaseSchemaTest` must be rewritten to assert all of the above, including that a
refused database is byte-identical afterwards.

### Migrations

- `0.42.0` is a clean baseline; no pre-`0.42.0` conversion.
- From the baseline onward, schema changes use ordered, versioned steps in one
  transaction, recorded in a migration table.
- Take a backup before an upgrade under the deployment lock.
- Use `PRAGMA foreign_keys=OFF`, run the steps, run `foreign_key_check`, then commit;
  roll back on any failure.
- Refuse unknown versions and downgrades. Never auto-migrate.
- The migration runner exists from the first release even though this release has
  nothing to migrate.

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
  migrate 251 programs to JUnit as part of this work.
- Selected runs: `./gradlew testUnit -PunitTests=A,B`.
- Compile/package check: `./gradlew build`.
- Wire changes: structural decoding plus **synthetic** cross-feature golden fixtures.
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
| 0. Baseline | Set `VERSION` to `0.42.0`; record content hash, JAR size, tick/DB/queue baselines; write the current thread/lock inventory; identify required wire evidence; add synthetic fixture scaffolding | `build` and selected tests green; baselines recorded |
| 1. Content v2 | Mission folders, `shared/missions/`, `format.txt`, recursive v2 reader, converter, per-file manifest, `--check` on all importers | v1/v2 mission domains identical; converted deployment boots; mixed/unknown trees refused |
| 2. Mission runtime | Wire evidence for MissionObject/objectives, mission bar, gate map, party config, reward wheel and lobby; objective model and `objectives.yml`; room progress; exchange draw; settle-once | `shadow_beast` runs floor-by-floor with live objectives, bar and room progress; one `mission` graphical scenario; no invented layouts |
| 3. Cycle breaking | Move shared models to `:core`; replace protocol→world/storage/town with view contracts; remove storage→protocol and content→world; break server↔web | No forbidden edge in the target graph; no behaviour change |
| 4. Concurrency contract | Split current/ target lock order; bound or refactor every commit-under-lock path; platform-thread storage executor; byte-based output budget; scene scheduler, failure and shutdown contracts; clock fix | The eight measurements exist; budgets defined; lock-order and commit-path tests green |
| 5. Persistence reset | Baseline marker, schema 1, repositories, one writer, migration runner, backup, SQLite settings, remove pre-0.42.0 upgrade paths | Historical schemas refused untouched; migration and backup tests green |
| 6. Module extraction | Create the seven Gradle projects and enforcement; keep one shaded JAR; preserve `testUnit`/packaging paths | Exactly one JAR; dependency check green; packaging integration green |
| 7. Session and codec split | `WireContext`, cross-feature synthetic goldens, router and feature handlers, `WorldSession` facade, per-feature codecs | Per-feature session and wire tests green |
| 8. Cutover | Delete v1 mission reader and compatibility shims; remove project licence/notice packaging; update `docs/architecture.md`, `docs/content.md`, `docs/operations.md`; tag `0.42.0` | Release build uses only v2 content and the new baseline; docs match |
| 9. Post-0.42.0 systems | Arcade, guild hall, PvP, events, Shadow Lairs, achievements — each its own epic using the reserved packages and folders | Per-epic acceptance; no shared abstraction invented ahead of two real users |

Phases 3–7 are separate changes. Combining them hides correctness and performance
regressions. Phase 9 is explicitly outside the 0.42.0 definition of done.

## Definition of done for `0.42.0`

- `./gradlew build` yields exactly one `tortodrone.jar`.
- Content is v2 for missions, with the existing shared roots unchanged and
  `systems/` reserved folders documented.
- Every rank route whose floors are bundled is playable with objectives, mission
  bar, room progress and one settlement. Unresolved routes stay hidden with an
  explicit reason; “108 playable” is not claimed.
- Database uses the new baseline marker, schema 1, no pre-`0.42.0` upgrade, and a
  versioned migration runner for future releases.
- Seven modules with no forbidden dependency edge.
- `WorldSession` is a facade; feature handlers and codecs are independently tested.
- Wire layouts are byte-verified with synthetic fixtures.
- Scene ownership, written lock order, commit-before-publish and one-writer storage
  hold; the named commit-under-lock paths are bounded and tested.
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
