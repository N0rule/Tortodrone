# Item catalogue and game-file reference

Tortodrone owns item instances and equipment in SQLite. The installed game owns
its assets and display configuration. An offline Java exporter reads those
definitions with the unchanged stock ConfigManager and produces a server catalogue
of references, basic metadata and model arguments. No client classes or assets are
bundled into the server, and nothing writes to the installation or real preferences.

## Prepare and deploy the catalogue

Use a JDK capable of reading the installed classes (Java 25 for the tested game):

```sh
./gradlew shadowJar
java scripts/StockTools.java items "/path/to/Spiral Knights"
```

Build the server JAR first; the exporter then creates **build/exports/items/catalog.json**
and **build/exports/items/provenance.tsv**. Output must be a fresh directory; pass a different
directory as the optional final argument for another snapshot. The exporter reads
the installation locally and never downloads game assets.

The checked-in definitions live in `content/packs/vanilla/items/catalog.json` and are
copied by `./gradlew prepareContent`. Item catalogs are whole resources in the versioned content
registry: a selected pack may explicitly replace the whole `items` resource, but rows are not
merged or individually removed. The loader validates the complete resource set and cross-references
before account/key storage opens. Invalid or missing selected resources fail startup. Restart after
changing definitions; preserve operator packs across updates. The game is not a runtime dependency.

The checked-in v2 snapshot contains **9,348 definitions**, of which **8,570 are
grantable** under the current native-record and model-argument rules. Counts describe
the tested installation and may differ in another installed version. V2 records the
stock item subtype (`variant`), an optional artifact seed, and the independent
`locked` state.

Grantable entries need a nonzero stock ID, usable display metadata and a supported
stock item record. Equipment also needs a verified slot and representable model
arguments. Stock `creatable` is not an obtainability filter: event or permanent gear
is grantable when it meets those checks. Unsupported definitions remain indexed but
cannot be granted.

The exported subtype and inventory slot select the native item record. Equipment,
costume and trinket slots use `LevelItem`. For generic inventory, `SEED_ITEM` uses
`SeedItem` with its 64-bit seed, `LEVEL_ITEM` uses `LevelItem`, and `ITEM` uses the
common `Item` record. Upgrade variants are indexed only. Artifacts are grantable only
when a seed-item subtype and seed are present. Other generic records can be granted
when their stock type is supported. `permanent` and `locked` are distinct stock flags;
permanent entries are always locked. Artifact, Mineral, Token and TokenArtifact
records default to locked, while v2 preserves the stock lock state for other entries.
Locked records can be granted, but their instances retain the lock and cannot be
traded. Binding is separate: permanent gear is bound when created, and `bindOnEquip`
gear becomes bound when equipped.

The UTF-8 JSON document has `format: "tortodrone-items"`, `version: 2`, and an
`items` array. Each item records `config`, `stockId`, `type`, `nameKey`, `rarity`,
`stackable`, `slot` (null means index only), `bindOnEquip`, `permanent`, and
`modelArguments`, plus `locked`, `variant`, and `seed`. Values are readable JSON
rather than Base64. Model references,
normal/translated colorizations, player colors and finite floats carry explicit
`type` objects; strings, integers and booleans use native JSON values. This preserves
the exact stock wire types. Unknown fields/types, duplicates, invalid flags and
oversized/deep data are rejected. TSV/CSV is not a runtime catalogue format; obtain
a fresh JSON export rather than migrating account/character data. V1 catalogues remain
readable with stock-derived lock defaults; artifact entries without v2 seed metadata
remain indexed but are not grantable.

Stock IDs may be signed and are distinct from persistent item instance IDs.
Only unique nonzero IDs resolve; exact references remain case insensitive.
Rarity -1 is an unspecified stock value. `provenance.tsv` records original installed
metadata hashes; definitions contain references, never textures, meshes or audio.

## Item commands

In normal scene chat, a current Game Master can type:

```text
!help
!items Calibur
!giveitem Alice-Knight 2 Weapon/Sword/Calibur
!giveitem Alice-Knight 2 1157187935
```

