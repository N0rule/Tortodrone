# Item catalogue and game-file reference

Tortodrone owns item instances and equipment in SQLite. The installed game owns
its assets and display configuration. An offline Java exporter reads those
definitions with the unchanged stock ConfigManager and produces a server catalogue
of references, basic metadata and model arguments. No client classes or assets are
bundled into the server, and nothing writes to the installation or real preferences.

## Prepare and deploy the catalogue

Use a JDK capable of reading the installed classes (Java 25 for the tested game):

```sh
sh scripts/prepare-items.sh "/path/to/Spiral Knights"
```

This builds both server tools and creates **build/items/catalog.tsv** and
**build/items/provenance.tsv**. The output directory must be fresh; for another
snapshot, pass a different directory as the second argument. The exporter reads
the installation locally; it does not download game content. Copy `items/` beside
both deployment JARs, alongside `maps/` and `data/`. Retain it across upgrades and
restart server/tools after replacing it. Without it, a built-in catalogue supports
the creator's helms/armor and Proto sword/gun/shield only. Invalid catalogues fail
startup rather than silently falling back. The game is never a server runtime dependency.

The tested installation provides **9,348 definitions**, including **883 supported
equipment references**: 214 helms, 204 armor, 109 shields, 138 swords, 134 guns and
84 bombs. Counts depend on the installed version. Recipes, materials, costumes,
accessory tickets and internal parts are indexed for research but cannot be granted
as equipment. Equipment must be creatable, non-stackable, supported by the stock
normal equipment slot rules, and have representable model arguments. Permanent
items and unknown argument types remain catalogue-only.

The UTF-8 TSV columns are config reference, stock config ID, resolved implementation
type, localized name key, rarity, stackability, supported slot (empty means index
only), bind-on-equip, and bounded Base64 model arguments. Stock config IDs can be
negative or zero; they are **not** persistent instance IDs. References are resolved
case-insensitively and stored with their stock spelling. Rarity -1 is an unspecified
stock value, not an invented star rank. `provenance.tsv` records hashes of the
installed code JAR and item definitions. Model arguments preserve typed strings,
integers, booleans, colorizations and nested references; player colors resolve at
rendering time. They contain no textures, meshes, audio or executable objects.

## Item commands

In normal scene chat, a current Game Master can type:

```text
!items Calibur
!giveitem Alice Alice-Knight 2 Weapon/Sword/Calibur
```

Use the **account username and saved knight name**, then quantity and the exact
config reference. Config names can contain spaces; chat and server terminal
commands consume the remaining text as the reference, without quotation marks.
The command and its result stay private to the sender. Unknown, unsupported or
invalid requests do not mutate inventories. Permission is rechecked in the write
transaction, including authentication revision and live GM revocation. Ordinary
chat beginning with an unrelated exclamation word still behaves as chat.

The local server terminal uses the same syntax without `!`:

```text
items Calibur
giveitem Alice Alice-Knight 2 Weapon/Sword/Calibur
```

The administration CLI also works while the server is running:

```sh
java -jar build/tortodrone-admin.jar --items Calibur
java -jar build/tortodrone-admin.jar --give-item Alice Alice-Knight 2 "Weapon/Sword/Calibur"
```

Grants create 1–20 distinct, unequipped, level-zero, zero-heat instances. The current
limit is 256 instances per knight, including starters; this is a Tortodrone limit,
not a claim about official inventory capacity. Grants do not displace equipped gear
or reset movement. Online recipients receive original item entry events observed
by the stock Arsenal; offline recipients receive their items on selection.
Equipping applies the item's verified bind-on-equip rule. Grants, instance ID
allocation, profile revision and `item_events` audit records commit together.
Audit rows identify the GM account; local console/CLI grants use a null actor.
Knight deletion removes its item/audit rows. Creator edits affect only original
starter cosmetics, retaining additional items of the same type.

This milestone implements storage, equipment presentation and grants. Combat
damage, drops, stacking, crafting, trade, costumes, weapon switching, levelling,
random variants and accessory attachment are separate work. The lowest equipped
weapon slot remains the visible held weapon. Catalogue rarity/model information
does not imply server combat stats have been implemented.

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
