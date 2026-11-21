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

Not baselined, owned by Phase 4: lock wait, tick cadence/lateness p50/p99/max under the
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

Town/scene workload with the repaired tool:

```text
./gradlew benchmark --args="--seconds 20 --clients 16 --instances 2"
16,town,20,16,16,0,2475.81,0.35,0.76,3.09,320,19.66,3136,2.48,0.37,1004.00,84,2
```

All 16 clients selected a knight, left the Ready Room, entered a live town instance
(two instances, eight players each) and produced 3,136 input frames and 2.48 MiB
downstream in 20 s with no failures. A second 20 s run produced
`16,town,20,16,16,0,2706.17,0.44,1.08,4.11,320,19.65,3120,2.48,0.36,1103.04,83,2`;
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

Not baselined, owned by Phase 4: per-session frame/byte depth, overflow/throttle/
disconnect counts, largest frame, retained snapshots, heap per slow reader.

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
  rollbacks and maximum duration from static counters. It is exposed only through the
  WebUI `status` response; no CLI task prints it.
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

No `SQLITE_BUSY`, WAL-size, checkpoint or sweep-cost series was captured; the
diagnostics expose the aggregate only. Phase 4 owns those measurements.

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
| **Gate map** | Mission map UI state and its update/lifecycle | Gate/map data is served as the native `MissionGateSummary` wheels (`WireWriter.missionGate`) plus `LevelPartyObject.actualGateId`/`actualFloor`; no mission-map-specific oracle exists beyond those fields |
| **Party config** | Lobby party configuration and mission party selection | Served by the native `MissionPartyObject`/`LevelPartyObject` flags (`inviteOnly`, `allowFriends`, `allowGuild`, `restricted`, `memberLimit`) in `PartyCodec.party`; `PartyCompatibility` covers the base party snapshot |
| **Reward wheel** | Wheel definitions, wedge selection and result framing | **Missing.** No codec or oracle; needs the stock client's wheel config/behaviour and one observed spin |
| **Room progress** | `LevelPartyObject.visited` reveal set and its `ModifyIntSet` update | Implemented against the installed client. `visited` is per-tile fog-of-war, not a room table: `dungeon.client.C` reveals around each packed tile `Coord` using `map.client.a.e`, which derives its own radial falloff, so the server only has to report simulated tile positions. `SceneSimulation` records them for non-town scenes, `PartyCodec.party` sends the snapshot through `integerSet`, and new tiles go out as `PartyObject$ModifyIntSet` deltas. `MissionRouteSessionTest` asserts a non-empty set and the `mission` graphical scenario asserts the subscription shows room progress |
| **Lobby wedge chain** | Lobby scene and each floor present for a route | Only `shadow_beast` (lobby 351; floors 380, 382, 384) has the complete verified lobby-to-floor chain. `ancient_generator` requires lobby 351 then 337, but the shipped opening gate goes straight to 337, so its authored lobby is unreachable. `choose_your_sprite`, `laboratory_visit` and `hall_of_heroes_4` are lobby-only definitions with no authored floor scenes. The other routes stay blocked until the client supplies the missing wedge or floor |
| Cross-feature golden stream | Byte-identical combined stream across features | **Scaffolding only.** `WireFixture`/`SyntheticWireFixtureTest` (Phase 0); the actual goldens are Phase 7 work |

## Phase 4 concurrency measurements

Recorded from `ConcurrencyMeasurementTest` on 2026-11-21. Host: Linux/amd64, 8
logical CPUs, Temurin OpenJDK 25.0.5, default test heap (~2.9 GiB maximum). The
synthetic load is eight entered players in Haven for two seconds plus a temporary
SQLite store; it is not the full 16-client, dual-heap load described in
`docs/redesign.md`, so treat the numbers as a working baseline rather than the
final gate measurement.

| Measurement | Value |
| --- | --- |
| Scene ticks in 2 s | 38 |
| Last lock wait / tick work / max tick work | 0.2 µs / 550.7 µs / 22,781 µs |
| Scheduler cadence lateness p50 / p99 / max | 0.0 / 0.0 / 0.0 ms |
| Scheduler overruns | 0 |
| SQLite 200 writes / 200 reads, average | 938.0 µs / 231.1 µs |
| SQLite operations / slow / failures / rollbacks / max | 402 / 1 / 0 / 0 / 115 ms |
| WAL bytes before checkpoint | 0 |
| Checkpoint | 1.5 ms |
| Resting platform threads / peak | 15 / 20 |
| Snapshot allocation per call | 16,958 B |
| GC collections / time | 24 / 622 ms |
| Scheduler shutdown drain | 0.4 ms |

The first write includes schema initialization, which explains the 115 ms maximum;
later writes stay sub-millisecond. The per-session output budget, overflow and
disconnect policy is covered by `SessionOutputBudgetTest`, and the scheduler serial
lane, cap, failure isolation and shutdown by `SceneSchedulerTest`.

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
