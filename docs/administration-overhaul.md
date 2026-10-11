# Administration overhaul audit and plan

Audit baseline: 0.25.6, schema 8, commit `158bf7d`, clean `dev` checkout
(already two commits ahead of origin). Java 25; no player data or client changes.

## Existing architecture and reusable behavior

- `Server.main/run/accept` owns deployment locking, resources, listener, account
  watcher, terminal and handler drain. `ClientConnections.close` joins handlers
  before data/log/lock cleanup. GUI lifecycle must preserve this ordering.
- `SecureHandshake` verifies ordinary credentials; `AccountStore.attach` owns
  single-account takeover and revision invalidation. `ClientSession` and
  `WorldSession` own stock service routing and world membership. Monitoring must
  observe these transitions, never infer authentication from TCP or log strings.
- `Database` uses schema 8, WAL and `BEGIN IMMEDIATE`, with scoped connections.
  Currency, grants, equipment, revisions and audits already share transactions.
  Absolute currency updates and inventory administration belong here, without
  schema migration. `WorldStatePoller` already publishes committed live changes.
- `ItemCatalog` validates external v1/v2 JSON; 9,348 definitions, 8,570 grantable
  in the bundled snapshot. Canonical paths, stock IDs, variants and model args
  already drive serialization. Friendly identity is derived presentation only.
- F3 ItemCreator is implemented on stock service 13, method 7, with GM checks
  inside grant transactions. Console and GM chat share `ItemCommands`.
  Client slash commands are handled by the stock client; server terminal `/`
  syntax does not establish an in-game command transport.
- Costumes already use slots 3/4/15 and override base appearance. Existing native
  compatibility fixtures cover composition and costume equip. Investigate gaps
  using these fixtures before changing behavior; no speculative slot remapping.
- `AdminWindow` uses SwingWorker for account and currency operations and captures
  persistent identities on mutations. It lacks a selectable knight/inventory
  browser, catalogue dialog and absolute currency operation.
- `SceneCatalog/SceneRegistry/SceneSimulation` own checked templates, runtime
  instances and actual member counts. Keep external maps and provenance intact.
- `ServerLog` distinguishes normal/debug but lacks bounded rotation and structured
  subscriptions. Session correlation and normal connection events are incomplete.
- Gradle builds both executable JARs and discovers standalone regression main
  programs. Packaged integration tests use disposable deployments. Existing
  process cleanup and LF resource rules address Windows locks/checksums.

## Implementation sequence and acceptance

1. Audit and baseline full build; record existing behavior and limits here.
2. Derived friendly names, deterministic aliases/collision disambiguation,
   catalogue search/pages and concrete unsupported reasons. Keep the grantable
   set and original resource bytes unchanged; test ambiguous references.
3. Structured operational log subscriptions, bounded UTF-8 rotation, portable
   host metrics and session correlation. Never retain credentials/payloads.
4. Small immutable session/status/scene snapshots fed by existing lifecycle
   callbacks. No network I/O or Swing work on gameplay threads.
5. Shared dispatcher for terminal/GUI, `/grant`, transactional `/set`, session
   and knight inspection; retain legacy commands and explicit transport docs.
6. Optional Swing server panel with real status, players, scenes, performance,
   bounded docked console and graceful stop/start. Explicit `--nogui` bypasses
   desktop initialization; fixtures must request terminal mode.
7. Account → knight → inventory views, friendly catalogue grants, transactional
   quantity/removal controls and shared currency operations. Destructive actions
   require confirmation; committed revisions synchronize supported live changes.
8. Verify costume persistence, invalid operations, native appearances/updates;
   record any unsupported stock behavior rather than inventing it.
9. Retain useful legacy commands, mark replacement syntax in accurate help/docs.
10. Full build, native compatibility and isolated graphical checks where available;
    review diffs and commit focused releases. Windows/macOS execution must be
    reported as unverified unless those environments are actually available.

Source and tests remain authoritative; this plan is not a completion claim.

## Completed release: 0.26.0

The audit and implementation are complete. The existing server, authentication,
SQLite transactions, revision publication, native inventory/equipment services,
scene registry and Swing account manager were extended rather than duplicated.
No dependency, schema migration, client wire identifier or stock content change
was introduced. The initial unrelated two commits ahead of origin were preserved;
no branches were pushed, merged or deleted.

