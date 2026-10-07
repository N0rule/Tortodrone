# Server Architecture

Tortodrone runs as one Java process on the host network. Its executable
JAR bundles SQLite JDBC and JLine and targets Java 17. A separate desktop administration
JAR shares the local SQLite database. The launcher lives in
its own repository and connects through the ordinary TCP socket interface.
Only normal username/password login is in scope; Steam and guest authentication
will not be implemented. Compatibility work belongs in the server, without client patches.

## Runtime

`org.tortodrone.server.Server` is the entry point. It locates its JAR, locks its
adjacent `data/` directory, starts logging, loads `data/server.properties`, loads
or creates a persistent 2048-bit RSA identity, loads normal accounts and their
saved character rosters,
binds the configured address/port, then accepts clients and terminal commands.
Missing configuration is generated from the defaults bundled in the JAR.

```sh
cd build
java -jar tortodrone.jar
java -jar tortodrone.jar --config /path/to/server.properties
java -jar tortodrone.jar --version
```

By default, the server generates `data/server.properties` and all runtime state
under `data/` beside the JAR, regardless of the working directory. Copying the JAR
into an empty directory and running it needs no setup files. Both `make server`
and `java -jar build/tortodrone.jar` use `build/data/`. `--config` selects a settings
file only: state always stays beside the JAR. There is no `data-directory` setting.
Unknown property names and invalid values
fail startup with an error so a misspelling does not silently change behavior.

| Setting | Default | Purpose |
| --- | --- | --- |
| `server-ip` | `0.0.0.0` | Interface/address to listen on; empty also means all IPv4 interfaces |
| `server-port` | `47624` | TCP port; `0` selects an ephemeral port for tests |
| `log-mode` | `normal` | Basic operational logs; `debug` adds protocol details |

`server-ip` and `server-port` configure the listener. Bootstrap uses the accepted
connection's concrete local interface address and the actual listening port, so
a wildcard bind (`0.0.0.0`) does not send an unusable address to clients. Public
keys remain in `data/keys/public.txt`; no connection settings file is exported.

Change settings while the server is stopped, then restart it. `stop` or Ctrl+C closes the
listener and active sockets. A data directory lock prevents concurrent servers
from creating or using the same identity. The OS also rejects an occupied port.

`HandshakeKeys` keeps `data/keys/private.pk8` and `public.txt` across restarts.
It validates that they match, refuses an incomplete identity with a missing
private key, and can recover a missing public file from the existing private
key. POSIX private-key and key-directory permissions are restricted to the owner.
Back up this directory; moving it with the JAR preserves the server identity.

Logs go to the console and `data/logs/latest.log`, using
`[HH:mm:ss] [thread/LEVEL]: message` with local timestamps. Normal mode prints
startup, connection/login events, character saves, warnings, errors, and shutdown.
Debug mode adds protocol message types, sizes, bootstrap, snapshots, receiver
registration, and invocation routing. `SessionLog` routes operational events at
INFO/WARN and protocol diagnostics at DEBUG. Neither mode exposes credentials,
password hashes, private keys, session identifiers, raw packets, or argument values.

`VERSION` is bundled as `version.txt` by the Makefile. Startup and `--version`
read this packaged release identity; moving the JAR does not affect it.

## Protocol boundary

`HandshakeCodec` handles bounded length-framed messages, modified UTF strings,
and connection-local class mappings for the verified handshake schema.
`SecureHandshake` performs RSA secret/salt validation, sends `SecureResponse`,
decrypts `AESAuthRequest`, resolves and verifies a registered normal account, and sends
`AuthResponse`. It clears negotiated secrets and hands the connection to
`ClientSession` after successful authentication.

`ProjectXAuthResponseData.ident` is null for both successful and rejected logins.
The stock client persists a non-null value in its installation identifier outside
the game directory, shared with official-server login. A private server must
leave this identifier unchanged. Earlier builds supplied `tortodrone-local`,
which could break official authentication even after reinstalling the game; see
the [identifier repair investigation](research/official-login-repair.md).

`SessionCodec` preserves outgoing mappings from authentication. The current-build
`ProjectXBootstrapData` supplies a connection ID, private client object ID, eight
service handles required by login observers, TCP endpoint, and no UDP endpoints.
Server/configuration object IDs are 1/2, invocation routing uses 3, and client
object IDs begin at 1001. Other optional game OIDs remain zero.

