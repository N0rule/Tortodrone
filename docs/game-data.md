# Item catalogue and game-file reference

Tortodrone owns item instances and equipment in SQLite. The installed game owns
its assets and display configuration. An offline Java exporter reads those
definitions with the unchanged stock ConfigManager and produces a server catalogue
of references, basic metadata and model arguments. No client classes or assets are
bundled into the server, and nothing writes to the installation or real preferences.

## Prepare and deploy the catalogue

Use a JDK capable of reading the installed classes (Java 25 for the tested game):

```sh
java scripts/StockTools.java items "/path/to/Spiral Knights"
```

This builds both server tools and creates **build/items/catalog.json** and
**build/items/provenance.tsv**. Output must be a fresh directory; pass a different
directory as the optional final argument for another snapshot. The exporter reads
the installation locally and never downloads game assets.

The checked-in default definitions live in `defaults/items/catalog.json` and are
copied by `make defaults`. Both applications prefer custom `items/catalog.json`
beside their JARs, then use the default catalogue. Server startup warns and uses
built-in starter definitions if neither is usable. Restart after changing definitions;
preserve custom catalogues across updates. The game is not a runtime dependency.

The tested installation provides **9,348 definitions**, including **8,454 grantable
player items**: 905 normal equipment references, 2,421 costumes, 93 trinkets and
5,035 plain inventory definitions. The latter include materials, recipes, accessory
and variant tickets, minerals, tokens, rarity items, sprite eggs, cosmetic upgrades,
boxes and consumables. Counts depend on the installed version.

Grants require a nonzero stock ID, usable display metadata and a supported stock
item record. Equipment additionally requires verified slot rules and representable
model arguments. Unobtainable/event/permanent player gear is allowed when it passes
those checks; stock `creatable` is not treated as an obtainability restriction.
Internal parts, developer/test/PvP definitions, expiring items, dungeon-only pickups
and unimplemented specialized records (furniture, seed/artifact, gift-box and
upgrading records) remain indexed but unavailable for grants. This is a conservative
compatibility policy, not a guarantee that every item animation or gameplay action
has been exercised. Accessory tickets may derive their names from accessory definitions.

The UTF-8 JSON document has `format: "tortodrone-items"`, `version: 1`, and an
`items` array. Each item records `config`, `stockId`, `type`, `nameKey`, `rarity`,
`stackable`, `slot` (null means index only), `bindOnEquip`, `permanent`, and
`modelArguments`. Values are readable JSON rather than Base64. Model references,
normal/translated colorizations, player colors and finite floats carry explicit
`type` objects; strings, integers and booleans use native JSON values. This preserves
the exact stock wire types. Unknown fields/types, duplicates, invalid flags and
oversized/deep data are rejected. TSV/CSV is not a runtime catalogue format; obtain
a fresh JSON export rather than migrating account/character data.

Stock IDs may be signed and are distinct from persistent item instance IDs.
Only unique nonzero IDs resolve; exact references remain case insensitive.
Rarity -1 is an unspecified stock value. `provenance.tsv` records original installed
metadata hashes; definitions contain references, never textures, meshes or audio.

## Item commands

In normal scene chat, a current Game Master can type:

```text
!items Calibur
!giveitem Alice Alice-Knight 2 Weapon/Sword/Calibur
!giveitem Alice Alice-Knight 2 1157187935
```

Use the **account username and saved knight name**, then quantity and the exact
config reference or catalogue ID shown by `items`. The example ID is Calibur in
the tested installation; consult your exported catalogue. Config names can contain spaces; chat and server terminal
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

This milestone implements storage, stacking, equipment/costume presentation and
grants. Crafting, learning recipes, opening boxes, using tickets/consumables, sprite
hatching, accessory attachment, combat damage, drops, trade, weapon switching,
levelling and random variants remain unavailable; grants do not implement those actions. The lowest equipped
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

The 0.11.0 checks also validate every grantable native item class/count/binding flag,
translated-color costume actor serialization and cosmetic precedence. Live stock
checks exercise grants by ID, costume slots, plain recipes, stack merges and
reconnect. The isolated graphical check accepted Steam Knight Mask and Cool
Hexoskeleton icons/binding/world models, a live Brimstone stack update to 10 and
movement after closing Arsenal. Unsupported item-use actions remain unavailable.
