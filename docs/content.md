# Content packs and legacy resources

The 0.29.0 resource architecture resolves one complete, validated content snapshot at startup. Definitions remain external to the JAR and separate from persistent state. The loader does not download content, partially fall back after errors, merge individual item rows, or reload content while running.

## Deployment layout

```text
config/
  content.properties       # explicit pack selection and legacy policy
  server.properties        # listener/log settings
content/packs/vanilla/     # canonical checked-in versioned baseline
custom/packs/<id>/         # operator-managed versioned packs
cache/content.lock         # diagnostic snapshot, written by the server
data/                      # accounts, keys, SQLite, logs; not content
```

The stock repository content is prepared offline by `./gradlew prepareContent` into `build/content/packs/vanilla/`; `buildServer` depends on it. `./gradlew prepareDefaults` remains a deprecated alias, and `-PdefaultsOutput` remains accepted. `./gradlew prepareMaps` prepares only the canonical vanilla `maps/` directory; `-PcontentOutput` selects the complete vanilla pack output directory. `buildAdmin` prepares content as well. Preparation validates pinned sources, preserves owner-edited deployment files and refuses planned changes when `data/server.lock` is held.

A new deployment uses `config/server.properties`. If that file is absent and `data/server.properties` already exists, the server reads that legacy file in place; it does not move or rewrite it. SQLite remains schema 8. This architecture changes resource organization and startup validation, not account data or game protocol.

## Pack manifest

The canonical pack is `content/packs/vanilla/`; enabled custom pack IDs resolve under `custom/packs/<id>/`. Each pack has `pack.properties`, with `format=1`, `engine-format=1`, `id`, and `version` fields (`X.Y.Z`, with an optional prerelease suffix). Every supplied resource is declared as `resource.<key>=<relative-path>` and pinned with `sha256.<key>=<64 lowercase hex digits>`. A file hash is SHA-256 of its bytes. A directory hash is deterministic over its sorted relative file paths and file digests, as implemented by `ContentFiles`: SHA-256 of each UTF-8 relative path, a zero byte, and its raw 32-byte file digest, concatenated in path order. Use `java -cp tortodrone.jar org.tortodrone.resources.ContentFiles /resource-path` to calculate a pin, and `java -cp tortodrone.jar org.tortodrone.resources.ContentManager /deployment` for read-only full validation.

The required whole-resource keys are `entry`, `items`, `weapons`, `equipment`, and `fires`. Maps are declared one scene directory at a time as `scene.<positive-id>`. Thus packs can add scenes, or replace a whole catalog/map resource. Item rows are not merged or individually removed.

Dependencies are exact: `require.<pack-id>=<version>`. Replacing a resource owned by an earlier enabled pack requires both a dependency on that exact pack version and `override.<resource-key>=<current-provider-id>`. Pack IDs are explicit and are not discovered by scanning `custom/packs`. For example, a scene replacement pack can declare:

```properties
format=1
engine-format=1
id=my-scenes
version=1.0.0
require.vanilla=0.29.0
resource.scene.2=maps/bazaar
sha256.scene.2=<digest produced by ContentFiles>
override.scene.2=vanilla
```

Enable it with `enabled=vanilla,my-scenes` and `legacy=disabled`. A new scene uses its own positive scene ID and needs no override declaration. The scene descriptor must agree with the registered ID; portals must name installed destinations and entrances.

## Selection and compatibility

`config/content.properties` defaults to:

```properties
format=1
enabled=vanilla
legacy=auto
```

`enabled` is a comma-separated ordered list of explicit pack IDs and must include at least one pack. `vanilla` selects the bundled baseline. `legacy` is either `auto` or `disabled`. In `auto`, the read-only compatibility resolver activates if any legacy `defaults/`, `maps/`, `items/catalog.json`, or combat catalog path is present; the established per-resource precedence is retained. Legacy resources cannot be combined with versioned custom packs. Migrate first, or deliberately set `legacy=disabled` after ensuring the selected versioned packs provide all required resources.

Migration command (run against a stopped deployment):

```sh
java -cp tortodrone.jar org.tortodrone.resources.ContentMigration /path/to/deployment
```

It validates and exports the effective legacy resources into `custom/packs/legacy-import/` and writes a `config/content-migrated.properties` candidate. It does not select that candidate automatically and does not overwrite an existing pack or config. Review/copy the candidate selection explicitly when ready. The command refuses to run while `data/server.lock` is held. Keep a backup and preserve the entire `data/` directory as usual.

## Startup guarantees and limits

The server checks paths, symlinks, format versions, digests, dependencies, declared overrides, scene metadata, catalogue references and cross-catalog combat references before opening the account/key stores. Scene templates/IDs and portal references are checked by the engine; actual compressed stock-scene identities and client asset compatibility still require the unchanged-client oracle. Missing, corrupt, changing or incomplete selected content fails startup; the server does not download resources or fall back to a partial set. `cache/content.lock` records resolved pack versions and resource providers/digests for diagnostics only; it never controls selection or validation and is regenerated by the server at startup.