| Milestone | Implemented result |
| --- | --- |
| Audit | Lifecycle, authentication, storage, item/equipment, F3, scenes, CLI, GUI and build dependencies reviewed; baseline build passed |
| Item usability | Derived friendly names, actual categories, deterministic unique aliases, collision choices, search/inspection/pages and concrete unsupported reasons |
| Logging | Measured host/startup summaries, correlated connections and verified account/knight identities, scene transitions, safe debug diagnostics, structured events and bounded UTF-8 rotation |
| Monitoring | Immutable session snapshots, real lifecycle/status/JVM metrics, loaded templates and runtime instance/member counts |
| Commands | Shared terminal/GUI dispatcher; optional local `/`, `grant`, transactional `set`, `players`, `session`, `inspect`, expanded `status` and item inspection |
| Server desktop | AUTO/GUI/terminal launch selection; players/details, scenes, performance, docked bounded console and graceful stop/start/restart/exit |
| Account desktop | Account/knight selection, numeric knight counts, searchable inventory/catalogue, validated grants, confirmed quantity/removal and Add/Remove/Set balances off the EDT |
| Compatibility | Native costume helmet/armor/shield equip, unequip, updates and reconnect; supported live inventory mutations checked against stock observers |
| Consolidation | Shared operations and accurate transport/help documentation; legacy commands deliberately retained with recommended replacements |
| Final verification | Full Java 25 build, independent review, native compatibility, real desktop acceptance and stock game rendering passed on Linux |

## Data and compatibility findings

The bundled catalogue still contains **9,348 definitions and 8,570 grantable
entries**. All 9,348 aliases are unique. The remaining **778** entries currently
report **No verified inventory slot**; they remain indexed, inspectable and
ungrantable. Other validation refusals cover unsupported upgrade records,
missing artifact seeds, stackable equipment and missing representable models
when those conditions occur in a supplied catalogue. No correctness check was
bypassed to enlarge the grantable set.

Canonical paths and IDs are extracted identity. Leaf display names, category
prefixes, normalized aliases and collision digests are derived presentation.
Server implementations remain reconstructed behavior wherever no upstream
implementation exists; no reconstructed balancing/content values were inserted
into extracted catalogues. Adding a new alias collision can change an unsuffixed
alias; canonical paths are the durable reference.

Costume slots were already implemented. The expanded unchanged-client checks
accepted slots **3, 4 and 15**, ordinary-slot rejection, native equip/unequip,
appearance updates, saved outfits and reconnect. No costume server defect was
reproduced, so no speculative equipment remapping was made. The native saved
loadout has twelve slots; shield costume slot 15 uses the separate player
equipment array. The acceptance fixture now respects this distinction.

Live stack adjustment and recipe removal/re-grant reached native inventory
observers and persisted. Existing revision publication remains authoritative.
Starter, permanent and equipped instances are protected; the GUI does not force
equipment changes. Authenticated GM/F3 permission checks remain transactional.

The original `defaults/` resources, manifest/provenance/checksum bytes and external
map packaging are unchanged. Known required-pack completeness checks were
reviewed and retained as the release contract; runtime scene browsing uses the
loaded catalogue rather than a new hardcoded scene list. SQLite schema stays 8.

## Actual validation, 2026-10-11

Environment: Linux amd64, Eclipse Temurin **25.0.4.1+1**, real niri/Wayland desktop.
All deployments, accounts, copied resources, keys, home directories and preferences
used for acceptance were disposable. Only processes started by the checks were
controlled; the installed client and owner preferences/data/processes were untouched.

| Command/check | Result and scope |
| --- | --- |
| `./gradlew build` baseline | PASS, 69 tasks, before behavioral changes |
| Focused item/admin/currency, console/server/log/model Gradle tasks | PASS; deterministic aliases, collisions, ambiguity, pagination, inventory identity, guarded mutations and shared operations |
| Final `./gradlew build` | PASS, 3m47s, 74 tasks; 61 standalone unit programs and four packaged integrations (`integrationAdmin`, `integrationConsole`, `integrationMaps`, `integrationServer`) |
| `./gradlew compileTestJava` after strengthening the opt-in desktop fixture | PASS; final optional fixture compiled separately from normal build/test execution |
| `java --enable-native-access=ALL-UNNAMED -cp build/tortodrone.jar:.build/classes/java/test org.tortodrone.server.DesktopCheck /tmp/tortodrone-desktop-final` | PASS; actual Swing windows, overview dimensions, verified account rows, scene/performance snapshots, logs, direct commands, grants, balances, active-connection drain, stop/start and exit |
| `java scripts/compat/CheckClientWorld.java <installed-game-directory>` | PASS; installed unchanged stock readers and networking, catalogue extraction/serialization, portals, interiors, GM/F3, tells, emotes, concurrent instances, costume slots/live inventory and trade |
| `java scripts/compat/CheckCombatRendering.java <installed-game-directory> /tmp/tortodrone-graphics-overhaul-2` | PASS; Haven/Training/return, all nine combat actions, native animations and server projectile sprites |
| Disposable catalogue audit | PASS; 9,348 indexed, 8,570 grantable, 9,348 distinct aliases; all 778 unavailable entries classified |
| Diff/whitespace review | PASS; original CRLF in `AdminIntegrationTest` retained and checked with `cr-at-eol`; no static resources, runtime data, assets, secrets or build output staged |

For the stock commands, the argument was the locally installed Steam Spiral
Knights directory, read only. Final full-build output is retained at
`/tmp/tortodrone-release-build.log`; native networking output at
`/tmp/tortodrone-stock-overhaul-2.log`. Desktop component renders/logs are in
`/tmp/tortodrone-desktop-final`; stock OpenGL captures/logs are in
`/tmp/tortodrone-graphics-overhaul-2`. These are local review artifacts, not
repository assets or committed player data.

