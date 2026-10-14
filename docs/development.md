# Development and verification

Start with [AGENTS.md](../AGENTS.md), then the files relevant to your task. Source, current tests and the **unchanged stock client** outrank historical documentation. Keep test accounts, server state, client preferences and processes disposable; do not modify the owner's installed client or active deployment.

## Build and test (JDK 25)

```sh
./gradlew buildWithoutTests    # compile main, tests and build-tool sources; package both JARs; no tests run
./gradlew buildServer buildAdmin  # executable JARs; selected content pack
./gradlew build                   # full unit + integration suite
./gradlew testUnit
./gradlew testIntegration
./gradlew testProcessCleanup
./gradlew prepareDefaults
./gradlew prepareMaps
./gradlew benchmark --args="--seconds 20 --instances 4 --clients 300"
```

Windows: use `.\gradlew.bat`. The wrapper and `gradle/verification-metadata.xml` pin distribution/dependency integrity. First build needs Gradle/Maven/plugin downloads; cached inputs support `--offline`. The normal `build`/`test` suites **do not** run native stock graphical checks. Unit programs run sequentially in one JVM; packaged integration fixtures use separate processes. A shared build-service slot serializes regression runs; don't run competing builds in the same checkout.

`runServer` and `runAdmin` operate on **real `build/data/`** and are not disposable tests. `clean` removes generated JARs/scratch, but **preserves** `build/data/`, `build/content/` and legacy `build/deps/`. If using headless Linux, `env -u DISPLAY ./gradlew build` is possible; it is not graphical acceptance.

## Test by change type

| Change | Minimum evidence |
| --- | --- |
| Parser/protocol | Unit regression for real stock types, malformed inputs, callbacks and mapping reuse; stock reader compatibility |
| Login, saved state, trades | Isolated integration with real SQLite, stale/auth-revision and race/rollback checks; no actual user database |
| Scenes and portals | Map/catalog tests plus native scene enter/return and two-client subscriptions if shared |
| Items/equipment/combat | Typed catalog and persistence tests; native client equip/actor and rendering checks when visual behavior changes |
| Local GUI | Headless/Swing unit checks and opt-in isolated desktop fixture |
| Performance/concurrency | Benchmarks plus correctness/race tests; no production capacity claim from synthetic results |
| Documentation only | Check links, current CLI/source accuracy and diff; **no code test claim without running it** |

Run `./gradlew build` when feasible, `git diff --check` and the focused compatibility suite. Stop **only test processes you started**. Review actual logs and output before reporting a pass. Avoid editing real installed-game assets, paths, home or preferences.

## Stock client compatibility

Useful checked-in Java tools (from repository root with a separate read-only game installation):

```sh
./gradlew build
java scripts/compat/CheckClientWorld.java "/path/to/Spiral Knights"
java scripts/compat/CheckCombatRendering.java "/path/to/Spiral Knights" /tmp/tortodrone-combat-check
java scripts/StockTools.java previews "/path/to/Spiral Knights"
```

`scripts/compat/` also contains narrower handshake, account, scene, portal, inspection, trade, item, emote, weapon, fire and protocol fixtures. Read each tool's source/usage before running it: some launch temporary servers and clients; some only inspect installed classes/configs. The exporter `scripts/StockTools.java` handles `scene`, `items`, `weapons`, `fires`, `equipment` and current source-maintainer scene/capture subcommands in read-only game input mode with a fresh output directory. `previews`/`items` require a built server JAR.

**Stock reader/network acceptance ≠ graphical acceptance.** Rendering, attachment composition, animation and transition readiness require a graphical unchanged-client fixture, and multiplayer correctness may require two live clients in an isolated environment. Use disposable endpoints, public keys, home/prefs/cache/temp/native extraction and test accounts; never patch the production launcher/client. Save and review screenshots or logs where the fixture provides them. Keep the game window focused; platform window-manager and input behaviors can invalidate short action captures.

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
```

Benchmarks copy packaged resources into disposable deployments, simulate clients/inputs and report connection/readiness, ping percentiles, memory/CPU and network traffic. Run on an otherwise idle host. Short local simulations do not establish WAN experience, long-term stability, rendering or full combat parity.

## Editing, packaging and release hygiene

- Keep changes bounded; update tests for behavior and one canonical document for user-facing changes. Do not add a new milestone diary.
- Do not commit installed game assets, third-party material without appropriate rights, private captures, secrets, `data/`, `build/` or `.build/`.
- Review `git status`, `git diff`, `git diff --check` and tests; give exact test results and what remains unverified.
- Bump `VERSION` only for deliberate behavioral releases. Release workflow tags must match `v<VERSION>`; CI tests/builds do not substitute for native graphical verification. See [Operations](operations.md).
- Third-party runtime/license metadata: `src/main/resources/THIRD-PARTY.md`, `licenses/` and `gradle/verification-metadata.xml`. Inspect changes to pinned hashes carefully.

Recent scene and Training Hall validation details are retained in [Modern captures](modern-captures.md) and [Training Hall](training-hall.md). Detailed prior investigations and their original commands stay accessible via Git history (for example `git show <commit>:docs/protocol-audit.md`); [Research](research.md) points to the relevant sources and caveats.
