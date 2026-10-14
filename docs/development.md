# Development workflow (Java 25)

Start with [AGENTS.md](../AGENTS.md), [README](../README.md), [milestone](PROJECT.md); inspect Git and task-specific files. Never touch installed client/launcher, real keys/data/preferences, or owner's live processes. Older detailed test procedures are available in Git history when needed.

## Build and tests

```sh
./gradlew buildServer buildAdmin # server and local admin JARs, no tests
./gradlew build                  # both JARs and full regression (Java 25)
./gradlew testUnit
./gradlew testIntegration
./gradlew testProcessCleanup     # deterministic graceful/forced/interrupted child cleanup
./gradlew prepareContent         # prepare canonical versioned vanilla pack
./gradlew prepareDefaults        # deprecated alias; -PdefaultsOutput remains supported
./gradlew prepareMaps            # canonical vanilla maps only
./gradlew prepareHaven -PgameDir="/path/to/Spiral Knights" -PcapturedScenes="/path/to/captures" -PhavenOutput="/tmp/fresh-haven-maps" # explicit candidate export; output must be fresh
./gradlew clean                  # preserves build/data/, build/content/ and build/deps/
```

Run stock networking/graphics fixtures separately from the Gradle regressions to avoid competing authentication work and socket deadlines.

Standalone regression JVMs share one Gradle build-service slot, including when the configuration cache is reused. This avoids CPU contention from RSA/password hashing triggering local socket deadlines. Compilation and packaging retain normal worker scheduling; each fixture still runs its own concurrent scenarios. No `--max-workers=1` flag is needed for `build`, `testUnit` or `testIntegration`.

`testIntegration` includes `testProcessCleanup`. It compiles the standalone stock
launchers separately from the shipped JARs and checks child exit and interrupted
cleanup with deterministic process doubles. Stock launchers wait for exit after
forcible termination before deleting their disposable trees. Run `buildServer`
before `StockTools previews`, as for item export.

The pinned Gradle Wrapper uses Java 25. Dependency/plugin checksums are in `gradle/verification-metadata.xml`; downloads are cached in Gradle user home. Use `.\gradlew.bat` on Windows. For headless Linux: `env -u DISPLAY ./gradlew build` (not a substitute for graphical tests). Avoid `JAVA_TOOL_OPTIONS`: startup output can break packaged fixture assertions. Tests must use disposable deployments; `./gradlew runServer` or `./gradlew runAdmin` starts real processes/data. Persistent compat tools are **Java**; throwaway work goes under `/tmp`.

Before commit: add focused regression, run `./gradlew build`, run relevant compat/graphical checks, inspect `git diff` and `git diff --check`; update only current docs, commit major milestones, bump `VERSION` for behavioral releases only. Record commands/results, actual limitations and stopped processes.

## Opt-in local desktop acceptance

The local Swing server/account-manager acceptance fixture is outside normal
`build` and `test` tasks. Build its packaged code/classes, then choose a fresh,
nonexistent output folder under `/tmp`:

```sh
./gradlew buildServer buildAdmin testClasses
java --enable-native-access=ALL-UNNAMED -cp 'build/tortodrone.jar:.build/classes/java/test' org.tortodrone.server.DesktopCheck /tmp/tortodrone-desktop-check-0260
```

Run from the repository root on a real graphical desktop with Java 25. The fixture
copies deployment content packs into its disposable folder, creates test accounts,
isolates home/preferences and runs only its own child JVM windows/server. It
checks actual Swing windows, session snapshots, structured log display, command
transactions, catalogue grants, balance changes and stop/start/exit. It retains
`desktop.log` and component-rendered `server.png`, `scenes.png`, `performance.png`, `performance-light.png`,
`catalogue.png` and `inventory.png`. Its captures use Swing component rendering (`printAll`) rather
than screen capture, avoiding the Wayland screencast portal. Review the captures;
they do not prove native desktop compositor/focus behavior or game rendering.

The 0.26.3 fixture also checks stable performance scrolling, graph pause/resume,
theme switching/resizing, log selection during new output, protected inventory
actions, selection across balance/manual refresh and newly created account focus.

This fixture does not launch the stock game. Native stock-client graphical
acceptance remains separate and follows the isolated procedures below. Linux
Swing acceptance and unchanged-client rendering passed for 0.26.0; Windows/macOS
graphical environments were unavailable. See [actual validation](administration-overhaul.md).

## Load testing

```sh
./gradlew benchmark
./gradlew benchmark --args='--seconds 60 --clients 50,100,200,300'
./gradlew benchmark --args='--session-only --clients 50,100,200,300'
./gradlew benchmark --args='--instances 4 --clients 300'
```

Fixture copies JARs and selected content packs into disposable `/tmp` deployments, raises `max-clients` locally, enters town or session-only, sends movement/pings and cleans up its own clients/server. Default 20 s after ramp; `--instances` 1–16. Runs server at 512 MiB heap and 256 KiB thread stacks, measures ping percentiles, updates/client/s, CPU, RSS, thread count, bytes, readiness/failures. Run on idle machine; local synthetic benchmarks **do not prove** production WAN performance, smooth rendering, dynamic combat or long-run stability. 300-player snapshot is in [milestone](PROJECT.md); older detailed measurements are in Git history.

