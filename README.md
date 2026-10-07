# TortodroneServer

A standalone Java server emulator for Spiral Knights. The server and KnightLauncher
are separate projects. This repository builds one executable JAR; it needs no game
installation, launcher, Python, Maven, or network namespace tools.

The current server implements secure exchange, a fixed test account, bootstrap,
player/server/configuration snapshots, receiver registration, TCP ping/pong,
name validation, character creation, and a saved character roster. The character
creator renders correctly, confirmed by the user. Characters survive server
restarts. **World entry, gameplay, and additional accounts remain unimplemented.**
Unsupported operations receive failure callbacks.

## Build and start

Build with a JDK 17+ and GNU make:

```sh
make build
java -jar build/tortodrone-server.jar
```

`make server` runs the same JAR. Press Ctrl+C to stop it. Start your separate
KnightLauncher fork normally; there is no server-side client launch command.

For deployment, copy just `build/tortodrone-server.jar` into an empty directory
and run `java -jar tortodrone-server.jar`. A Java 17+ runtime is sufficient;
make and a compiler are only needed to build it. The server creates its
configuration, keypair, connection details, and logs under `build/` on first start:

```text
build/settings/server.properties editable server settings
build/data/
  keys/private.pk8                persistent private key; keep secret
  keys/public.txt                 public key to copy into KnightLauncher
  client-connection.properties    host, port, public key for client configuration
  accounts/1.properties           saved characters for the fixed test account
  logs/latest.log                 console output, appended across restarts
  server.lock                     prevents two servers using the same data
```

## Configure the launcher

With the server and game on this computer, use:

| KnightLauncher setting | Value |
| --- | --- |
| Server address (`game.endpoint`) | `127.0.0.1` |
| Port (`game.port`) | `47624` |
| Public key (`game.publicKey`) | Entire value from `build/data/keys/public.txt` |

For players on another computer, set `client-host` in `build/settings/server.properties` to
this server's reachable LAN/public address and use that address in the launcher.
The default `server-ip=0.0.0.0` listens on all IPv4 interfaces; it is a bind
address, not an address to enter in the launcher. The current listener is TCP.

Use the normal username/password form with `tortodrone` /
`tortodrone`. Guest and Steam authentication are unsupported. Your launcher
must apply the matching public key as well as the address and port. See
[client configuration and troubleshooting](docs/client-redirection.md).

Keep `build/data/keys/` across restarts and deployments. Replacing its keypair requires
updating every launcher's public key. The existing local server keypair was
preserved during the cleanup.

## Characters

Use the creator with the test account. Names contain 3–16 characters: letters
with optional single hyphens. Names are unique without regard to case, and the
account has three slots. The server saves the chosen appearance and assigned
character ID before reporting the result. Back up `build/data/` to preserve both your
characters and server identity. An invalid profile stops startup rather than
silently replacing your save.

After creating a knight, restart the game and log in again to see the saved
roster. Creation clears the busy state and reports "Knight saved. Reconnect to
view it; world entry is not available yet." Automatic entry into the game world
is not implemented. The roster uses a basic preview with
color and height; selected equipment, accessories, and eyes are saved but are
not yet applied to that preview. Selecting a knight returns an explicit failure
until world entry is implemented. Character editing and deletion are unavailable.

## Development

```sh
make test
make clean
```

The Java tests exercise the packaged JAR, host-network connections, RSA/AES
exchange, authentication acceptance/rejection, complete object schemas, receiver
registration, batched requests, invocation failure callbacks, malformed messages,
object ownership, creation and roster callbacks, duplicate names, character
slots, failed saves, concurrent creation, character restoration after restart,
logoff, reconnects, logs, shutdown, configuration errors, and key persistence.
They use temporary directories and
synthetic clients; they need no game files. `make clean` removes build output.

The installed `ProjectXApp` was also tested on the host network: it completed
test login, registered its receivers, and accepted player and server snapshots.
A temporary client probe also completed name validation and saved a character
using the real creator's choices. The installed serializer independently accepted
the saved roster and update event. The separate fork's Play button
was not automated; the user confirmed reaching the rendered creator. On reconnect,
the installed-client probe found the saved-character picker added/showing with
an available idle animation for the basic preview.

- `src/main/java/org/tortodrone/server/` — server startup, connections, keys, and authentication.
- `src/test/java/org/tortodrone/server/` — Java tests.
- `src/main/java/org/tortodrone/protocol/` — the implemented subset of the client wire protocol.
- `docs/` — current setup/architecture and historical protocol research.
- `server.properties.example` — defaults bundled in the JAR.
- `build/data/` — ignored runtime state, created on first start.
- `local/` — optional ignored upstream references and protocol probes; never a build dependency.

See [server architecture](docs/server-architecture.md) for the current boundary
and next milestone. Earlier protocol observations and pinned source references
remain under [docs/research/](docs/research/research-sources.md). Original game
assets, extracted code, private keys, and raw captures are not tracked here.