Use a globally unique saved **knight name**, then quantity and the exact config
reference or catalogue ID shown by `items`. The example ID is Calibur in the tested
installation; consult your exported catalogue. Config names can contain spaces; chat
and server terminal commands consume the remaining text as the reference, without
quotation marks. The previous account-qualified form remains supported, for example
`!giveitem Alice Alice-Knight 2 Weapon/Sword/Calibur`.
The command and its result stay private to the sender. Unknown, unsupported or
invalid requests do not mutate inventories. Permission is rechecked in the write
transaction, including authentication revision and live GM revocation. Unknown GM
`!` commands return a private error pointing to `!help`. For normal players and
revoked staff, every `!` message remains ordinary scene chat, with no command
recognition or staff help.

The local server terminal uses the same syntax without `!`:

```text
items Calibur
giveitem Alice-Knight 2 Weapon/Sword/Calibur
```

The administration CLI also works while the server is running:

```sh
java -jar build/tortodrone-admin.jar --items Calibur
java -jar build/tortodrone-admin.jar --give-item Alice-Knight 2 "Weapon/Sword/Calibur"
```

Grants give 1–20 units per request. Equipment creates distinct, unequipped,
level-zero, zero-heat instances. Stackable plain items merge into an existing
unbound stack, or create a new one; each stack is capped at 9,999 units. Other
non-stackable inventory items remain distinct. Permanent equipment is bound from
creation; other equipment follows its verified bind-on-equip rule. Costumes use
slots 3, 4 and 15, and trinkets use slots 10/11. Costume models replace normal gear
appearance without removing the underlying equipped gear. The current
limit is 256 instances per knight, including starters; this is a Tortodrone limit,
not a claim about official inventory capacity. Grants do not displace equipped gear
or reset movement. Online recipients receive original item entry events observed
by the stock Arsenal; offline recipients receive their items on selection.
Equipping applies the item's verified bind-on-equip rule. Grants, instance ID
allocation, profile revision and `item_events` audit records commit together.
Audit rows identify the GM account; local console/CLI grants use a null actor.
Knight deletion removes its item/audit rows. Creator edits affect only original
starter cosmetics, retaining additional items of the same type.

Storage, stacking, equipment/costume presentation and grants are implemented. Verified
weapon controls and authored projectile emissions are described in
[town player behavior](town-player-behavior.md). Crafting, learning recipes, opening
boxes, using tickets/consumables, sprite hatching, accessory attachment, hostile
targets, damage, drops, levelling and random variants remain unavailable; granting an
item does not implement its use action. The lowest equipped weapon slot remains the
visible held weapon. Item rarity/model metadata does not establish combat statistics.

## Index every installed file

```sh
java scripts/compat/GameFileIndex.java "/path/to/Spiral Knights" local/catalogue/game-files
```

Choose a fresh output directory outside the installation. `files.tsv` lists physical
files by relative path, size, SHA-256 and a coarse purpose hint. `archive-entries.tsv`
lists entries inside JAR/ZIP files by container, entry path, sizes and CRC32. Symlinks
are listed but never followed; assets are never extracted. Archive CRCs are reference
metadata, not cryptographic authenticity checks. The tested snapshot contains 16,672
physical files and 34,872 archive entries. Both generated indexes remain ignored
under `local/`; they describe this machine's installation and are not build inputs.

| Location | How to interpret it |
| --- | --- |
| `code/projectx-pcode.jar` | Stock protocol readers, data classes and client behavior; inspect with `javap` |
| Other `code/*.jar` | Networking/rendering libraries, native libraries and platform dependencies |
| `rsrc/config/item.dat` | Named item definitions; decode with the stock ConfigManager |
| `rsrc/config/item_property.dat`, `level_table.dat`, `variant*.dat` | Item properties, heat/level tables and variant references for later implementation |
| `rsrc/config/recipe*.dat` | Recipe definitions/properties; not a crafting service |
| `rsrc/i18n/` and matching archive entries | Localized display strings keyed by item metadata |
| `rsrc/item/`, `character/`, `animation/` | Models, textures and animation resources; client-owned assets |
| `rsrc/ui/`, `world/`, `sound/` | Interface resources, scene/world resources and audio |
| `rsrc/*-bundle.jar` | Resource bundles; consult the archive index for contained files |
| `*.jarv` | Distribution verification metadata, not another item database |

