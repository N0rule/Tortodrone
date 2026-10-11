# Repository cleanup audit

Audit date: 2026-10-11. Baseline: `aa0f95c`, version 0.25.5, SQLite schema 8,
Java 25.0.4.1, clean working tree. Baseline `./gradlew build` passed on Linux
(67 tasks, 3m 3s). Windows and macOS were not tested.

## Scope and component boundaries

Reviewed 56 production Java files, 65 test/support/benchmark files, Gradle Kotlin DSL,
wrapper and dependency verification, Java preparation/export/compatibility tools,
configuration, resource manifests, Git attributes and documentation. Checked map
pack paths and integrity through the baseline preparation and regression tests;
binary assets were not edited. Runtime data and the installed game were excluded.

- `Server` owns the deployment lock, listener, account watcher and client handlers.
  `SecureHandshake` performs the stock RSA/AES and normal-password exchange;
  `ClientSession` owns subscriptions and ordered `SessionOutput` frames.
- `WorldSession` routes selected-character operations. `SceneRegistry` owns
  instances, `SceneSimulation` owns the shared clock/state, and per-recipient
  delivery uses immutable snapshots. `WorldStatePoller` batches storage revisions.
- `AccountStore` owns authentication/session leases; `CharacterStore` owns roster
  publication. `Database`, `WorldProfiles` and `TradeTransfer` scope JDBC resources
  and transaction/ownership checks. SQLite connections are opened per operation;
  there is no persistent connection pool to close at store shutdown.
- The admin application shares storage, without an admin network endpoint.
  Build tooling prepares external defaults; stock tools launch disposable probes
  against read-only client resources. Gradle runs standalone test JVMs serially
  using its regression build service.

## Prioritized plan (before implementation)

1. **Maintainer process cleanup and preview command — verified, low risk.**
   `StockTools.previews` invokes `make build-server` although the Makefile was
   removed. Replace this with the existing packaged-JAR prerequisite pattern.
   The cleanup methods in `StockTools`, `CheckClientWorld` and
   `CheckCombatRendering` can return while a forcibly terminated process is still
   alive, especially after interruption; scratch deletion then races open JARs,
   SQLite files and extracted native libraries. A deterministic `/tmp` Java probe
   reproduced this for all three methods before edits. Establish exit after force
   termination, remember and restore interruption, and cover the paths with
   focused regressions. Consolidate the repeated server-readiness checks in
   `CheckClientWorld` while preserving their deadlines and failure diagnostics.
2. **Server handler ownership at shutdown — verified ordering gap, moderate risk.**
   `Server.accept` closes sockets but never joins client handlers. It can return
   to account/log cleanup and release `server.lock` while authentication or session
   teardown is still running. Track owned socket/thread pairs, refuse new work
   once closing begins, and drain handlers outside monitors used by those handlers.
   Coordinate the shutdown hook with ordinary cleanup without holding the log
   monitor across joins. Verify blocked cleanup, concurrent close, interrupt
   preservation and packaged stop/closed-stdin behavior.
3. **Documentation and proven dead imports — verified, low risk.**
   README and architecture still describe the 0.21.0 scope and exclude implemented
   trade/interior controls. Refresh current scope and ownership descriptions.
   Remove the unused `HandshakeCodec` import in `SessionOutput`; retain public
   compatibility overloads and test entry points. A private, uncalled `ints` array
   writer in `SessionCodec` was also identified by a reference scan and removed.
   No unused dependency or safely removable public class was established.

Each milestone receives focused checks, diff review and a commit; final acceptance
includes `./gradlew build`. No dependencies, wire fields, SQL schema, item/map
formats, gameplay rules, CLI syntax or deployment layout need changing.

## Deliberately deferred findings

- The optional default-recovery URL is a documented placeholder. Choosing a
  maintained hosting source/trust policy is an owner decision; replacing it or
  silently disabling recovery would exceed this behavior-preserving cleanup.
- `WorldSession`, `SessionCodec` and `Database` have broad responsibilities.
  Routing/serialization/SQL extraction is a possible later milestone, but size
  alone does not prove a bug. Any wire/world change requires stock evidence and
  relevant graphical acceptance. Cosmetic repository-wide reformatting is avoided.
- `prepareMaps` and `prepareDefaults` share a destination and have no explicit
  mutual ordering. They use separate JVMs and OS locks, so same-JVM overlapping
  lock exceptions are not the mechanism. A false active-server refusal during
  parallel preparation is a hypothesis, not a reproduced data corruption issue.
- Static trade/session registries and executor shutdown deserve longer-running
  churn measurements. Existing ownership and detach tests pass; no protocol,
  authentication or trade-rule changes are justified by speculative leaks/races.
- UTF-8 is explicit for configuration/resource bytes and most redirected input;
  interactive console rendering still depends on terminal capabilities. Do not
  infer Windows/macOS acceptance from Linux results or normalize hashed resources.
- Filesystem-specific atomic moves, hard links, native extraction and WAL/SHM
  locking require actual Windows/macOS runs. Existing LF rules pin hashed default
  text, and map/source manifest validation must remain intact.

## Validation record