Content definitions do not implement game behavior. The unchanged stock client still owns models, textures, audio, UI and named display configurations; client assets, protocol constants, authentication, server mechanics and SQLite schema are unaffected. Custom content is limited to validated scene additions or whole-resource replacements. There is no row-level item merge/removal or runtime reload. See [maps](maps.md), [game data](game-data.md), and [architecture](server-architecture.md).


## Removed assumptions and retained engine rules

`DefaultResources` and its GitHub-main ZIP recovery, hardcoded required-map file list and recovery tests were removed. The five independent scene/item/combat deployment selectors now resolve through `ContentManager`; empty-map/starter-catalog production fallback was removed. The unused `TownTerrain` wrapper was removed. Stock export outputs stage under `build/exports/` rather than accidentally acting as top-level legacy overrides. Stock test/build inputs use the canonical paths. Existing legacy paths occur only at explicit compatibility/preparation boundaries.

Scene IDs, map membership, the entry scene, source locations and pack precedence live in content/selection manifests. Java still owns the supported resource schemas, bounded scene/instance capacity, native scene-config types and distributed object-ID ranges, stock player/picker identities and model argument conventions, serializers/protocol constants, collision/combat mechanics, authentication/authorization and schema-8 storage checks. Starter preview/fixture definitions remain for verified stock identities; they are not a production substitute for missing content. No new wire fields, missions or rewards were introduced.

## Acceptance record (2026-10-14)

- `./gradlew build`: passed with Java 25, 79 tasks (73 executed). Includes registry/dependency/override/path/checksum validation, explicit legacy export, unchanged data/key/settings bytes, real packaged migration and reconnect, preparation ownership/locks, current-schema refusal and the full existing engine/protocol/admin regression suite. An earlier socket timeout while overlapping native/graphical tests passed when rerun separately.
- `./gradlew integrationMaps integrationServer unitserver_network_OptionRefusalTest --offline`: passed independently, including the real SQLite/key/config preservation and migrated-pack login scenario.
- All 40 old baseline files were compared with Git `88f2964`: 39 moved byte-for-byte; only `default-source.properties` gained source/ownership pins for the new `pack.properties`. Seven scenes, 9,348 items, 382 weapons, 123 shields and 1,827 fire graphs remain present.
- The release assembly block validated a fresh canonical deployment with `ContentManager`. The local system has no `zip` executable; equivalent Python ZIP/TAR assembly and integrity checks passed and confirmed that player data, cache and operator pack files are excluded. The GitHub workflow still uses its existing ZIP/TAR commands.
- `java scripts/compat/CheckClientWorld.java "$GAME"`: stock readers validated seven scene identities, 18 portal links, item metadata/model arguments, native scene/player/trade/party/delta schemas; live checks passed login/reconnect, town movement/travel, peer updates, roles/F3 ItemCreator, tells, inspection, 68 emotes and all twelve town walking links. The overall fixture failed on the Training walking-entry callback. A focused canonical-pack probe reproduced it; the same probe against an untouched `88f2964` / 0.28.0 build in `/tmp` failed identically (`scene=294`, logged on, no move callback). This is an existing limit, not a passing six-interior-link acceptance result. Diagnostics were retained under `/tmp/tortodrone-canonical-native-4t256c12/{diagnostics,baseline-diagnostics}`; the disposable accounts/keys and spawned processes were cleaned up.
- `java scripts/compat/CheckCombatRendering.java "$GAME" /tmp/tortodrone-content-0290-graphics`: interrupted with signal 143 before full acceptance. Canonical vanilla startup, two graphical town clients, peer movement, all seven scene renders, Training actors/Konway/vial state and several sword/gun/bomb animation checks completed before interruption. Pixel review confirmed town, Laboratory, Training actors and vial quickbar; the throw capture was black. Full combat/throw pixel acceptance cannot be inferred from that run.

- The `--training-only` retry at `/tmp/tortodrone-content-0290-training-retry` failed the initial graphical town-controller movement check. A fresh full retry at `/tmp/tortodrone-content-0290-focused` rendered all seven scenes and passed shared town movement, Training actors, Konway and vial pickup, then failed `Native throw did not create a faction-owned Dynamic/Lift Objects/Throwing Vial actor`. These failures leave full graphical combat/throw acceptance incomplete; they are not described as passing checks. The first run did observe a faction-owned throw, but that does not make the failed retry a pass. Neither protocol/gameplay code nor the prepared scene/combat payloads changed in this migration.

Only test-owned processes and disposable deployments were used. The installed stock client, real preferences, owner accounts/keys/database and running sessions were not modified. Windows/macOS graphical behavior, arbitrary third-party stock assets, every item/effect, general economy/missions/dungeons, hot reload and row-level catalog merging remain unverified or unsupported as described above.
