# External scenes

The server loads `defaults/maps/` beside its JAR before opening its listener;
custom `maps/` takes precedence. No map files
are bundled in either JAR. Complete local defaults avoid all startup downloads. The complete regular
Town Square, Bazaar, Arcade and Garrison packs live in `defaults/maps/`, including original compressed
scene exports and matching collision masks. `make maps` verifies their checksums
and copies missing files into `build/defaults/maps/`, without accessing an archive repository
or download cache. Existing destination files are preserved.

Deploy the complete `build/defaults/` tree beside the server JAR. The archive repository may disappear
without affecting map preparation or running the server.
Keep edited packs across updates; install a new map explicitly while stopped,
replacing its scene, descriptor, collision mask and matching portal metadata together.

`maps/catalog.properties` selects `entry-scene`. Each child directory contains:

| File | Purpose |
| --- | --- |
| `map.properties` | Scene/zone IDs, names, town loading image, version, spawn, instance count, SHA-256 digests |
| `scene.dat` | Original compressed scene export, sent to the stock reader |
| `walkability.gz` | Server collision samples derived from that scene and installed configurations |
| `portals.properties` | Optional verified server trigger polygons/rectangles and tagged arrival positions/facing, tied to the layout digest |

Missing default files trigger a bounded startup download from the hardcoded placeholder
repository in `DefaultResources.SOURCE`. Only root `defaults/` is imported, including
items and future directories; existing files are preserved. Download failures warn
and continue. If maps remain missing or invalid, the server starts with login and
local tools available and refuses world entry clearly. Checksums, supported scene
kinds and walkable spawns are still required before loading any geometry.
The current verified object/configuration protocol supports **town scenes only**.
Geometry does not implement NPCs, shops or quests. All ten directed links between
the four Haven towns have verified automatic triggers and original tagged arrival markers. Scene IDs identify
templates; each configured instance has a separate distributed-object ID, occupants,
movement and entry baseline. A knight is assigned a stable instance by world ID.
The client scene-version argument remains a cache version, never an instance selector.

## Maintainer imports

Original archive paths and checksums are recorded in [dependency notices](dependencies.md).
Obtaining another scene export is maintainer work. Add a verified pack under
`defaults/maps/<name>/`; preparation discovers folders automatically, with no Java
name list to edit. Use `make defaults` to copy maps, items and any future directories.
Town Square uses the regular 2017 export, rather than the snowy static export.
Scene revisions are signed client integers; a negative revision is valid.

To prepare another archived town without drawing a map, download its original
compressed export from the [scene archive](https://github.com/reference-source/SpiralKnightsSceneArchive)
and run:

```sh
java scripts/StockTools.java scene "/path/to/Spiral Knights" /tmp/archive.dat \
  /tmp/new-town-pack 1 Haven
```

This Java importer reads unchanged installed assets/configurations, verifies the
scene kind and derives quarter-unit collision using the stock player shape. It
chooses the closest walkable point to the origin; optional final `spawn-x spawn-y`
arguments choose a specific verified point. Output must be a new directory. Inspect
its descriptor and copy the pack into the deployment's `maps/` while stopped.
Use an authentic unique scene ID; set `instances=1..16` as needed. The server allows
64 templates and 128 total instances. Scene travel RPCs are supported and tested;
automatic travel connects Town Square–Bazaar, Town Square–Arcade,
Town Square–Garrison, Bazaar–Arcade and Arcade–Garrison in both directions.
Other archived links require verified destination packs and metadata before activation.

Sampled collision is approximate. Archives may reference outdated client configs;
inspect import warnings and test loading with the installed stock reader before
accepting a new pack. No game meshes, textures, audio or installed JARs are copied
into this repository.

Portal metadata formats 1 and 2 list `entrances` by tag and `triggers` by archived entry
ID, with `layout-sha256` matching `map.properties`. Each entrance records its entry
ID, walkable x/y and rotation; each trigger records destination scene/tag and
min/max x/y for rectangles. Format 2 also accepts `shape=polygon` with
`vertices=x,y;x,y;...` in their archived winding, bounded to 3–32 strictly convex
vertices. All current sensors have zero expansion and mask 65535. Circle/polygon
intersection checks the interior and distance to each segment, including corners;
the PC radius is .333333. Loading refuses malformed polygons, missing tags and arrivals intersecting
another sensor. Packs with no metadata remain valid and have no automatic portals.
