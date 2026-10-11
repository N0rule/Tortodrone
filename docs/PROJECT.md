# Tortodrone: working context

**This is the agent's short project handoff, not an authoritative feature checklist.** Snapshot: **2026-10-11, version 0.26.1, SQLite schema 8**. Check `VERSION`, Git status/log, relevant source and tests on every task. Read [AGENTS.md](../AGENTS.md) for mandatory safety, delegation and commit rules.

## Goal and constraints

Java 25 clean-room server emulator for the **unmodified standalone Spiral Knights client**, with local Java administration; normal username/password login, no Steam/guest auth. Implement verified stock behavior incrementally, not a speculative MMO redesign. Target ~300 concurrent clients, including a crowded Haven; synthetic short benchmarks are not proof of live playability. No migration of old schema or destruction of existing state.

## Current recorded baseline

- 0.26.1 restores the stock trade notification HUD by including native flag 32 in selected-world flags for the existing intro bypass, preserving it through GM/privacy updates. The current stock client posts an incoming chat notice; clicking the trade notification icon opens **No thanks / Let's trade!**. No mission completion or reward records are created. See [town interactions](town-interactions.md) for wire evidence and validation.

- 0.26.0 local administration milestone: automatic desktop server control with explicit `--gui`/`--nogui` modes, shared start/stop/exit lifecycle, verified live session identities, scene-template/instance snapshots, measured performance and bounded docked logs. Account manager adds selectable knights, searchable inventory/catalogue, grants, confirmed quantity/removal and Add/Remove/Set balances. Console adds `players`, `session`, `inspect`, `grant` and `set`; legacy forms remain supported. Item names/categories/aliases are derived from catalogue metadata without wire or JSON changes. UTF-8 size rotation is configurable. Linux full build, stock-client networking/rendering and isolated Swing acceptance passed; Windows/macOS remain unverified. See [audit, files and validation](administration-overhaul.md), [desktop controls](server-gui.md), [commands](commands.md) and [logging](logging.md).

- 0.25.6 cleanup: accepted client handlers drain before account/log/deployment-lock cleanup; stock-tool subprocesses are confirmed exited before disposable files are removed, including interrupted waits. The preview tool uses the Gradle-built JAR. [Repository audit and remaining risks](repository-audit.md) records the findings and validation.

- Java 25 Gradle Kotlin DSL build; executable server/admin JARs; isolated standalone regression suite. Normal resource-fallback warnings are consolidated; debug includes recovery/storage details. Console history includes invalid/incomplete input, with grouped and command-specific help. Startup hints use Gradle and respect the active log mode. Admin lists knight names and browses their inventory; console supports `ac` and `gm <account>` toggles; console/chat grants accept globally unique knight names with legacy account-qualified forms retained.
- RSA/AES authentication; one active session/account; case-insensitive unique knight names; persisted account/roster/character/inventory/currency; creation/selection/deletion; local admin and GM commands.
- Four imported Haven districts: Town Square, Bazaar, Arcade, Garrison; **ten native portal transitions**, checked external map/collision packs, server-authoritative movement and static collision. Independent scene instances, shared `SceneSimulation` tick (**50 ms**) and per-recipient actor delta state, bounded output queues and shared revision/role polling.
- Inventory, Arsenal, equipment/costumes/trinkets, binding/stacks, live visual updates; v2 catalogue grants 8,570 of 9,348 indexed definitions, including native seed artifacts and locked items; scene chat, private tells, broadcasts, sitting/emotes recorded through the last committed milestone as applicable. Intro missions bypassed **without inventing completion/rewards**.
- Trade and same-scene inspection/privacy are implemented; see [town interactions](town-interactions.md) for their recorded scope. Interior knight controls and authored weapons are implemented within [the recorded limits](town-player-behavior.md). NPCs, shops, quests, crafting, general gate/dungeons, custom news, location persistence and full social functionality remain outside that scope.
- Packet audit 0.24.1: stock loot-report/party setting enums decode into clean unsupported-operation callbacks instead of disconnecting; settings persistence and general parties remain unsupported. See [audit evidence and limits](protocol-audit.md).
- **Original F3 Game Master dashboard**: the stock `ProjectXAdminMarshaller` is advertised as bootstrap service 13 and implemented for ItemCreator only (`createItem`, method 7): real catalogue resolution, a stored Game Master account check inside the grant transaction, unequipped instances, item audit events and live inventory publication. The server authorizes every call independently; other dashboard tools answer with a clear unsupported-operation message. Account tokens stay support-plus (ring bit 8), never the ADMIN ring bit, and remain independent of the per-knight badge. See [administration](administration.md).

## Current work / next validation

Advanced Training Hall, Laboratory and Hall of Heroes are part of the checked-in defaults, so an ordinary build needs no `-PgameDir`, archive or extra source: `./gradlew build` prepares all seven scenes in `build/defaults/maps/` from `defaults/maps/`. `./gradlew prepareArchive -PgameDir="/path/to/Spiral Knights" -ParchiveDir="/path/to/reference-inputs"` and `./gradlew prepareInteriors -PgameDir="/path/to/Spiral Knights"` are maintainer re-exports that republish new packs into `defaults/maps/`; `buildServer -PgameDir=...` also performs the archive export once. The owner's regular 2017 Haven geometry and F3 dashboard are preserved. Bundled numeric combat defaults contain 382 weapons, 123 shields, 1,827 authored attack graphs and level-zero equipment control modifiers. Interiors support normal/charged/chained attacks, shielding/bash/regen, weapon switching, dodge and bullet/bomb/orbital emissions. See [town player behavior](town-player-behavior.md) for specialized fire gaps and sampled collision limits. Owner-edited packs keep their bytes while packs this source published earlier are upgraded in place; conflicting custom edits and active-server map changes are refused.

