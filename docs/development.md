# Development workflow

Read [AGENTS.md](../AGENTS.md), [README](../README.md) and the
[current milestone](world-entry-plan.md) first. Then inspect Git and relevant code.
[Architecture](server-architecture.md) records protocol/storage invariants;
[resource assessment](server-research.md) points to primary sources. Ignored `local/`
files are not build dependencies or current procedures.

## Build and automated checks

The project targets Java 25 and uses GNU Make, `javac` and `jar`. The first build
needs network access; the Java build tool verifies SHA-256 pins and later builds
use cached libraries. Map packs are checked in and prepare offline.
No Maven/Gradle, installed game or database service is required.

```sh
make build             # server and local admin JARs
make defaults          # copy all local defaults, preserving existing files
make build-server      # server only
make build-admin       # manager only
make build test        # rebuild and run all checks
make test-unit         # Java unit/socket fixtures
make test-integration  # disposable packaged deployments
make clean             # keep runtime data and dependency cache
```

On Linux without an accessible display, use `env -u DISPLAY make build test`.
The table fixtures work headlessly; this does not replace graphical acceptance.
Avoid `JAVA_TOOL_OPTIONS` for this suite: its JVM startup message interferes with
packaged tests that check exact command output.

`make server` and `make admin` launch real tools using `build/data/`; use temporary
copies of the JARs for tests instead. Tests have Java `main` methods and names
ending in `Test`; shared synthetic wire helpers live in `src/test/java/org/tortodrone/support/`.
The current suite has 41 unit and five integration checks. It covers persistence,
schema refusal/transaction rollback, ownership/revocation, protocol bounds, character/session
lifecycle, town input/deltas, local administration and packaged shutdown/restart.
The map preparation check uses a temporary source tree without game files or
download caches, and verifies offline copying, corrupt-source rejection and
preservation of edited deployment packs.
The build-input check uses a local HTTP fixture to verify cache reuse without
network access, retries, checksum rejection, stalled response-body deadlines and
temporary-file cleanup.

Add meaningful regression checks for behavior changes. Review `git diff` and
`git diff --check`; update current docs and commit major completed milestones.
Documentation-only edits do not need a version increment. Leave test processes
stopped and report checks/limitations. Follow the owner's instruction on merging.

## Load benchmarks

```sh
make benchmark
make benchmark BENCHMARK_ARGS='--seconds 60 --clients 50,100,200,300'
make benchmark BENCHMARK_ARGS='--session-only --clients 50,100,200,300'
make benchmark BENCHMARK_ARGS='--instances 4 --clients 50,100,200,300'
```

The Java fixture starts a fresh localhost server for each load, using copied JAR/default
resources and disposable accounts/knights in `/tmp`. It raises `max-clients` only in
that deployment. Town clients enter one Haven instance by default, continuously drain the
downstream stream, send movement input at 10 Hz and ping once per second. The default
measurement is 20 seconds at each of 50, 100, 200 and 300 clients, after an eight-login
concurrent ramp. `--session-only` subscribes to private rosters and pings without
entering town, to separate connection overhead from scene replication. `--instances`
(1–16, default 1) changes only the disposable Haven manifest. Normal server assignment
spreads world IDs across those instances; clients address and validate their assigned
scene object. This reuses the same login, workload, metrics and cleanup fixture.

Reports include readiness/completion/failures, login p95, ping p50/p95/p99 and sample
count, scene deltas per requested client per second, input count, downstream MiB,
server CPU use in cores, peak sampled RSS and native thread count (Linux `/proc`), plus instance count.
The server runs with a 512 MiB heap limit and 256 KiB native thread stacks. Reports,
server logs and failure details remain at the printed temporary path; disposable
accounts/private keys are deleted on normal completion. All fixture processes stop
on exit. Client failures return a nonzero status after reporting every load; startup
failures abort with the server log path. The login ramp has a ten-minute deadline.

