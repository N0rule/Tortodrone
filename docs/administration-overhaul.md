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