Follow the **owner's latest task** and current Git diff first; don't redo work under way. The incoming trade notification controls are restored in 0.26.1; this installed client opens their popup when the trade icon is clicked. Other reported problems: inspect another knight and privacy/equipment rules; concurrent travel through the same gate leaving one player unable to move; logout/reconnect town or position behavior. For reconnect, **research original game's location semantics before adding exact-coordinate persistence**. Trade and scene changes need stock wire evidence, two-client testing and transactional/ownership checks. Do not infer a new feature's completion from client UI elements alone.

The 0.25.2 collision milestone checks all crossed cells of the sampled static grid
for bullets and collision-enabled orbitals, preventing skipped walls while
preserving authored orbital wall-passing flags. It does not recover exact shapes or
moving-target hits. The 0.25.3 combat rendering fixes serialize native item
configuration IDs, remove duplicate held equipment from interior body models and
align fire delays/refractory periods with the native attack-speed multiplier.
The 0.25.5 projectile elevation fix preserves the native unspecified-floor sentinel;
the client applies authored bullet height once, keeping gun particles at the barrel.
`CheckCombatRendering` provides a repeatable isolated stock graphical fixture;
see [development](development.md) for usage and [town player behavior](town-player-behavior.md)
for the acceptance record and limits. Sealed Sword's stripped condition also wraps
a monster spawn table, so a generic
conditional wrapper alone would not complete that weapon. Agent carriers and
modern loop execution remain unsupported; see [town player behavior](town-player-behavior.md).

## Architecture and correctness essentials

- `server/{Server,ServerControl,ServerOptions,ServerWindow,SessionRegistry,ServerLog}`, `server/network/{ClientSession,WorldSession,SessionOutput,WorldStatePoller}`: listener, login, connection/subscription lifecycle and output. `protocol/{HandshakeCodec,SessionCodec,SessionDecoder}`: Narya/Presents framing and serialized service/event messages.
- `world/{SceneSimulation,SceneCatalog,SceneRegistry,TownMovement,CollisionMap}`: simulation, snapshots, maps/instances, server-side movement, portal crossing; scene instance owns mutable simulation state. `account/AccountStore`, `character/CharacterStore`, `storage/Database`: auth, characters, SQLite state; `admin/` and `resources/` for local administration/default packs.
- **Identifiers have different scopes:** account-local roster, global knight/actor, item instance, scene object and connection-local distributed object; never exchange them blindly. Only authenticated owners may mutate private data; login takeover, auth revision and GM authorization checks must survive reconnect/disconnect races.
- SQLite schema **8**, WAL, foreign keys, transaction rollback and `BEGIN IMMEDIATE` writes. Durable changes precede notifications. Never rewrite real data as a test or auto-migrate unrecognized schemas.
- Narya serializers use **per-connection directional class/intern mappings** and strictly bounded frames; stock object IDs, service indices, event order and listener shape must be verified. Never substitute Java serialization, unchecked dynamic classes or guessed packet layouts. Preserve `ProjectXAuthResponseData.ident = null` for unmodified stock client identity behavior.
- Every scene instance advances once per tick and publishes a shared immutable snapshot, with receiver-specific baselines; movement is authoritative, client input is intent rather than coordinates. Register observers with a consistent initial snapshot; gate travel must not leak old-instance inputs/events. No blocking network I/O inside simulation/store locks. World/state changes require stock-reader and relevant two-client graphical acceptance, not just unit tests.

## Build and performance

Run `./gradlew build` with **JDK 25**; the installed game is never modified. Use disposable test environments. `./gradlew benchmark --args='--instances 4 --clients 300'` measures synthetic workload; see [development](development.md) for other modes, limits and graphical procedures. The 0.24.1 audit's local 20-s/300-player runs completed with zero failures and **19.65 deltas/client/s (1 instance)** / **19.85 (4 instances)**, but dense-scene replication used **64.6 MiB/s aggregate** downstream (four instances: **16.5 MiB/s**). See [audit measurements and limits](protocol-audit.md); these short results are not a performance guarantee.

## Documentation on demand (do not preload)

- [README](../README.md): install, run, config and backups.
- [Architecture](server-architecture.md): deeper thread, state and wire invariants.
- [Development](development.md): test commands, load-test configuration, stock-client/graphical isolation.
- [Local archive reference](reference-inputs.md) and [retrieval index](reference-inputs-index.json): curated offline source, tools and capture evidence at the pinned local archive; read for feature research.
- [Server research](server-research.md): upstream protocol evidence; [feature investigations](server-milestones.md) and [town-entry investigation](town-entry-investigation.md).
- [Server GUI](server-gui.md), [commands](commands.md), [logging](logging.md), [maps](maps.md), [game data](game-data.md), [admin](administration.md), [client connection](client-redirection.md), [dependencies](dependencies.md): read only for related tasks.
- Historical full milestone/architecture/testing text is available in **Git history** (`git show HEAD:docs/PROJECT.md`, etc., if the current HEAD retains it), not duplicated under `docs/history/`.
