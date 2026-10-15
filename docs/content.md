# Content and default resources

**Current format (v0.30.1):** versioned content packs selected through `data/server.properties`. The shipped `vanilla` pack contains the checked-in scenes and catalogs; explicitly enabled custom packs may add or replace complete resources under declared dependency/override rules. Older `defaults/` and top-level resource layouts remain available through read-only legacy compatibility when no versioned pack is selected.

## What ships today

`./gradlew validateContent` checks the selected checked-in pack; `./gradlew prepareContent` prepares it under `build/content/packs/vanilla/`. `buildServer` prepares the content with the executable JAR. Game installation and online archives are **not** required for ordinary preparation. The runtime resolves and validates the selected content before key/account storage and does not download missing resources.

```text
data/server.properties
content/packs/vanilla/pack.properties
content/packs/vanilla/maps/...
content/packs/vanilla/items/catalog.json
content/packs/vanilla/combat/{weapons.tsv,equipment.tsv,fires.json}
content/custom/packs/<id>/pack.properties
```

The seven stock scenes are Haven (1), Bazaar (2), Arcade (3), Garrison (445), Advanced Training (294), Laboratory (30893) and the four-star Hall of Heroes (318). Captured stock layouts from October 2026 support twelve directed town portals plus six interior travel links. Training includes prepared actors, collisions and limited interactive mechanics; the other interiors remain more limited. None of this constitutes general dungeon/mission support. See [modern captures](modern-captures.md) and [Training Hall evidence](training-hall.md).

`data/server.properties` uses `content-packs=vanilla` and `legacy-content=auto` by default. It lists enabled pack IDs (`vanilla` by default) and the legacy policy. The vanilla pack is at `content/packs/vanilla/`; other selected packs are at `content/custom/packs/<id>/`. Each pack declares a version, resource paths and SHA-256 digests in `pack.properties`. A resource is a complete catalog or scene directory. A custom pack can override an existing resource only when it declares the exact dependency/version and override owner. Packs are resolved and validated as one snapshot before the server opens persistent account/key state; unknown properties, unsafe paths, bad hashes, missing references, incompatible dependencies and undeclared overrides fail startup.

Legacy layouts remain read-only compatible when `legacy-content=auto` and legacy files are present. Do not edit or delete an existing deployment as part of migration. To prepare a candidate, run the packaged `org.tortodrone.resources.ContentMigration` command against a stopped deployment. It exports to a new `content/custom/packs/legacy-import/` directory and writes `data/server-migrated.properties`; review both before explicitly selecting the candidate. It refuses occupied destinations and does not change original resources or player data. See `src/main/java/org/tortodrone/resources/ContentMigration.java` for the exact command contract.

## Map pack contract

`content/packs/vanilla/maps/catalog.properties` selects the town spawn with `entry-scene=1` by default. Every scene directory is declared as a whole `resource.scene.<id>` in its pack manifest and needs:

| File | Meaning |
| --- | --- |
| `map.properties` | Positive unique `scene-id`, `zone-id` and zone/name/image, `kind` (`town`/`training`/`level`), signed `version`, walkable `spawn-x/y`, `instances` (1–16), SHA-256s |
| `scene.dat` | Original compressed Tudey scene export sent to the stock reader |
| `walkability.gz` | Derived sampled collision grid used for authoritative movement |
| `portals.properties` | Optional validated entrance tags, rectangular/convex-polygon triggers and destinations |
| `content.properties` / `static-collision.properties` | Optional supported stock-derived scene actors/interactions and detailed collision (present in Training); not required for every map |

The loader scans subfolders, not a fixed Java map-name list. It checks header/hashes, descriptor paths, valid spawn, portal arrival safety, unique scene IDs, shared zone-name consistency and existing destination entrance tags. Limits: **64 templates**, **128 configured instances**, scene file up to 900,000 bytes and collision file up to 1 MiB. Missing portal metadata is allowed but provides **no automatic travel**; merely adding a scene does not make it visitable. Every non-town template currently uses a private per-knight interior runtime; that is not a general dungeon/party implementation.

