# Tortodrone

A Java server emulator for Spiral Knights, with a separate local account manager.
Only normal username/password login is supported. The game and launcher remain
separate and unmodified.

## Current state

Version **0.9.1**, SQLite schema **4**: normal login; saved character creation,
editing, deletion and selection; external Town Square/Bazaar maps and instances; movement, mouse
facing, sampled static collision and multiplayer actors; five persistent starter
items with persistent binding and Arsenal equip/unequip; per-character crowns and
energy with local administration; original-protocol scene chat with instance isolation;
Game Master roles, stock title/support icon and permission-checked broadcasts.

One authenticated connection per account: a successful new login replaces the
previous connection and releases its knight. Failed logins do not disconnect it.

Intro missions are skipped. Combat, quests, shops, NPC dialogue, portal travel,
position persistence and other in-game staff commands are not implemented. Reconnect
to switch characters; every entry starts at the town spawn.

The 0.7.1 entry fixes passed automated and isolated graphical creation/reconnect
checks. [Current milestone and next work](docs/world-entry-plan.md) records the
limits; [investigation](docs/town-entry-investigation.md) explains the fixes.

## Build and run

Install Git, GNU Make, JDK 17+, `curl` and `sha256sum`. No Maven, Gradle, game
installation or database service is required. The first build downloads pinned,
checksum-verified libraries and scene exports; later builds use `build/deps/`.
`make maps` prepares external packs without starting the server; see [maps](docs/maps.md).

```sh
make build
java -jar build/tortodrone-admin.jar
```

In the manager, click **Create account**, choose a username/password, and save.
Fresh installations have no default account. Then start the server:

```sh
java -jar build/tortodrone.jar
```

Wait for `Done! Listening`. Stop with `stop` in its terminal or Ctrl+C.
`make admin` builds/opens the manager; `make server` builds/starts the server.
`make build` starts neither. Both JARs report their version with `--version`.

Both JARs use `data/` beside themselves, regardless of the working directory.
For deployment, keep them together, copy `build/maps/` beside the server JAR,
and preserve `data/` and edited map packs across upgrades.
The manager can use another deployment with `--data /path/to/server/data`.
It runs locally and opens no administration port.

## Connect

Configure your separately maintained KnightLauncher:

| Setting | Local server |
| --- | --- |
| `game.endpoint` | `127.0.0.1` |
| `game.port` | `47624` |
| `game.publicKey` | Entire value from `build/data/keys/public.txt` |

For another computer, use the server's reachable address. Sign in with the normal
username/password form. Create or select a knight to enter Haven. See
[connection and troubleshooting](docs/client-redirection.md).

Stop the server before changing `build/data/server.properties`:

```properties
server-ip=0.0.0.0
server-port=47624
log-mode=normal
```

`server-ip` is the listening interface; `0.0.0.0` accepts all IPv4 interfaces.
`log-mode=debug` adds protocol diagnostics. Logs append to `data/logs/latest.log`.
`--config /path/to/server.properties` selects settings only, not a data directory.

## Administration and backups

The manager supports account creation, password checks/resets, Game Master roles,
deletion, individual knight deletion and per-knight currency changes. The server terminal offers the same
account services; type `help` for commands. Game Masters can use the stock `/broadcast`
command after entering a scene. [Administration reference](docs/administration.md) covers commands,
account rules and migrations.

To back up, stop the server and close the manager, then copy the entire `data/`
directory, including database and keys. A live SQLite database may have changes
in its WAL. Replace both JARs while stopped and retain `data/` and `maps/`. Version 0.7.2
adds persistent item binding in schema 4; stop the old server before opening the
new release. Do not downgrade an upgraded database.

## Work on the repository

Start with [AGENTS.md](AGENTS.md), then [the current milestone](docs/world-entry-plan.md)
and [development workflow](docs/development.md). For a new AI thread, this is enough:

> Read AGENTS.md, README.md and docs/world-entry-plan.md, inspect Git and the
> relevant code, then continue the next manageable server milestone using the
> documented workflow.

```sh
make build test
```

Tests use disposable state. `make clean` preserves runtime data and cached
dependencies. Optional installed-client checks are documented in the development
guide; persistent test tools are Java.

| Reference | Purpose |
| --- | --- |
| [Architecture](docs/server-architecture.md) | Code map, protocol, storage and concurrency invariants |
| [Resource assessment](docs/server-research.md) | Upstream networking/game research and source links |
| [Dependency notices](docs/dependencies.md) | Pins, licenses and scene/collision provenance |
| [Historical research](docs/research/README.md) | Earlier evidence; not current setup instructions |
