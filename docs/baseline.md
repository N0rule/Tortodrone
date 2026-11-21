# Phase 0 baseline (0.42.0 redesign)

This is the fixed starting point for the redesign's measurements and evidence gates.
Behaviour is defined by source and tests; this file records what the current build
and tools actually produce. It is not a milestone diary.

Recorded on 2026-11-21 from commit `3e8f15d3be10e7b59d27e11b5bdbcc845339c7c6`
with `VERSION` `0.41.16` (not bumped in Phase 0). One pre-existing working-tree edit
to `LoadBenchmark` was present during the measurements; it does not change the
recorded constants below. Host/JDK: Temurin OpenJDK 25.0.5, Linux 7.3.5 x86_64,
Intel i5-8250U (4 cores/8 threads), 15 GiB RAM.

## Artefacts

| Item | Value |
| --- | --- |
| `content/` files | 485 (all tracked; no content changes) |
| `content/` bytes | 112,609,511 |
| `content/` tree SHA-256 | `8f421b751a94917fae7601e2ee61f26dc86ddc0377799bf969d4e8aa659a7737` |
| `build/tortodrone.jar` bytes | 15,780,467 |
| `build/tortodrone.jar` SHA-256 | `8e4cc15474749375e7e48e83710f34804caf2c755733cbce9e6ba02b0b14debb` |
| JARs at `build/` depth 1 | one: `tortodrone.jar` |

The tree digest is the SHA-256 of the sorted per-file `sha256sum` listing, with
paths relative to `content/`:

```sh
(cd content && find . -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum)
sha256sum build/tortodrone.jar
find build -maxdepth 1 -name '*.jar'
```

`./gradlew build` was green (`BUILD SUCCESSFUL`) before these values were read. The
JAR is reproduced from this commit by `./gradlew build`; it is not committed.

## Tick baseline

Existing tool: `./gradlew benchmarkScenes --args=10000` (no sockets or accounts). Each
line is the second pass; microseconds and allocated bytes per operation:

| Scene (actors) | interaction | collision | advance | snapshot |
| --- | --- | --- | --- | --- |
| 1 Haven (43) | 4.23 µs / 469 B | 1.73 µs / 233 B | 15.52 µs / 4,986 B | 10.19 µs / 11,424 B |
| 318 (25) | 2.49 µs / 42 B | 1.41 µs / 232 B | 10.78 µs / 3,128 B | 3.35 µs / 5,160 B |
| 294 (218) | 1.99 µs / 346 B | 10.82 µs / 964 B | 65.24 µs / 22,981 B | 17.13 µs / 42,024 B |

Source-verified structure behind these numbers:

- Each active scene owns one **virtual-thread** single-thread scheduled executor and
  runs `scheduleWithFixedDelay(..., 50, 50, MILLISECONDS)`
  (`SceneSimulation.start`). The tick and snapshot capture happen under that scene's
  monitor; the executor re-arms after the task returns, and publication callbacks run
  after the monitor is released.
- `MonsterMetrics` keeps a 256-sample tick window and exposes p50/p99, but only as part
  of the scene status object. No sampler records cadence lateness, lock wait,
  publication/delivery time or per-tick allocation.
- The existing load tool cannot provide a live scene tick series: its town workload
  fails before subscription (see Queue below). Scene metrics are emitted through
  `System.Logger` at DEBUG with no JUL configuration, so the shipped server log does
  not record them either.

Not baselined, owned by Phase 4: lock wait, tick work/lateness p50/p99/max under the
defined load, publication/delivery time, GC/allocation, overrun policy.

## Queue and session baseline

Existing tool: `./gradlew benchmark --args="--seconds 20 --clients 16 --session-only"`
(16 is the shipped `max-clients` default; the tool hardcodes `-Xmx512m`).
CSV header and row:

```text
clients,workload,seconds,ready,completed,failed,login_p95_ms,ping_p50_ms,ping_p95_ms,ping_p99_ms,ping_samples,deltas_per_client_s,input_frames,downstream_mib,server_cpu_cores,server_peak_rss_mib,server_peak_threads,instances
16,session,20,16,16,0,2308.01,0.86,1.24,2.07,320,0.00,0,0.01,0.08,1083.73,67,1
```

All 16 clients authenticated and logged off cleanly with no failures. Login p95 is
dominated by the unchanged 600,000-iteration password verification. The run produced
no scene deltas or input frames, so it does not exercise scene output or storage
writes beyond session setup.

Source-verified bounds:

- `SessionOutput` holds at most **64 frames** per session in an `ArrayBlockingQueue`;
  a frame may be up to 1 MiB downstream. A full queue fails the session with
  "Downstream queue full" rather than blocking the scene.
- Delivery uses one `SceneDelivery-<oid>` virtual thread and three in-memory
  collections (`pending`, `activityPublications`, `partyEvents`). The two deques are
  unbounded; overflow is signalled by `partyOverflow`/`publicationOverflow` flags.
- There is no queue-depth-in-frames, queue-depth-in-bytes, overflow, throttle,
  disconnect or largest-frame counter.

