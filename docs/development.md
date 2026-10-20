# Development and verification

Start with [AGENTS.md](../AGENTS.md), then the files relevant to your task. Source, current tests and the **unchanged stock client** outrank historical documentation. Keep test accounts, server state, client preferences and processes disposable; do not modify the owner's installed client or active deployment.

## Verification policy (FAST by default)

Choose the **least expensive check that proves the requested change**. Do not run the full test suite or launch the stock graphical client after every edit.

1. Check the specific source and add/update a focused regression for behavior changes.
2. For code changes use `./gradlew testUnit -PunitTests=AffectedTest,OtherAffectedTest` to compile/package and run only directly relevant programs. Use `buildWithoutTests` when checking compilation/packaging alone. For docs-only changes, review links and diffs instead.
3. Escalate to focused integration for auth, storage, trades, protocol, cross-client synchronization and other high-risk paths. Include negative cases, ownership and rollback.
4. Run one isolated stock-client/graphical acceptance scenario only when the change affects behavior that cannot be verified otherwise (rendering, animations, HUD, live scene transitions). Do **not** start repeated graphical sessions for backend-only changes. If graphical acceptance is necessary but unavailable, report it as **not verified**, not passed.
5. Reserve a complete `./gradlew build` for significant cross-cutting changes, explicit release readiness or a requested full regression, **once at the final checkpoint**. A routine patch `VERSION` bump alone does not trigger the full suite. Benchmarks and other expensive checks are opt-in.

Stop when the task's acceptance criteria pass. Avoid repeated successful suites, unrelated test coverage, speculative bug hunts and `clean` unless rebuilding from scratch is necessary.

## Build and test (JDK 25)

```sh
./gradlew buildWithoutTests      # DEFAULT: compile/package main, tests and tools; execute no tests
./gradlew buildServer buildAdmin # package both apps and unified content
./gradlew testUnit               # all standalone unit tests (not a focused filter)
./gradlew testUnit -PunitTests=SceneSimulationTest,MovementCollisionTest # focused compile/package + tests
./gradlew testIntegration        # all packaged integration tests
./gradlew build                  # full suite; milestone/release verification
./gradlew testProcessCleanup     # focused cleanup integration task
./gradlew prepareDefaults
./gradlew prepareMaps
./gradlew benchmark --args="--seconds 20 --instances 4 --clients 300" # explicit performance work only
```

### Running just the affected tests

Tortodrone's unit tests are standalone `main` programs, **not JUnit**. `./gradlew testUnit --tests ...` is not supported. Prefer `-PunitTests=SceneSimulationTest,MovementCollisionTest`; fully qualified names also work. Unknown, empty or ambiguous names fail instead of silently running zero tests. Without the property, `testUnit` still runs every unit program. Programs run sequentially, report elapsed time and list the five slowest programs; authentication, storage and rollback coverage is retained.

Unit/server checks build only the server JAR; admin integration and `buildWithoutTests` also package the admin app. Map preparation integration reuses compiled `BuildTools` instead of invoking Java's source compiler for each fixture operation.

To keep an existing deployment untouched, use `-PdeploymentOutput=/tmp/tortodrone-check` with build/test tasks. Both JARs and prepared configuration/content go there, while Gradle compilation stays in `.build/`. Test output redirection does not upgrade an existing `build/content/packs` deployment.

The bundled `StandaloneTestSuite` also accepts fully-qualified test classes directly. From the repository root (Linux/macOS):

```sh
./gradlew buildWithoutTests
java --enable-native-access=ALL-UNNAMED \
  -cp 'build/tortodrone.jar:.build/classes/java/test' \
  org.tortodrone.support.StandaloneTestSuite \
  org.tortodrone.world.SceneCatalogTest \
  org.tortodrone.world.SceneSimulationTest
```

Windows PowerShell/CMD (classpath uses `;`):

```powershell
.\gradlew.bat buildWithoutTests
java --enable-native-access=ALL-UNNAMED -cp "build/tortodrone.jar;.build/classes/java/test" org.tortodrone.support.StandaloneTestSuite org.tortodrone.world.SceneCatalogTest org.tortodrone.world.SceneSimulationTest
```

Replace the example classes with the specific tests for your change; do not run them all by default. This uses the same packaged JAR and compiled test output as the Gradle unit suite. Tests which need arguments or packaged servers have their own named Gradle integration tasks.


