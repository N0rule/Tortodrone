# Server architecture

One Java 17 server process, one TCP listener and a local SQLite database. A separate
Swing/CLI admin JAR shares the database on the same PC. Both JARs bundle SQLite
JDBC and JLine. Maps are external validated packs. No upstream game server code or installed client
classes are compiled into Tortodrone. See [dependency provenance](dependencies.md).

## Code map

All packages are under `src/main/java/org/tortodrone/`.

| Package | Starting points and responsibility |
| --- | --- |
| `server` | `Server`: lifecycle; `ServerConsole`/`ConsoleInput`: commands; `ServerLog`: output |
| `server.security` | `HandshakeKeys`: persistent RSA identity |
| `server.network` | `SecureHandshake`: login; `ClientSession`: routing/subscriptions; `SessionOutput`: ordered writer; `WorldSession`: selected knight/scene ticks |
| `protocol` | `HandshakeCodec`, `SessionCodec`, `SessionDecoder`: bounded explicit wire schemas |
| `account` | `AccountStore`, `Passwords`: identity, roles, authentication/revocation |
| `character` | `CharacterStore`, `Knight`: roster; `WorldProfile`, `StarterCatalog`, `Currency`: world state/catalog |
| `storage` | `Database`: schema/transactions; `WorldProfiles`: identity/items/state; `AtomicProperties`: legacy storage |
| `world` | `SceneCatalog`/`SceneRegistry`: templates/instances; `CollisionMap`: terrain; `TownMovement`, `ActorState`, `SceneClock`: simulation/replication |
| `admin`, `config` | `AdminApplication`/`AdminWindow`: local tools; `ServerSettings`: settings |

## Runtime and storage

`Server` resolves state beside its JAR, holds `data/server.lock`, loads settings,
logs, RSA keys and accounts, prepares town, then accepts connections/terminal input.
Defaults: listen `0.0.0.0:47624`, normal logs. `--config` selects settings only.
Unknown/invalid properties fail startup. `stop`/Ctrl+C closes listener and sessions;
stdin EOF only disables commands. The GUI can run alongside an initialized server.

Keys are persistent 2048-bit RSA. An incomplete/mismatched identity fails rather
than being replaced; a missing public file can be reconstructed from the private
key. Bootstrap uses the accepted socket's concrete local address/actual port,
not the wildcard listening address. Credentials/raw packets/secrets are never logged.

Schema 4 stores accounts, account-local rosters, globally identified world knights,
owned item instances, binding, equipped slots, per-knight balances and audit events.
Connections enable foreign keys, WAL and a five-second busy timeout. Writes use
`BEGIN IMMEDIATE` with commit/rollback; password hashing happens outside SQL
transactions. IDs are monotonic and never recycled. Migrations are transactional
and require the deployment lock. See [administration/upgrades](administration.md).

Account-local roster ID, global world/actor ID, item ID, scene model ID and
connection-local distributed-object ID are different identities. Never substitute
one for another. Roster/kit creation shares one transaction; failed writes publish
nothing and consume no IDs. Deletion cascades owned state. Reopening grants no kit.

## Authentication and protocol

`HandshakeCodec` implements Presents framing/custom typed serialization, not Java
object serialization. Directional class/intern mappings persist across frames.
`SecureHandshake` processes RSA secure exchange, AES credentials and normal
account verification, then hands the socket/mappings to `ClientSession`.
Unexpected/platform/guest credentials are rejected. `ProjectXAuthResponseData.ident`
**must remain null**: a non-null value changes the stock client's persistent
installation identifier and can interfere with official-server login.

Bootstrap advertises service handles and object IDs, with TCP only. Player snapshots
are private to the authenticated account; server/config objects are shared empty
state. Unknown or another player's object requests fail. Receiver registration is
restricted to the connection's own `receivers` field; zero-based 16-bit IDs matter.
`SessionDecoder` explicitly parses bounded batches/events/RPC arguments. A peer
class name must never cause reflection or class loading.

