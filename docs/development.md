# Development and verification

Start with [AGENTS.md](../AGENTS.md), then the files relevant to your task. Source, current tests and the **unchanged stock client** outrank historical documentation. Keep test accounts, server state, client preferences and processes disposable; do not modify the owner's installed client or active deployment.

## Verification policy (FAST by default)

Choose the **least expensive check that proves the requested change**. Do not run the full test suite or launch the stock graphical client after every edit.

1. Check the specific source and add/update a focused regression for behavior changes.
2. For code changes use `./gradlew testUnit -PunitTests=AffectedTest,OtherAffectedTest` to compile/package and run only directly relevant programs. Use `./gradlew build` when checking compilation/packaging alone. For docs-only changes, review links and diffs instead.
3. Escalate to focused integration for auth, storage, trades, protocol, cross-client synchronization and other high-risk paths. Include negative cases, ownership and rollback.
4. Run one isolated stock-client/graphical acceptance scenario only when the change affects behavior that cannot be verified otherwise (rendering, animations, HUD, live scene transitions). Do **not** start repeated graphical sessions for backend-only changes. If graphical acceptance is necessary but unavailable, report it as **not verified**, not passed.
5. Reserve a complete `./gradlew testAll` for significant cross-cutting changes, explicit release readiness or a requested full regression, **once at the final checkpoint**. A routine patch `VERSION` bump alone does not trigger the full suite. Benchmarks and other expensive checks are opt-in.

Stop when the task's acceptance criteria pass. Avoid repeated successful suites, unrelated test coverage, speculative bug hunts and `clean` unless rebuilding from scratch is necessary.

## Build and test (JDK 25)

```sh
./gradlew build                  # DEFAULT: server JAR, WebUI + prepared content; no tests
./gradlew buildServer            # package the server, WebUI and unified content
./gradlew testUnit               # all standalone unit tests (not a focused filter)
./gradlew testUnit -PunitTests=SceneSimulationTest,MovementCollisionTest # focused compile/package + tests
./gradlew testIntegration        # all packaged integration tests
./gradlew testAll                # complete unit + integration suite; milestone/release verification
./gradlew testProcessCleanup     # focused cleanup integration task
./gradlew prepareContent         # offline source validation/copy
./gradlew prepareMaps
./gradlew benchmark --args="--seconds 20 --instances 4 --clients 300" # explicit performance work only
```

### Running just the affected tests

Tortodrone's unit tests are standalone `main` programs, **not JUnit**. `./gradlew testUnit --tests ...` is not supported. Prefer `-PunitTests=SceneSimulationTest,MovementCollisionTest`; fully qualified names also work. Unknown, empty or ambiguous names fail instead of silently running zero tests. Without the property, `testUnit` still runs every unit program. Programs run sequentially, report elapsed time and list the five slowest programs; authentication, storage and rollback coverage is retained.

`build` packages the single server JAR, WebUI and prepared content. Unit and packaged WebUI/console integration tests use that same executable. Map preparation integration reuses compiled `BuildTools` instead of invoking Java's source compiler for each fixture operation.

To keep an existing deployment untouched, use `-PdeploymentOutput=/tmp/tortodrone-check` with build/test tasks. The JAR, frontend and prepared configuration/content go there, while Gradle compilation stays in `.build/`. Test output redirection does not upgrade an existing `build/content/packs` deployment.

The bundled `StandaloneTestSuite` also accepts fully-qualified test classes directly. From the repository root (Linux/macOS):

```sh
./gradlew build testClasses
java --enable-native-access=ALL-UNNAMED \
  -cp 'build/tortodrone.jar:.build/classes/java/test' \
  org.tortodrone.support.StandaloneTestSuite \
  org.tortodrone.world.SceneCatalogTest \
  org.tortodrone.world.SceneSimulationTest
```

Windows PowerShell/CMD (classpath uses `;`):