Windows: use `.\gradlew.bat`. The wrapper and `gradle/verification-metadata.xml` pin distribution/dependency integrity. First build needs Gradle/Maven/plugin downloads; cached inputs support `--offline`. The normal `build`/`test` suites **do not** run native stock graphical checks. Unit programs run sequentially in one JVM; packaged integration fixtures use separate processes. A shared build-service slot serializes regression runs; don't run competing builds in the same checkout.

`runServer` and `runAdmin` operate on **real `build/data/`** and are not disposable tests. `clean` removes generated JARs/scratch, but **preserves** `build/data/`, `build/content/` and legacy `build/deps/`. If using headless Linux, `env -u DISPLAY ./gradlew build` is possible; it is not graphical acceptance.

## Test by change type

| Change | Default checks | Escalate only when needed |
| --- | --- | --- |
| Docs/comments/refactor with no behavior change | Link/diff checks; compile only if code was touched | None |
| Ordinary Java/content change | `buildWithoutTests` + affected standalone tests | Related integration if behavior crosses services |
| Protocol, auth, storage, trades | Targeted positive/negative/ownership/rollback tests | Relevant packaged integration or stock-reader check |
| Scene or portal logic | Map/simulation tests | Stock scene enter/return or two-client check for changed transitions |
| Items/combat | Targeted catalog, damage/equipment tests | One isolated graphical test if modifying actual client-visible effects |
| Server GUI | Focused UI tests | One isolated desktop fixture when visual behavior changed |
| Performance/concurrency | Focused correctness/race checks | Benchmark only for explicit performance questions |
| Major cross-cutting change / release | `./gradlew build` once at the end | Relevant stock-client acceptance |
| Documentation only | Diff and link/source review | No Java tests |

Stop **only test processes you started**, and report which checks were skipped, failed or unavailable. Use `git diff --check` before completion. Never claim graphical parity from a passing network fixture.

## Stock client compatibility

Useful checked-in Java tools (from repository root with a separate read-only game installation):

```sh
./gradlew buildWithoutTests
java -cp build/tortodrone.jar scripts/compat/CheckClientWorld.java "/path/to/Spiral Knights"
java -cp build/tortodrone.jar scripts/compat/CheckCombatRendering.java "/path/to/Spiral Knights" /tmp/tortodrone-combat-check
java scripts/StockTools.java previews "/path/to/Spiral Knights"
```

`scripts/compat/` also contains narrower handshake, account, scene, portal, inspection, trade, item, emote, weapon, fire and protocol fixtures. Read each tool's source/usage before running it: some launch temporary servers and clients; some only inspect installed classes/configs. The exporter `scripts/StockTools.java` handles `scene`, `items`, `weapons`, `fires`, `equipment` and current source-maintainer scene/capture subcommands in read-only game input mode with a fresh output directory. `previews`/`items` require a built server JAR.

**Stock reader/network acceptance ≠ graphical acceptance.** For a change to rendering, attachment composition, animation or transition readiness, one isolated unchanged-client graphical scenario may be necessary; for altered shared-instance behavior, test with two clients. This is **not** a default validation step for unrelated tasks. Use disposable endpoints, public keys, home/prefs/cache/temp/native extraction and test accounts; never patch the production launcher/client. Save and review screenshots or logs where the fixture provides them. Keep the game window focused; platform window-manager and input behaviors can invalidate short action captures.

`CheckCombatRendering` uses a fresh output path, records logs/screenshots and exercises sword/gun/bomb controls and Haven↔Training travel. It does not verify every weapon, moving-target hits, full parties or general dungeon mechanics. Source-client binary/current wire observations matter more than an old 2011 marshaller ID table.

## Opt-in server/admin Swing check

On a Java 25 **graphical desktop**, from the source directory:

```sh
./gradlew buildServer buildAdmin testClasses
java --enable-native-access=ALL-UNNAMED -cp 'build/tortodrone.jar:.build/classes/java/test' org.tortodrone.server.DesktopCheck /tmp/tortodrone-desktop-check-fresh
```

Choose a fresh/nonexistent output directory under `/tmp`. The fixture starts only disposable local processes and stores `desktop.log` plus captured GUI component PNGs for review. It exercises windows, account/knight browsing, sessions, charts, logs, grants, balances and lifecycle actions; **it does not launch the stock game**. Use OS-appropriate classpath separators when adapting this Linux example. Linux/niri may need `_JAVA_AWT_WM_NONREPARENTING=1` for stock-client graphical probes.

