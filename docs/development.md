# Development guide

Tortodrone targets Java 17 and builds two executable JARs with GNU Make, `javac`
and `jar` from `PATH`. SQLite JDBC and JLine are downloaded once, verified by SHA-256 and bundled in
both JARs. Read [AGENTS.md](../AGENTS.md) before changing code.

## Scope and layout

Develop the server and local administration tools. Support normal username/password
login only. Do not develop or patch the client/launcher or add Steam or guest
login. The current town checkpoint supports entry, movement, facing, starter equipment
and stored currencies; combat, quests and travel remain future work.

Packages under `src/main/java/org/tortodrone/`:

| Package | Responsibility | Starting points |
| --- | --- | --- |
| `account` | Shared identities, roles, password verification and session revocation | `AccountStore`, `Passwords` |
| `admin` | Desktop account UI and optional local CLI | `AdminApplication`, `AdminWindow`, `AccountTableModel` |
| `storage` | SQLite transactions, schema, migration; legacy atomic files | `Database`, `AtomicProperties` |
| `character` | Account-owned choices, validation, persistence and observers | `Knight`, `CharacterStore` |
| `config` | Listener/log settings and defaults | `ServerSettings` |
| `protocol` | Verified bounded wire schemas | `HandshakeCodec`, `SessionCodec`, `SessionDecoder` |
| `server` | Startup, terminal commands, listener and logging | `Server`, `ServerConsole`, `ServerLog` |
| `server.network` | Authentication, subscriptions and invocations | `SecureHandshake`, `ClientSession`, `SessionOutput` |
| `world` | Archived town, collision samples and timestamped movement | `TownSquare`, `TownTerrain`, `TownMovement` |
| `server.security` | Persistent RSA identity | `HandshakeKeys` |

Add verified wire parsing to codecs, route operations in `ClientSession`, and keep
persistence with its feature service. Never trust a client-supplied account ID.
The desktop and server terminal call the same `AccountStore`; future in-game
commands should use it too, with permissions checked against current stored Admin
status. Admin is currently a stored flag, with no client commands implemented.

## Build and test

```sh
make build             # both build/tortodrone.jar and build/tortodrone-admin.jar
make build-server      # only server JAR
make build-admin       # only account manager JAR
make server            # build/start server from build/
make admin             # build/open desktop manager
make test              # all checks
make test-unit         # storage, UI table, protocol and socket checks
make test-integration  # disposable packaged-JAR deployments
make clean             # preserve data and cached dependencies
```

First build needs network access, `curl` and `sha256sum`. Dependency versions and digests
are pinned in the Makefile and `scripts/fetch-sqlite.sh`, `scripts/fetch-jline.sh` and `scripts/fetch-town-square.sh`;
update the matching entries together.
Bundled native libraries make a separate SQLite installation unnecessary.
Sources/resources are discovered recursively. Tests have a `main` method and names
ending in `Test`. Unit tests exclude the integration package; reusable synthetic
wire scenarios belong under `src/test/java/org/tortodrone/support/`.

Tests cover migrations and rollback, account CRUD and Admin, safe password storage,
stale selections, failed writes, deletion cascades, concurrent uniqueness,
headless Swing table search/sorting, packaged admin commands on a live server,
session revocation, running-server stdin commands and clean stop, protocol/frame
limits, character management, private live rosters, shutdown/restart, keys, logs,
configuration and runtime paths, timestamped input batches, scene readiness and
movement under scheduler jitter. Use temporary deployments, never real saved data.
Testing may start servers; leave them stopped afterward.

A read-only harness may load installed client networking classes against temporary
server state/preferences. This verifies actual serializers without modifying or
launching the user's game installation. Graphical rendering and animation need a
separate mapped window check. Town entry is implemented in 0.7.0; intro missions
remain outside scope.

## Runtime and upgrades