## Stock compatibility and graphics

```sh
./gradlew build
java scripts/compat/CheckClientWorld.java "/path/to/Spiral Knights"
java scripts/compat/CheckCombatRendering.java "/path/to/Spiral Knights" /tmp/combat-render-check
java scripts/StockTools.java previews "/path/to/Spiral Knights"
```

These use the **unchanged installed client classes/resources** as an oracle; stock network fixture ≠ graphical acceptance. When relevant, run the task-specific Java compatibility tools under `scripts/compat/` and document exact output. `WeaponCompatibility` checks Dungeoneer equipment modifier state/deltas; `FireCompatibility combat/fires.json` samples real projectile/effect argument shapes, including orbital deltas. `InteriorConnectionCheck port public-key host maps-dir --weapons` is a small native Arsenal/input/emission check; its disposable LanPlayer/Town-Knight fixture needs Calibur, Proto Gun and Blast Bomb grants. `CheckClientWorld` includes `AdminConnectionCheck`, which drives the original F3 admin dashboard through the stock client: login, service resolution from the bootstrap, the ItemCreator `createItem` RPC, live grant delivery, per-call authorization after a role revocation, refusal reasons and reconnect persistence. All of it uses disposable accounts/data and reads the installed game read-only.

Graphical acceptance is authorized only in an **isolated** environment: disposable server, accounts, game preferences/cache/home/native/temp extraction and endpoint/public key. Read installed game read-only; never patch launcher/client or overwrite real preferences. On niri, `_JAVA_AWT_WM_NONREPARENTING=1`; verify test window/process/focus before synthetic input, then check both local/peer views, movement, inventory/equipment and actual scene transitions. Set stock `resource_dir` to installed read-only `rsrc/` and `java.io.tmpdir` to disposable writable path. Known caveats: niri vsync and virtual gamepad detection; see Git history for earlier environment notes. **Stop only spawned tests**; record inconclusive behavior honestly.

`CheckCombatRendering` requires Java 25, a graphical desktop, the built server JAR
and all seven prepared maps. Its optional output directory must be fresh and outside
the repository and installed game; omitting it creates a fresh `/tmp` directory.
The launcher isolates server data, accounts, home/preferences, cache and native
extraction, including separate profiles for its two stock graphical clients, then cleans up
only its child processes and sandbox. It retains logs and PNG captures in the output directory.
It dismisses the automatic stock Uplink window through its normal API in these disposable profiles.
The unchanged native controller sends movement,
sword/gun/bomb presses and charges, defense, bash and dodge. The fixture checks native
equipment identity, body attachment composition, authoritative activities, playing
activity animations and visible server projectile sprites. Both clients observe shared Haven;
the peer must show displacement of the other knight's actual model transform. The primary tours
all seven modern scenes through normal scene requests, exercises Training and returns to Haven.
Walking through all 18 portals is covered by the separate stock networking fixture.
Append `--training-only` for the focused Training run: native actor sprites, walking to
Konway, both conversation nodes, vial pickup/quickbar/use/throw, and Blitz Needle
normal/charged facing in two directions.
`TrainingCompatibility` uses the stock reader for all prepared entity subtypes, native Training
party type/capacity, health/status resubscription, damage feedback, non-empty knight/lift/status
deltas, conversation frames and focused scene gameplay checks.
`SceneCollisionCompatibility game-dir content/packs/vanilla/maps/advanced_training content/packs/vanilla/combat/fires.json`
compares the prepared native masks, static/dynamic shapes, rotated actor contacts and floor
cells with the installed client.
Compile it together with `FireCatalogExport` and `FireCompatibility`. When using temporary
probe classes, put `.build/classes/java/main` before `/tmp` on the runtime classpath so an
older temporary dependency cannot shadow the current server classes.
The fixture disables vsync in its disposable preferences before native startup to
avoid niri frame pacing stalls. Keep the disposable test window focused; the fixture
refuses unfocused combat checks because slow sampling distorts short presses and poses.
Review the captured pixels as
well: animation playback and sprite visibility flags alone do not prove rendering.
This fixture does not test keyboard bindings or every catalogue weapon. See the
[0.27.0 acceptance record](modern-captures.md) for commands, pixel review and limits.

## Source/resource rules

`VERSION` is bundled in both JARs. `gradle.properties` enables Gradle’s configuration cache. `.build/` is scratch output; `build/content/`, legacy `build/deps/` cache and `build/data/` live state survive clean. Use disposable deployments for migration and content validation; the migration command exports legacy resources without changing selection. See [content packs](content.md). Pin/digest and license provenance: [dependencies](dependencies.md); validated maps: [maps](maps.md); client redirection: [client-redirection](client-redirection.md). Do not commit game assets or copy unlicensed upstream server code.