## Load testing

```sh
./gradlew benchmark --args="--session-only --clients 50,100,200,300"
./gradlew benchmark --args="--instances 4 --clients 300"
./gradlew benchmarkScenes --args=5000 # CPU/allocation comparison: Haven, Hall of Heroes, Training
```

Benchmarks copy packaged resources into disposable deployments, simulate clients/inputs and report connection/readiness, ping percentiles, memory/CPU and network traffic. Run on an otherwise idle host. Short local simulations do not establish WAN experience, long-term stability, rendering or full combat parity.

`benchmarkScenes` creates no accounts or sockets. It warms interaction, collision, entity advancement and snapshot paths, then prints microseconds and allocated bytes per operation. Compare the same iteration count/JVM/host; these figures do not measure graphical FPS or complete request latency. Decoded immutable actor references are shared, while collision poses and simulation state remain owned by each scene instance.

For a reported Haven dialog backlog, the focused stock scenario is:

```sh
java --enable-native-access=ALL-UNNAMED -Dtortodrone.deployment=/tmp/tortodrone-check \
  -cp /tmp/tortodrone-check/tortodrone.jar scripts/compat/CheckCombatRendering.java \
  "/path/to/Spiral Knights" /tmp/tortodrone-dialog-check --town-dialogs-only
```

This uses disposable accounts/preferences and performs twelve rapid native crafter open/close cycles, reporting each round trip and capturing the opened/closed window. The original client defaults to ten TCP messages/second; input and service calls share its FIFO. Previously the server sent the existing 100-message throttle update only when entering an interior. A fresh Town subscription now sends it before its scene snapshot, once per connection, so movement input does not accumulate ahead of NPC/crafting requests. `TownServicesSessionTest` guards initial publication and dialog ownership; `InteriorCompatibility` checks the throttle's native wire layout. Socket TCP_NODELAY also avoids delayed-ACK stalls for small responses.

## Editing, packaging and release hygiene

- Keep changes bounded; update tests for behavior and one canonical document for user-facing changes. Do not add a new milestone diary.
- Do not commit installed game assets, third-party material without appropriate rights, private captures, secrets, `data/`, `build/` or `.build/`.
- Review `git status`, `git diff` and `git diff --check`; report **only tests actually run**, with failures and what remains unverified. Avoid repeated runs after passing checks without relevant changes.
- Bump `VERSION` only for deliberate behavioral releases. Release workflow tags must match `v<VERSION>`; CI tests/builds do not substitute for native graphical verification. See [Operations](operations.md).
- Third-party runtime/license metadata: `src/main/resources/THIRD-PARTY.md`, `licenses/` and `gradle/verification-metadata.xml`. Inspect changes to pinned hashes carefully.

Recent scene and Training Hall validation details are retained in [Modern captures](modern-captures.md) and [Training Hall](training-hall.md). Detailed prior investigations and their original commands stay accessible via Git history (for example `git show <commit>:docs/protocol-audit.md`); [Research](research.md) points to the relevant sources and caveats.

Unified content regressions are `resources.ContentManagerTest` and `resources.PackContentTest`; fixtures are generated in temporary directories from the unified defaults, never maintained as a second checked-in catalog. The focused graphical `--dialogue-only` mode selects another verified localized Konway line through disposable YAML, enters Training, captures both nodes and closes the native dialog. Arbitrary literal messages and responses are rejected after the stock graphical scenario showed the client appending the knight name instead of decoding literals.

Economy/content regressions: `DepotStoreTest`, `AuctionParityTest`, `SparseContentTest`
and the existing Town/featured/expiry/session tests use disposable SQLite state.
`SparseContentTest` compares all seven compiled scene/portal snapshots and the full
item catalog against pre-overhaul `0078b50` fingerprints. The focused stock
`--depot-only` fixture schedules a Silver Key discount and verifies its native
price fields, Specials, purchase debit/delivery and refused real-money billing.

Gameplay wire checks: `scripts/compat/GameplayCompatibility.java` reads loadout DSet
events, Arsenal entry keys/equipment policies, interaction icons, warp/invulnerability
deltas, conversation bundles and auction mail with the installed retail classes.
`ConfettiCompatibility.java` checks all fourteen original animation definitions and
their timestamp-seeded durations. These are read-only reader checks; visible blinking,
animation composition and live UI/travel behavior still require in-game acceptance.
