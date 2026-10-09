# Tortodrone

A Java server emulator for Spiral Knights, with a separate local account manager.
Only normal username/password login is supported. The game and launcher remain
separate and unmodified.

## Current state

Version **0.18.0**, SQLite schema **8**: normal login; saved character creation,
editing, deletion and selection; external Town Square/Bazaar maps and instances; movement, mouse
facing, sampled static collision and multiplayer actors with shared scene simulation; five persistent starter
items plus player equipment/costumes/trinkets and stackable inventory, grants by catalogue ID or reference, and Arsenal equip/unequip; per-character crowns and
energy with local administration; peaceful town stance with shields on the back and live open-loadout updates;
original-protocol scene chat with instance isolation and private tells across maps;
Game Master roles, stock title/support icon, permission-checked broadcasts and item commands;
character select previews that show each knight's currently equipped outfit.

One authenticated connection per account: a successful new login replaces the
previous connection and releases its knight. Failed logins do not disconnect it.
Knight names are globally unique across all accounts, ignoring case, for creation
and renaming.

Use `/tell Knight-Name message` for a private message to an entered knight, and
`/reply message` to answer the last incoming tell. The English stock client also
accepts `/msg`, `/whisper`, `/respond` and `/r`. Tells use knight names, ignoring
case, and share local chat's rate and 256-character limits. Offline knights and
knights still loading a scene receive no messages. Away/idle reporting is unavailable.

Intro missions are skipped. Combat, quests, shops, NPC dialogue, portal travel,
position persistence and other staff gameplay commands are not implemented. Reconnect
to switch characters; every entry starts at the town spawn.

The 0.7.1 entry fixes passed automated and isolated graphical creation/reconnect
checks. [Current milestone and next work](docs/world-entry-plan.md) records the
limits; [investigation](docs/town-entry-investigation.md) explains the fixes.

## Build and run

Install Git, GNU Make, JDK 25. No Maven, Gradle, game
installation or database service is required. The first build downloads pinned,
checksum-verified libraries; later builds use `build/deps/`. Downloads show progress
and use up to three attempts with a 30-second deadline each. Keep `build/deps/`
to avoid downloading again; `make clean` preserves it. Complete regular
Town Square and Bazaar packs and JSON item definitions are checked in under
`defaults/maps/` and `defaults/items/`. `make defaults` prepares all local default
resources without downloads; `make maps` prepares only maps. See [maps](docs/maps.md).

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
Copy `build/defaults/` beside the JARs when deploying. Startup attempts to fill
missing defaults from a hardcoded placeholder Git repository; failed downloads
warn and continue with available resources. Without maps, login and local tools
work but world entry is unavailable. Replace the placeholder URL in
`src/main/java/org/tortodrone/resources/DefaultResources.java` when the defaults
repository is hosted.
Keep both JARs together and preserve `data/` and edited map packs across updates.
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
max-clients=16
log-mode=normal
```

`server-ip` is the listening interface; `0.0.0.0` accepts all IPv4 interfaces.
`max-clients` limits simultaneous connections (1–1024, default 16).
`log-mode=debug` adds protocol diagnostics. Logs append to `data/logs/latest.log`.
`--config /path/to/server.properties` selects settings only, not a data directory.

## Administration and backups

The manager supports account creation, password checks/resets, Game Master roles,
deletion, individual knight deletion and per-knight currency changes. The server terminal offers the same
account services; type `help` for commands. Game Masters can use `!help`, the stock `/broadcast`
command and normal-chat `!items`/`!giveitem` after entering a scene. Unknown GM `!`
commands return a private error; normal players' `!` text stays ordinary chat.
Login distinguishes an unknown username from an incorrect password. Default item
definitions include the wider equipment set; [game data](docs/game-data.md) explains
maintainer exports and custom catalogues.
[Administration reference](docs/administration.md) covers commands,
account rules and supported storage.

To back up, stop the server and close the manager, then copy the entire `data/`
directory, including database and keys. A live SQLite database may have changes
in its WAL. Replace both JARs while stopped and retain `data/`, `defaults/` and custom resources.

Schema 8 databases and version 1 JSON item catalogues are supported.
There are no database upgrades or property-file imports. Other database versions
are refused without changing saved data. Use a fresh deployment data directory
for older releases; preserve the original database and keys as backups.
Preserve `defaults/` and optional custom `maps/` and `items/catalog.json` beside
the JARs. Custom files take precedence. TSV item catalogues must be regenerated
as JSON; no database or saved-character migration is performed.

## Work on the repository

Start with [AGENTS.md](AGENTS.md), then [the current milestone](docs/world-entry-plan.md)
and [development workflow](docs/development.md). For a new AI thread, this is enough:

> Read AGENTS.md, README.md and docs/world-entry-plan.md, inspect Git and the
> relevant code, then continue the next manageable server milestone using the
> documented workflow.

```sh
make build test
make benchmark         # disposable 50/100/200/300-client town load runs
```

Tests use disposable state. `make clean` preserves runtime data and cached
dependencies. Optional installed-client checks are documented in the development
guide; persistent test tools are Java.

| Reference | Purpose |
| --- | --- |
| [Architecture](docs/server-architecture.md) | Code map, protocol, storage and concurrency invariants |
| [Resource assessment](docs/server-research.md) | Upstream networking/game research and source links |
| [Dependency notices](docs/dependencies.md) | Pins, licenses and scene/collision provenance |