```powershell
.\gradlew.bat build testClasses
java --enable-native-access=ALL-UNNAMED -cp "build/tortodrone.jar;.build/classes/java/test" org.tortodrone.support.StandaloneTestSuite org.tortodrone.world.SceneCatalogTest org.tortodrone.world.SceneSimulationTest
```

Replace the example classes with the specific tests for your change; do not run them all by default. This uses the same packaged JAR and compiled test output as the Gradle unit suite. Tests which need arguments or packaged servers have their own named Gradle integration tasks.


Windows: use `.\gradlew.bat`. The wrapper and `gradle/verification-metadata.xml` pin distribution/dependency integrity. First build needs Gradle/Maven/plugin downloads; cached inputs support `--offline`. `build` and the explicit test tasks **do not** run native stock graphical checks. Unit programs run sequentially in one JVM; packaged integration fixtures use separate processes. A shared build-service slot serializes regression runs; don't run competing builds in the same checkout.

`runServer` operates on **real `build/data/`** and are not disposable tests. `clean` removes generated JARs/scratch, but **preserves** `build/data/`, `build/content/` and legacy `build/deps/`. If using headless Linux, `env -u DISPLAY ./gradlew build` is possible; it is not graphical acceptance.

## Test by change type

| Change | Default checks | Escalate only when needed |
| --- | --- | --- |
| Docs/comments/refactor with no behavior change | Link/diff checks; compile only if code was touched | None |
| Ordinary Java/content change | `build` + affected standalone tests | Related integration if behavior crosses services |
| Protocol, auth, storage, trades | Targeted positive/negative/ownership/rollback tests | Relevant packaged integration or stock-reader check |
| Scene or portal logic | Map/simulation tests | Stock scene enter/return or two-client check for changed transitions |
| Items/combat | Targeted catalog, damage/equipment tests | One isolated graphical test if modifying actual client-visible effects |
| WebUI | Focused HTTP/security/domain tests | Disposable HTTPS browser fixture |
| Performance/concurrency | Focused correctness/race checks | Benchmark only for explicit performance questions |
| Major cross-cutting change / release | `./gradlew testAll` once at the end | Relevant stock-client acceptance |
| Documentation only | Diff and link/source review | No Java tests |

Stop **only test processes you started**, and report which checks were skipped, failed or unavailable. Use `git diff --check` before completion. Never claim graphical parity from a passing network fixture.

## Stock client compatibility

Useful checked-in Java tools (from repository root with a separate read-only game installation):

```sh
./gradlew build
java -cp build/tortodrone.jar scripts/compat/CheckClientWorld.java "/path/to/Spiral Knights"
java -cp build/tortodrone.jar scripts/compat/CheckCombatRendering.java "/path/to/Spiral Knights" /tmp/tortodrone-combat-check
java scripts/StockTools.java previews "/path/to/Spiral Knights"
```

`scripts/compat/` also contains narrower handshake, account, scene, portal, inspection, trade, item, emote, weapon, fire and protocol fixtures. Read each tool's source/usage before running it: some launch temporary servers and clients; some only inspect installed classes/configs. The exporter `scripts/StockTools.java` handles `scene`, `items`, `weapons`, `fires`, `equipment` and current source-maintainer scene/capture subcommands in read-only game input mode with a fresh output directory. `previews`/`items` require a built server JAR.

**Stock reader/network acceptance ≠ graphical acceptance.** For a change to rendering, attachment composition, animation or transition readiness, one isolated unchanged-client graphical scenario may be necessary; for altered shared-instance behavior, test with two clients. This is **not** a default validation step for unrelated tasks. Use disposable endpoints, public keys, home/prefs/cache/temp/native extraction and test accounts; never patch the production launcher/client. Save and review screenshots or logs where the fixture provides them. Keep the game window focused; platform window-manager and input behaviors can invalidate short action captures.

`CheckCombatRendering` uses a fresh output path, records logs/screenshots and exercises sword/gun/bomb controls and Haven↔Training travel. It does not verify every weapon, moving-target hits, full parties or general dungeon mechanics. Source-client binary/current wire observations matter more than an old 2011 marshaller ID table.

