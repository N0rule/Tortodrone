# Server Architecture

TortodroneServer runs as one Java process on the host network. Its executable
JAR has no third-party dependencies and targets Java 17. The launcher lives in
its own repository and connects through the ordinary TCP socket interface.

## Runtime

`org.tortodrone.server.Server` is the entry point. It loads `build/settings/server.properties`,
binds the configured address/port, loads or creates a persistent 2048-bit RSA
identity, exports launcher connection values, then accepts concurrent clients.
Missing configuration is generated from the defaults bundled in the JAR.
Connection values are written to a temporary file and replaced after closing it,
so clients do not read a partially written endpoint or public key during startup.

```sh
java -jar build/tortodrone-server.jar
java -jar build/tortodrone-server.jar --config /path/to/server.properties
```

The working directory contains the default configuration. With `--config`, the
data-directory path is relative to that configuration file, even when the server
is started from another directory. Unknown property names and invalid values
fail startup with an error so a misspelling does not silently change behavior.

| Setting | Default | Purpose |
| --- | --- | --- |
| `server-ip` | `0.0.0.0` | Interface/address to listen on; empty also means all IPv4 interfaces |
| `server-port` | `47624` | TCP port; `0` selects an ephemeral port for tests |
| `client-host` | `127.0.0.1` | Reachable address exported for launchers |
| `data-directory` | `../data` | Persistent runtime files, relative to the configuration |

Change settings while the server is stopped, then restart it. Ctrl+C closes the
listener and active sockets. A data-directory lock prevents concurrent servers
from creating or using the same identity. The OS also rejects an occupied port.

`HandshakeKeys` keeps `build/data/keys/private.pk8` and `public.txt` across restarts.
It validates that they match, refuses an incomplete identity with a missing
private key, and can recover a missing public file from the existing private
key. POSIX private-key and key-directory permissions are restricted to the owner.
Back up this directory; moving it with the JAR preserves the server identity.

Logs go to the console and `build/data/logs/latest.log`. They record message types,
sizes, connection lifecycle, and authentication results without credentials,
private keys, session IDs, or raw authentication payloads.

## Protocol boundary

`HandshakeCodec` handles bounded length-framed messages, modified UTF strings,
and connection-local class mappings for the verified handshake schema.
`SecureHandshake` performs RSA secret/salt validation, sends `SecureResponse`,
decrypts `AESAuthRequest`, validates the fixed synthetic account, and sends
`AuthResponse`. It clears negotiated secrets and hands the connection to
`ClientSession` after successful authentication.

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
method 5 saves a new non-Steam character. Name validation uses Confirm listener
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
list. Existing-character edits, Steam fields, and unknown operations are refused.

`CharacterStore` shares one synchronized store across connections for the fixed
test account. It persists up to three knights in `build/data/accounts/1.properties`,
including stable IDs, names, creation times, and appearance choices. Writes use
a flushed temporary file and atomic replacement; an I/O failure neither publishes
the character nor consumes an ID. Invalid profiles stop startup without changing
the file. Name checks are advisory; creation rechecks uniqueness under the same
lock. Other already-connected sessions see changes on resubscription/reconnect.

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
- [x] Secure exchange, fixed test login, bootstrap and heartbeat.
- [x] Current player, server and configuration snapshots.
- [x] Login service handles, receiver registration and invocation failure callbacks.
- [x] Installed client reports logged on and enters the character creation state.
- [x] Rendered character creator confirmed by the user.
- [x] Persist test-account characters; name validation, creation and roster updates.
- [ ] Add separate persistent accounts and account authentication.
- [ ] Apply saved appearance to previews and reconstruct starter inventory.
- [ ] Implement character selection, minimal inventory, zone entry and world state.

The temporary UI probe skipped the introductory sequence through the client's
existing method. Captured frames were black; the user subsequently confirmed
the rendered creator. A further installed-client probe used its real appearance
choices and received name-validation success and the updated saved roster.
The original success callback left the stock creation UI busy; the revised
result uses its existing failure handler to clear that state. A stock-handler
probe reproduced "Creating knight", then verified the controls were re-enabled
and the saved/world-unavailable message appeared without a missing-translation
warning. On reconnect,
the installed client naturally opened its saved
character picker; the probe checked that it was added/showing and its preview
had an idle animation. Automated tests also verify roster restoration after
restarting the packaged JAR. Selection and world entry remain separate checkpoints.
Investigation of the disappearing picker traced an unmapped-controller event to
the client's window dismissal. A temporary input filter preserved the picker and
passed actual slot-click checks; see the [client input investigation](research/client-input-fix.md).

## Build and tests

`make build` compiles with `javac --release 17` and packages the executable JAR
with `jar`. `make test` runs Java protocol tests and launches the JAR in a
separate process with temporary configuration and state. Tests cover host-network
connections, full synthetic authentication, bootstrap service routing, complete
snapshot schemas, receiver resets/registration, zero-based IDs, batched requests,
invocation callback correlation, character creation, duplicate names, slot limits,
malformed profiles, failed writes, concurrent creation, persistent rosters,
object ownership, repeated ping/pong, unknown
subscriptions, clean logoff, wrong passwords, malformed/nested frames,
independent/reconnecting clients, saved logs, shutdown/restart, stable keys,
duplicate startup, and invalid configuration.
No client files are needed.

Optional ignored research under `local/` contains small Narya/samskivert/headless
references, focused SKapsule bootstrap sources, and compatibility probes against
the installed client. Those probes verified that the client serializer reads
the independently encoded snapshots and that its creation invocation is decoded.
KnightLauncher is researched in the separate repository. None is a server
dependency, and no upstream implementation is compiled into the JAR.

The earlier Python/bubblewrap launcher workflow and Maven toolchain were removed.
Historical experiments remain under `docs/research/`; their old commands and
paths are historical evidence, not current runtime requirements.
