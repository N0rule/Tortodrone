# External scenes

The server loads `defaults/maps/` beside its JAR; a deployment's custom `maps/` takes precedence.
Map files stay outside both JARs. `./gradlew build`, `buildServer`, `prepareDefaults` and
`prepareMaps` use the checked-in packs offline and prepare the seven default scenes under
`build/defaults/maps/`. The exporter does not run during ordinary builds, including
`buildServer -PgameDir=...`.

The checked-in source contains Town Square (1), Bazaar (2), Arcade (3), Garrison (445), Advanced
Training Hall (294), Laboratory (30893), and the four-star Hall of Heroes variant (318). All seven
scene binaries were decoded and their capture hashes verified. The town scenes include twelve
directed native portal routes between the four districts; the six routes to and from the three
interiors bring the total to eighteen. Descriptor spawns and rotations are the authored defaults.
Layouts describe geometry and portal metadata. Training also supplies a separately hash-pinned
`content.properties` pack for [scene actors and mechanics](training-hall.md). Other rooms need
prepared content before those mechanics appear; missions, shops and rewards remain unsupported.

## Preparing defaults

Deploy the complete `build/defaults/` tree beside the server JAR. The source manifest
`defaults/maps/scene-source.properties` verifies each pack and keeps digest records for known
prior default generations. Those `owned.*` records allow preparation to upgrade deployments made
from older defaults. Existing custom files and owner-edited packs are preserved. A source pack
that fails its manifest or descriptor checks stops preparation before output is written. Map updates
also respect the deployment lock and refuse to change a running server's defaults.

Each scene directory has a `map.properties` descriptor, compressed `scene.dat`, derived
`walkability.gz`, and (where applicable) `portals.properties`. `catalog.properties` selects the
entry scene. Optional `content.properties` and `static-collision.properties` require their respective
`content-sha256` and `static-collision-sha256` descriptor pins and their own layout digests.
Descriptor and portal metadata digests tie collision and triggers to the scene layout.
Missing defaults can be recovered from the repository's `main` ZIP when available; if maps remain
missing or invalid, login and local administration remain available while world entry is refused.

The fallback collision grid is a sampled approximation derived using the installed client's
configuration and stock player shape. Training's separate collision pack preserves tile flags,
floor cells and placeable geometry so pawns and projectiles can apply their own native masks
and shapes. Movement sweeps still sample at small intervals. Portal polygons and destination arrivals are checked against the captured scene
and collision data. Scene IDs identify templates; each configured instance has its own distributed
object ID, occupants, movement and entry baseline.

## Rebuilding from local captures

Re-export only when deliberately preparing a fresh candidate tree. Use the unchanged installed
stock client, a directory of captured `<scene-id>.dat` binaries, and an output directory that does
not exist:

```sh
./gradlew prepareHaven \
  -PgameDir="/path/to/Spiral Knights" \
  -PcapturedScenes="/path/to/captured-scenes" \
  -PhavenOutput="/tmp/fresh-haven-maps"
```

The capture directory must contain the seven scene IDs listed above. Each binary is SHA-256
verified against `capture.<id>.*` in `defaults/maps/scene-source.properties` before conversion.
The task invokes the existing `StockTools.java haven` command, which uses the stock client's
configuration resources and the existing `HavenSceneExport`/`ScenePackExport` converters to derive
descriptors, collision masks, and verified native portal links. It writes only to the fresh staging
path; it does not publish into `defaults/maps/`. Review the generated packs and explicitly update
the checked-in defaults and manifest when accepting a new candidate.

The same conversion can be run directly:

```sh
java scripts/StockTools.java haven \
  "/path/to/Spiral Knights" "/path/to/captured-scenes" "/tmp/fresh-haven-maps"
```

Do not use the removed `prepareArchive`, `prepareInteriors`, `publishArchive` or
`publishInteriors` tasks; those workflows depended on archive checkouts and published exports
automatically. No game files are modified by preparation. The scene references are proprietary
game layouts; their provenance is recorded as official gameplay captures dated 2026-10-12. This
documentation makes no licensing claim. Private guild scene 528 is excluded. Seven other decoded
and hash-verified captures are not integrated.

See [modern capture integration](modern-captures.md) for all 14 scene hashes, the replacement
record, stock-client acceptance and dependent gameplay evidence gaps.

Training content exports automatically. `java scripts/StockTools.java scene ... --content` also
prepares native actor metadata for another room. Exported startup bindings and per-scene tags
drive the shared runtime; unsupported actor types are refused. Server-only restored defaults
are recorded in `defaults/combat/actor-rules.properties`, separate from captured geometry.