Town/scene workload is **not measured**: the only existing load tool cannot enter a
scene. Reproduced with the smallest case:

```text
./gradlew benchmark --args="--seconds 5 --clients 1 --instances 1"
```

Every client fails with `IOException: Missing scene subscription`. `LoadBenchmark`
subscribes to template id `10 + (id+1) % instances`, but Haven is template id `1` and
a scene subscription is matched against the live instance OID (`world.ownsScene`,
`ClientSession`), which the server never advertises as a template id. The benchmark's
`FIRST_SCENE` is stale; this is a tool defect, not a server failure. Fixing the
benchmark is not Phase 0 work and would be its own change.

Not baselined, owned by Phase 4: per-session frame/byte depth, overflow/throttle/
disconnect counts, largest frame, retained snapshots, heap per slow reader.

## Database baseline

Source-verified settings and write path:

- SQLite schema 18; WAL enabled at initialization; `foreign_keys=ON` and
  `busy_timeout=5000` per connection; a fresh connection for every `read`/`write`;
  writes use `BEGIN IMMEDIATE`.
- Every write calls `InventoryExpiry.prune(...)` before the caller's work
  (`Database.write`), so the global expiry sweep is on the write path.
- `synchronous`, `wal_autocheckpoint`, `journal_size_limit` and `cache_size` are not
  set and therefore inherit SQLite/JDBC defaults.
- `Database.diagnostics()` reports operations, operations ≥100 ms, failures,
  rollbacks and maximum duration from static counters. It is exposed only through the
  WebUI `status` response; no CLI task prints it.
- The game path and the WebUI/admin path each construct their own `Database` on the
  same file (two instances, `Server.run` and the enclosing `AdminService` account
  store).

No transaction-latency, `SQLITE_BUSY`, WAL-size, checkpoint or sweep-cost baseline was
captured: the load tool's scene workload is unusable (above), and the session-only
run exits before the WebUI status can be read, so the counters have no recorded
sample. The queue/tick sections state the same limit. Phase 4 owns these
measurements; this file does not claim them.

## Thread inventory (verified against source)

| Thread / executor | Count | Kind | Owner and bounds |
| --- | --- | --- | --- |
| `ServerMain` | 1 | platform | Accept loop; acquires a semaphore slot per connection (`max-clients`, default 16) |
| `Client-<id>` | per connection | platform | `ClientConnections`; one handler per accepted socket, joined on shutdown after sockets close |
| `<Client>-Output` | per session | platform | `SessionOutput`; drains the 64-frame queue and writes the socket |
| `SceneDelivery-<oid>` | per selected session | virtual | `WorldSession.deliverPublications`; drains party/activity publications |
| `Scene-<oid>` | per active scene | virtual | Single-thread scheduled executor, fixed 50 ms delay; stops when the instance is idle |
| `ServerConsole` | 1 | platform | Terminal command reader |
| `AccountSessions` | 1 | platform daemon | 250 ms account/auth-revision watcher; performs a database read per attached session |
| `WorldStateRefresh` | 0–1 | platform daemon | Batched 250 ms world/social poller while sessions are registered |
| `AuctionExpiry` | 1 | platform daemon | 30 s auction/mail expiry writer |
| `HTTP-*` | 4–16 | platform pool | Shared News/WebUI listener; 64-slot queue, `AbortPolicy` |
| `webui-metrics` | 0–1 | platform daemon | Five-second metric samples retained for one hour |

JDBC has no dedicated thread. It runs on whichever thread calls `Database`: client
platform threads, the watcher/poller/expiry daemons, HTTP workers, and the session
thread inside the documented commit-under-lock paths (for example
`SceneSimulation.revive`, whose Javadoc says it runs on the requesting session
thread). The scene tick itself performs no JDBC; the storage calls that hold a scene
monitor are made by the requesting session thread.

## Lock inventory (verified against source)

| Owner | Lock / mechanism | Notes |
| --- | --- | --- |
| `ServerControl` | one monitor | Lifecycle state, listener, console, resource snapshot. `snapshot()` holds it while reading sessions and all scene statuses |
| `SessionRegistry` | `ConcurrentHashMap` + `LongAdder` | No blocking lock; immutable snapshots |
| `AccountStore` | one monitor + per-account attachment object | Login/takeover serialisation per account; the watcher calls `current()` (a DB read) every 250 ms |
| `CharacterStore` | one monitor | Serialises roster I/O; listeners are called under it and only enqueue bounded frames |
| `WorldSession` | session monitor + `publications` monitor | Session state, then delivery mailbox; `SessionOutput` has its own monitor/queue |
| `SceneRegistry` | one monitor | Instance/OID allocation; `statuses()` enters each simulation monitor |
| `PartyRegistry` | one monitor | Party membership/view plus `soloGate`/`revive` transactions run under it |
| `SceneSimulation` | one monitor per instance | Tick, membership, entity mutation and snapshot capture. Named commit paths call storage under it; recipients are called after release |
| `Database` | none shared | Connection-per-call, WAL, `busy_timeout=5000`; `server.lock` file lock guards startup/upgrade |
| `ServerLog` | one monitor | Bounded file writes/rotation |

