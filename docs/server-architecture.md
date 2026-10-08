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

The bootstrap supplies Knight, Location, Chat, Scene, Zone, ProjectXScene,
ProjectXZone, Body, Item, Uplink and Feed marshallers. Handles allow stock observers
to bind services; each operation is explicitly supported or refused. Empty news
and feed results support town HUD initialization. Initial hardware statistics
are discarded without logging their values.

Knight method 3 validates names, 4 selects, 5 creates/edits and 6 deletes. Create
commits and publishes the roster before selection/world events and its success
callback. A fixture without a scene receiver can save a knight but gets an explicit
saved result instead of attempting world entry. Confirm callbacks use method 1
with null arguments for success; failures use method 0 with a literal `~` reason.
Character save validates all fourteen arguments, including nulls, height/eye
ordinals and the empty normal-account friend list. Missing characters, duplicate
names, platform fields and unknown operations are refused. Online knights cannot
be edited/deleted. Other sessions on the same account receive roster changes.

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
table. The preview uses verified starter helmet/armor and accessory references,
with saved costume recolors, height and eye/blink textures. New saves must match
the starter catalog. Unknown legacy equipment stays saved and is omitted from
the preview. Selected state uses the persisted equipment set, not the roster
preview. Town callbacks and subscriptions supply the world objects and actors.

The session answers pings, acknowledges unsubscribe, and closes on EOF/logoff.
Limits are 16 clients, 64 KiB upstream / 1 MiB downstream per frame, 15 seconds idle during authentication,
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
- [x] Apply saved appearance to previews and reconstruct starter inventory.
- [x] Character selection, starter equipment, Haven entry, town movement and currency counters.

Earlier installed-client experiments confirmed the rendered creator and saved
roster. Their details, including temporary client probes and input workarounds,
remain [historical research](research/README.md). Current development verifies
the server with synthetic fixtures and does not modify clients. Automated tests
check roster restoration after restarting the packaged JAR. Town rendering remains a separate graphical verification from network fixtures. The [world entry plan](world-entry-plan.md)
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
A queue is limited to 64 pending frames, up to 1 MiB downstream (64 KiB upstream). A failed or stalled
recipient is closed and removed from publication without undoing a saved change.


## Global character identities and starter equipment (0.6.0)

SQLite schema 2 retains `(account_id, id)` as the roster key and adds a unique
`world_id` in `world_characters`. This keeps existing roster references stable
while giving future shared scenes a globally unique identity. The new identity
is server-owned; the unselected `PlayerObject.knightId` stays zero. Roster IDs
are still sent in `AvailableKnight`; they must not be used as shared actor IDs.

`inventory_items` contains five durable items per knight: chosen helm/armor,
Proto Sword, Proto Gun and Proto Shield. It records globally unique item IDs,
creator accessories, starter slots and heat. This is the development starter
kit policy, not verified introductory-mission rewards or a general equipment
system. Creation, kit assignment and sequence increments share `BEGIN IMMEDIATE`.
Kit failure rolls back the roster as well as both sequences. Creator edits update
starter appearance without replacing IDs or heat. Deletion cascades through the
owned world profile and items; sequences are never reset.

The schema upgrade holds the deployment lock and runs DDL, deterministic
backfill and `user_version=2` in one transaction. Reopening schema 2 does not
regrant items. Failed upgrades remain at schema 1 and can be retried. Reads use
one transaction snapshot for identity and items and can check the account's
authentication revision. Incomplete inventories are rejected without regranting.
Local `knight list` / `knight info` commands inspect this state without logging
credentials or changing account/session privileges.

## Haven town and character state (0.7.0)

Selection RPC method 4 checks the account-owned knight and current authentication
revision, then acquires a single controller for its global world ID. Creation
with the normal scene receiver registered immediately selects its saved result.
A roster-only fixture without that receiver reports the save explicitly and
cannot enter a world. Resubscribing to PlayerObject restores selected state.

`WorldSession` owns its knight, equipment/balances snapshot and `TownMovement`.
Zone 1 / scene 1 resolves to scene object 10 and the pinned archived Town Square
layout. Scene snapshots contain occupant metadata and three verified service
handles. Updates begin after the stock controller's `enteredPlace` notification,
which follows attachment of its delta listener. Each session ticks at 20 Hz.
Since 0.7.1, the first delta adds the actors and subsequent TCP deltas reference
the previously queued snapshot, matching Clyde's reliable-transport policy.
One baseline is retained; delayed input acknowledgements cannot cause full actor
replacement or reset the baseline. Re-entry resets it, duplicate scene timestamps
are skipped, and an in-flight tick cannot publish across a resubscription.
Ordinary movement omits unchanged model configuration; gear changes are compared
to the previously queued configuration. New occupant metadata precedes added
actors. Movement checks finite angles, frame count, freshness, ordering, scene
ownership, and the sampled static collision mask. Bounded future input queues
apply frames at their scene timestamps, integrating elapsed time in small substeps.
Actor snapshots carry a nonzero movement limit derived from the verified collision
shape, enabling stock local prediction. Delta elapsed time and ping estimates
follow the scene clock rather than being fixed placeholders. Peer class names never cause
class loading. Unsupported combat flags do not authorize movement or actions.

SQLite schema 3 adds per-knight crowns/energy and a monotonic state revision,
nullable equipped slots and currency audit events. Give/withdraw and equip/displace
are atomic and ownership guarded. Existing kits are equipped once during upgrade;
new kits are equipped during their roster transaction. Reopening does not change
loadouts. Profile polling every 250 ms reflects local manager/terminal currency
changes in online player objects. Disconnect releases controller/subscriptions
and stops its tick executor. Static town state is shared across active knights;
position remains transient. See [checkpoint limits](world-entry-plan.md).