Subscriptions return correlated `ObjectResponse` snapshots for the requesting
connection's `PlayerObject`, shared empty `ServerObject`, and `DConfigObject`.
The player has a non-null saved knight roster and initialized collections;
configuration editing is unavailable. Unknown/other-client OIDs receive
`FailureResponse`. Object identities and receivers are connection-local; character
profiles persist in the data directory.

`SessionDecoder` accepts bounded `CompoundUpstreamMessage` batches and
`ForwardEventRequest` envelopes using the same incoming class map as the
handshake. It explicitly parses receiver transactions and invocation arguments;
it never reflects over or loads a class named by the client. Receiver writes
are allowed only to the connection's own `receivers` field. Zero-based 16-bit
receiver IDs are preserved, checked for uniqueness, and included in subsequent
player snapshots. Arbitrary object/field writes are rejected.

The service handles are Knight, Location, Chat, Scene, Zone, ProjectXScene,
ProjectXZone, and Body marshallers. They let observers bind their client services;
they do not imply gameplay support. Initial Knight hardware statistics are
discarded without logging their values. Knight method 3 validates names and
method 5 creates or updates a normal-account character, and method 6 deletes
a character by name. Name validation uses Confirm listener
method 1 with null arguments for success. Creation sends an `availableKnights`
attribute update after the save, then uses the correlated method-zero callback
with "Knight saved. Reconnect to view it; world entry is not available yet."
The stock creator's success handler waits for world events and leaves its busy
state active; its failure handler clears that state and displays the result.
Once world entry is implemented, creation can use the success callback again.
Literal callback messages carry the client's `~` prefix so they display directly
instead of being looked up as missing translation keys.
Other supported invocations with
listeners receive the standard method-zero failure callback through
`EventNotification`/`InvocationResponseEvent`. Creation validates the fourteen
arguments, including nulls, height/eye ordinals, and the non-Steam empty friend
list. A null previous name creates a new character; an existing previous name
updates that account's character. Editing preserves its ID and creation time and
uses the same save/roster-update flow, with the explicit "Knight updated" result
while world entry is unavailable. Deletion accepts a Name and Confirm listener,
saves the reduced roster, emits its update, then sends a success confirmation.
Steam fields, missing characters, duplicate names and unknown operations are refused.

`CharacterStore` shares one synchronized store across connections for each
authenticated account. It persists up to three knights per account in
`data/tortodrone.db`, including stable IDs, names, creation times and appearance.
A transaction verifies the account's authentication revision and commits before
publishing the roster. Failed writes publish nothing and consume no ID. Deleting
a knight frees a slot without recycling IDs; edits retain IDs and creation times.
Legacy property files are read only during initial migration.

Name checks are advisory; creation rechecks uniqueness under the mutation lock.
All subscribed sessions on that account receive live updates. Snapshot publication
and subscription registration use that lock so snapshots cannot overtake a newer
update. Unsubscribe and disconnect detach observers.

The `AvailableKnight` wire schema includes a non-null model reference. Its
configuration names and argument keys use a separate connection-local intern
table. The basic preview applies color and height. Equipment, accessories, and
eye choices persist, but their preview configuration and inventory reconstruction
are future work. The creator displays the saved/unavailable result; reconnecting
shows the saved roster. Character selection still fails explicitly because zone/world
objects do not exist yet.

The session answers pings, acknowledges unsubscribe, and closes on EOF/logoff.
Limits are 16 clients, 64 KiB/frame, 15 seconds idle during authentication,
60 seconds idle after authentication, 256 incoming class mappings, eight nested
levels, 128 elements per collection, and 512 parsed nodes per session frame.
Malformed or unsupported schemas are rejected with fixed diagnostics, never
argument values. UDP is not implemented.

## Roadmap

- [x] Independent executable JAR, persistent keys, configuration and host networking.
- [x] Secure exchange, normal login, bootstrap and heartbeat.
- [x] Current player, server and configuration snapshots.
- [x] Login service handles, receiver registration and invocation failure callbacks.
- [x] Installed client reports logged on and enters the character creation state.
- [x] Rendered character creator confirmed by the user.
- [x] Persist account-owned characters; name validation, creation and roster updates.
- [x] Edit saved names/appearance; delete knights and reuse slots without recycling IDs.
- [x] Separate persistent accounts and protected password verification.
- [x] SQLite persistence, desktop account manager, live terminal commands and Admin flags.
- [x] Account-scoped live roster updates and bounded ordered downstream queues.
- [ ] Apply saved appearance to previews and reconstruct starter inventory.
- [ ] Implement character selection, minimal inventory, zone entry and world state.