Run on an otherwise idle machine for comparisons. The load generator shares the
machine, so these are local stress measurements, not a remote capacity guarantee.
Login hashing keeps the server's normal 600,000 iterations; setup shares one
synthetic verifier across disposable accounts and is excluded from measurement.
The bounded benchmark reader checks frame/header/correlation and delta readiness;
installed-reader tests separately validate complete actor payloads. It does not
render graphics, simulate combat or measure inventory writes. Keep benchmark
results out of Git; record a concise dated observation when it informs a change.

Observed on 2026-10-09, Java 25, one local Haven instance, 20 seconds per load:

| Clients completed | Ping p95 (ms) | Received deltas/client/s | Peak server RSS (MiB) | Peak native threads |
| --- | ---: | ---: | ---: | ---: |
| 50/50 | 6.70 | 26.40 | 392.41 | 180 |
| 100/100 | 11.91 | 4.77 | 440.75 | 327 |
| 200/200 | 131.22 | 0.98 | 741.62 | 629 |
| 300/300 | 110.10 | 0.46 | 809.00 | 928 |

There were no client failures and all expected pings returned. Received frame rates
include scheduler catch-up bursts after login; a rate above 20 is not a steady
simulation frequency. Town replication falls below its 20 Hz target at the larger
loads, so successful connections do not establish smooth gameplay capacity. Profile
scene ticks, per-player database polling and actor snapshot contention before raising
normal deployment limits. The default stays 16 connections.

After shared simulation (0.17.0), same Java 25/20-second local town workload:

| Clients completed | Instances | Ping p95 (ms) | Deltas/client/s | CPU cores | Peak RSS (MiB) | Peak native threads |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 50/50 | 1 | 2.99 | 19.75 | 0.75 | 331.70 | 139 |
| 100/100 | 1 | 5.70 | 19.70 | 1.20 | 373.20 | 238 |
| 200/200 | 1 | 6.21 | 19.45 | 2.74 | 397.64 | 438 |
| 300/300 | 1 | 21.37 | 18.45 | 4.70 | 411.08 | 638 |
| 300/300 | 4 | 5.73 | 19.71 | 2.59 | 410.18 | 641 |

All expected pings returned and no clients failed. At 300 in one instance, received
updates improved about 40×, peak RSS roughly halved, and native threads fell by 290.
Simulation/snapshot work now runs once per instance and metadata polling once per
deployment. Connection-specific all-actor encoding remains quadratic in a crowd:
the 300-client run sent 1,212.71 MiB over 20 seconds, versus 30.97 MiB when the old
loop produced far fewer updates. Four existing instances (75 players each) lowered
traffic to 328.49 MiB, about 73% less, while returning close to the 20 Hz target.
These header/latency checks complement stock
payload and graphical checks; they are not a remote capacity or combat guarantee.


## Installed-client compatibility

From the repository root, with both JARs built:

```sh
java scripts/compat/CheckClientWorld.java "/path/to/Spiral Knights"
java scripts/StockTools.java previews "/path/to/Spiral Knights"
```

These load unchanged installed game classes/resources, not a graphical game.
Use a JDK that can read the installed JARs (the tested installation requires Java
25). The world harness copies both server JARs into temporary storage, creates
disposable accounts/preferences and stops its server on exit. It checks the stock
reader/predictor, creation/reconnect, delayed acknowledgements, activity timing,
model retention, peaceful town models, ignored combat inputs, equipment element/current-loadout
observer callbacks before confirmation, currency, scene round trips, separate-instance isolation
and two-account actor synchronization. It also checks same-account knight takeover
and rapid reconnect using stock login/selection readers. Picker validation and creation
reject names owned by another account, including case variants. Complete defaults are copied into each normal test deployment. The missing-default
fixture blocks outbound repository access and checks warning-only startup, normal
login and character persistence. Local HTTP fixtures exercise downloads, future
directories, validation and preservation of existing files.
Chat fixtures use the stock scene speak RPC and reader, including Unicode, speaker
names, sender echo, emote mode and isolation across instances and map transitions.
Tell fixtures use the stock global marshaller, private player-object reader and
success/failure listener, checking cross-map delivery, reply, offline names,
message limits, rate limits, departure/re-entry and disconnected recipients.
The portal fixture walks the original west Town Square/east Bazaar sensors, reads
the actual forced receiver callback, exercises scene/zone RPCs and tagged arrival
position/facing, and checks peer departure/rejoin and fresh baselines.
The Haven oracle compares all four original native scenes, ten portal polygons,
sensor settings and tagged arrival transforms. The Haven network fixture plans
test-only walking routes over sampled collision and walks every directed link
using original stock input, forced callbacks and move RPCs.
The world fixture also calls the stock sit RPC, checks a persistent seated actor
and its exact standing deadline, and compares the installed configuration/predictor.
GM fixtures verify login/occupant role and support icon, broadcasts across maps,
normal/revoked permission denial and live role changes without model/position resets.
Item fixtures export a disposable catalogue and compare all metadata and supported
equipment models with stock configuration at three colors. Every grantable record is
read by stock classes and checked against its native constructor, count and binding flags. Actual scene commands
exercise private feedback, live/offline grants, Arsenal item observers, binding,
equipment models, denial/revocation and reconnect persistence. See [game data](game-data.md).
The preview fixture compares starter model construction with the stock builder.
Persistent compatibility tools are Java; do not introduce Python scripts.