Target order in the plan is `session registry -> party -> scene -> entity
collections`. Current code is not that order everywhere: party work can nest scene
work on the input path, listener callbacks run while holding `CharacterStore`'s
monitor, the control snapshot walks control then every scene monitor, and the
commit-under-lock paths hold scene or party monitors across storage. Those
exceptions are named in the plan and stay Phase 4 work.

## Plan/code discrepancies found in Phase 0

1. The plan's inventory says the scene tick runs "on the shared `ForkJoinPool`"; the
   code creates a virtual-thread `ScheduledExecutorService` per scene
   (`SceneSimulation`). There is no shared scheduler or active-scene cap today.
2. The plan describes "two additional queues" for delivery without saying they are
   unbounded deques; `SessionOutput`'s 64 slots are the only bounded queue.
3. The plan requires tick/DB/queue baselines "using existing tools"; the existing
   tools expose scene hot-path cost and session ping/bytes/threads, but no tick
   cadence series, DB latency counters or queue depth, and the load tool's scene
   workload is broken. This file records that gap instead of inventing numbers.

## Required wire evidence and its source

Evidence rules: installed-client classes may be read read-only by the
`scripts/compat/` oracles; test bytes are synthetic and sanitized; no capture, asset
or decompiled file is committed. A layout without evidence is a Phase 2 blocker.

| Area | Evidence needed | Existing source and status |
| --- | --- | --- |
| MissionObject, settlement dialog, rank/prestige | Served object fields, completion entry/dialog and bound reward references | `scripts/compat/MissionProtocolCompatibility.java` reads the installed native reader and applies events (`enumPolicy=ORDINAL`); verified serialization only |
| Objectives | `Objective`, `ObjectiveConfig.Simple`, `Persistence`, stable set/index behaviour | `scripts/compat/ObjectiveCompatibility.java` (installed reader); verified serialization only |
| Mission catalogue/requirements/rewards/conversations | Native `MissionConfig`, `ConversationConfig`, reward spawn tables | `scripts/compat/OpeningMissionCompatibility.java` (read-only config oracle) |
| Comlink dialogue panels | Bundle/name/message/model/UI/music/choices | `scripts/compat/MissionProtocolCompatibility.java`; twelve First Contact B / Welcome to Haven panels verified |
| Interior party, gate summary, scene object, Dungeoneer deltas | Native party capacity/leader, gate fields/wedges, place and status layouts | `scripts/compat/InteriorCompatibility.java`, `PartyCompatibility.java`; `TestWire` mirrors the gate/party snapshot schema |
| Terrain/floor/platform and entrances | Native `getFloorZ` parity and authored floor placeables | `scripts/compat/SceneFloorCompatibility.java` (installed client oracle) |
| Tutorial floors and Haven handoff | Final-floor report, callbacks, rejection of premature/foreign completion | `scripts/compat/TutorialFloorCompatibility.java` (stock-reader plus packaged session) |
| Opening gate mechanics | Pinned lobby/floor wedge chain and control mechanics | `scripts/compat/OpeningGateCompatibility.java`; only `shadow_beast` has the complete lobby-to-floor chain |
| Exchange draw | Native exchange object, RPC layouts, order book/own offers | `scripts/compat/ExchangeCompatibility.java` (installed reader) |
| **Mission bar** | Native HUD mission-bar state and update path | **Missing.** No codec, no compat oracle and no live observation exists. Needs the installed client's mission HUD reader plus one observed stock mission; do not invent fields |
| **Gate map** | Mission map UI state and its update/lifecycle | **Missing** as a mission feature. Gate summary layout exists (above), but mission-map behaviour has no oracle |
| **Party config** | Lobby party configuration and mission party selection | **Missing.** `PartyCompatibility` covers `LevelPartyObject` membership/vitals, not a mission lobby configuration |
| **Reward wheel** | Wheel definitions, wedge selection and result framing | **Missing.** No codec or oracle; needs the stock client's wheel config/behaviour and one observed spin |
| **Lobby wedge chain** | Lobby scene and each floor present for a route | The plan records only `shadow_beast` (lobby 351; floors 380, 382, 384) with the complete chain; the other four bundled routes are recorded as blocked |
| Cross-feature golden stream | Byte-identical combined stream across features | **Scaffolding only.** `WireFixture`/`SyntheticWireFixtureTest` (Phase 0); the actual goldens are Phase 7 work |

## Refresh procedure

Run only when this baseline must be replaced, not per change:

```sh
./gradlew build
./gradlew benchmarkScenes --args=10000
./gradlew benchmark --args="--seconds 20 --clients 16 --session-only"
(cd content && find . -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum)
sha256sum build/tortodrone.jar
```

The town/scene workload cannot be recorded until `LoadBenchmark` is repaired; do not
report a scene tick/DB/queue measurement from it before then.
