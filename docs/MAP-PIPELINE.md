# Map pipeline

Maps are **development inputs**, not runtime imports. A release build ships the compiled maps in
`content/maps`; the server never runs the compiler.

## Source layout

```text
map-sources/
  advanced_training/
    scene.dat            # the only binary input
    map.yml              # identity and the facts a scene cannot state
```

```yaml
id: 294
name: Advanced Training Hall
type: training
zone-id: 1
zone-name: "%town:m.haven"
defaultSpawn: level_start
instances: 1
```

`zone-id` and `zone-name` are the two values a cached scene does not establish. Routing (`link`)
is a design decision and is not invented.

## Commands

```bash
./gradlew generateNativeCollision   # derive static collision + collision.bin from every map's scene.dat
./gradlew compileMapsFromSource -PgameDir=/path/to/read-only-game [-PreplaceMaps]
./gradlew generateCollision         # recompute collision.bin from existing static-collision.properties
./gradlew validateContent
./gradlew build
```

`generateNativeCollision` discovers the Steam install through the same locator the WebUI artwork
step uses; `-PgameDir` overrides it. It never rewrites portals, actors or YAML — it only adds
collision to maps that already exist. Use it for the bundled maps; use `compileMapsFromSource`
for a new `.dat` source.

`compileMapsFromSource` invokes the stock exporter for each `map-sources/*/scene.dat`, installs the
map into the build deployment, and compiles `collision.bin`. `-PreplaceMaps` is for disposable
build output only; an existing deployment map is refused without it.

## Generated artifacts

```text
content/maps/<name>/
  map.yml
  portals.yml
  actors.yml
  native/
    scene.dat
    collision.bin
    bindings.json
    metadata.properties
    static-collision.properties   # native source for collision.bin
    walkability.gz                # legacy fallback, not used when collision.bin exists
```

`collision.bin` is versioned, gzip-compressed, and pinned to `sha256(scene.dat)` by its header and
by `metadata.properties`. It contains:

- exact 1-unit tile masks and floor cells,
- native placeable shapes (circle, capsule, polygon, rail) with masks and stable ids,
- map bounds.

The runtime builds a uniform-grid broadphase from those shapes. A movement query touches only the
shapes near the body; it does not scan the map. Portal entrances are validated against the same
exact collision, and projectile/entity queries use the same broadphase rather than scanning every
shape. `walkability.gz` is not generated or read by the unified pipeline.

## Guarantees

- The compiler refuses a scene whose spawn is not on a supported floor or is blocked.
- Collision is tied to the scene hash; a changed `.dat` cannot silently reuse old collision.
- `validateContent` fails if a map carries native static collision without a generated
  `collision.bin`.
- No map behaviour is invented: unsupported actors, actions and portal geometry are reported by
  the exporter and remain absent.
- Every exported placeable shape carries its source entry id. When a door opens or a block is
  destroyed, the movement query ignores that source's static shape and the entity's dynamic
  penetration takes over.

## Current status

All nine bundled maps now carry generated exact collision:

| Maps | Exact collision |
| --- | --- |
| haven, bazaar, arcade, garrison, rescue_camp, advanced_training, crash_site, laboratory, hall_of_heroes | generated and used |

`collision.bin` is 276 KiB for all nine maps in total. `walkability.gz` is no longer required or generated: `collision.bin` is mandatory, and the runtime
collision view is exact for movement, portal entrances, entities and projectile sweeps. A
generated town map validates against the full deployment; portals whose destination scene is
absent are dropped and reported rather than published as dead travel edges.

## Scaling to 500+ maps

- One compact binary per map; no per-map bitmap is required at runtime.
- Collision is loaded per map and indexed once; queries are grid-local.
- `scene.dat` and `collision.bin` are hash-checked and immutable after compilation.
- A `SceneCatalog` indexes all map ids from metadata and loads a map body on first use. Only the
  entry scene (plus the tutorial/opening maps the registry explicitly asks for) is loaded at
  startup; `validateContent` still forces every map and validates the full portal graph.
- The compiler is fail-closed, so a large batch either publishes a consistent map or refuses it
  before the release is assembled.