## WebUI verification

`./gradlew testUnit -PunitTests=WebIdentityStoreTest,WebUiTest,WebAdminServiceTest,ServerSettingsTest,MonitoringTest,CompanionInspectionTest` exercises disposable HTTP, credential and domain fixtures. `./gradlew integrationWebUi integrationConsole` verifies packaged first-run setup, WebUI identity management, console recovery while the listener is stopped and ordinary local administration. For browser acceptance, use a disposable deployment with explicitly configured direct HTTP or the same-host TLS proxy described in [Operations](operations.md#secure-webui-setup), never live player data. Frontend sources live in `src/webui/`; `buildWebui` packages them beside the JAR, including custom `deploymentOutput`.

The framework-free WebUI uses shared components in `assets/components/`, page controllers in `assets/pages/`, and CSS tokens/layout/components/pages. A page mounts once; its `refresh` updates existing nodes. Polling refreshes are serialized at the page boundary, obsolete page reads are aborted, stale navigation results are discarded, and polling pauses in hidden tabs. Knight inspection probes revisions and live companion state every six seconds through `knight-state`, fetching the heavy profile only when its identity/revision changes; metric samples are collected server-side every five seconds with one hour of bounded in-memory retention. `MetricHistoryTest` covers retention and session counters. Knight tab filters and open Sprite details survive refreshes. Unsaved page and editor drafts require an explicit discard on navigation; stale revisions preserve drafts and disable saving. Console drafts and history remain in memory until sign-out. Dark/light theme and comfortable/compact density are the only locally persisted UI preferences.

`node --test src/test/webui/news-test.mjs src/test/webui/chart-test.mjs src/test/webui/table-test.mjs` checks visual News serialization against native optional-action rules, timestamp-based chart inspection across sampling gaps, and stable table sorting with one value evaluation per row. For reusable browser regressions, copy `src/test/webui/acceptance.js` into a disposable fixture’s `webui/assets/` directory, sign in there, then call `import('/admin/assets/acceptance.js').then(m => m.run(group, {disposable: true, username, knight}))` in the browser for groups `navigation`, `gear`, `polish`, `news`, `grants`, `console` and `refresh`; run `pages` at desktop and mobile viewport widths. Use a fixture Knight with starter gear and an owned Sprite. The `polish` group additionally expects The Ban Stick at stored level 4 with Attack Speed 4, Beast Damage 4 and Charge Time 4 in weapon slot 6, and Fiery Diskgun in slot 7. These checks edit gear, attach an accessory and publish generated test News images; never run them against owner data or package the test module. Copy `src/test/webui/refinements.js` beside the fixture’s `api.js` and call `run({disposable: true})` and `runTerminal({disposable: true})` for keyed stats, delayed/stale previews, Level/Heat precision, UV compatibility, terminal retention and scroll/focus checks. Copy `src/test/webui/shell.js` there and call `run({disposable: true})` at desktop and mobile widths for refresh recovery across navigation, Skip to content and mobile drawer dismissal/focus checks; this group needs only a signed-in fixture administrator. These use actual item definitions and a disposable loopback session. `node --check <module.js>` checks individual frontend modules without a build toolchain. Frontend sources use four-space formatting (Prettier 3.6.2, single quotes, print width 100, no trailing commas); this tooling is optional and is not part of production deployment.

`knight-state`, `knight-view`, `knights-all`, `catalogue`, `item`, `item-definitions`, `accessories` and `item-preview` are read-only adapters over existing services. Level display is stored level + 1; Heat remains the native fraction. Gear draft validation, compatible UV choices and current/draft stats come from domain rules. `content/shared/presentation.yml` contains deduplicated native tooltip ratings, separate built-in bonuses, localized UV labels/ranks and Sprite skill/perk names. Regenerate read-only with `java scripts/StockTools.java webui-presentation "/path/to/Spiral Knights" content /tmp/fresh-presentation.json` and review the data before replacing the YAML (JSON syntax is valid YAML). Damage bars use the native depth-30 tooltip reference, not promised damage against every target. No client classes or artwork are embedded in this metadata. Raw data stays under Advanced. The shared gear editor keeps its controls and keyed stat rows mounted while previews update; maximum-level gear omits its Heat meter. UV rows use native labels and circular symbols, while built-in equipment bonuses use cyan. Console commands and live logs share one bounded terminal with common search, filtering, copy/export and scroll controls; the standalone Logs page remains available.

WebUI `equipment` uses the shared equip transaction, owned item/slot checks, unlock rules, bind-on-equip and current world revision. Grant-and-equip allocates and equips in the same SQLite transaction. Saved loadout snapshots remain unchanged. These administrative equipment operations require the account to be signed out: the account attachment lock prevents a login racing the write. Live Arsenal equipment changes remain game-owned and appear through the revision probe; WebUI explicitly refuses online equipment edits. `WebGearTest`, `NamedLoadoutsTest` and `InventoryExpiryTest` cover the affected paths. Accessories use the resolved item-ticket artwork. Sprite inspection exposes owned/selected state and the native icon tier; active deployment, follow state, shield and recharge come from the existing scene-owned companion. Stopped, disconnected, wrong-owner or changed-destination sessions expose no live state. Inspection never advances simulation or allocates an instance; offline selection does not imply deployment. `PetCombatTest` and `CompanionInspectionTest` cover these guards. News provides a visual three-slot editor (570×300, 280×190 twice), an advanced YAML editor, and PNG/JPEG uploads limited to 1 MiB, 128 library images and 32 MiB total. Saves retain schema validation, revisions and atomic publication; a listener restart publishes a changed pack selection.

Optional original artwork preparation is local and read-only with respect to the client:

```sh
java scripts/StockTools.java webui-assets "/path/to/Spiral Knights" local/webui-assets
python scripts/OptimizeWebUiAssets.py local/webui-assets local/webui-assets-optimized
./gradlew build
```

The optional optimizer requires Pillow with WebP support. Appending a previous raw pack and its optimized pack reuses unchanged images after checking content hashes, image roles and optimization settings. The stock client's resolver and colorizer supply exact item/Sprite mappings; original HUD navigation/action icons, empty-slot category icons and Sprite skill icons are also exported; images are resized, content-hash deduplicated, and recorded in `manifest.json`. `buildWebui` overlays `local/webui-assets-optimized`, or an explicit `-PwebuiAssets=/prepared/path`, into `build/webui/assets/game/`. Neither the client nor Python is required at runtime or for ordinary builds. The exported `symbols` map includes the native Trojan horse mark, HUD navigation/actions and category glyphs with their material-mask backing removed. SVG tinting makes them inherit cyan, active gold or destructive red from the control, including Battle Sprites and both themes. The complete images and manifest are copied into the deployment: neither the server machine nor the browser needs an installed client or the preparation directory. Copy the entire generated `webui/` beside the JAR. Clean source builds without a prepared pack retain source-owned outline navigation, a horse mark and item/UV fallbacks; client artwork remains outside Git. Prepared client artwork stays under ignored `local/`; do not commit it or redistribute it without the necessary rights. Run `node src/test/webui/assets-test.mjs /path/to/deployment/webui` against a copied deployment to verify all original images, native symbols and content hashes without a client installation. Copy `src/test/webui/icons.js` beside a disposable fixture’s `api.js` and call `run({disposable: true})` for native/fallback loading and theme inheritance; visually inspect both themes. Static modules and image/font MIME types remain confined to the symlink-free asset directory; hashed icons are cacheable, while private APIs remain uncached.

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

Current Training Hall behavior and verification are in [Training Hall](training-hall.md); bundled scene provenance and evidence standards are in [Research](research.md). Detailed prior investigations and their original commands stay accessible via Git history (for example `git show <commit>:docs/protocol-audit.md`).

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
