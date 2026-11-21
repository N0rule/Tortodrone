# Redesign plan

Target: a modular monolith — one process, one executable JAR, explicit compile-time
boundaries, mission-owned content with shared assets in one place, and concurrency
that is measured rather than assumed.

This is a design and sequencing document. It describes the intended end state and
the order of work. Current behaviour is defined by source and tests, not by this
file.

This is a **full redesign**, not a feature patch. Code moves into six modules, the
three god classes are split by responsibility, persistence is rebuilt at schema 1,
content becomes mission-owned, and every unimplemented gameplay system gets a
reserved code package and content folder now — even when its implementation is
several releases away. The phases differ in risk and order, not in ambition.

**Release target: `0.42.0`** — a behavioural release with a new database schema and
content format. It is not `1.0.0`. Pre-`0.42.0` user data is not migrated in this
release; explicit migrations are added for later releases.

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
8. No network or database I/O inside a scene tick or a scene/storage lock.
9. The runtime reads only project-owned formats. Installed client data, extracted
   assets and decompiled material never ship.
10. Verification stays focused: targeted `testUnit` programs and one graphical
    scenario only when the change is client-visible.

## Why the build uses Shadow

The project already packages with the Shadow plugin (`com.gradleup.shadow`), which
merges application classes and runtime dependencies into one executable archive
with a `Main-Class` manifest.

Benefits that matter here:

- **One file is the deployment contract.** Copy or replace one JAR; there is no
  `lib/` directory to drift, partially update or lose during backup.
- **Atomic update and rollback.** A failed release is undone by restoring one file,
  not by reconciling a directory of dependency JARs.
- **Dependency versions are pinned in the artifact.** The server cannot accidentally
  run against a different SnakeYAML, JLine or SQLite JDBC version present on the
  host.
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
- **No licence packaging step.** Release packaging does not add project licence or
  notice files. Bundled dependencies keep their own terms; this plan neither
  relicenses them nor adds enforcement around them.
- **One archive, not one class loader.** Shadow does not isolate dependencies. If
  two libraries ever need conflicting versions, relocate or remove one; do not add
  a second class loader.

Alternatives rejected:

| Alternative | Why not |
| --- | --- |
| Application distribution with `lib/` | Violates the single-JAR contract and makes partial updates possible. |
| Bootstrap JAR that downloads libraries on first run | Adds a network dependency to startup, allows version drift, breaks air-gapped hosts and turns a supply-chain failure into a server that will not boot. With three runtime dependencies there is nothing to gain. |
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

A download-on-first-run bootstrap is deliberately not used. Minecraft server
launchers do it because their dependency graphs are large and change often. Here the
runtime is three libraries and the current archive is about 16 MiB, so downloading
them would add startup failure modes, version drift and an air-gap problem without
any meaningful size benefit. Ship the dependencies inside the one JAR.

## Concurrency model

### Current behaviour

| Path | Today |
| --- | --- |
| Client accept | Main thread accepts; one platform thread per connection. |
| Session output | One writer platform thread per session; bounded queue of 64 frames; a full queue closes the session. |
| Scene tick | One virtual thread per active scene; fixed 50 ms delay; the whole tick and snapshot capture run under the scene monitor. |
| Parallelism | Across scenes only. A single scene is never ticked concurrently. |
| Storage | A connection per transaction; WAL enabled; session threads perform storage calls outside scene ticks. |
| HTTP | Shared WebUI/News listener. |

This is already the right shape: **one owner per scene, parallel across scenes**.
The weaknesses are lifecycle and measurement, not the basic model:

- one platform thread per connection plus one writer thread per connection;
- scene virtual threads run CPU-bound ticks on the shared carrier pool, so active
  scene count and CPU cost are coupled;
- per-tick snapshot capture allocates under the scene monitor;
- no explicit budget for tick p99, queue depth or storage wait.

### Target rules

- **Connections:** virtual threads for blocking reads, with a bounded connection
  limit and explicit cleanup. A slow or dead client must never block a scene.
- **Output:** bounded per-session queue and a dedicated writer path. Producers
  enqueue and continue; a full queue throttles or disconnects that client only.
  Batch small frames per flush where measurement shows a win.
- **Scenes:** an explicit scheduler with a bounded carrier pool and one serial
  execution lane per scene. Fixed 50 ms cadence, no burst catch-up. A scene that
  overruns its budget is logged with template, instance and p99.