Earlier installed-client experiments confirmed the rendered creator and saved
roster. Their details, including temporary client probes and input workarounds,
remain [historical research](research/README.md). Current development verifies
the server with synthetic fixtures and does not modify clients. Automated tests
check roster restoration after restarting the packaged JAR. Selection and world
entry remain separate checkpoints. The [world entry plan](world-entry-plan.md)
keeps a minimal starting scene separate from introductory missions.

## Build and tests

`make build` compiles with `javac --release 17` and packages both executable JARs with a bundled SQLite JDBC driver
with `jar`. `make test` runs Java protocol tests and launches the JAR in a
separate process with temporary configuration and state. Tests cover host-network
connections, full synthetic authentication, bootstrap service routing, complete
snapshot schemas, receiver resets/registration, zero-based IDs, batched requests,
invocation callback correlation, character creation/editing/deletion, duplicate
names, full-slot edits, slot reuse, identity preservation,
malformed profiles, failed writes, concurrent creation, persistent rosters,
object ownership, repeated ping/pong, unknown
subscriptions, clean logoff, wrong passwords, malformed/nested frames,
independent/reconnecting clients, saved logs, shutdown/restart, stable keys,
duplicate startup, invalid configuration, release identification, JAR-relative
state from other working directories, and normal/debug log filtering and format.
No client files are needed.

Optional ignored research under `local/` contains historical source references
and probes. None is a server dependency, and no upstream implementation is
compiled into the JAR. Do not run old client modification probes as part of
current server work.

The earlier Python/bubblewrap launcher workflow and Maven toolchain were removed.
Historical experiments remain under `docs/research/`; their old commands and
paths are historical evidence, not current runtime requirements.

## Account ownership and administration (0.5.0)

`AccountStore` owns stable account IDs, protected password verifiers and separate
character stores. The normal credential decoder supplies only a username and
client password transform; it does not authorize a player. Authentication resolves
the account, and the PlayerObject snapshot uses that account's ID and canonical
username. All character invocations use its shared store; another account's
characters and distributed player objects are inaccessible.

Production accounts and knights live in one SQLite database with foreign keys,
transactional saves, WAL and a busy timeout. Fresh installations have no accounts.
The first open migrates existing property registries/profiles in one transaction,
retaining IDs, counters and protected verifiers. Source files remain untouched;
imported Admin flags are false. Profile-only 0.2 installs retain their fixed login.
The initial migration needs the deployment lock; stop an old server once first.

`AdminApplication` opens a local Swing account window, with database/hash work on
background workers. It has create/list/check/reset/delete/role actions, searching
and manual refresh, and optional CLI commands for headless hosts. By default it
uses data beside its JAR, or an explicit `--data` directory. It opens no network
endpoint. Once initialized, the manager and server use short database transactions
concurrently. Backups should stop both processes before copying the whole data
folder, including keys; a live database can have uncheckpointed WAL changes.

`ConsoleInput` provides JLine editing in interactive terminals and plain input
for redirected stdin. Its 100-command memory history excludes sensitive prompts
and rejected command syntax. Passwords and confirmations use a separate reader
without history; log output redraws above whichever prompt is active.

`ServerConsole` reads commands on a daemon thread while the listener handles
connections. `help`, `status`, `stop` and `account` commands use the same account
service as the desktop. Passwords use separate hidden terminal prompts rather
than command arguments, and account deletion requires typing the username. EOF
leaves the service running. Old server account startup options are removed.

Passwords cannot be recovered: candidate checks and reset are supported. A reset
increments the account's authentication revision; deletion removes it. Login
checks that revision after hashing and when attaching its socket. A 250 ms watcher
closes stale sessions, and character saves guard identity/revision in their SQL
transaction. Roles are stored separately and must be read fresh for future command
permissions. No in-game admin commands exist yet. Keys are unaffected by account
administration.

## Downstream publication (0.4.0)

A session registers one account-local roster observer when subscribing to its
PlayerObject. Committed character mutations queue `availableKnights` updates
addressed to each recipient's own client OID, before the originating invocation
callback. Sessions that have not subscribed retain the existing direct roster
reply to their own mutation. Subscriptions to server/configuration objects do
not change account-local roster registration.

The codec and `SessionOutput` queue share a serialization lock; the writer sends
frames in that same order. Network I/O runs outside the character-store lock.
A queue is limited to 64 pending frames, each at most 64 KiB. A failed or stalled
recipient is closed and removed from publication without undoing a saved change.