Knight methods: 3 validates a name, 4 selects, 5 creates/edits, 6 deletes. Save
validates all fourteen fields and commits/publishes the roster before selection
and success callbacks. Confirm listeners use method 1/null arguments for success,
method 0/literal `~` reason for failure. Names are rechecked under the mutation
lock. Online knights cannot be edited/deleted. Unsupported services fail explicitly;
empty feed/news handles support initialization without inventing gameplay.

Limits include 16 clients, 64 KiB upstream/1 MiB downstream frames, 256 incoming
class mappings, eight nesting levels, 128 collection entries and 512 parsed nodes.
Authentication/session idle limits are 15/60 seconds. Preserve bounds and fixed
error diagnostics; never log argument values.

## Ownership, publication and lock order

Authentication resolves account ID, canonical name and authentication revision.
Login rechecks revision after hashing and at socket attachment. Password reset
increments it; deletion removes the account. A 250 ms watcher closes stale sessions.
Character/state mutations check ownership and revision inside their transaction.
Future in-game permissions must read the current Admin role, not a login snapshot.

`AccountStore` shares a synchronized `CharacterStore` across that account's sessions.
Only one authenticated socket lease per account is current. Takeover closes the
old socket and its registered world controller before returning the new lease;
late registration is also cleaned up. Conditional removal prevents old finalizers
from releasing a replacement. Cleanup runs outside account/lease locks. Normal
EOF/logoff, timeout and revision revocation release the same lease. Request routing
rechecks it and closed world sessions cannot select or mutate movement.
Commit before replacing the roster/notifying observers. Register subscriptions and
queue their initial snapshot under the same mutation lock, preventing older state
from overtaking a change. Resubscribe replaces observers; disconnect detaches them.

`SessionOutput` encodes and enqueues under one serialization lock so mappings and
frame order agree. One writer performs socket I/O outside the roster lock. Its
64-frame queue closes a stalled/failing recipient without undoing committed state.
Observers must not perform socket I/O or acquire another character-store lock.

## World entry and synchronization

Selection loads owned appearance/items/equipment/balances and acquires one controller
per global knight ID, within the account's sole live connection. Creation selects automatically when the scene receiver is
registered. A roster-only fixture can save without entering the world.

The external catalog supplies town templates; the registry assigns each instance
a distinct scene object ID. Scene travel resets membership/input/baselines and
uses the original zone callback with each summary's neighbor arrays. Scene
subscriptions publish occupants and service handles; updates start only after
`enteredPlace`, when the stock controller has attached its delta listener.
`WorldSession` ticks at 20 Hz with elapsed scene time, bounded future input and
sampled collision. Client input supplies movement/facing, never authoritative
coordinates. A verified nonzero movement limit permits stock prediction.

TCP deltas use the preceding successfully queued snapshot, independent of input
acknowledgements. Preserve unchanged model configs; replace them only for real
gear changes. Every delta supplies activity/start time because the stock predictor
can mutate its first snapshot. Inventory activity 3 closes through activity 4
for 889 ms, blocking movement/facing before returning to idle. Combat flags are ignored.

Re-entry resets input/baseline; scene generations prevent old ticks crossing a
subscription. Skip duplicate timestamps. Publish occupant metadata before new actors.
Poll profile revisions every 250 ms for local currency changes. Equip/currency
writes are atomic and ownership checked. Disconnect releases controller/subscriptions
and stops its tick executor. Positions remain transient in this checkpoint.

The [milestone plan](world-entry-plan.md) owns feature status; the
[entry investigation](town-entry-investigation.md) owns defect/acceptance evidence.

Arsenal equipItem (item method 3) and moveItem (method 9) share the owned equipment
transaction. Binding is separate from the equipped slot and persists on removal.
Equipment updates publish items/loadout/slots together so a new binding is visible
before the success callback. Stock Item flags bit 2 carries mutable binding;
the permanent-lock bit is not used.