The initial desktop screen-capture attempt blocked in Wayland's screencast portal;
the final fixture renders live Swing content directly and verifies its layout
dimensions. It does not capture compositor decorations/focus behavior. The first
combat-rendering run accidentally walked through Training Hall's real south exit
after cumulative combat movement. The fixture now uses the stock controller's
verified north keyboard axis to move into the corridor before combat; all original
rendering/projectile assertions remain. Neither issue required a gameplay change.

Concurrency acceptance uses independent SQLite stores, `BEGIN IMMEDIATE`, audit
failure rollback, stale identity guards and reopen checks. Packaged integrations
also assert password/transform redaction, normal/debug separation, unique identity
scopes, closed stdin, process drain/restart, headless GUI refusal and no Swing/toolkit
class loading under `--nogui` even with `java.awt.headless=false`.

## Changed files and logical commits

Paths below are relative to the repository. Grouped basenames share the stated
directory; this list covers source, tests, tools and release documentation.

- Root: `VERSION`, `README.md`.
- `docs/`: `administration-overhaul.md`, `PROJECT.md`, `administration.md`,
  `development.md`, `game-data.md`, `server-architecture.md`, `commands.md`,
  `logging.md`, `server-gui.md`.
- `src/main/java/org/tortodrone/account/`: `AccountStore.java`.
- `src/main/java/org/tortodrone/admin/`: `AccountTableModel.java`,
  `AdminApplication.java`, `AdminWindow.java`, `DesktopBootstrap.java`, `KnightPanel.java`.
- `src/main/java/org/tortodrone/character/`: `ItemCatalog.java`.
- `src/main/java/org/tortodrone/config/`: `ServerSettings.java`.
- `src/main/java/org/tortodrone/server/`: `ConsoleHelp.java`, `ItemCommands.java`,
  `RuntimeMetrics.java`, `Server.java`, `ServerConsole.java`, `ServerControl.java`,
  `ServerLog.java`, `ServerOptions.java`, `ServerWindow.java`, `SessionRegistry.java`.
- `src/main/java/org/tortodrone/server/network/`: `ClientSession.java`,
  `SecureHandshake.java`, `SessionLog.java`, `WorldSession.java`.
- `src/main/java/org/tortodrone/storage/`: `Database.java`.
- `src/main/java/org/tortodrone/world/`: `SceneRegistry.java`.
- `src/main/resources/`: `server.properties`.
- `src/test/java/org/tortodrone/admin/`: `AccountTableModelTest.java`, `InventoryModelTest.java`.
- `src/test/java/org/tortodrone/character/`: `AdministrationTest.java`, `ItemIdentityTest.java`.
- `src/test/java/org/tortodrone/server/`: `DesktopCheck.java`, `MonitoringTest.java`, `ServerLogTest.java`.
- `src/test/java/org/tortodrone/integration/`: `AdminIntegrationTest.java`,
  `ConsoleIntegrationTest.java`, `ServerIntegrationTest.java`.
- `src/test/java/org/tortodrone/benchmark/`: `LoadBenchmark.java` (terminal launch flag only).
- `scripts/compat/`: `CheckClientWorld.java`, `CheckCombatRendering.java`,
  `CombatRenderingCheck.java`, `ItemGrantConnectionCheck.java`.

Completed implementation commits: audit `4c06306`; item usability `dfcb7e0`
(0.25.7); transactional administration `93db2d0` (0.25.8); Swing inventory
`252313e` (0.25.9); monitoring/logging/commands/server desktop `3b56183` (0.26.0);
native compatibility fixtures `f24665c`. Final documentation records this acceptance;
its commit is available in Git history and the engineering handoff.

## Limits and recommended follow-up

- Windows/macOS execution was unavailable. Run the build, closed-stdin/shutdown,
  SQLite/JAR-lock and real desktop acceptance there before claiming native support.
- Availability diagnostics do not implement unsupported stock types or item-use
  actions. Research the 778 unverified slots against stock evidence before enabling
  anything; no custom content database or balancing system was added.
- Costume protocol/model/persistence checks are not graphical acceptance of every
  cosmetic asset. Extend native Arsenal/world rendering checks for additional types
  when investigating a reproducible appearance defect.
- The account database does not track last-login/disabled status/live scene state.
  Standalone admin does not fabricate online fields. Session location belongs to
  the running server registry. No new quests, progression or rewards were invented.
- Supported live mutations use existing native publication; forced equipment edits
  remain client operations. External admin changes are durably audited in SQLite,
  rather than promised as a cross-process live GUI/log event feed.
- Desktop snapshots refresh once per second, with bounded histories; CPU/physical
  RAM metrics can be unavailable. Client hardware and unmeasured latency are absent.
- Stop preserves the local window for Start; settings require process relaunch.
  There is no detached/background JVM or tray hosting mode.
- Legacy commands were retained after sharing their backend operations. Further
  removal should be a deliberate compatibility decision after usage evidence.