**Install a prepared third-party pack:** stop the server; back up `data/server.properties` and current packs; copy the complete pack under `content/custom/packs/<id>/`; validate its `pack.properties` hashes and required catalog/scene references; add its ID to `content-packs=`; then restart and check scene logs and an unchanged client. A custom pack replacing an existing resource must declare the exact dependency and override owner. There is no runtime map reload or GUI pack installer.

**Change starting town:** edit the selected pack's complete `maps/catalog.properties` resource, e.g. `entry-scene=123` for a valid `kind=town` scene, update that resource's manifest digest, then restart. This changes everyone's default entry scene. For portals, `trigger.<entryId>.scene` and `trigger.<entryId>.entrance` must reference an installed target with a matching `entrance.<tag>.*` and a walkable, safe arrival. Return links require explicit configuration.

**Import a verified raw archived scene (maintainer/advanced):**

```sh
java scripts/StockTools.java scene "/path/to/Spiral Knights" "/path/to/archive-scene.dat" "/tmp/new-scene-pack" 1 Haven
```

Run from the **source checkout**, with JDK 25 and a compatible, read-only stock game installation (including `code/projectx-pcode.jar`); the output folder must be new. The importer derives native kind, collision samples, closest walkable spawn, descriptor/hashes and optional default interior entrance. The newer source tools can also extract optional scene content and collision metadata where supported. It cannot convert arbitrary images/models/Unity scenes, create full dungeon functionality or infer all return portals. Try the scene in the unchanged client: archive revisions/assets can differ.

Maintainer stock extraction and captured-layout re-exports are separate source-maintenance workflows; ordinary Gradle builds use the checked-in validated packs and do not automatically import games or captures. See `scripts/StockTools.java`, `scripts/compat/ScenePackExport.java` and `docs/development.md`.

## Items and combat

- `items/catalog.json` is the stock-derived **v2 JSON** definition catalog (v1 readable), in the selected pack; persistent owned items/equipment, binding and currencies live in SQLite. The indexed default snapshot contains 9,348 definitions; 8,570 satisfy current grantability checks. An indexed definition is **not** evidence its gameplay/use/crafting behavior works.
- `combat/weapons.tsv`, `equipment.tsv` and `fires.json` supply numeric weapon controls, modifiers and authored fire graphs from the selected pack; server Java implements state/attacks/replication. Current snapshot includes 382 weapons, 123 shields and 1,827 attack graphs with known unsupported effects/actions.
- To customize definitions, create or select a complete pack under `content/custom/packs/` and declare its resources, hashes, dependencies and overrides in `pack.properties`. Restart after changing content. For raw stock extraction, use `java scripts/StockTools.java items|weapons|equipment|fires ...` with command-specific help/code; outputs are not automatically installed.
- The stock client retains meshes, textures, audio, UI and named configs; these are **not bundled** as server assets. Server configs reference stock-compatible identities.

## Checksums and update behavior

`content/packs/vanilla/pack.properties` pins each whole resource with SHA-256 digests. Preparation tools verify checked-in source manifests, fill missing output files and upgrade only destination files recognized as earlier published versions; owner-modified destination bytes are preserved. Changing a source resource requires updating its manifest digest and, for changed map geometry, matching collision/portal data. Custom packs have their own independent manifests.

Beware: owner modifications preserved by preparation may still fail runtime validation if only one matching file was edited. Do not merge geometry without matching metadata. The server may start with login/admin but reject world entry when scene loading fails.

Content packs provide external definitions; they do not add mechanics to the Java engine. The current implementation supports complete scene/catalog resources and their declared selection/dependency/override rules. It does not provide hot reload, partial-file overlays, arbitrary dungeon behavior, missions, shops or general NPC/monster mechanics. Persistent accounts, ownership and progression remain in `data/`, separate from replaceable content.

Builds and startup generate `data/server.properties` from bundled defaults when it is missing. Content validation reads the settings without creating files, and no separate config or diagnostic cache directory is needed. Pack hashes remain in `pack.properties`. Existing settings are preserved; missing options use the bundled defaults.
