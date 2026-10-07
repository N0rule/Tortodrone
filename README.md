# Tortodrone

Tortodrone is a standalone Java server emulator for Spiral Knights. This project
builds the server; the game and KnightLauncher are separate projects. Only normal
username/password login is supported. Steam and guest login are outside the scope
of the project.

The current server supports secure exchange, the fixed test account, bootstrap,
TCP heartbeat, name validation, character creation, and a saved character roster.
**World entry, gameplay, character editing/deletion, and additional accounts are
not implemented.** Characters survive server restarts. Unsupported operations
return explicit failures.

## Fresh install

Install Git, GNU Make, and a JDK 17 or newer. The JDK must provide `java`, `javac`,
and `jar` on your system's `PATH`. There are no Maven, Gradle, game-file, or launcher
build dependencies.

Clone the repository using its actual URL, then enter `Tortodrone/`:

```sh
git clone <repository-url> Tortodrone
cd Tortodrone
java -version
javac -version
make --version
make build
java -jar build/tortodrone.jar
```

`make build` only compiles. `make server` builds and starts the same JAR from
`build/`. Press Ctrl+C to stop the server. The default data directory is always
beside the JAR: running `java -jar build/tortodrone.jar` from the repository root
uses `build/data/`, too.

The server creates its configuration, keys, and log on first
start. No game installation is required to run it. For deployment, copy only
`tortodrone.jar` into a directory and run `java -jar tortodrone.jar` with Java 17+.
Keep its `data/` directory across restarts and upgrades.

## Files and settings

```text
Tortodrone/
  AGENTS.md                      contributor rules
  VERSION                        release version bundled into the JAR
  src/                           server sources, resources, and tests
  docs/                          current guides and historical research
  build/
    tortodrone.jar                executable server
    .build/                      disposable production and test classes
    data/
      server.properties          editable settings
      keys/private.pk8           private server identity; keep secret
      keys/public.txt            public key for launcher settings
      accounts/1.properties      saved knights, created when a knight is saved
      logs/latest.log            server output, appended across restarts
      server.lock                prevents concurrent use of the same data
```

Stop the server before editing `build/data/server.properties`, then restart:

```properties
server-ip=0.0.0.0
server-port=47624
log-mode=normal
```

| Setting | Meaning |
| --- | --- |
| `server-ip` | Local interface to listen on. `0.0.0.0` accepts connections on all IPv4 interfaces. |
| `server-port` | TCP listening port. Default: `47624`. |
| `log-mode` | `normal` for basic operational messages; `debug` for those messages plus protocol details. |

`server-ip` and `server-port` configure the listener. Clients need the server's
reachable address, that TCP port, and the public key from `data/keys/public.txt`.
For the default `server-ip=0.0.0.0`, use `127.0.0.1` on this computer or the
server's LAN/public address from another computer.

There is no `data-directory` setting. All runtime state stays in `data/` beside the
JAR. `--config /path/to/server.properties` selects another settings file without
moving keys, characters, or logs.

If upgrading an older installation, remove `data-directory` from the editable
configuration. If its data is still in the repository root, stop the server and
move the whole directory to `build/data/` only if that destination does not already
exist. If both directories exist, back them up and choose which state to keep.
Preserve the complete keypair and account profiles. Changing bundled defaults
never overwrites an existing configuration.

## Logs and version

The startup banner includes the release version from `VERSION`:

```text
[19:30:01] [ServerMain/INFO]: Starting Tortodrone server version 0.1.1
[19:30:01] [ServerMain/INFO]: Loading properties
[19:30:01] [ServerMain/INFO]: Log mode: normal
[19:30:01] [ServerMain/INFO]: Generating server keypair
[19:30:01] [ServerMain/INFO]: Loading saved characters
[19:30:01] [ServerMain/INFO]: Starting Tortodrone on *:47624
[19:30:01] [ServerMain/INFO]: Normal login and saved characters available; world entry is not implemented.
[19:30:01] [ServerMain/INFO]: Done! Listening on 0.0.0.0:47624 (TCP)
```

Normal mode includes startup, connection/login events, character saves, warnings,
errors, and shutdown. Debug mode adds handshake/frame diagnostics, bootstrap,
snapshots, receiver registration, and invocation routing. Both console and
`data/logs/latest.log` use the same format and filtering. Neither mode logs secrets
or raw client payloads. Timestamps use the machine's local time.

```sh
java -jar build/tortodrone.jar --version
```

## Connect and create a knight

Start the separately maintained KnightLauncher normally and configure:

| Launcher setting | Value for this computer |
| --- | --- |
| `game.endpoint` | `127.0.0.1` |
| `game.port` | `47624` |
| `game.publicKey` | Entire value from `build/data/keys/public.txt` |

Log in through the normal username/password form with `tortodrone` / `tortodrone`.
For another computer, use the server's reachable address and `server-port`.
See [connection guidance](docs/client-redirection.md).

Names contain 3–16 letters with optional single hyphens. Names are unique without
regard to case, and the test account has three slots. The server saves appearance
choices and stable character IDs before reporting the result. After creating a
knight, reconnect to view the saved roster. Selecting a knight returns a failure
until world entry is implemented. The basic roster preview applies color and
height; equipment, accessories, and eyes are saved but are not yet applied to it.
Back up `data/` to preserve characters and server identity.

## Contribute

Read [AGENTS.md](AGENTS.md) and the [development guide](docs/development.md).
Keep changes focused on the server and normal login. Client and launcher changes,
Steam authentication, and guest authentication are outside this repository's scope.

Create a branch, make the change, and run the relevant checks:

```sh
git switch -c describe-your-change
make test
```

`make test-unit` runs character, protocol, and socket/session tests.
`make test-integration` starts temporary copies of the packaged JAR. Tests use
synthetic clients and need no game files or external services. `make clean`
removes compiled output and the JAR while preserving runtime data.

Review `git diff`, commit the files you changed, and submit your branch for review
through the repository's hosting service. Include what changed and which checks
passed. Never commit build output, runtime data, private keys, or game assets.

See [server architecture](docs/server-architecture.md) for implementation details
and the next milestones. [Historical research](docs/research/README.md) records
past observations; its old paths and client experiments are not current setup or
contribution instructions.