- **Tick contents:** fixed system order (movement, status, combat, portals,
  elevators, capture), no parallel streams, no blocking calls, no storage or
  network access, reusable scratch buffers.
- **Publication:** build an immutable snapshot after mutation, release the scene
  lock, then deliver. Recipients never observe a partially updated scene.
- **Storage:** one writer at a time, WAL, busy timeout, batched transactions.
  Never hold a scene or party lock while a transaction is open.
- **HTTP:** one listener, virtual-thread request handling, no shared gameplay
  locks.
- **Party and travel:** keep generation counters for subscriptions and travel.
  A stale generation is dropped, never replayed into the new scene.

Lock order, once written down, is enforced by review and tests:

```text
session registry -> party -> scene -> entity collections
```

Storage is never acquired while holding any of them.

**Do not:** tick one scene on multiple threads, create a thread per monster or per
entity, run asynchronous database work inside a tick, or add a global gameplay
lock. Parallelism is across independent scenes and connections.

**Measure before changing:** tick p99/worst case at a defined actor load, carrier
utilisation, output queue depth, storage wait, heap per scene and per session, and
snapshot bytes per client. The scheduler rewrite is justified only by those numbers.

## What is actually missing

Evidence at the time of writing: 273 catalogue missions (108 rank, 147 prestige,
3 expansion, 15 hidden), 53 imported gate routes with one fully playable beyond the
pinned opening gate, and 251 standalone test programs. The stock-client catalogue,
live captures and the public wiki show these unimplemented areas.

| System | State | Priority |
| --- | --- | --- |
| Mission objectives, mission bar, room-level progress | Absent; settlement works, `MissionObject` is empty | P0 |
| Gate map, adventure board, party difficulty/depth | Absent | P0 |
| Gate-conversation lobbies, exchange daily draw | Absent or partial | P0 |
| End-of-mission reward wheel | Absent; prize boxes exist | P1 |
| Prestige missions, rotations, badges | Catalogue imported, not runnable | P1 |
| Arcade / Clockworks floors and generation | Absent; only the Haven hub exists | P1 |
| Guild hall, expansions, furniture, upkeep | Absent | P2 |
| Achievements and leaderboards | Empty DSets | P2 |
| PvP: Lockdown, Blitz, Coliseum, Crucible | Only loadout flags | P3 |
| Events, Uplink, casino | News only | P3 |
| Shadow Lairs | Absent | P3 |

Implemented and out of scope for this plan: accounts, knights, inventory, equipment,
combat controls, battle sprites and harnesses, pets, mail, trade, auction, exchange,
guild formation/roster/ranks, friends, parties, chat, shops, forge, Depot, prize
boxes, News, WebUI administration and the opening route.

## Target code architecture

Six Gradle modules, shaded into the existing single JAR. Fewer modules than a
theoretical maximum, because every module must remove a real dependency cycle.

```text
:app          packaging, composition root, build and release tasks
:core         kernel, domain models and contracts, content schemas/loaders
:protocol     handshake, decoder, wire codecs over domain views
:world        scene templates, instances, simulation, collision, portals
:gameplay     storage plus application services (mission, trade, auction, mail,
              guild, town, loot)
:server       process lifecycle, sessions, console, WebUI, News, artwork
```

Dependency direction:

```text
:core
  ^
:protocol   :world   :gameplay
  ^          ^         ^
  +----------+---------+
             |
          :server
             ^
           :app
```

Rules:

- `:protocol` imports `:core` only. It encodes read-only views, not live scenes or
  storage objects.
- `:world` imports `:core` only. It never imports storage, protocol or server.
- `:gameplay` coordinates `:core`, `:world` and persistence. Persistence may stay
  inside `:gameplay` until it justifies its own module.
- `:server` translates requests and owns transport, not game rules.
- `:app` wires everything; nothing imports `:app`.
- An architecture check fails the build on a forbidden package dependency.
- No JPMS. Shaded modular JARs add friction without enforcing more than Gradle
  project dependencies already do.

Hot classes are split by responsibility, not by line count:

```text
server/session/     SessionRuntime, InvocationRouter, SessionResponder, handlers/*
world/scene/        SceneRuntime, EntityWorld, SnapshotBuilder, systems/*
protocol/           SessionCodec facade + SceneCodec, PartyCodec, MissionCodec,
                    TradeCodec, AuctionCodec, MailCodec, GuildCodec, TownCodec
gameplay/mission/   catalogue, settlement, gate, comlink, exchange,
                    objective/, prestige/, gateconversation/, missiontest/
```

Reserved packages contain `package-info.java` stating the contract, never invented
classes.

## Target content architecture

```text
content/
  format.txt
  shared/
    items/
    loot/
    monsters/
    news/
    town/
    presentation.yml
    maps/
      level/<scene>/
      town/<scene>/
      training/<scene>/
    missions/
      comlink/           reusable dialogue fragments and actors
      objectives/        shared objective definitions
      gate-lobbies/      shared lobby conversations
  missions/
    shadow_beast/
      mission.yml         required; the row id must match the folder
      rewards.yml         optional
      comlink.yml         optional
      route.yml           optional
      objectives.yml      reserved until the objective subsystem lands
      tests.yml           reserved until mission tests land
      gate-conversation.yml  reserved until lobby conversations land
      exchange.yml        reserved until exchange rules need their own record
  systems/
    arcade/               reserved; README only
    guildhall/            reserved; README only
    pvp/                  reserved; README only
    events/               reserved; README only
    lairs/                reserved; README only
    achievements/         reserved; README only
  _internal/
    default-source.properties
    provenance/
```

Rules:

- A mission folder owns mission-specific records only. Reused maps, items, monsters,
  towns, loot and dialogue fragments stay in `shared/`.
- A shared scene is never copied into a mission folder. Lobby `351` serves dozens of
  routes; duplication would make one map fix require dozens of edits.
- Each file keeps its existing section schema (`missions:`, `grants:`, `comlinks:`,
  `routes:`), so validation logic does not change — only the partition.
- Unknown files are rejected. Reserved files are accepted only when their subsystem
  exists. Reserved directories contain a `README.md`, not empty YAML with invented
  fields.
- Rank, prestige, expansion and hidden remain mission families in `mission.yml`,
  not separate parsers.
- The loader auto-detects v1 and v2 layouts and refuses a mixed tree. Both produce
  the same immutable catalogue, comlinks and gates.
- The manifest pins one digest per mission folder, with per-file ownership inside
  it, so editing one mission yields one precise conflict.
- Importers are the only writers of the new layout, and support `--check`.
- Catalogue equivalence between v1 and v2 is the gate for deleting v1.

### What `format.txt` is

`content/format.txt` is a one-line layout version marker, not a manifest, catalogue
or checksum file. Its whole content is the version the tree uses:

```text
2
```

The loader reads it before walking `content/` and uses it to choose the parser:
`1` is the current split-file layout (`rank.yml`, `grants.yml`, `comlinks.yml`,
`routes.yml`), `2` is the per-mission folder layout. It is explicit instead of
inferred from directory shape, so:

- a half-migrated tree fails immediately instead of being guessed at;
- a future format `3` can be added without changing the detection rules;
- an unknown or missing version is refused with a clear operator message;
- the version is independent of the per-file provenance manifest, which answers
  "who wrote this file", not "how is this tree shaped".

`format.txt` is not user-editable content. Importers and the content migration task
write it; startup only reads it.

## Database

SQLite remains the correct choice: single node, no external service, ACID economy,
WAL readers, one-file backup and a mature JDBC driver already inside the JAR.

Target:

- Schema **1** for `0.42.0`.
- `STRICT` tables, integer primary keys, explicit foreign keys, covering indexes.
- Hand-written SQL and prepared statements; no ORM.
- Repositories behind application services; `Database` owns open/schema/transaction
  boundaries only.
- Normalized item instances, equipment, escrow, mail attachments, auction lots and
  guild membership.
- Catalogue definitions stay in content, not the database.
- WAL, `foreign_keys=ON`, busy timeout, explicit checkpointing, online backup.
- Batch related writes in one transaction; commit before publishing events.
- Unknown existing database: refuse startup with an actionable message. Never
  auto-migrate, auto-delete or silently open pre-`0.42.0` data.

Migration policy:

- `0.42.0` is a clean baseline. Data from earlier schemas is not read or converted.
- From `0.42.0` onward, schema changes use explicit, ordered, versioned migration
  steps with a recorded schema version, run in one transaction, and refuse unknown
  versions instead of guessing.
