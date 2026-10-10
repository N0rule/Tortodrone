# Server implementation research — 2026-10-08

The most useful public references are **Narya/Presents for networking and
distributed objects, Clyde/Tudey for actor synchronization and movement, and
Vilya/Whirled for scene and zone transitions**. The scene archive supplies layouts.
The other listed projects mostly explain dependencies, game data or client tools.
They do not supply a verified drop-in implementation of today's game server.

This dated review began at `d1e57ac`, version 0.7.0. It contributed to the
[0.7.1 entry fixes](town-entry-investigation.md), now checked graphically and merged.
Availability findings below are from 2026-10-08, not a live directory.
The [checkpoint plan](PROJECT.md) defines current acceptance and next work;
obsolete client experiments have been removed.

## Verification method and limits

All 20 requested repository URLs resolve to public repositories, confirmed through
GitHub pages and API metadata. Their README files were read separately. The
Spiral Studio organization and topic listing also exist. The review inspected
focused Narya, Clyde and Vilya source, and used the installed game only as a
read-only serialization/configuration/networking oracle. No client or launcher
code was changed, no reference implementation was imported, and no production
server was contacted for login or gameplay testing.

Fandom direct requests are blocked. Search-index copies confirm the exact home,
Monsters, Character and Starting Out pages, but are not fresh direct reads. The
requested plural `/Missions` address could not be verified as a page or redirect;
the index supplies the singular `/Mission` article. Do not classify a blocked
request as a missing resource, or infer an alias without checking it.

Useful source snapshots:

