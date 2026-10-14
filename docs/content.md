# Content and default resources

**Current format (v0.28.0):** external `defaults/`, with direct catalogs and map packs. **Proposed future content-pack design:** described below and **not implemented**. Do not write files using the proposed format and expect current releases to load them.

## What ships today

`./gradlew buildServer` or `./gradlew prepareDefaults` validates the checked-in source at `defaults/` and copies it to `build/defaults/`. Package/deploy that complete directory beside `tortodrone.jar`. Game installation and online archives are **not** required for ordinary preparation. Server startup can attempt optional missing-file download from a hardcoded GitHub `main` ZIP; it never replaces files. Prefer complete local deployment.

```text
defaults/
  maps/
    catalog.properties
    scene-source.properties
    haven/
      map.properties
      scene.dat
      walkability.gz
      portals.properties
    advanced_training/
      content.properties    # prepared scene actors/interactions
      static-collision.properties
    bazaar/ arcade/ garrison/
    laboratory/ hall_of_heroes/
  items/
    catalog.json
    provenance.tsv
  combat/
    weapons.tsv
    equipment.tsv
    fires.json
    provenance.tsv
  default-source.properties
```

The seven stock scenes are Haven (1), Bazaar (2), Arcade (3), Garrison (445), Advanced Training (294), Laboratory (30893) and the four-star Hall of Heroes (318). Captured stock layouts from October 2026 support twelve directed town portals plus six interior travel links. Training includes prepared actors, collisions and limited interactive mechanics; the other interiors remain more limited. None of this constitutes general dungeon/mission support. See [modern captures](modern-captures.md) and [Training Hall evidence](training-hall.md).

At runtime, a top-level `maps/` beside the JAR **replaces the entire** `defaults/maps/` catalog if that path exists; it does not merge. Likewise a top-level `items/catalog.json` and files under `combat/` override their matching defaults. Server `data/` is independent.

## Map pack contract

`defaults/maps/catalog.properties` selects the town spawn with `entry-scene=1` by default. Every direct child scene folder needs:

| File | Meaning |
| --- | --- |
| `map.properties` | Positive unique `scene-id`, `zone-id` and zone/name/image, `kind` (`town`/`training`/`level`), signed `version`, walkable `spawn-x/y`, `instances` (1–16), SHA-256s |
| `scene.dat` | Original compressed Tudey scene export sent to the stock reader |
| `walkability.gz` | Derived sampled collision grid used for authoritative movement |
| `portals.properties` | Optional validated entrance tags, rectangular/convex-polygon triggers and destinations |
| `content.properties` / `static-collision.properties` | Optional supported stock-derived scene actors/interactions and detailed collision (present in Training); not required for every map |

The loader scans subfolders, not a fixed Java map-name list. It checks header/hashes, descriptor paths, valid spawn, portal arrival safety, unique scene IDs, shared zone-name consistency and existing destination entrance tags. Limits: **64 templates**, **128 configured instances**, scene file up to 900,000 bytes and collision file up to 1 MiB. Missing portal metadata is allowed but provides **no automatic travel**; merely adding a scene does not make it visitable. Every non-town template currently uses a private per-knight interior runtime; that is not a general dungeon/party implementation.

**Install a prepared third-party pack:** stop server; back up the map catalog; copy its folder under deployed `defaults/maps/` (or add it to a complete top-level `maps/` catalog); keep its binary files and matching hashes together; ensure a valid town `entry-scene` or authored portal connection; restart and check Scenes/debug logs and an unchanged client. No runtime map reload or GUI map installer exists.

**Change starting town:** edit deployed `defaults/maps/catalog.properties`, e.g. `entry-scene=123` for a valid installed `kind=town` scene 123, then restart. This changes everyone's default entry scene. For portals, `trigger.<entryId>.scene` and `trigger.<entryId>.entrance` must reference an installed target with a matching `entrance.<tag>.*` and a walkable, safe arrival. Return links require explicit configuration.

**Import a verified raw archived scene (maintainer/advanced):**

```sh
java scripts/StockTools.java scene "/path/to/Spiral Knights" "/path/to/archive-scene.dat" "/tmp/new-scene-pack" 1 Haven
```

Run from the **source checkout**, with JDK 25 and a compatible, read-only stock game installation (including `code/projectx-pcode.jar`); the output folder must be new. The importer derives native kind, collision samples, closest walkable spawn, descriptor/hashes and optional default interior entrance. The newer source tools can also extract optional scene content and collision metadata where supported. It cannot convert arbitrary images/models/Unity scenes, create full dungeon functionality or infer all return portals. Try the scene in the unchanged client: archive revisions/assets can differ.

Maintainer stock extraction and captured-layout re-exports are separate source-maintenance workflows; ordinary Gradle builds use the checked-in validated packs and do not automatically import games or captures. See `scripts/StockTools.java`, `scripts/compat/ScenePackExport.java` and `docs/development.md`.

## Items and combat

- `defaults/items/catalog.json` is the stock-derived **v2 JSON** definition catalog (v1 readable); persistent owned items/equipment, binding and currencies live in SQLite. The indexed default snapshot contains 9,348 definitions; 8,570 satisfy current grantability checks. An indexed definition is **not** evidence its gameplay/use/crafting behavior works.
- `defaults/combat/weapons.tsv`, `equipment.tsv` and `fires.json` supply numeric weapon controls, modifiers and authored fire graphs; server Java implements state/attacks/replication. Current snapshot includes 382 weapons, 123 shields and 1,827 attack graphs with known unsupported effects/actions.
- Local overrides use `items/catalog.json` and `combat/{weapons.tsv,equipment.tsv,fires.json}` beside the JAR. Restart after changing. For raw stock extraction, use `java scripts/StockTools.java items|weapons|equipment|fires ...` with command-specific help/code; outputs are not automatically installed.
- The stock client retains meshes, textures, audio, UI and named configs; these are **not bundled** as server assets. Server configs reference stock-compatible identities.

## Checksums and update behavior

`maps/scene-source.properties` and `default-source.properties` are **build-time source/ownership manifests**, not runtime registration catalogs. Source preparation verifies pinned `source.*` digests, copies missing files and upgrades only destination files recognized as earlier published `owned.*` versions; owner-modified destination bytes are preserved. Changing checked-in pinned source packs requires updating their source manifest and, for a changed layout, all related collision/portal data and per-pack digests. Directly installed valid custom packs do **not** need registration in the archive manifest.

Beware: owner modifications preserved by preparation may still fail runtime validation if only one matching file was edited. Do not merge geometry without matching metadata. The server may start with login/admin but reject world entry when scene loading fails.

## Direction for a full vanilla server — **design, not runtime API**

Instead of expanding root `defaults/` forever, eventually distinguish:
- `content/packs/vanilla/`: immutable, versioned stock-derived world, actors, combat, economy, missions and dungeon **definitions**;
- `custom/packs/`: explicitly enabled/validated additive packs and declared overrides;
- `config/`: operator settings and enabled pack selection;
- `data/`: player/account/progression/world state, not replaceable content;
- `cache/`: disposable compiled indices.

A future `ContentRegistry` could atomically resolve pack dependencies, referential integrity, native IDs, compatibility and a lockfile at startup. The **Java engine** must still implement protocol, movement, AI, combat, purchases, missions, persistence and permissions: importing definitions cannot by itself create official features. Implement this in small backwards-compatible steps, grounded in stock behavior; **no content pack loader, pack schema, custom content installer or hot reload is shipped today**.