- The migration runner exists from the first release even though this release has
  nothing to migrate. Adding it later would force another data reset.
- Downgrades are not supported. A backup is taken before an upgrade, and an
  operator must archive or remove pre-`0.42.0` data deliberately.

PostgreSQL is revisited only if measured multi-node or concurrency requirements
appear. The repository boundary keeps that possible.

## Verification

- Unit programs stay standalone `main` programs under the existing runner. Do not
  migrate 251 programs to JUnit as part of this work.
- Selected runs: `./gradlew testUnit -PunitTests=A,B`.
- Compile/package check: `./gradlew build`.
- Wire changes: structural decoding plus byte-golden fixtures captured before the
  codec split.
- Storage changes: ownership, rollback, escrow and concurrent trade/auction/mail
  tests; commit-before-publish.
- Content changes: v1/v2 catalogue equivalence, then v2 snapshot hashes.
- Architecture: dependency check per module.
- Concurrency: scene tick budgets and lock-order tests; benchmarks are opt-in.
- Client-visible changes: one relevant graphical scenario, never routine.
- `testAll` only with explicit owner approval.

## Phases

| Phase | Work | Exit gate |
| --- | --- | --- |
| 0. Baseline | Content snapshot hash, dependency report, tick/DB/queue baselines, wire golden fixtures, this ADR | Current `build` and selected tests green |
| 1. Content v2 | Per-mission folders, `shared/` root, reserved `systems/` folders, v1/v2 loader, importer and manifest changes | v1/v2 catalogues identical; validation green |
| 2. Mission runtime | Objective model and `objectives.yml`, mission bar/DSet, room progress, gate map, party config, gate-conversation lobbies, exchange draw, reward wheel | One rank mission runs floor-by-floor with live objectives and settles; one graphical scenario |
| 3. Boundaries | Move code into the six modules, introduce domain view contracts, break the known cycles, add the architecture check | Exactly one JAR; dependency check green; no behaviour change |
| 4. Persistence reset | Schema 1 baseline, repositories, transaction services, online backup, versioned migration runner (no pre-0.42.0 upgrade) | Storage, trade, auction, mail and inventory tests green |
| 5. Session and codec split | Router and feature handlers, `WorldSession` facade, per-feature codecs over golden fixtures | Per-feature session and wire tests green |
| 6. Concurrency hardening | Explicit scene scheduler, connection/output lifecycle, budgets and profiling, allocation reduction | Tick and queue budgets met under the defined load |
| 7. New systems | Arcade, guild hall, PvP, events, Shadow Lairs, achievements — each its own epic with reserved folders already in place | Per-epic acceptance; no shared abstraction invented ahead of two real users |
| 8. Cutover | Delete v1 mission reader, legacy schema paths and compatibility shims; bump `VERSION` to `0.42.0`; package one JAR | Release build uses only v2 content and schema 1 |

Phases 4–6 are separate changes. Combining them hides correctness and performance
regressions.

## Definition of done for `0.42.0`

- `./gradlew build` yields exactly one `tortodrone.jar`.
- Content is v2, mission-owned, with shared assets centralized and reserved folders
  documented.
- The 108 rank missions have a room-level runtime: objectives, mission bar, progress
  and settlement.
- Database is schema 1, with no upgrade path from pre-`0.42.0` data and a versioned
  migration runner ready for later releases.
- Six modules with no forbidden dependency edge.
- `WorldSession` is a facade; feature handlers and codecs are independently tested.
- Wire layouts are byte-verified.
- Scene ownership, lock order, commit-before-publish and single-writer storage hold.
- Tick, queue and storage budgets are measured and reported.
- Headless startup, console, WebUI, News and single-JAR deployment still work.
- No pre-`0.42.0` user data is migrated or silently changed.
- No client assets, decompiled code or proprietary tables are shipped.

## What this plan deliberately does not do

- Rewrite the simulation before profiling proves it necessary.
- Split one scene tick across threads, or add a thread per entity.
- Migrate the standalone test runner to a framework for its own sake.
- Introduce a second database, a message broker, a service mesh or a desktop admin
  application.
- Invent quests, rewards, dialogue, packet layouts or mechanics to fill a folder.
- Copy another server's implementation. Public captures, stock-client behaviour and
  the wiki are evidence for interoperability, not source material.