Purpose hints come from paths and extensions. A `.dat` suffix alone does not identify
the serialization format. Resolve references with the relevant stock reader before
implementing behavior; never infer packet fields from filenames. For repository
files, `git ls-files > local/catalogue/repository-files.txt` creates the companion
tracked-file list; the package map in [architecture](server-architecture.md) explains
the server code. Regenerate that list after adding or removing tracked files.

## Compatibility evidence

`CheckClientWorld.java` now exports a disposable catalogue, compares every metadata
row and all supported equipment model arguments against stock configuration at three
player colors, and exercises actual speak/equip RPCs and item-observer callbacks.
It checks private results, cross-map grants, normal/revoked denial, unsupported types,
offline targets, binding and reconnect persistence. A stock-reader fixture also applies
combined grant/equip events so an equip transaction cannot swallow new-item notifications.
All deployments/accounts and preferences are temporary. The independent graphical
Arsenal check accepted Calibur icons, distinct instances, live additions, the binding
prompt and the equipped highlight moving to the selected sword. It also checked the
persisted loadout on reconnect and movement after closing inventory. It does not accept
all item animations or resolve the previously intermittent creation symptom;
network/model equality alone cannot establish walking animation acceptance.

The 0.11.0 checks also validate every grantable native item class/count/binding flag,
translated-color costume actor serialization and cosmetic precedence. Live stock
checks exercise grants by ID, costume slots, plain recipes, stack merges and
reconnect. The isolated graphical check accepted Steam Knight Mask and Cool
Hexoskeleton icons/binding/world models, a live Brimstone stack update to 10 and
movement after closing Arsenal. Unsupported item-use actions remain unavailable.

Run focused item, equipment and combat regressions as listed in [town player behavior](town-player-behavior.md). The unchanged
stock-reader check is `java scripts/compat/CheckClientWorld.java "/path/to/Spiral
Knights"`; it uses disposable server data and checks emitted schemas. Network and
schema acceptance do not establish graphical rendering, animations or complete
gameplay behavior.

## Local catalogue identities and browsing

`items <search> [--page N]` searches friendly names, aliases, canonical paths,
stock IDs, extracted name keys and types. `items category <type> [--page N]`
filters the exact extracted type; each command displays five entries per page.
`items info <reference>` inspects a definition, including definitions that cannot
be granted, and explains their availability. These commands do not grant items.

Friendly names are derived from the final canonical path segment, rather than
localized client text. Categories use the exported `type`, including separate
HelmCostume, ArmorCostume and ShieldCostume categories. Aliases are derived at
load time as `category-prefix:name-slug` (for example `sw`, `gun`, `bomb`,
`helm`, `armor`, `acc`, `mat` and `recipe`, otherwise the exact type slug); they are not extracted stock identifiers or
additional JSON fields. When several entries share that alias, every member gets
a suffix containing its stock ID and a SHA-256 digest of its case-insensitive
canonical path. This makes collision choices deterministic even when source
ordering changes; stock ID zero does not uniquely identify an item.

Grants accept an exact canonical path, derived alias, stock ID or exact friendly
name. Ambiguous IDs and names list explicit alias/path choices. Unsupported
entries remain inspectable, while grants retain the existing native-record and
model-argument restrictions. Derived aliases may change if a catalogue adds a
new collision; canonical paths remain the preferred persistent references.

Colliding aliases use a reserved `@<stock-id>-<12-hex-path-digest>` suffix;
uniqueness is validated. Ambiguity feedback is bounded to five choices. Aliases
are reproducible within a catalogue snapshot; adding a new name collision can
change the formerly unsuffixed alias. Canonical paths remain the durable identity.
