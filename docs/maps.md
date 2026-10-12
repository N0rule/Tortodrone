# External scenes

The server loads `defaults/maps/` beside its JAR before opening its listener;
custom `maps/` takes precedence. No map files
are bundled in either JAR. Complete local defaults avoid all startup downloads. One checked-in
source, `defaults/maps/`, carries all seven Haven packs — haven, bazaar, arcade, garrison,
advanced_training, laboratory and hall_of_heroes — with the original compressed scene exports,
matching collision masks, `catalog.properties` and the provenance manifest
`scene-source.properties`. `./gradlew prepareMaps` verifies each pack's `scene.dat` and
`walkability.gz` digests against `map.properties`, verifies the packs against
`scene-source.properties`, then copies missing files into `build/defaults/maps/`. No archive
repository, download cache, game directory or `local/` tree is consulted, so `build`,
`buildServer` and `prepareDefaults` prepare all seven scenes on every machine. Existing custom
files are preserved; a file this source published earlier (a digest recorded as `owned.*`) is
upgraded atomically in place, an owner-edited file keeps its bytes and is never refused, and a
dangling owner-file symlink is preserved.

A manifest records the archive path and digest of every exported pack plus the digests of the
generations this source published before, so a deployment prepared from an older pack is upgraded
in place instead of left inconsistent. A missing `defaults/maps` warns and preparation continues
for the other defaults; a pack that no longer matches the manifest fails preparation rather than
deploying an inconsistent set.

Deploy the complete `build/defaults/` tree beside the server JAR. The archive repository may disappear
without affecting map preparation or running the server.
Keep edited packs across updates; install a new map explicitly while stopped,
replacing its scene, descriptor, collision mask and matching portal metadata together and
updating `defaults/maps/scene-source.properties`, because a pack that no longer matches that
manifest is refused instead of prepared.

`maps/catalog.properties` selects `entry-scene`. Each child directory contains:

| File | Purpose |
| --- | --- |
| `map.properties` | Scene/zone IDs, names, town loading image, version, spawn, instance count, SHA-256 digests |
| `scene.dat` | Original compressed scene export, sent to the stock reader |
| `walkability.gz` | Server collision samples derived from that scene and installed configurations |
| `portals.properties` | Optional verified server trigger polygons/rectangles and tagged arrival positions/facing, tied to the layout digest |

Missing default files trigger a bounded startup download from the repository's
`main` branch ZIP (`https://github.com/N0rule/Tortodrone/archive/refs/heads/main.zip`),
configured in `DefaultResources.SOURCE`. GitHub redirects the request to its ZIP
download host. Unauthenticated recovery works only when this repository is public;
while private, the request fails with a warning. Only root `defaults/` is imported,
including items and future directories; existing files are preserved. Download failures warn
and continue. If maps remain missing or invalid, the server starts with login and
local tools available and refuses world entry clearly. Checksums, supported scene
kinds and walkable spawns are still required before loading any geometry.
The bundled defaults contain the town scenes and the three solo interiors, with verified transport
between them. Geometry does not implement NPCs, shops or quests. All ten directed links between
the four Haven towns have verified automatic triggers and original tagged arrival markers. Scene IDs identify
templates; each configured instance has a separate distributed-object ID, occupants,
movement and entry baseline. A knight is assigned a stable instance by world ID.
The client scene-version argument remains a cache version, never an instance selector.

## Maintainer imports

Original archive paths and checksums are recorded in [dependency notices](dependencies.md).
Obtaining another scene export is maintainer work. Add a verified pack under
`defaults/maps/<name>/`; preparation discovers folders automatically, with no Java
name list to edit. Use `./gradlew prepareDefaults` to copy maps, items and any future directories.
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

## Re-export the Haven interiors

Advanced Training Hall, Laboratory and Hall of Heroes are part of the checked-in seven-scene source,
so ordinary builds already prepare them with their verified layouts, town entry/return links and
portal metadata, without a game path. Running the export again re-derives those three packs from an
executable stock client, which is explicit maintainer work:

```sh
./gradlew prepareInteriors -PgameDir="/path/to/Spiral Knights"
```

This reads the installed stock client without changing it, checks the pinned archive digests, and
re-exports the three interior packs together with the linked town entry/return metadata into the
throwaway staging tree `build/interiors-export/`; `-PinteriorsOutput=/path/to/fresh/maps` selects a
different tree and `-PsceneCache=/path/to/cache` a different source cache instead of
`build/stock-scenes/`. The `publishInteriors` copy task then republishes the exported packs into the
checked-in `defaults/maps/` source and `prepareDefaults` prepares `build/defaults/`. Ordinary
`buildServer` does not run this export; run it only when the checked-in packs must change. The
staging directory must not exist, so delete `build/interiors-export` to re-export; it is a build
tree, never a committed source, and `clean` does not remove it. An existing staging tree is reused
rather than regenerated.

A deployed `build/defaults/` is prepared from the same source. Use `-PinteriorsOutput=/path/to/fresh/maps` and optionally `-PsceneCache=/path/to/cache` to export a separate fresh tree; only the staging tree published into `defaults/maps/` reaches ordinary builds. Deploy the complete `build/defaults/` tree beside the JAR, and restart the server yourself when ready. Map updates refuse a deployment whose server lock is held. A custom `maps/` beside the JAR still takes precedence. Never merge only the interior folders: the town entrance and return metadata must travel with them. The exported packs are committed scenes, not game meshes, textures, audio or installed JARs. See [solo interior scope](town-interactions.md).

## Prepare the local scene archive

`prepareInteriors` re-derives just the three interior packs. `prepareArchive` exports all seven
Haven scenes from a local scene-archive checkout instead:

```sh
./gradlew prepareArchive -PgameDir="/path/to/Spiral Knights"
```

The archive root defaults to `../reference-inputs` (`-ParchiveDir` overrides it) and must contain
`Master/Static`; a `vendor/scene-archive/` checkout is accepted too. The export reads the pinned
archive scenes and the installed stock client without changing either, and writes `scene-source.properties`
with archive paths, digests and the ownership history of generations this source published. It
exports the seven scenes and their native portal links into the throwaway staging tree
`build/archive-export/` (`-ParchiveOutput` overrides it), the `publishArchive` copy task republishes
them into the checked-in `defaults/maps/`, and `prepareDefaults` then prepares `build/defaults/`.
`./gradlew buildServer -PgameDir="/path/to/Spiral Knights"` performs that same export and publish
once. Later builds need no game path, no network and no extra source.

The packs must keep the checked-in geometry: Town Square stays the regular 2017 export, and the
export refuses to publish a pack whose kind, IDs, names, instance count, version, digests, spawn or
scene export differs from the checked-in one. A rebuild therefore can never silently replace the
checked-in scenes; updating one is explicit work in `defaults/maps/` together with its portal
metadata and manifest entries. The staging tree survives `clean` and is reused while it exists, so
re-exporting means deleting `build/archive-export` first.
