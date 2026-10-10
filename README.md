# Tortodrone

Clean-room **Java 25 Spiral Knights server emulator** plus local account manager. Stock game and KnightLauncher are separate, unmodified. Supports only normal username/password; no Steam/guest auth.

## Current state (verify `VERSION` and Git)

At **0.21.0 / SQLite schema 8**: login and account takeover protection; globally unique knight names; character creation, editing, selection and deletion; four external Haven towns (Town Square, Bazaar, Arcade, Garrison), ten native walking portals, authoritative movement/static collision, shared per-instance 50 ms simulation and multiplayer actor updates. Persistent inventory/equipment/costumes/trinkets/stacks/binding/currencies, local administration/GM roles and commands, live Arsenal updates, scene chat/private tells, sitting and stock character previews. Intro missions are bypassed without fabricated completion/rewards.

**Not implemented at the recorded checkpoint:** combat, missions, NPC services, shops, crafting/item use, dungeon/gate gameplay, position persistence, trade, custom news and broad social features. Some of these may be **in-progress in uncommitted source**; inspect Git before choosing work. The [current project handoff](docs/PROJECT.md) is the concise checkpoint; older milestone detail is available in Git history.

## Build, run and connect

Requirements: Git, GNU Make, **JDK 25**. No Maven/Gradle, database service or installed game required for build. Dependencies are checksum-verified and cached in `build/deps/`; default maps/items ship under `defaults/` and prepare offline.

```sh
make build test                      # build both JARs and test
java -jar build/tortodrone-admin.jar  # create local account first
java -jar build/tortodrone.jar        # start server; `stop` or Ctrl+C
make defaults                       # prepare local packs
```

Both JARs keep `data/` beside themselves; copy `build/defaults/` beside them when deploying. Preserve `data/` (especially keys/DB) and customized `defaults/`. The optional startup default-resource downloader points to a placeholder repository; missing maps prevent world entry but not login/admin. See [maps](docs/maps.md).

KnightLauncher settings: `game.endpoint=127.0.0.1` (or reachable server IP), `game.port=47624`, `game.publicKey` = entire contents of `build/data/keys/public.txt`. [Connection help](docs/client-redirection.md).

Stop the server before editing `build/data/server.properties`:

```properties
server-ip=0.0.0.0
server-port=47624
max-clients=16
log-mode=normal
```

`max-clients` range 1–1024; `--config` changes the settings-file path, **not** data directory. Logs: `data/logs/latest.log`. Admin manager accepts `--data /path/to/server/data`; it opens no network admin port. `help` in the server terminal lists commands. [Admin details](docs/administration.md), [item data](docs/game-data.md).

## Backups, development and references

Stop server and manager; copy the **whole `data/` directory** (SQLite WAL and keys). Only current schema 8 and JSON item catalogue format v1 are supported; no old-DB imports/migrations. Never discard original state while testing.

Start coding with [AGENTS.md](AGENTS.md) and [project handoff](docs/PROJECT.md); consult [architecture](docs/server-architecture.md) and [development](docs/development.md) for invariants, tests, stock-client checks, benchmarks and cleanup. See [upstream research](docs/server-research.md) only when the task needs it. Do not load all historical documents into agent context.
