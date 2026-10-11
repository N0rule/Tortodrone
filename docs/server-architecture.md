# Server architecture — concise map

**Authoritative reference:** current Java 25 source, tests and Git. This describes the recorded 0.25.6 baseline; in-progress changes may differ. Earlier detailed architectural notes remain in Git history; consult them only if the current code and this summary are insufficient.

## Code map (`src/main/java/org/tortodrone/`)

| Area | Entry points |
| --- | --- |
| Listener/auth | `server/Server`, `ClientConnections`, `server/security/HandshakeKeys`, `server/network/SecureHandshake` |
| Connection and routing | `server/network/ClientSession`, `WorldSession`, `SessionOutput`, `WorldStatePoller` |
| Wire | `protocol/HandshakeCodec`, `SessionCodec`, `SessionDecoder` |
| Accounts/knights | `account/AccountStore`, `Passwords`; `character/CharacterStore`, `Knight`, `WorldProfile`, `ItemCatalog`, `StarterCatalog` |
| SQL | `storage/Database`, `WorldProfiles`, `TradeTransfer` |
| Instances/physics | `world/SceneCatalog`, `SceneRegistry`, `SceneSimulation`, `CollisionMap`, `TownMovement`, `ActorState`, `SceneClock` |
| Interior controls/projectiles | `world/KnightCombat`, `SceneWeapons`, `WeaponCatalog`, `EquipmentCatalog`, `FireCatalog` |
| Player trading | `server/network/PlayerTrades`, `storage/TradeTransfer` |
| Resources/admin | `resources/DefaultResources`; `admin/AdminApplication`, `AdminWindow`; `config/ServerSettings` |

## Hard invariants

- One authenticated connection/account. Successful login takes over old session; failed login must not. Auth revisions on reset/delete; cleanup conditional on lease identity. GM authorization rechecked at use/transaction time.
- **IDs differ:** account-local roster, globally identified knight/actor, item instance, scene object, connection-local distributed object. Never conflate them. Knight names globally unique case-insensitively. Commit storage before publications and confirm callbacks.
- SQLite **schema 8**, foreign keys/WAL, five-second busy timeout, `BEGIN IMMEDIATE` writes and rollback on failure. Account/roster/item/equipment/currency/audit/revision updates are atomic where applicable. No migration; refuse other schema without mutating it.
- Preserve persistent RSA keys; never log raw credentials, private keys or unbounded packet contents. Secure RSA/AES authentication with stock username/password. `ProjectXAuthResponseData.ident` **must be null** to avoid changing the installed client's persistent identity.
- Protocol uses explicit Presents/Narya serializers with per-connection **directional class/intern mappings**; never Java serialization, peer-supplied class loading/reflection or invented wire fields. Bounds: incoming 64 KiB, outgoing 1 MiB, 256 class mappings, depth 8, 128 collection entries, 512 parsed nodes; auth/session idle 15/60 s. Listener results and stock event order matter.
- Only owned subscriptions/object receivers may be registered; never publish another account's private roster or accept client coordinates/activities as authoritative. Register observer plus initial snapshot atomically with mutation ordering.
- `SessionOutput` serializes in mapping/frame order; one writer and bounded 64-frame queue isolate slow clients; do not hold account/store/simulation locks during socket I/O or cross-session callbacks.
- `ClientConnections` closes all owned sockets, then waits for handler cleanup before accounts, logs and the deployment lock are released. Main and the JVM shutdown hook drain without holding the registry/log monitors; main waits for the hook's final message if shutdown has started. Interrupted cleanup still drains and restores the interrupt flag. SQLite connections are scoped per operation rather than held by `AccountStore`.

## World and events

- External `maps/` overrides `defaults/maps/`; verified scene/collision data shared immutably, **mutable state per instance**. `SceneSimulation` advances **all** entered knights, checks collisions/portals, then captures one immutable scene snapshot per 50 ms tick. Movement input carries intentions/facing, not coordinates; spectator/paused input is gated.
- Entered-place readiness is required before publishing deltas. Each recipient coalesces pending publications and diffs against its **last successfully queued** TCP snapshot, not merely the previous global tick. Preserve actor appearance configs, per-client class mappings, movement/activity timestamps and ack fields. Stop simulation clock on empty instance; stale callbacks may not revive detached occupants or reset replacements.
- Travel: detect verified portal polygon crossing during movement substeps, freeze crossing, server-select destination/tag, validate current owner/readiness, notify native zone/scene move, reset source membership/recipient baseline and re-enter at original marker. Cache version/client destination cannot authorize travel. Prevent old-scene inputs/queued events crossing boundary.
- `WorldStatePoller` checks auth/role/profile revisions **once per deployment every 250 ms** outside simulation/session locks; loads inventory on revision changes only; reject stale asynchronous reads. Persisted state publishes ordered item, equipment element, then loadout events so open Arsenal reacts; reconnect rebuilds picker from equipped outfit.
- Scene chat routes only among ready members of one instance; private tells route by knight name across instances. Both enforce current authentication, generation/ownership and bounded queues, five-message burst + one/second, 256-code-point maximum. GM broadcast checks live role. `/sit` uses verified client action; owned activity 26 → standing 46 (1333 ms) on movement, inventory interruption; ordinary inventory closing is activity 4 for 889 ms.
- Solo interiors use `KnightCombat` for authored player controls and `SceneWeapons` for emissions. Both advance on the shared instance clock before snapshot capture. General dungeon gameplay, NPC AI, rewards and missions remain outside the implemented scope; their formats must come from stock evidence. See [player behavior and combat limits](town-player-behavior.md).

## Useful links

[Milestone/current limits](PROJECT.md) · [Build/tests](development.md) · [Administration/storage](administration.md) · [Catalogue](game-data.md) · [Maps/portal import](maps.md) · [Protocol research](server-research.md)