## Isolated graphical checks

The owner authorizes launching the installed, unmodified stock `ProjectXApp` for
acceptance checks. Read JARs, assets and native libraries from
`~/.local/share/Steam/steamapps/common/Spiral Knights`; never write there or to real
client preferences. No graphical test launcher is committed: assemble an isolated
environment for the installed version, using its deployment/JVM requirements.

1. Create a temporary directory with a server JAR and defaults copy, fresh data/keys, disposable
   normal accounts, client home/preferences/cache/native extraction directories.
2. Start that server on an isolated localhost port. Create a temporary deployment
   configuration retaining required stock settings but using its endpoint and
   public key. Read the installation through a read-only mount where available.
3. Launch stock `ProjectXApp` with a disposable username/password/character and
   isolated home/preferences. On niri set `_JAVA_AWT_WM_NONREPARENTING=1`.
4. Identify the target client window/process and verify focus before sending
   X11/XTest keys or mouse events. Use niri window screenshots to inspect rendering.
5. Check fresh creation and existing-character reconnect: close entry HUD, walk,
   stop/start, reverse direction, face the mouse, open/close inventory, equip/unequip.
   Check both views when accepting graphical multiplayer changes.
6. Stop only the clients/servers you started and verify cleanup. Preserve real
   accounts, characters, keys, installed files and preferences.

Network fixtures cannot prove that animations render. On the tested niri desktop,
vertical sync stalled native swaps; disabling it in **disposable preferences**
removed that stall. Virtual pointer devices can be detected as gamepads; exclude
joystick input devices in the temporary environment if necessary while preserving
GPU access. These are test-environment accommodations, not server/client patches.
Record inconclusive checks and environment limitations explicitly. See the
[0.7.1 investigation](town-entry-investigation.md) for accepted checks.

Set the stock `resource_dir` property to the read-only installed `rsrc/` directory;
omitting it can crash the image cache. Set `java.io.tmpdir` to a writable temporary
directory for font extraction. Center the niri test window before coordinate input,
and match X11's `_NET_WM_PID` to the host client PID; niri may show a namespace PID.
For GM visual checks, use a second disposable peer and temporary always-visible
knight labels: the stock renderer hides the controlled knight's own nameplate.

## Resources and releases

Sources/resources are discovered recursively. `VERSION` becomes bundled
`version.txt`; both JARs use it. `build/.build/` is disposable packaging output,
`build/deps/` caches verified inputs, and `build/data/` is real runtime state.
`make clean` preserves the latter two.

Missing dependencies print their source URL, attempts, progress and verification
result. Each of up to three attempts has a 30-second complete-transfer deadline;
failures identify the dependency and suggest rerunning after fixing connectivity.
Existing verified dependencies are reused without network requests.

Dependency pins/digests live in the Makefile and Java preparation tools; update
their [notices](dependencies.md) when changing them. Scene geometry and collision
must match; [maps](maps.md) documents preparation and the Java importer.
Never commit installed game assets or assume an
unlicensed reference project can be copied into the server.
