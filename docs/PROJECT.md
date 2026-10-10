# Tortodrone: working context

**This is the agent's short project handoff, not an authoritative feature checklist.** Snapshot: **2026-10-10, version 0.21.0, SQLite schema 8**. Check `VERSION`, Git status/log, relevant source and tests on every task. The supplied checkout has **uncommitted multiplayer, trade and emote changes**. Do not overwrite or assume those changes are shipped. Read [AGENTS.md](../AGENTS.md) for mandatory safety, delegation and commit rules.

## Goal and constraints

Java 25 clean-room server emulator for the **unmodified standalone Spiral Knights client**, with local Java administration; normal username/password login, no Steam/guest auth. Implement verified stock behavior incrementally, not a speculative MMO redesign. Target ~300 concurrent clients, including a crowded Haven; synthetic short benchmarks are not proof of live playability. No migration of old schema or destruction of existing state.

## Current recorded baseline (0.21.0)

- RSA/AES authentication; one active session/account; case-insensitive unique knight names; persisted account/roster/character/inventory/currency; creation/selection/deletion; local admin and GM commands.
- Four imported Haven districts: Town Square, Bazaar, Arcade, Garrison; **ten native portal transitions**, checked external map/collision packs, server-authoritative movement and static collision. Independent scene instances, shared `SceneSimulation` tick (**50 ms**) and per-recipient actor delta state, bounded output queues and shared revision/role polling.
- Inventory, Arsenal, equipment/costumes/trinkets, binding/stacks, live visual updates; scene chat, private tells, broadcasts, sitting/emotes recorded through the last committed milestone as applicable. Intro missions bypassed **without inventing completion/rewards**.
- At this recorded checkpoint, do **not** assume trade, inspect, combat, NPCs, shops, quests, crafting, gate/dungeons, custom news, location persistence or full social functionality works. Verify any later/uncommitted implementations before treating them as complete.

## Current work / next validation

Follow the **owner's latest task** and current Git diff first; don't redo work under way. Relevant reported problems: incoming trade Accept/Reject/Hide notification; inspect another knight and privacy/equipment rules; concurrent travel through the same gate leaving one player unable to move; logout/reconnect town or position behavior. For reconnect, **research original game's location semantics before adding exact-coordinate persistence**. Trade and scene changes need stock wire evidence, two-client testing and transactional/ownership checks. Do not infer a new feature's completion from client UI elements alone.

## Architecture and correctness essentials

- `server/Server`, `server/network/{ClientSession,WorldSession,SessionOutput,WorldStatePoller}`: listener, login, connection/subscription lifecycle and output. `protocol/{HandshakeCodec,SessionCodec,SessionDecoder}`: Narya/Presents framing and serialized service/event messages.
- `world/{SceneSimulation,SceneCatalog,SceneRegistry,TownMovement,CollisionMap}`: simulation, snapshots, maps/instances, server-side movement, portal crossing; scene instance owns mutable simulation state. `account/AccountStore`, `character/CharacterStore`, `storage/Database`: auth, characters, SQLite state; `admin/` and `resources/` for local administration/default packs.
- **Identifiers have different scopes:** account-local roster, global knight/actor, item instance, scene object and connection-local distributed object; never exchange them blindly. Only authenticated owners may mutate private data; login takeover, auth revision and GM authorization checks must survive reconnect/disconnect races.
- SQLite schema **8**, WAL, foreign keys, transaction rollback and `BEGIN IMMEDIATE` writes. Durable changes precede notifications. Never rewrite real data as a test or auto-migrate unrecognized schemas.
- Narya serializers use **per-connection directional class/intern mappings** and strictly bounded frames; stock object IDs, service indices, event order and listener shape must be verified. Never substitute Java serialization, unchecked dynamic classes or guessed packet layouts. Preserve `ProjectXAuthResponseData.ident = null` for unmodified stock client identity behavior.
- Every scene instance advances once per tick and publishes a shared immutable snapshot, with receiver-specific baselines; movement is authoritative, client input is intent rather than coordinates. Register observers with a consistent initial snapshot; gate travel must not leak old-instance inputs/events. No blocking network I/O inside simulation/store locks. World/state changes require stock-reader and relevant two-client graphical acceptance, not just unit tests.

## Build and performance

Run `./gradlew build` with **JDK 25**; the installed game is never modified. Use disposable test environments. `./gradlew benchmark --args='--instances 4 --clients 300'` measures synthetic workload; see [development](development.md) for other modes, limits and graphical procedures. Historical local 20-s/300-player runs reached **18.45 deltas/client/s (1 instance)** and **19.71 (4 instances)** after shared simulation, but dense-scene replication used substantial bandwidth. These numbers are a *checkpoint*, not a performance guarantee.

## Documentation on demand (do not preload)

- [README](../README.md): install, run, config and backups.
- [Architecture](server-architecture.md): deeper thread, state and wire invariants.
- [Development](development.md): test commands, load-test configuration, stock-client/graphical isolation.
- [Server research](server-research.md): upstream protocol evidence; [feature investigations](server-milestones.md) and [town-entry investigation](town-entry-investigation.md).
- [Maps](maps.md), [game data](game-data.md), [admin](administration.md), [client connection](client-redirection.md), [dependencies](dependencies.md): read only for related tasks.
- Historical full milestone/architecture/testing text is available in **Git history** (`git show HEAD:docs/PROJECT.md`, etc., if the current HEAD retains it), not duplicated under `docs/history/`.