Both JARs default to `data/` beside themselves. Keep them together or use the
manager's `--data` option. Server `--config` chooses settings only, never state.
Configuration defaults are bundled in `src/main/resources/server.properties`;
existing settings and RSA keys are preserved. `VERSION` is bundled as `version.txt`
and used by startup and each JAR's `--version`; the current release is `0.7.1`.

`build/.build/` contains disposable classes and packaging output; `build/deps/`
caches pinned libraries and the town layout. Git ignores all build/runtime output. Upgrades replace both
JARs and keep data. Stop the server and close the manager before a simple copy
backup of the whole data directory; copying only a live DB can omit WAL changes.

SQLite schema version 3 imports legacy registries and profiles transactionally on
first open. Keep account/knight IDs, protected verifiers, appearance, creation
times and next-ID counters. Imported Admin is false. Profile-only 0.2 deployments
retain their original fixed login. Fresh deployments start empty. Source files
remain untouched; once migrated, they are backups, not a second active store.
Missing owners, corrupt files and unknown schema versions fail without replacing
identity. Initial schema creation/migration requires the deployment lock; stop the
old server once before upgrading. Upgrading a version 1 SQLite deployment adds
global character IDs and five starter item IDs per knight in the same transaction,
without changing roster IDs or credentials. No database server or remote admin endpoint exists.

## Transactions and session identity

`Database` opens a connection per operation, enables foreign keys and a five-second
busy timeout, and uses WAL for readers alongside short writes. Mutations use
`BEGIN IMMEDIATE`, commit or rollback as a unit. Password derivation runs outside
SQL transactions. IDs come from persistent monotonic counters, not row counts.
Deleting an account cascades its knights without recycling the account ID.
Account metadata changes are recorded in `account_events`, without credentials.

One server per deployment holds `server.lock` for its lifetime. Once initialized,
local admin processes use SQLite concurrently, without taking that lifetime lock.
This database is for processes on the same PC, not a shared network drive.
The UI uses Swing workers so hashing and SQL never block its event thread.
`DesktopBootstrap` detects niri and relaunches only the GUI with
`_JAVA_AWT_WM_NONREPARENTING=1` before AWT initializes, preserving JVM options and
application arguments. Existing overrides are respected. CLI operations bypass it.
Display checks must map an actual window: an offscreen Swing paint does not test
window-manager configuration or compositor rendering.
Changes made elsewhere appear on Refresh. A mutation from a selected row includes
its expected ID so a deleted/re-created username cannot redirect a stale action.

Authentication resolves server-owned ID, canonical username and authentication
revision. The handshake rechecks revision after password derivation and when
attaching its socket. Password reset increments it; deletion removes identity.
The server polls attached sessions every 250 ms and closes invalid sessions.
Character saves also verify account existence and revision in the save transaction,
so stale sessions cannot write after reset/delete or resurrect an account.
Admin changes do not reset passwords or disconnect sessions. Future permission
checks must read the current role rather than a login-time snapshot.

Normal credentials reject platform flags/tickets, session credentials and unexpected
fields. PBKDF2-HMAC-SHA256 derives a verifier from the client's MD5 transform with
600,000 iterations and random salt. Keep plaintexts, transforms, salts and verifiers
out of logs and arguments. Forgotten passwords are reset, never recovered.
The in-memory fixed account constructor and legacy property storage exist only for
isolated wire tests and migration; production uses SQLite.

## Publication and lock order

`AccountStore` shares one `CharacterStore` per authenticated account in the server.
A character mutation commits its database transaction before replacing the in-memory
roster and notifying observers. Subscription queues the initial snapshot and installs
its observer under that same mutation lock. No failed save publishes or consumes an
ID. Resubscribe replaces the observer; unsubscribe/logoff/disconnect detach it.

`SessionOutput` serializes frame encoding and queue insertion together, preserving
connection-local class/intern mappings. One writer performs socket I/O outside the
character-store lock. Its queue holds at most 64 frames. Encoding/socket failures
or overflow close only that connection. Observers enqueue without socket I/O or
acquiring another character store. Preserve this lock order.