| Reference | Inspected revision | Scope |
| --- | --- | --- |
| [Narya](https://github.com/threerings/narya/tree/296182516e48a90140027c00fd8cec3206bd77df) | `296182516e48a90140027c00fd8cec3206bd77df` | Existing research checkout; streaming, authentication, distributed objects, tests and design notes |
| [Clyde](https://github.com/threerings/clyde/tree/23758c9ff6b67e5c1b5b940e25670de09ef8efd2) | `23758c9ff6b67e5c1b5b940e25670de09ef8efd2` | Fresh shallow checkout; server liaison, pawn logic/advancers, delta processing and sprite update paths |
| [Vilya](https://github.com/threerings/vilya/tree/af199f6eae7569b03ec1b74b585b615871f165f7) | `af199f6eae7569b03ec1b74b585b615871f165f7` | Fresh shallow checkout; scene/zone directors, providers, managers and callbacks |
| [ReferenceServer preservation](https://github.com/excal69/reference-server/tree/d66e3bd7b62b7fa5418c39911164e5528740ff48) | `d66e3bd7b62b7fa5418c39911164e5528740ff48` | README, repository tree and selected server files; no build, execution or copied implementation |

Current Tortodrone codecs remain independently implemented;
existing layout provenance is in [dependency notices](dependencies.md).

## Requested resources

Usefulness below concerns this Java server, not the quality of a client tool.
“Confirmed” means the repository/page exists, not that its advertised features
were run or verified against the current installed game.

| Resource | Existence/status | Server usefulness and practical use |
| --- | --- | --- |
| [threerings/narya](https://github.com/threerings/narya) | Confirmed public repository | **Highest.** Presents messages, custom streaming, secure handshake, service marshallers, subscriptions, transactions and Crowd body/place lifecycle. First reference for wire boundaries. |
| [threerings/clyde](https://github.com/threerings/clyde) | Confirmed public repository | **Highest.** Tudey scene model, actor fields, reflective deltas, client liaison, input queues, movement/collision and predicted versus interpolated actors. Produced the TCP baseline finding below. |
| [threerings/vilya](https://github.com/threerings/vilya) | Confirmed public repository | **High.** Whirled scene/zone identities, portal moves, scene-version negotiation, callbacks and location transitions. Next reference for one connected scene. |
| [threerings/nenya](https://github.com/threerings/nenya) | Confirmed public repository | **Supporting.** Resource, media, geometry and animation dependencies. Helps interpret configurations and collision derivation; rendering code is outside server scope. |
| [threerings/ooo-util](https://github.com/threerings/ooo-util) | Confirmed public repository | **Supporting.** Shared utilities and dependency conventions. Useful when reading upstream implementations; not a gameplay protocol or feature package. |
| [threerings/ooo-user](https://github.com/threerings/ooo-user) | Confirmed public repository | **Limited.** Legacy account-management context. Its own README discourages new-project use. Keep Tortodrone's local normal accounts, revisions and password storage. |
| [threerings/getdown](https://github.com/threerings/getdown) | Confirmed public repository; README recommends maintained fork | **Low.** Java application update/bootstrap documentation. Distinguish updater endpoints from Presents game authentication. No launcher/updater implementation work here. |
| [threerings/depot](https://github.com/threerings/depot) | Confirmed public repository | **Supporting.** Relational persistence/migration patterns for understanding other servers. No reason established to replace the current transactional SQLite store with this ORM. |
| [threerings/game-gardens](https://github.com/threerings/game-gardens) | Confirmed public repository | **Medium examples.** ToyBox and hosted-game integration atop Narya/Nenya/Vilya. Useful service/lifecycle examples; not Spiral Knights gameplay. |
| [threerings/maven-repo](https://github.com/threerings/maven-repo) | Confirmed public repository | **Supporting.** Historical dependency artifacts and reproducible upstream builds. It is an artifact repository, not missing server source. Check each artifact's version and notices. |
| [threerings/ooo-build](https://github.com/threerings/ooo-build) | Confirmed public repository | **Low.** Legacy Ant build infrastructure. Helps read old code-generation/build conventions; current Tortodrone uses Make and Java 25. |
| [Spiral Studio](https://github.com/spiralstudio) | Confirmed organization and API repository listing | **Discovery/reference.** Forks of Narya/Clyde/Vilya and related infrastructure plus SK-Tweaks. Compare specific fork commits where needed; organization existence does not establish a complete game server. |
| [azzerial/spiral-knights-headless-client](https://github.com/azzerial/spiral-knights-headless-client) | Confirmed public repository | **High secondary reference.** Presents client wiring, bootstrap/service consumption and exchange examples. Historical build/IP/schema constants differ from the installed client; copied legacy/game-specific files are not the protocol authority. |
| [reference-source/SpiralKnightsSceneArchive](https://github.com/reference-source/SpiralKnightsSceneArchive) | Confirmed public repository | **High data source.** Static scene exports and indexes; Town Square is already pinned. Procedural captures live partly in Legacy. Layouts do not implement enemies, scripts, mission rules or rewards. |
| [lucasluqui/spiralview](https://github.com/lucasluqui/spiralview) | Confirmed public repository; releases integrated into KnightLauncher per README | **Medium inspection tool.** Wrappers around Three Rings editors help inspect scene/config references. Uses separate installed-game dependencies; not a server or protocol capture. |
| [lucasluqui/KnightLauncher](https://github.com/lucasluqui/KnightLauncher) | Confirmed public repository | **Low for core server.** Connection/deployment and runtime context. Existing separate launcher setup suffices; no modifications authorized here. |
| [SKonstruct/SKapsule](https://github.com/SKonstruct/SKapsule) | Confirmed public repository | **Low.** Android JVM/native/Getdown bootstrap context. Its platform-login work is outside scope and unnecessary for normal username/password authentication. |
| [SKonstruct/KnightLauncher-Android](https://github.com/SKonstruct/KnightLauncher-Android) | Confirmed public repository; archived/discontinued | **Low historical.** Superseded Android launcher. No useful next server feature identified. |
| [EtiTheSpirit/ThreeRingsSharp](https://github.com/EtiTheSpirit/ThreeRingsSharp) | Confirmed public repository | **Low.** IKVM-based library access and model/glTF export. May clarify asset structures; native Java upstreams are better networking references. |
| [Crowfunder/Kozmadeus](https://github.com/Crowfunder/Kozmadeus) | Confirmed public repository | **Low.** Model import and XML templates for Clyde/SpiralView. Not networking, combat logic or character persistence. |
| [Crowfunder/Spiral-Knights-Modding](https://github.com/Crowfunder/Spiral-Knights-Modding) | Confirmed public repository | **Low discovery.** Guides/assets and links to inspection tools. Client modifications are outside the server task. |
| [GitHub Spiral Knights topic](https://github.com/topics/spiral-knights) | Confirmed listing | **Discovery only.** Finds tools and archives; topic membership is not evidence of protocol accuracy, licensing or server functionality. |
| [Fandom wiki home](https://spiralknights.fandom.com/wiki/Spiral_Knights_Wiki) | Exact page confirmed in index; direct read blocked | **Gameplay reference.** Navigation hub. The indexed page warns of little growth; verify individual mechanics with better evidence. |
| [Fandom Missions](https://spiralknights.fandom.com/wiki/Missions) | Exact plural URL unverified; [Mission](https://spiralknights.fandom.com/wiki/Mission) indexed | **Later gameplay reference.** Objective types, progression and once-only rank rewards. No packet schema or executable mission scripts; do not invent tutorial completion. |
| [Fandom Monsters](https://spiralknights.fandom.com/wiki/Monsters) | Exact page confirmed in index; direct read blocked | **Later gameplay reference.** Families and damage affinities for a small enemy milestone. Not authoritative AI timing, hitboxes or replication fields. |
| [Fandom Character](https://spiralknights.fandom.com/wiki/Character) | Exact page confirmed in index; direct read blocked | **Gameplay reference.** Equipment/progress UI expectations. Installed service/schema checks remain the wire authority. |
| [Fandom Starting Out](https://spiralknights.fandom.com/wiki/Starting_Out) | Exact page confirmed in index; direct read blocked | **Gameplay reference.** Creation/tutorial overview. Indexed official name rules differ from Tortodrone's documented local rules; compatibility research is needed before changing saved-name policy. |

## Additional discoveries and archived documentation

- [Narya Presents overview](https://github.com/threerings/narya/blob/296182516e48a90140027c00fd8cec3206bd77df/docs/presents/overview.html)
  and [design notes](https://github.com/threerings/narya/blob/296182516e48a90140027c00fd8cec3206bd77df/docs/presents/design.txt)
  explain proxy objects, server access checks, listeners and distributed sets.
  The overview's invocation-services section is unfinished; use source/tests for RPC.
- [Narya 1.15 DObject Javadoc](https://threerings.github.io/narya/apidocs/com/threerings/presents/dobj/DObject.html)
  is reachable. It documents subscriptions, listener lifetime, attribute/set
  events and transactions. Old published APIs need comparison with the installed
  build. The Narya GitHub wiki has no substantive pages; some Clyde/Vilya Javadoc
  URLs failed during this review, so the source remains the dependable reference.
- Narya's [TestServer](https://github.com/threerings/narya/blob/296182516e48a90140027c00fd8cec3206bd77df/core/src/test/java/com/threerings/presents/server/TestServer.java)
  and Crowd [JabberServer](https://github.com/threerings/narya/blob/296182516e48a90140027c00fd8cec3206bd77df/core/src/test/java/com/threerings/crowd/server/JabberServer.java),
  plus [samskivert/reversi](https://github.com/samskivert/reversi), provide focused
  client/server examples without needing a purported complete game emulator.
- [samskivert/samskivert](https://github.com/samskivert/samskivert) supplies utility,
  reflection/configuration and historical account infrastructure used by the
  library ecosystem. This corroborates configuration behavior, not ProjectX schemas.
- [excal69/reference-server](https://github.com/excal69/reference-server) is a preserved server project,
  with 182 Java source files at the inspected revision. Its tree includes knight,
  scene/zone, town, item, dungeon and administrative providers. Selected source
  confirms use of Narya/Clyde service infrastructure. This is a useful historical
  architecture lead, but runtime compatibility, implementation completeness and
  source provenance are unverified. No repository license was detected. Do not
  treat its bundled game classes/assets or client-editing setup instructions as
  implementation dependencies or authorization. It was not built or run.
- [Spiral Studio's Narya](https://github.com/spiralstudio/narya),
  [Clyde](https://github.com/spiralstudio/clyde) and
  [Vilya](https://github.com/spiralstudio/vilya) are alternate reference trees.
  Its API listing also reveals ooo-db, ooo-cron and ooo-messaging. These are
  infrastructure leads; their implementations were not needed for this milestone.
  [SK-Tweaks](https://github.com/spiralstudio/SK-Tweaks) concerns client QoL.
- [Getdown documentation](https://github.com/threerings/getdown/wiki) exists;
  the upstream README recommends [bekoenig/getdown](https://github.com/bekoenig/getdown).
  Both are updater references, outside the core server backlog.
- [Puzovoz/Bootshuze](https://github.com/Puzovoz/Bootshuze) and
  [Crowfunder/Bootshuze-GUI](https://github.com/Crowfunder/Bootshuze-GUI) are model
  conversion predecessors linked by Kozmadeus/topic listings. Their server value
  is limited to asset inspection. SpiralView credits SpiralSpy at
  `spiral.onyxbits.de`; that historical site was not retrievable, so its current
  availability/source is unconfirmed.
- The game's [wiki home](https://wiki.spiralknights.com/Main_Page),
  [Mission](https://wiki.spiralknights.com/Mission) and
  [Monster](https://wiki.spiralknights.com/Monster) are additional documentation
  leads. Direct fetches failed; indexed official-wiki articles exist. No fresh
  mechanics claims in this report depend on those unavailable pages.

## Protocol findings that guide implementation

| Layer | Primary reading | Consequence for Tortodrone |
| --- | --- | --- |
| TCP framing/custom streaming | Narya `io/FramingOutputStream`, `FramedInputStream`, `ObjectInputStream`, `ObjectOutputStream`, `Streamer`, `FieldMarshaller` | This is a custom typed stream, not Java `ObjectInputStream` serialization or JSON. Keep directional class maps across frames and explicit bounded parsers. |
| Authentication | Narya `presents/net/SecureRequest`, `SecureResponse`, `AESAuthRequest`, `PublicKeyCredentials`, `presents/util/SecureUtil` | Preserve the secure exchange and independently verify normal credentials through the local account store. The encrypted credential payload has its own mapping context. |
| Bootstrap and RPC | Narya `presents/net/BootstrapData`, `BootstrapNotification`, `presents/client/InvocationDirector`, `presents/server/InvocationManager` | Bootstrap advertises object IDs/service handles. Method numbers and listener IDs belong to verified marshallers, not names invented by the emulator. |
| Distributed state | Narya `presents/dobj/DObject`, `DSet`, `CompoundEvent`, attribute/set events and subscription messages | Send initial state before incremental events, guard ownership and unregister listeners. Group related fields and publish only after successful storage changes. |
| Place/scene/zone lifecycle | Narya Crowd body/place managers; Vilya `whirled/client/SceneDirector`, `zone/client/ZoneDirector`, scene/zone providers | Scene model IDs, distributed-object IDs, zones and character identities are distinct. A layout callback is not sufficient: subscription/location/readiness must follow. |
| Actor/input replication | Clyde `tudey/server/ClientLiaison`, `logic/PawnLogic`, `util/PawnAdvancer`, `client/TudeySceneController`, `TudeySceneView` | Keep received-input acknowledgements separate from scene references. Apply queued input at its scene time; send deltas against an available snapshot. |

Streamer field order is version-sensitive: Narya has optional alphabetical
sorting that is not its legacy default. Static/transient fields are excluded;
final component arrays use different streaming and null-mask rules. Enum policy,
typed-field markers, configuration string interning and inherited fields must be
checked against the deployed reader. Upstream source explains the machinery;
the installed reader verifies today's ProjectX additions. The headless repository
is useful corroboration but includes older bootstrap fields/constants.

Character creation/selection is ProjectX-specific, above Presents. Current
Tortodrone verifies Knight service methods, saves an account-local roster before
selection/callbacks, and supplies a separate global world/actor identity and owned
equipment. Public Narya does not define Spiral Knights' knight roster, inventory,
mission flags, combat activities or item catalogs. Keep such game-specific wire
claims tied to compatibility fixtures, with storage authorization independent
of client-supplied IDs.

The important synchronization finding is transport-dependent. Clyde's
[ClientLiaison](https://github.com/threerings/clyde/blob/23758c9ff6b67e5c1b5b940e25670de09ef8efd2/core/src/main/java/com/threerings/tudey/server/ClientLiaison.java)
retains unacknowledged history for datagrams, but considers each sent snapshot
received over reliable ordered transport. Tortodrone had copied the former
behavior into a TCP-only server. Delayed loading acknowledgements therefore
caused repeated full actor/configuration replacements. The 0.7.1 correction and
tests are detailed in the [entry investigation](town-entry-investigation.md).

## Feature order and evidence required

Graphical creation/reconnect checks passed for 0.7.1; repeat them when relevant
behavior changes. Next, persist server-computed town position as its own small
storage/session milestone, with schema validation,
revocation and restart tests. A clean picker return should be a separate verified
transition. For portals, inspect one archived adjacent scene, its configurations
and portal destination, then implement one round trip with occupant removal/addition
and fresh input/baseline state. Do not grant access or rewards by inventing mission
flags.

Before combat, generalize owned item instances and verify weapon selection. A
small isolated combat scene can then introduce one enemy, activity inputs, health,
hit/damage events, death and reconnect behavior. Wiki descriptions guide expected
gameplay; Clyde source and installed reader/configuration checks must establish
the actual synchronization boundary. Implement one persisted mission with
idempotent rewards only after that foundation. In-game administration should use
fresh stored Admin status and the existing account/currency services.