All three selected plan items are implemented. Version 0.25.6 records the verified
shutdown/lifecycle bug fixes. No source/data format or gameplay rule changed.

Changed files:

- `scripts/StockTools.java`, `scripts/compat/CheckClientWorld.java`,
  `scripts/compat/CheckCombatRendering.java`: child-exit/interrupt cleanup,
  shutdown-hook removal race handling, preview JAR prerequisite, and consolidation
  of two readiness loops into the existing helper. Removed its unused Duration
  import. Standalone launchers retain their independent source-launch invocation.
- `scripts/ProcessCleanupTest.java`, `build.gradle.kts`: deterministic subprocess
  regressions, including multiple children after interruptions, wired into
  `testIntegration` and therefore `build`. Build-tool classes stay outside both
  shipped JARs; external defaults packaging/preparation is unchanged.
- `src/main/java/org/tortodrone/server/ClientConnections.java`, `Server.java`:
  socket/thread lifecycle ownership and drain ordering. Joins hold neither the
  registry nor log monitor. Late registrations are refused and their sockets closed.
- `src/test/java/org/tortodrone/server/ClientConnectionsTest.java`,
  `src/test/java/org/tortodrone/integration/ServerIntegrationTest.java`: blocked
  cleanup/concurrent close/interrupt checks and packaged partial-handshake stop.
- `src/main/java/org/tortodrone/protocol/SessionCodec.java`,
  `src/main/java/org/tortodrone/server/network/SessionOutput.java`: remove one
  uncalled private array writer and one unused import. No files/classes were deleted.
- `VERSION`, `README.md`, `docs/PROJECT.md`, `docs/development.md`,
  `docs/server-architecture.md`, this audit: current scope, lifecycle/test workflow,
  catalogue v2 writing/v1 reading, prioritized findings and limitations.

Completed focused validation on Linux/JDK 25.0.4.1:

- Pre-edit `/tmp` cleanup probe reproduced returning with an alive child after
  interrupted termination for all three launcher entry points. The new regression
  fails against the baseline at `FORCED_WAIT_INTERRUPTED` and passes changed code.
- Pre-edit disposable packaged-server probe printed `Server stopped.` before
  `Client disconnected`. The enhanced integration assertion now requires the
  opposite ordering and exactly one final stop message.
- A post-change `/tmp` Python probe sent SIGTERM only to its own disposable server
  with an active partial handshake. Linux hook validation passed: client EOF,
  handler disconnect before final log, exactly one stop message, process exit 143
  (SIGTERM), and scratch removal after exit.
- `javac -d /tmp/tortodrone-process-cleanup/primary scripts/StockTools.java
  scripts/compat/CheckClientWorld.java scripts/compat/CheckCombatRendering.java
  scripts/ProcessCleanupTest.java`, then
  `java -cp /tmp/tortodrone-process-cleanup/primary ProcessCleanupTest`: passed.
- `./gradlew unitserver_ClientConnectionsTest integrationConsole integrationServer`:
  passed (1m 1s); includes closed subprocess stdin and packaged restart/key checks.
- `./gradlew testProcessCleanup testBuildTools unitprotocol_BootstrapTest integrationServer`:
  passed (59s); includes the enhanced shutdown regression, HTTP stalled-body cleanup,
  checksum refusal, and shipped protocol schemas/mappings.
- `java scripts/StockTools.java previews "/home/n0rule/.local/share/Steam/steamapps/common/Spiral Knights"`:
  passed, 173 previews / 126 starter references / 47 eyes match native builders.
- `git diff --check`: passed. Changed existing source files retain LF. JAR content
  inspection found bundled `version.txt` and no `defaults/` or `maps/` entries.

- `java scripts/compat/CheckClientWorld.java "/home/n0rule/.local/share/Steam/steamapps/common/Spiral Knights"`:
  passed (exit 0). Native readers/network fixtures covered account replacement and
  reconnect, item metadata/models, multiplayer/instance isolation, all ten Haven
  links and six interior links, concurrent private training entries, 68 emotes,
  GM/F3 grants and revocation, inspection/privacy, tells, and atomic trade including
  rejection/dialog-close/disconnect cancellation. The disposable scratch tree was
  removed after child exit; the preview scratch tree was removed as well.

- Final `./gradlew build`: passed (3m 4s; 69 actionable tasks, 63 executed,
  6 up-to-date). Both JARs, all standalone unit programs, packaged server/admin/
  console/map integration, download regressions and process-cleanup regressions
  passed. Existing assertions were retained. The final diff review found no
  public API, wire/authentication, schema, dependency, asset or manifest changes.

All fixtures use disposable deployments/accounts/preferences and stop only their
own processes. Windows/macOS and graphical rendering were not tested in this
cleanup; the changed behavior concerns shutdown/tooling, without rendering changes.

Recommended follow-ups: run the wrapper suite on actual Windows and macOS, including
closed stdin, forced subprocess exit, temporary JAR/native library deletion and
SQLite WAL/SHM cleanup; then measure longer-lived reconnect/trade/scene churn before
changing executor or registry architecture. Select a maintained recovery source
separately. Larger routing/SQL extraction should remain independently validated
milestones with native evidence where wire/world behavior is involved.
