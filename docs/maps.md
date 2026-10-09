# External scenes

The server loads `maps/` beside its JAR before opening its listener. No map files
are bundled in either JAR, and startup performs no downloads. The complete regular
Town Square and Bazaar packs live in `maps/default/`, including original compressed
scene exports and matching collision masks. `make maps` verifies their checksums
and copies missing files into `build/maps/`, without accessing an archive repository
or download cache. Existing destination files are preserved.

Deploy `build/maps/` beside the server JAR. The archive repository may disappear
without affecting map preparation or running the server.
Keep edited packs across updates; install a new map explicitly while stopped,
replacing its scene, descriptor and collision mask together.

`maps/catalog.properties` selects `entry-scene`. Each child directory contains:

| File | Purpose |
| --- | --- |
| `map.properties` | Scene/zone IDs, names, town loading image, version, spawn, instance count, SHA-256 digests |
| `scene.dat` | Original compressed scene export, sent to the stock reader |
| `walkability.gz` | Server collision samples derived from that scene and installed configurations |

Missing files, wrong checksums, unsupported kinds and blocked spawns fail startup.
The current verified object/configuration protocol supports **town scenes only**.
Geometry does not implement NPCs, shops, quests or portal triggers. Scene IDs identify
templates; each configured instance has a separate distributed-object ID, occupants,
movement and entry baseline. A knight is assigned a stable instance by world ID.
The client scene-version argument remains a cache version, never an instance selector.

## Maintainer imports

Original archive paths and checksums are recorded in [dependency notices](dependencies.md).
There is no map download command; obtaining another export is optional maintainer work.
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
automatic portal interactions remain unimplemented.

Sampled collision is approximate. Archives may reference outdated client configs;
inspect import warnings and test loading with the installed stock reader before
accepting a new pack. No game meshes, textures, audio or installed JARs are copied
into this repository.