## Terminal and logs

Interactive input uses JLine through `ConsoleInput`, with 100 in-memory command
history entries, Up/Down navigation and a prompt that survives asynchronous logs.
`CommandHistory` admits recognized command syntax only. A separate reader with
history disabled handles passwords and deletion confirmations, so no prompt
input can enter command recall. No history file is configured. Consecutive duplicate
commands are ignored. Parsing preserves literal quotes, backslashes and exclamation
marks. Closing the terminal restores its attributes; Ctrl+C stops the server.

The running server reads stdin on a daemon `ServerConsole` thread; networking stays
active during prompts/hashing. EOF disables terminal input without stopping the
service. `stop` closes the listener and active sockets. Password prompts use
a hidden JLine reader (plain `Console.readPassword` if editing is unavailable), or
bounded UTF-8 stdin lines otherwise;
commands never take password arguments. Old account startup options are removed.

`ServerLog` writes `[HH:mm:ss] [thread/LEVEL]: message` to console and latest.log.
Normal prints operational INFO/WARN/ERROR; debug adds protocol diagnostics.
Do not log raw input, credentials or payloads. Startup is `ServerMain`, console
commands `ServerConsole`, connections `Client-<number>`, shutdown `ServerShutdown`.

See [architecture](server-architecture.md), [connection guidance](client-redirection.md)
and [world entry plan](world-entry-plan.md). Old paths and client modification
experiments in [historical research](research/README.md) are not current instructions.
Ignored `local/` files are not build dependencies.


## Character foundation (0.6.0)

`WorldProfile` holds a global character ID alongside its preserved account-local
roster ID and five starter items. `WorldProfiles` assigns monotonic global IDs,
grants the starter kit, and removes owned inventory through foreign-key cascades.
Roster creation and kit creation share one transaction; failures publish nothing.
Creator edits change chosen helm/armor references without replacing item IDs or
resetting heat. No inventory/equip gameplay service or selected-player state is
exposed yet. Before gameplay, replace creator-only gear edits with appropriate
ownership and progression rules.

`StarterCatalog` limits new saves to the verified creator references and composes
preview models from `starter-models.txt`. Saved legacy unknown references stay
intact; previews omit unresolved equipment. The standard `make test` needs no
game installation. Optional compatibility inspection reads installed resources:

```sh
scripts/check-client-previews.sh "/path/to/Spiral Knights"
```

This fixture loads unchanged client serialization/configuration classes and
compares server previews to the client's model builder. It does not launch or
modify the client. See [verification evidence](research/starter-equipment.md).

## Town development (0.7.0)

Read [the town checkpoint](world-entry-plan.md) before extending gameplay. The
normal account flow enters archived Haven directly; do not invent tutorial progress
or implement client patches. Build resources include a pinned scene and a derived
static collision mask. Update them together when changing scene geometry.

`TownMovementTest` checks elapsed-time movement, mouse facing, input freshness,
replays, future batches, scheduler jitter, timeout and collision. `WorldInputTest` checks both base and ProjectX
input frame schemas, including empty acknowledgement batches. Currency/equipment
and migration fixtures use temporary SQLite databases; packaged tests exercise
terminal give/withdraw commands. Run `make test` after changes.

Optional installed-client check (Python 3 required):

```sh
make build
python3 scripts/check-client-world.py "/path/to/Spiral Knights"
```

It reads the separate installed game, uses synthetic credentials and temporary
state, checks local movement prediction and real zone/item/input marshallers on
creation and reconnect, delayed acknowledgements, retained model configuration,
equipment model updates and two-account multiplayer, then stops its own server.
`WorldSessionTest` also verifies TCP delta references across delayed, stale, zero
and fabricated acknowledgements and scene resubscription without game files.
Graphical scene entry, HUD, mouse facing and Arsenal behavior still need a mapped
game window check when altering their protocol. Leave test servers stopped when
finished. No game installation is required for the default build or test suite.
