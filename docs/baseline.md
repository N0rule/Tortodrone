# Phase 0 baseline (0.42.0 redesign)

This is the fixed starting point for the redesign's measurements and evidence gates. Its
schema numbers and upgrade notes describe the pre-0.42.0 build that was measured; the
0.42.0 persistence reset is recorded in [architecture](architecture.md#main-components).
Behaviour is defined by source and tests; this file records what the current build
and tools actually produce. It is not a milestone diary.

Recorded on 2026-11-21 from commit `3e8f15d3be10e7b59d27e11b5bdbcc845339c7c6`
with `VERSION` `0.41.16` (not bumped in Phase 0). The tick, DB and town/session
measurements below use the `LoadBenchmark` repair described under Queue; it is
test-only, so the artefact values still come from the same packaged sources.
Host/JDK: Temurin OpenJDK 25.0.5, Linux 7.3.5 x86_64, Intel i5-8250U (4 cores/8
threads), 15 GiB RAM.

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
- Scene metrics are emitted through `System.Logger` at DEBUG with no JUL
  configuration, so the shipped server log does not record them; the WebUI `status`
  response is the readable path.

Live tick work sampled through the WebUI `status` route during the 16-client town
run (two instances, eight players each):

| Run | tick p50 | tick p99 |
| --- | --- | --- |
| 30 s, 16 clients | 0.414 / 0.420 ms | 0.739 / 0.735 ms |
| 20 s, 16 clients | 0.92 / 0.92 ms | 18.5 / 25.2 ms |

These are per-tick work samples from the 256-sample window, not cadence lateness: the
scheduler re-arms after the task returns, so a late tick is not visible here. The p99
spread between the two runs is real and uncontrolled.

Recorded, owned by Phase 4: lock wait, tick cadence/lateness p50/p99/max under the
defined load, publication/delivery time, GC/allocation and the overrun policy — see
the Phase 4 measurements below.

## Queue and session baseline

Existing tool: `./gradlew benchmark --args="--seconds 20 --clients 16 --session-only"`
(16 is the shipped `max-clients` default; `--xmx` selects the server heap and defaults
to `512m`).
CSV header and row:

```text
clients,workload,seconds,ready,completed,failed,login_p95_ms,ping_p50_ms,ping_p95_ms,ping_p99_ms,ping_samples,deltas_per_client_s,input_frames,downstream_mib,server_cpu_cores,server_peak_rss_mib,server_peak_threads,instances,xmx
16,session,20,16,16,0,2308.01,0.86,1.24,2.07,320,0.00,0,0.01,0.08,1083.73,67,1,512m
```

All 16 clients authenticated and logged off cleanly with no failures. Login p95 is
dominated by the unchanged 600,000-iteration password verification. The run produced
no scene deltas or input frames, so it does not exercise scene output or storage
writes beyond session setup.

Source-verified bounds:

- `SessionOutput` holds at most **64 frames** and **4 MiB** per session in a bounded
  `ArrayDeque`; a reliable frame that would exceed either limit fails the session with
  "Downstream queue full" rather than blocking the scene. Optional update frames are
  skipped before encoding once the queue is over budget, and a client that stays over
  budget for 5 s is disconnected.
- Delivery uses one `SceneDelivery-<oid>` virtual thread and three in-memory
  collections (`pending`, `activityPublications`, `partyEvents`). The two deques are
  unbounded; overflow is signalled by `partyOverflow`/`publicationOverflow` flags.
- `SessionOutput.Metrics` records process-wide frames, bytes, largest frame, optional
  drops, reliable overflows, budget disconnects, and peak pending frames/bytes. The
  counters are observation only and never change policy.

Town/scene workload with the repaired tool:

```text
./gradlew benchmark --args="--seconds 20 --clients 16 --instances 2"
16,town,20,16,16,0,2475.81,0.35,0.76,3.09,320,19.66,3136,2.48,0.37,1004.00,84,2,512m
```

All 16 clients selected a knight, left the Ready Room, entered a live town instance
(two instances, eight players each) and produced 3,136 input frames and 2.48 MiB
downstream in 20 s with no failures. A second 20 s run produced
`16,town,20,16,16,0,2706.17,0.44,1.08,4.11,320,19.65,3120,2.48,0.36,1103.04,83,2,512m`;
login p95 and the CPU/RSS columns vary run to run.

The old flow was stale, not the server: it subscribed to template id
`10 + (id+1) % instances`, but a scene subscription is matched against the live
instance OID (`world.ownsScene`, `ClientSession`), which is never a template id, so
the server refused the subscription. The repaired `enter` follows the stock client and
`ReadyRoomSessionTest`: register the zone receiver, select via character method 4,
load the client-local Ready Room with its own zone move, request return-to-game
(KnightMarshaller method 11), read the `forceScene` zone/template, send the zone move,
and take the live OID from the `moveSucceeded` callback
(`InvocationResponseEvent`, callback 3, argument `scene.oid()`). It then subscribes,
sends `enteredPlace` and waits for the initial delta. The OID is never derived from a
template id.

Absolutely baselined by `SessionOutputBudgetTest` (slow-reader queue depth, largest
frame, optional drops and disconnect) and the Phase 4 measurements below.

## Database baseline

Source-verified settings and write path:

- Pre-0.42.0 measurement: SQLite schema 18 (superseded by the schema 1 baseline); WAL enabled at initialization; `foreign_keys=ON` and
  `busy_timeout=5000` per connection; a fresh connection for every `read`/`write`;
  writes use `BEGIN IMMEDIATE`.
- Every write calls `InventoryExpiry.prune(...)` before the caller's work
  (`Database.write`), so the global expiry sweep is on the write path.
- `synchronous`, `wal_autocheckpoint`, `journal_size_limit` and `cache_size` are not
  set and therefore inherit SQLite/JDBC defaults.
- `Database.diagnostics()` reports operations, operations ≥100 ms, failures,
  rollbacks and maximum duration from static counters. `Database.storageMetrics()`
  adds `SQLITE_BUSY` errors, expiry-sweep runs and time, checkpoints and time, WAL
  bytes, and transaction-duration p50/p99. Both are exposed through the WebUI
  `status` response; no CLI task prints them.
- The game path and the WebUI/admin path each construct their own `Database` on the
  same file (two instances, `Server.run` and the enclosing `AdminService` account
  store).

Measured in the town runs above, reading the WebUI `status` response. The 20 s
sample is the final counter read of the run reported as the second 20 s row; the 30 s
sample was taken during load. The counters are process-wide static counters, so they
include the game store and the WebUI identity store:

| Run | Operations | ≥100 ms | Maximum | Failures | Rollbacks |
| --- | --- | --- | --- | --- | --- |
| 20 s, 16 clients (end of run) | 2,090 | 1 | 151 ms | 0 | 0 |
| 30 s, 16 clients (during load) | 2,625 | 2 | 140 ms | 0 | 0 |

Procedure with existing tools only: start
`./gradlew benchmark --args="--seconds 20 --clients 16 --instances 2"`, read the fresh
`WebUI Ready http://127.0.0.1:<port>` URL and `WebUI first-run setup code` from that
deployment's `server-16.log`, complete `/admin/api/setup`, then poll
`/admin/api/status`. The counters reset on process exit, so a sample must be taken
while the run's disposable server is alive.

`SQLITE_BUSY`, WAL-size, checkpoint and sweep-cost series are recorded from
`storageMetrics()` in the Phase 4 measurements below.

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
   tools expose scene hot-path cost, session ping/bytes/threads, live scene tick
   p50/p99 and aggregate DB counters, but no cadence-lateness series, no
   per-operation DB latency and no queue depth. The load tool's scene workload was
   broken in the first baseline pass; this follow-up repairs it and records the
   measured values and remaining gaps instead of inventing numbers.

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
| **Mission bar** | Native HUD mission-bar state and update path | Implemented: the native `PlayerObject.objectives` DSet is written by `KnightCodec.objectives`, and the mission's own `o.<missionId>` text renders through `ObjectiveConfig.getText()`; `MissionRouteSessionTest` and the `mission` graphical scenario read it |
| **Gate map** | Mission map UI state and its update/lifecycle | Gate/map data is served as the native `MissionGateSummary` wheels (`WireWriter.missionGate`) plus `LevelPartyObject.actualGateId`/`actualFloor` in `PartyCodec.party`; `MissionFloorSessionTest` drives the same run through its authored floors (351→380→382→384), and no mission-map-specific oracle exists beyond those fields |
| **Party config** | Lobby party configuration and mission party selection | Served by the native `MissionPartyObject`/`LevelPartyObject` flags (`inviteOnly`, `allowFriends`, `allowGuild`, `restricted`, `memberLimit`) in `PartyCodec.party`; `PartyCompatibility` covers the base party snapshot |
| **Reward wheel** | Wheel definitions, wedge selection and result framing | No separate native layout exists in the installed client: the only `*Wheel` class is `GateSummary$Wheel`, already served by `WireWriter.missionGate`; mission completion carries an ordered `MissionCompleteDialogInfo.rewards` list, not a wheel. `Heat.goldWheel` is a lockbox/prize flag outside the mission runtime and `PartyCodec.partyHeat` writes it false for an empty Heat entry. Nothing is invented; a distinct reward wheel would need an observed stock behaviour before a codec is added |
| **Room progress** | `LevelPartyObject.visited` reveal set and its `ModifyIntSet` update | Implemented against the installed client. `visited` is per-tile fog-of-war, not a room table: `dungeon.client.C` reveals around each packed tile `Coord` using `map.client.a.e`, which derives its own radial falloff, so the server only has to report simulated tile positions. `SceneSimulation` records them for non-town scenes, `PartyCodec.party` sends the snapshot through `integerSet`, and new tiles go out as `PartyObject$ModifyIntSet` deltas. `MissionRouteSessionTest` asserts a non-empty set and the `mission` graphical scenario asserts the subscription shows room progress |
| **Lobby wedge chain** | Lobby scene and each floor present for a route | Only `shadow_beast` (lobby 351; floors 380, 382, 384) has the complete verified lobby-to-floor chain. `ancient_generator` requires lobby 351 then 337, but the shipped opening gate goes straight to 337, so its authored lobby is unreachable. `choose_your_sprite`, `laboratory_visit` and `hall_of_heroes_4` are lobby-only definitions with no authored floor scenes. The other routes stay blocked until the client supplies the missing wedge or floor |
| Cross-feature golden stream | Byte-identical combined stream across features | Implemented in Phase 7. `SessionWireGoldenTest` pins one synthetic cross-feature downstream stream (5,246 bytes, `sha256=b15cbffe653e52765f8485db975568355156d5d2fdbc51f5440626d731045fc0`) through the shared `WireContext`; no client capture or asset is committed |

## Phase 4 concurrency measurements

Recorded on 2026-11-22. Host: Linux/amd64, 8 logical CPUs, Temurin OpenJDK
25.0.5+8-LTS. `ConcurrencyMeasurementTest` was run standalone at `-Xmx512m` and
`-Xmx1g` (2 s window, eight entered players, Haven instance 1 and interior scene
380, plus a temporary SQLite store with a concurrent owner-checked write
transaction). `LoadBenchmark` ran the real TCP workload at both heaps.

### Scene tick and publication (eight players, 2 s window)

| Measurement (µs unless noted) | Haven, 512m | Haven, 1g | Interior 380, 512m | Interior 380, 1g |
| --- | --- | --- | --- | --- |
| Scene ticks | 38 | 38 | 38 | 38 |
| Lock wait p50 / p99 | 0.3 / 0.4 | 0.2 / 0.5 | 0.2 / 0.4 | 0.3 / 0.4 |
| Tick work p50 / p99 / max | 762.6 / 17,275.1 / 17,275.1 | 909.5 / 14,914.8 / 14,914.8 | 1,474.6 / 4,747.4 / 4,747.4 | 1,503.0 / 4,746.1 / 4,746.1 |
| Publication p50 / p99 | 395.4 / 8,145.2 | 477.5 / 6,211.5 | 518.8 / 920.1 | 522.6 / 869.5 |
| Delivery p50 / p99 / max | 2.3 / 9.8 / 9.8 | 2.9 / 6.1 / 6.1 | 2.1 / 7.3 / 7.3 | 2.3 / 7.7 / 7.7 |
| Cadence lateness p50 / p99 / max (ms) | 0.0 / 0.0 / 0.0 | 0.0 / 0.0 / 0.0 | — | — |
| Scheduler overruns | 0 | 0 | 0 | 0 |

The p99 equals the observed maximum because the 256-sample window holds only 38
ticks; the outlier is the first tick, which includes class loading and JIT. The
interior run keeps publication below 1 ms while tick work is roughly twice the
Haven value, which is what the fixed movement/status/combat/portal/capture order
predicts for a smaller, non-town map.

### Storage writer under scene load

| Measurement | 512m | 1g |
| --- | --- | --- |
| 200 serial writes / 200 reads, average | 833.8 / 801.4 µs | 915.7 / 847.9 µs |
| Transaction duration p50 / p99 | 694 / 844 µs | 697 / 1,109 µs |
| Operations / ≥100 ms / failures / rollbacks / max | 606 / 1 / 0 / 0 / 141 ms | 606 / 1 / 0 / 0 / 132 ms |
| `SQLITE_BUSY` errors | 0 | 0 |
| Expiry sweeps / average cost | 401 / 54.3 µs | 401 / 54.2 µs |
| WAL bytes before checkpoint / checkpoint time | 0 / 1.3 ms | 0 / 1.3 ms |

The single ≥100 ms operation is the first write, which creates the schema; every
later transaction stays near 1 ms while the scene lane keeps its cadence with zero
overruns. The concurrent run used the owner-checked preference write
(`inspectionHidden`), a real `BEGIN IMMEDIATE` transaction, rather than an empty one.

### Per-session output with a slow reader

`SessionOutputBudgetTest` blocks the writer socket and queues 100 optional 4 KiB
frames:

| Measurement | Value |
| --- | --- |
| Frames retained | 65 (the 64-frame budget plus one already handed to the writer) |
| Pending bytes at peak | 262,144 B |
| Largest queued frame | 4,096 B |
| Optional frames dropped before encoding | 35 |
| Heap delta for the retained set | 0 B after collection of the fixture |

Reliable overflow fails the session, a sustained over-budget reader is disconnected
after 5 s, and both are counted in `SessionOutput.Metrics`.

### 16-client TCP load (town movement, two instances, 20 s)

```text
clients,workload,seconds,ready,completed,failed,login_p95_ms,ping_p50_ms,ping_p95_ms,ping_p99_ms,ping_samples,deltas_per_client_s,input_frames,downstream_mib,server_cpu_cores,server_peak_rss_mib,server_peak_threads,instances,xmx
16,town,20,16,16,0,2664.74,0.44,0.83,2.21,320,19.70,3136,2.49,0.30,867.02,94,2,512m
16,town,20,16,16,0,2035.91,0.34,0.74,3.87,320,19.63,3104,2.48,0.30,1546.31,99,2,1g
16,town,20,16,16,0,2903.97,0.34,2.99,5.72,320,19.68,3152,2.48,0.39,1441.68,98,2,1g
```

All 16 clients authenticated, entered the live scene and logged off without failures
at both heaps. Login p95 remains dominated by the 600,000-iteration password check.
The `-Xmx1g` rows trade a larger RSS for headroom; deltas, inputs and ping stay in
the same range, so the shipped 512 MiB default is not the limiting factor at this
client count.

### Thread, GC and shutdown accounting

| Measurement | 512m | 1g |
| --- | --- | --- |
| Resting platform threads / peak | 15 / 20 | 15 / 20 |
| Virtual-thread carrier parallelism | 8 | 8 |
| Snapshot allocation per call | 16,912 B | 16,912 B |
| GC collections / time | 93 / 1,121 ms | 48 / 846 ms |
| Scheduler shutdown drain | 0.5 ms | 0.4 ms |

JDBC runs on the dedicated writer/reader platform threads, and the contended run
above shows the scene lane unchanged with `SQLITE_BUSY` 0, so storage contention does
not starve ticks. JMX does not enumerate virtual threads; sessions are one virtual
thread each (`SessionOutput` writer aside) and the 16-client run reports the live
session count through `ready`/`completed`.

### Budgets

These thresholds are the acceptance budgets for this host; the measured column is
the range across the runs above. Only the per-session queue limits and the 5 s
slow-reader disconnect are enforced in code; the rest are release gates checked by
the measurement programs.

| Area | Budget | Measured |
| --- | --- | --- |
| Scene tick work p99 | ≤ 25 ms | 4.7–17.3 ms |
| Snapshot publication p99 | ≤ 10 ms | 0.9–8.1 ms |
| Snapshot delivery p99 | ≤ 10 ms | ≤ 9.8 µs |
| Cadence lateness p99 / overruns | ≤ 50 ms / 0 | 0 ms / 0 |
| Storage transaction p99 / `SQLITE_BUSY` | ≤ 100 ms / 0 | 0.8–1.1 ms / 0 |
| Expiry sweep average | ≤ 1 ms | 54 µs |
| WAL checkpoint / residual WAL | ≤ 100 ms / 0 B | 1.3 ms / 0 B |
| Per-session queue | 64 frames / 4 MiB | enforced |
| Slow-reader disconnect | 5 s | enforced |
| Shutdown drain | ≤ 2 s | ≤ 0.5 ms |
| 16-client login p95 / failures | ≤ 5 s / 0 | 2.0–2.9 s / 0 |
| 16-client CPU / RSS at 1g | ≤ 1 core / ≤ 1.6 GiB | 0.30–0.39 cores / 1.4–1.5 GiB |

Not scripted end to end: the revive, owned-item and elevator-settlement actions are
exercised functionally by the session regressions (`MissionFloorSessionTest` settles
exactly once per run; item and revive paths have their own focused tests) but the
16-client tool currently drives town movement only. The eight-player interior load is
measured in-process, and per-input round-trip latency is represented by cadence
lateness plus the contended transaction series, not by a per-packet stopwatch.

## Refresh procedure

Run only when this baseline must be replaced, not per change:

```sh
./gradlew build
./gradlew benchmarkScenes --args=10000
./gradlew benchmark --args="--seconds 20 --clients 16 --session-only"
(cd content && find . -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum)
sha256sum build/tortodrone.jar
```

The town/scene workload is recorded with
`./gradlew benchmark --args="--seconds 20 --clients 16 --instances 2"`; its rows and
the WebUI counter samples are in the sections above. `ConcurrencyMeasurementTest`
records the scene-lane, storage and thread series separately.
