# Editing server content

Version 0.35 uses one `content/` tree and readable YAML under `config/`. Edit a file,
validate it, and restart the server. No pack selection, manifests, manual hashes,
Java compilation or hot reload is involved. Player inventories, identities and
progression remain in SQLite. Back up a stopped deployment before upgrading.

```text
config/
  server.yml                 listener, limits, logs, startup-map
  combat.yml                 dodgeCooldownMs
content/
  maps/<map>/
    map.yml                  readable identity, default entrance, instance count
    portals.yml              arrivals and travel destinations
    actors.yml               supported placed actor properties
    mechanics.yml            map-local numeric rules and signals
    npcs/<npc>.yml           dialogue graphs and text
    native/                  pinned scene, collision and source bindings
  items/
    weapons.yml              item definitions and supported attack controls
    gear.yml                 armor, helms, shields and trinkets
    costumes.yml
    accessories.yml
    materials.yml
    consumables.yml
    misc.yml                 other indexed item definitions
  town/
    shops.yml                ordinary merchant catalogs only
    recipes.yml
    stations.yml
    sprites.yml
    variants.yml
    accessories.yml
    progression.yml          forge tables and gear progression
  shared/
    trap-cycles.yml
    statuses.yml
    combat.yml               shared numeric balance and sparse gear defenses
    news/news.yml            Uplink News packs and their images
  _internal/                 item bindings/arguments, Town defaults, fire graphs, provenance
plugins/README.md            reservation only; no plugin loader
src/ scripts/ docs/ gradle/   implementation, import tools, docs and build system
data/                        private runtime state, keys and logs; never in Git
```

The checkout and portable deployment use the same content/configuration layout.
`./gradlew build` prepares `build/content`, `build/config` and
`build/plugins` beside both executable JARs. Ordinary builds are offline once
Gradle dependencies are cached and never require a game installation or capture.

The seven real maps are `haven` (1), `bazaar` (2), `arcade` (3), `garrison` (445),
`advanced_training` (294), `laboratory` (30893), and `hall_of_heroes` (318).
There are 9,348 indexed original item definitions, 382 weapon controls and 123
shields. Indexed content does not establish gameplay support. The implementation
still has the limits described in [Architecture](architecture.md) and the
[stock support index](item-support.csv); general missions, dungeon AI, rewards,
random vendor stock and many original actions remain incomplete.

## Configuration and validation

`config/server.yml` retains the existing listener/logging setting names:

```yaml
server-ip: 0.0.0.0
server-port: 47624
news-port: 8080
max-clients: 16
log-mode: normal
log-max-mib: 10
log-retention: 5
startup-map: haven
```

`startup-map` names an installed map folder. The current startup flow requires a
town; Ready Room/progression onboarding is not implemented. `--config path.yml`
changes the settings file without relocating `data/`. Existing explicitly chosen
`.properties` settings remain readable, including their listener overrides.
`config/combat.yml` holds the verified global dodge cooldown in milliseconds.
Static merchant rules belong to Town YAML. Depot offers/promotions and auctions
belong exclusively to SQLite; no Depot YAML or empty economy file is shipped.

Validate without starting a listener or opening SQLite/keys:

```sh
java -cp build/tortodrone.jar org.tortodrone.resources.ContentManager /deployment
```

All YAML is data-only, with bounded sizes/depth, duplicate-key rejection and field
validation. Use spaces, quote numeric-looking string keys and quote localization
text containing YAML punctuation. Files and directories cannot traverse symlinks.
Unknown fields, duplicate map/item/NPC identities, missing references, invalid
spawns, broken jumps and bad native fingerprints stop loading. Errors identify
the file or definition. Startup fingerprints inputs automatically, checks them
again after validation, and publishes one complete snapshot. Existing snapshots
remain usable if a subsequent load fails. Gameplay performs no YAML parsing or
content file scans.

## Uplink News

After a knight logs in the unmodified client requests Uplink News and shows one
pack of banners. `content/shared/news/news.yml` holds one or more packs, and each
request shows one at random; the client fetches every image as
`content_url + news/<file>`, so the launcher's `content_url` must point at this
server's `news-port`.

```yaml
enabled: true
packs:
  - featured:
      image: featured.png
      tooltip: Tortodrone Server
      url: https://github.com/N0rule/Tortodrone
    spotlights:
      left:
        image: left.png
      right:
        image: right.png
```

Every pack needs a `featured` banner; `spotlights.left` and `spotlights.right` are
optional side banners. Each banner needs a PNG or JPEG `image` stored directly in
this folder, sized for the client's news window: 570x300 for `featured` and 280x190
for the spotlights, the same sizes as the window's shipped parts. The optional
`tooltip` shows only when the same banner also sets a `url`, which opens in the
player's browser. Images may not escape the folder, and a missing, non-image or
escaping file stops startup, as does `enabled: true` with no packs. An absent
`news.yml`, or `enabled: false`, answers the client with no News.
Startup checks PNG/JPEG header dimensions against those slot sizes without
initializing AWT, and shares the HTTP server's 8 MiB file limit. This byte limit
is a local serving policy.
`action: depot` opens the native Supply Depot instead of a URL; it also enables
the tooltip. Other actions and nonempty arguments are rejected in this Town-only
implementation. The property names and action routing follow reference inputs
`client/bX.java:40-54`, `client/ds_1.java:369-372` and the captured Depot banner in
`contrib/check_uplink_news.py`; no archived banner assets are bundled.
The server serves this folder over HTTP on `server-ip:news-port` and refuses
traversal, non-image paths and unknown files, so point the launcher's `content_url` at the host and `news-port` configured
in `config/server.yml`.

## NPC dialogue

Open `content/maps/advanced_training/npcs/konway.yml`. Its actual opening text is:

```yaml
id: konway
dialogue:
  initial: part 1a
  nodes:
    part 1a:
      text: "Welcome to our advanced training hall. Here you will find useful survival tips to help you on your journey through the Clockworks. \n\nWould you like some directions?"
      responses:
      - text: Sure, where should I start?
        next: part 2a
      - text: No thanks, I'll explore the place myself.
        close: true
```

This excerpt omits the existing `part 2a` node: retain it when editing. Change
`text` to edit a sentence. Node keys and `next` retain the native graph; response
list order is significant. `close: true` ends a conversation. A response containing
`action: <index>` also invokes its verified native station/command action. Keep
that action reference when editing its sentence; dropping it fails validation.
Animations, interface models and exact placed NPC bindings are in the map's native
compatibility data. They do not need to appear in dialogue YAML.

The defaults resolve original English strings from the read-only stock
`code/projectx-config.jar`, including retained `npc` keys found in the
`conversation` bundle. Unchanged text preserves its original localization key on
the wire, so client localization and placeholders retain their behavior. Edited
text is accepted when it matches another verified source string in the same NPC's
native bindings, and resolves to that string's original key. Arbitrary new text
fails validation: the installed Conversation window calls
`MessageBundle.get(key, knightName)` for **both messages and responses**, bypassing
its separate `~` literal decoder. A literal produces a missing-translation fallback
with a visible prefix and appended knight name. The isolated graphical check
established this restriction; it is not advertised as working customization.
Arbitrary sentence authoring remains blocked by that unchanged-client API. No
client patch, invented packet, renamed player or replacement dialog is used.
Existing localization keys/placeholders and graph/service behavior are preserved.

Import without a stock installation retains unresolved original keys
as safe defaults and reports the exact missing bundle/key. It invents no text.
Shipped defaults contain resolved text and need no installed client. The
`_internal/localization-report.txt` records source conversion diagnostics.

## Items and weapon controls

Each family file contains an `items` mapping keyed by stable administrator IDs.
All 9,348 indexed entries have a readable `name` and `category`. The filename is a
broad editing family; `category` and optional `subcategory` are administrative
labels, independent of the native Java class and equipment slot. For example,
`aggro_aura` has category `Armor` and subcategory `Aura` while retaining native
AccessoryTicket serialization. Display labels may repeat. Editing `name` changes
administrator discovery only: the unchanged client still localizes its native
item configuration. It does not support an item-name override in this record.

`_internal/item-bindings.json` version 1 maps each ID to the exact original config,
stock ID, native type, slot, localization key, serialization variant, optional seed
and equipment membership. Six developer-only entries whose original localization
keys are absent from the stock item bundle use literal `~` names so server-composed
`%item:` text stays readable; the unchanged client still shows its own native
configuration name for those items. `_internal/item-arguments.json` retains typed model
arguments by native config. Treat both as internal compatibility resources. Never
renumber them or rename an ID to change a display label. IDs are used only in
static configuration/discovery; existing SQLite inventories and Depot offers keep
their exact native configuration paths. No database migration is required for this
item refactor, and no economy data is rewritten.

Initial IDs use the last component of the **native identity**, never a translated
label: NFKD normalization, remove combining marks, lowercase, replace non-ASCII
alphanumeric runs with `_`, trim separators; prepend `item_` for empty/numeric
starts, and truncate the stem to 110 characters. This is a one-time ID assignment,
not a runtime assumption about native path structure. Every colliding stem gets
`_s<unsigned-stock-id>_<first-12-hex-SHA256-of-exact-native-path>`. This distinguishes
identical names and identical/zero stock IDs. The migrated baseline disambiguates
374 entries across 102 colliding stems. A remaining collision fails for explicit review. After assignment,
**the bindings are authoritative**: adding records, changing names, moving rows
between files or changing sort order does not regenerate existing IDs. Every
binding must have exactly one YAML row. Unknown IDs, duplicate IDs/native paths,
and ambiguous references fail validation. No new native item support is inferred.

Use `wrench_wand` in ordinary shop goods, recipe outputs/ingredients/options,
shop tokens, sprite preferred food, gear progression, item-property override keys,
accessory blocker item keys and `shared/combat.yml` gear keys. For example:

```yaml
# Within an existing shop's goods:
- item: wrench_wand
  price: 100
```

References normalize centrally into native paths before Town or scene runtime
objects are constructed. Exact native paths and existing `sw:wrench-wand` style
aliases remain valid for references; localized labels are not static IDs. Recipe
catalog identities, native accessory definitions, variant names, sprite actions,
models and fire graphs have their own namespaces and are not inventory items.
Their existing references remain intact. Supply Depot configuration stays in
SQLite; there is no Depot YAML. The account manager and `items <search>` expose
the stable IDs alongside stock IDs and native paths.

The following is the complete shipped Wrench Wand entry:

```yaml
items:
  wrench_wand:
    name: Wrench Wand
    category: Sword
    rarity: 2
    bindsWhenEquipped: true
    combat:
      movement:
        whileAttacking: 1
        whileCharging: 1
      combo:
        maximumAttacks: 3
        repeatSequence: true
        attacks:
          first_swing:
            step: 1
            timing:
              effectStartsAfterMs: 200
              nextAttackAfterMs: 433
              actionEndsAfterMs: 766
          second_swing:
            step: 2
            timing:
              effectStartsAfterMs: 200
              nextAttackAfterMs: 433
              actionEndsAfterMs: 766
            movement:
              startsAfterMs: 100
              durationMs: 150
              forwardImpulse: 1.25
          third_swing:
            step: 3
            timing:
              effectStartsAfterMs: 200
              nextAttackAfterMs: 877
              actionEndsAfterMs: 877
            movement:
              startsAfterMs: 100
              durationMs: 150
              forwardImpulse: 2
      charge:
        fullChargeAfterMs: 3000
        fullChargeAction:
          timing:
            effectStartsAfterMs: 526
            nextAttackAfterMs: 1442
            actionEndsAfterMs: 1442
          movement:
            startsAfterMs: 500
            durationMs: 200
            forwardImpulse: 1
        incompleteChargeAction:
          timing:
            effectStartsAfterMs: 175
            nextAttackAfterMs: 408
            actionEndsAfterMs: 741
          movement:
            startsAfterMs: 0
            durationMs: 175
            forwardImpulse: 1
```

Swords use `combo`, guns use `firingSequence` with named shots, and bombs use
`deploymentSequence` if they have sequence controls. These sections describe only
the existing attack state machine; they add no reload or projectile mechanics.
Stage names are administrator labels; `step` is the explicit contiguous position
starting at one. Map order never determines native attack identity. The stage
count is the number of named actions; `maximumAttacks` independently limits chain
actions (zero means unlimited). `repeatSequence` wraps stage selection; otherwise
the final stage repeats when the chain rules permit another attack.

Timing is relative to the stage's start. `effectStartsAfterMs` activates its fire
graph; graph-specific delayed actions can occur later. `nextAttackAfterMs` makes
rearming eligible subject to input/chain/charge rules; it does not automatically
start another swing. `actionEndsAfterMs` clears the state if uninterrupted.
`resetAfterEffectMs` sets chain expiry relative to effect activation; zero uses
action end for ordinary attacks, but a recovery action resets at its effect time.
A configured `recoveryAction` starts at rearm after the chain limit is reached;
its rearm cannot be overridden. No recovery section means no cooldown action.

`charge.fullChargeAfterMs` uses existing equipment charge modifiers. Release at or
after that threshold selects `fullChargeAction`. **Early release uses the next
normal sequence stage when normal stages exist** (including chain expiry/cycling),
not `incompleteChargeAction`. That incomplete action is selected on early release
only for a weapon with no normal attacks, such as the shipped charge-only bombs.
Both special actions must exist for charging to be available. Retaining the sword's
incomplete metadata does not make it fire on every early release.

Attack-speed modifiers divide effect/rearm/clear times and impulse start delay,
with existing integer truncation. They do not divide impulse duration or translation.
Weapon movement settings are movement multipliers, **not attack-speed settings**.
Weapon and stage `allowMovementInput` must both be true to permit normal movement
during a stage; multipliers alone do not enable movement input. Charge/defense,
rotation rearm, cancellation, interruptions and weapon changes keep existing rules.

Impulse components rotate with facing. Movement integrates the difference in
`2t - t²` (clamped elapsed fraction) each tick: the translation is in game units
over the impulse, not velocity. Collision/status/other impulses can limit or alter
actual displacement. An omitted impulse means native absent timing `-1/-1`;
an explicit zero start/duration remains explicit. `sidewaysImpulse: 0` is safely
omitted. Timing zeroes, false flags and empty modifier maps are omitted only where
the documented parser default is identical; no hidden native fallback changes them.

Shields group pool `health`, `regeneration.fullHealthAfterMs`, `delayAfterHitMs`,
`delayAfterBreakMs`, `defendingSpeedAdjustment` and `bash.healthCostFraction` /
`bash.action`. Regeneration time means a full health pool after the delay, not a
per-point interval. The defending adjustment is additive: -0.5 yields a 0.5 base
multiplier through existing equipment rules. This field also supplies omitted
`modifiers.<class>.defendingSpeed` values for that shield, so editing it changes
actual defending movement with the full equipment catalog. An explicit per-class
modifier overrides that default (including an explicit zero). Export omits values
that merely duplicate the shield field. Bash requires full shield health and
spends its fraction at effect activation. Omitted modifier classes/values are zero;
classes are `NONE`, `SWORD`, `HANDGUN`, `BOMB`, with existing aggregation and caps.
The shield defending-speed inheritance above is the sole nonzero modifier default.
Empty native equipment membership remains in bindings even without YAML modifiers.

The scalar reference below is generated by `ItemFields`, which also supplies
validation/defaults (`java -cp tortodrone.jar org.tortodrone.resources.ItemFields`).
`name` and `category` are required nonblank labels of at most 512 characters;
`subcategory` is optional with the same limits. Object sections accept only the
fields shown here and in the working example; unknown keys, invalid ranges and
noncontiguous/duplicate steps report the file → item → stage → field.

| Field | Meaning | Unit | Omitted default | Range | Applies to |
|---|---|---|---|---|---|
| `name` | Administrator display label; never changes native client localization | characters | required | 1.0…512.0 | all items |
| `category` | Administrator category; independent of native item class and slot | characters | required | 1.0…512.0 | all items |
| `subcategory` | Optional administrator grouping, such as Aura | characters | absent | 1.0…512.0 | all items |
| `rarity` | Native star rarity; -1 is unranked | stars | -1 | -1.0…10.0 | all items |
| `bindsWhenEquipped` | Bind on equip using existing inventory rules | boolean | false | 0.0…1.0 | all items |
| `stackable` | Use existing stack handling; does not add support for unsupported variants | boolean | false | 0.0…1.0 | all items |
| `permanent` | Permanent items must remain locked | boolean | false | 0.0…1.0 | all items |
| `locked` | Existing locked-item restriction | boolean | false | 0.0…1.0 | all items |
| `effectStartsAfterMs` | Effect activation offset; fire graph delays remain additional | ms | 0 | 0.0…600000.0 | combat / shield action |
| `nextAttackAfterMs` | Earliest rearm threshold, subject to input, charge and sequence rules | ms | 0 | 0.0…600000.0 | combat / shield action |
| `actionEndsAfterMs` | Attack state clears at this offset if uninterrupted | ms | 0 | 0.0…600000.0 | combat / shield action |
| `fullChargeAfterMs` | Required held charge duration before equipment charge modifiers | ms | 0 | 0.0…600000.0 | combat / shield action |
| `resetAfterEffectMs` | Chain resets this long after effect activation; zero uses action end except cooldown uses effect activation | ms | 0 | 0.0…600000.0 | combat / shield action |
| `startsAfterMs` | Movement impulse start offset; scaled by attack speed | ms | 0 | 0.0…600000.0 | combat / shield action |
| `durationMs` | Movement impulse duration; not scaled by attack speed | ms | 0 | 0.0…600000.0 | combat / shield action |
| `delayAfterHitMs` | Shield regeneration delay after a surviving hit or bash | ms | 0 | 0.0…600000.0 | combat / shield action |
| `delayAfterBreakMs` | Shield regeneration delay after break | ms | 7000 | 0.0…600000.0 | shield.regeneration |
| `fullHealthAfterMs` | Time to regenerate one full shield health pool after the delay | ms | 1 | 1.0…600000.0 | shield.regeneration |
| `maximumAttacks` | Chain action limit; zero is unlimited, independent of number of named stages | count | 0 | 0.0…32.0 | combat sequence |
| `step` | Explicit contiguous stage position starting at one | position | 1 | 1.0…32.0 | sequence attacks |
| `repeatSequence` | Wrap normal stage selection; otherwise reuse final stage | boolean | false | 0.0…1.0 | combat sequence |
| `allowMovementInput` | Weapon AND active stage must allow movement input | boolean | false | 0.0…1.0 | combat.movement / attack.movement |
| `whileAttacking` | Base movement multiplier while an attack state is active | multiplier | 0.0 | 0.0…100.0 | combat.movement |
| `whileCharging` | Base movement multiplier while charging | multiplier | 0.0 | 0.0…100.0 | combat.movement |
| `forwardImpulse` | Facing-relative native impulse translation, integrated with 2t-t²; collisions can limit movement | game units | 0.0 | -10000.0…10000.0 | attack.movement |
| `sidewaysImpulse` | Perpendicular native impulse translation; same integration as forwardImpulse | game units | 0.0 | -10000.0…10000.0 | attack.movement |
| `health` | Shield health pool | health | 0 | 0.0…100000.0 | shield |
| `defendingSpeedAdjustment` | Base shield movement adjustment: -0.5 means multiplier 0.5; supplies omitted defendingSpeed modifiers | fraction | 0.0 | -1.0…0.0 | shield |
| `healthCostFraction` | Fraction of maximum shield health spent at bash effect time; full shield required to bash | fraction | 0.0 | 0.0…1.0 | shield.bash |
| `chargeTime` | Positive values reduce charge time: base × (1 − sum capped to ±0.48) | fraction | 0.0 | -1000.0…1000.0 | modifiers per weapon class |
| `speed` | Movement multiplier is 1 + equipment/weapon sum capped to ±0.24 | fraction | 0.0 | -1000.0…1000.0 | modifiers per weapon class |
| `attackSpeed` | Timing divisor is 1 + equipment/weapon sum capped to ±0.24, then status scaling | fraction | 0.0 | -1000.0…1000.0 | modifiers per weapon class |
| `defendingSpeed` | Defending movement multiplier is 1 + equipped gear sum; omitted shield values inherit its defendingSpeedAdjustment; selected weapon modifier is excluded | fraction | shield adjustment for shields; 0 otherwise | -1000.0…1000.0 | modifiers per weapon class |

Damage, damage types, status effects, projectile graphs and detonation behavior
remain internal in `_internal/fires.json`. `FireCatalog` retains typed compound,
depth-scaled and delayed actions; `SceneWeapons` schedules those graphs, while
`SceneDamage` resolves their types/scales/statuses. A universal `damage` value
cannot safely describe those graphs. No decorative damage control or multiplier
is accepted. Editing timing changes the existing action/effect schedule; it does
not claim to replace every native animation or implement missing game logic.
Lockboxes and Prize Boxes retain their current indexed/grant support and opening
limitations: this refactor adds no opening, loot selection or reward behavior.

Item status damage also applies to scene enemies, including status-only hits.
The 33 chemical bomb variants retain their separate blast and repeating cloud
areas, recovered from the stock actor parameters corroborated by reference inputs's
`actor.xml`; their damage areas are independent of the point-shaped actor.
Enemy stun scales attack land, duration and rearm using the speed captured when
the attack starts; freeze prevents turning, and poison suppresses regeneration.
`EnemyItemStatusTest` exercises the actual bomb, sword and handgun graphs against
enemies, including cloud edges, damage masks and expiry; `SceneStatusTest` covers
thrown vials. Production status probabilities and powers remain authored values.
These checks establish simulation behavior, without new graphical verification.
Fixed server-only areas without surviving bindings, including some ghost-sword
and shard opening blasts, remain unrecovered; no radius is guessed for them.

For older unified YAML with native-path keys, migrate the **effective customized**
content offline into a fresh candidate, never regenerate over your deployment:

```sh
java -cp /new/tortodrone.jar org.tortodrone.resources.ItemMigration /old/deployment /fresh/item-candidate
```

The tool holds the source deployment lock if present, fingerprints content/config,
loads legacy defaults plus overrides, preserves their effective values and names,
converts references, validates the whole candidate and compares every normalized
item/combat/shield/equipment record and Town table before atomic publication.
It copies only content/config; it does not open/copy SQLite, keys or runtime data.
It refuses an occupied/nested destination, running source, source changes,
ambiguous IDs, unknown fields or semantic differences. Existing readable candidates
are copied without regeneration, preserving custom names/categories/IDs byte for
byte. Review `item-migration.txt`, then install the candidate content/config while
stopped and retain your original data. Old path-keyed item definitions are accepted
only by the migration reader; mixed layouts fail startup. Legacy pack deployments
still use `ContentMigration`, whose exporter emits this same readable format.

## Map identity, actors, portals and traps

`map.yml` preserves the native ID and type, provides readable administration
labels, and selects `defaultSpawn` by a named entrance. Names used in native
`Name` serialization, scene version, image and zone IDs stay in
`native/metadata.properties`; changing an administration label does not rename
client-localized maps. The `zone` field is descriptive; native zone membership
requires a verified reimport. `instances` remains bounded to 1–16 per template,
64 templates and 128 total configured instances.

`portals.yml` distinguishes `entrances` (arrival ID, x, y, facing in degrees) from
`portals` (readable trigger alias, destination map folder, arrival entrance).
Aliases resolve to unchanged native trigger IDs. Displayed arrival headings use up
to four decimal places; unchanged displayed values restore the exact inherited
radians, so this cleanup changes no effective orientation. Rectangular
and convex polygon geometry stays with verified native bindings. To redirect the
Advanced Training exit, change its existing portal's `destination` and `entrance`;
there is no need to copy vertices. Destination entrances must exist and be walkable;
arrivals intersecting a portal trigger are rejected. A coordinate-only default
point has no native `id`, is not a named travel arrival, and cannot be targeted by
a portal. This preserves older server spawn coordinates without inventing native
marker identities. A default entrance is used
only when no explicit arrival applies. Travel/subscription generations, parties
and scene ownership still use the existing runtime implementation.

`actors.yml` contains **only overrides**; shipped files start with `actors: {}`.
Every placed actor, its order, stable key, source/handler bindings, precise heading,
shape and defaults remain in `native/bindings.json`. Omitting an actor keeps it.
Actor creation/removal is not a supported YAML operation. Readable aliases identify
individual placements, not just a shared config path. Discover them without a server:

```sh
java -cp tortodrone.jar org.tortodrone.resources.ContentManager --actors /deployment advanced_training
```

For example, this intentional health edit targets an existing training generator:

```yaml
actors:
  respawn_pad_1:
    monster:
      health: 31
```

Verified semantic controls are `position: {x, y}` in game units, `facing` in degrees
[-180,180], and `monster.health` / `monster.attackIntervalMs` where that actor has
the corresponding native health/attack-interval field. Millisecond intervals are
not rounded. Advanced existing flat fields (for example `child.health` or `cycle`)
remain supported; unknown fields, duplicate alias/native references and identity
changes fail validation. Do not edit internal bindings to create new gameplay.

Optional `mechanics.yml` contains each map's own rules. Native `destination.*`
markers moved to immutable bindings: travel uses the single editable arrival in
`portals.yml`. The retained markers are scene metadata, not a second travel control.
`shared/combat.yml` owns global player/scaling/status parameters and gear stats.
The archive/stat-reader import covers 1,012 grantable equipment definitions,
295 shared level tables and 116 defense, resistance and family-damage variants.
An item's numeric `table` selects `gear.table.<number>.levels.<zero-based-level>`;
identical tables are shared, and missing table references fail startup. These
tables supply health, four defenses, status resistance, weapon-class damage and
charge/attack/movement changes. Defense variants use the item's rarity; costume
slots contribute no combat stats. Weapon level damage retains native depth gates,
uncapped relative arithmetic and the separate capped class/family bonuses.
Attack damage modifiers are captured when an attack starts and retained through
projectiles and their children. Companion skills use their own authored damage.
Existing sparse base values remain the fallback for deployments without a level
table; omitted values default to zero.

The sprite/harness tables and all 27 base/ultimate attacks (1,728 power/damage
combinations) are retained from the archive/native reader. A successful skill
uses native knight activity 288 for 750 ms and writes the **used skill's**
harness-adjusted, minimum-clamped recharge to all three pet cooldown slots,
matching the archive's streamed live observations. Selected sprite resistance
and family-damage perks now contribute to combat, alongside existing health,
charge and speed perks.

Reproduce the numeric import with a separate read-only stock installation:

```sh
java -Dtortodrone.deployment=/tmp/tortodrone-check scripts/StockTools.java \
  gear-stats "/path/to/Spiral Knights" content /tmp/gear-stats-fresh.json
python3 scripts/content/import_combat_stats.py /path/to/reference-inputs \
  /tmp/gear-stats-fresh.json content
java -Dtortodrone.deployment=/tmp/tortodrone-check scripts/StockTools.java \
  gear-stats-check "/path/to/Spiral Knights"
java --enable-native-access=ALL-UNNAMED -Dtortodrone.deployment=/tmp/tortodrone-check \
  -cp /tmp/tortodrone-check/tortodrone.jar scripts/compat/CheckCombatRendering.java \
  "/path/to/Spiral Knights" /tmp/equipment-groups-fresh --equipment-groups
```

The focused stock-reader check compares 24 family/level combinations. The
graphical fixture uses two disposable knights in the same Training Hall and
samples armor, shields, sword families, handgun families, bomb families and all
three sprite species. It checks native animation/model initialization and remote
projectiles rather than launching every indexed item. Depth-scaled base damage,
defenses and shield health remain exported for depth zero/MEDIUM; importing
level bonuses does not establish dungeon-depth progression. Arbitrary stripped
conditions, sprite defense perks, obsolete Mixer Agent carriers, Sealed Sword
table summons and delayed developer shield actions remain unsupported. No client
assets or archive server implementation are copied.

The isolated two-client `--equipment-groups` acceptance passed all 26 groups.
Observer captures were reviewed for armor, shield bash, the Cutter charge,
charged Needle effects, the Vortex bomb and Seraphynx's aura. Actor/projectile
assertions supplement these samples; a passing group does not establish every
frame, item variant or original action.

Shared `trap-cycles.yml` owns
stock presets A/B/A2/B2, slow/cross variants and their inverse references.
`up`, `down`, `warn` and `offset` are **seconds**. An actor references its preset
by name. The engine's switch/signal transitions, offsets, collision and inverse
cycle behavior are retained. Serialized status objects/actions live in `_internal/statuses.json`.
`shared/statuses.yml` starts empty and permits only advanced existing numeric
fields; immutable config/class/action/data fields cannot be overridden there.
This keeps the native behavior without presenting class graphs as ordinary
configuration. Their presence does not claim every original effect works.

## Town editing

`town/shops.yml` contains ordinary merchant goods. Omitted good fields mean count
1, native price lookup (`-1`), Energy 0, limit 0 and no token requirement. Catalogs
omit irrelevant defaults. Recipes retain every output/ingredient relationship and
rarity-dependent cost; stations retain verified NPC services. Opaque item-property
and variant trees are in `_internal/town-definitions.json`; ordinary progression
and variants YAML no longer duplicate them. Advanced overrides merge by exact
`config`, with duplicate IDs rejected. No entries, rare goods or probabilities were
invented or removed. NPC filenames use verified native names where available;
`id` and native dialogue bindings remain authoritative even after a file rename.

The 27 `Rule/Hall of Heroes/<Family> <2|3|4> Star` catalogues filter the existing
nine captured 5-star families by original item rarity, retaining order, quantities
and prices (1/2/3/4/5 stars: 250/1,000/4,000/10,000/25,000 crowns). This reuses
the rule checked by reference inputs `check_hoh_stock_rule.py` and
`install_hoh_tier_stock.py`. Current Town maps place only 5-star mannequins;
these lower-tier definitions do **not** establish reachable lower-tier gameplay.
No mission hall or fabricated actor placement is added.

Harness merchant definitions in `town/sprites.yml` now include `prerequisite` and
`crowns`. Prerequisites and recharge modifiers come from installed stock configs;
the 8,000/32,000/128,000 crown prices are corroborated by the
[player gameplay guide](https://steamcommunity.com/sharedfiles/filedetails/?id=394352657)
and [harness reference](https://spiralknights.fandom.com/wiki/Battle_Sprites#Harnesses).
They await a live harness-purchase capture. Purchases check prerequisites and
ownership inside the currency transaction; upgrades retain shared harness identity.
GM sprite-level changes fill skipped native perk draws without rerolling earned perks.
Native auction groups are pinned per item, including Ticket subclasses whose group
depends on their native ticket type.

## Live economy administration

The Supply Depot and Auction House are **SQLite-only**. Use the existing account
manager's Supply Depot and Auctions panels while the server is running. Static
merchant YAML still requires validation and restart. Game-client GM status does not
expose the local economy-administration API.

A new database transactionally installs all **328** captured Depot offers from the
read-only JAR resource `seeds/supply-depot.json` (seed version 1, pinned SHA-256).
The exact ordered section from `0078b50` is retained, with its existing zero-based
Tortodrone offer IDs, quantities, Energy prices, limits and featured flags.
`sourcePrice=-1` records native item-property lookup; the stored Crown value is its
resolved value, even when positive Energy is the currency selected by the client.
The seed records provenance and derived support status. Nine indexed records have
unsupported grant representations; they remain visible to administrators, disabled
with exact support reasons. Catalog fidelity does not imply upgrade-item gameplay.
No game installation, capture or network access is needed to create a database.

Explicit initialization/seed-version metadata makes seeding one-time. Restart,
disabling all offers or intentionally clearing a managed catalog never reseeds it.
Existing purchases remain in the original `town_purchases` ledger: lifetime purchase
counts per knight and item config, shared by separate offers for that same item.
This is retained compatibility, **not proof of official per-account or per-offer
purchase-limit semantics**. `goodLimit` also has native client-owned-count UI
behavior; backend scope is not established. Quantity is items per purchase, not
finite global stock. Depot global stock and account/window limits are not supplied.

Offer edits use an expected catalog revision. Item, quantity, enabled state,
current price/currency, authentication, balance, grantability and shared lifetime
limits are checked in the same `BEGIN IMMEDIATE` transaction as debit, grant and
ledger update. The network path always supplies its server-issued quote. Failed
or stale purchases return the existing native failure callback and roll back.

**Promotions:** select an offer and Schedule special; browse Active, Scheduled,
Expired or Cancelled in Promotions, and use Player preview for the effective
catalog. `promotionOnly` makes an enabled offer available only during a promotion.
An ordinary offer retains its permanent price. Promotion rows store a direct price,
optional genuine regular-price comparison and temporary featured status. A supplied
comparison must match the offer's regular price and currency at scheduling time
and exceed the sale price. Without a comparison, the offer is a temporary price,
not advertised as a discount. A discount uses native `_originalPrice` / `_sale`;
featured goods use `RankGood.special`. Active promotions also use that flag and
join the featured response, including direct-price promotions. Native `promoHeader`
is `~`; `promoDetail` carries `m.promo_hours_left|~N` below 36 hours, otherwise
`m.promo_days_left|~N`. These verified wire values come from reference inputs
`DepotTimedGoods` and its cited native DEFAULT countdown. The quote retains the
fixed expiry so countdown changes cannot invalidate a purchase.

**Automatic daily Specials:** `DepotSpecials` overlays two equipment goods at noon
Pacific, using the twelve exact captured sale-day pairs transcribed as numeric
facts in `seeds/depot-specials.tsv` from reference inputs `DepotTimedGoods.ROWS`.
`contrib/check_depot_specials_rotation.py` documents the observed reset and native
discount defaults. Uncaptured dates use an explicitly **reconstructed**, restart-
stable SHA-256 selection: one 150/500-Energy good and one 1,600/3,500-Energy good
from the immutable seed, discounted 2–8% with a minimum 5-Energy reduction. This
is not the archive's interim RNG or a recovered official selection algorithm.
Winter noon Pacific timing is also reconstructed; only daylight-time resets
were captured. Calendar boundaries account for daylight-saving transitions.

SQLite remains authoritative for availability and prices. Disabled, temporary,
removed, quantity-changed or repriced selected offers are not restored or rerolled.
Administrator promotions override a selected automatic offer during their window;
cancellation/expiration reveals that day's automatic sale if still eligible.
No automatic promotion rows or schema changes are written. Negative promotion
identities distinguish automatic sale windows in server-issued quotes, so a stale
day's quote fails even if the replacement repeats its price. Player preview labels
automatic offers and their expiration and explains the reconstruction policy.

Times persist as Unix milliseconds UTC, **start inclusive, end exclusive**; the
admin forms show the local timezone. Overlapping uncancelled windows for the same
offer are rejected, adjacent flash-sale windows are allowed, and different offers
may run simultaneously. Cancelling restores the ordinary offer immediately (or
hides a promotion-only offer). Effective offers are resolved on each catalog
request and again inside purchase transactions, including after downtime/restart.
Expired/changed displayed quotes cannot spend. The stock window can retain an old
visual until its next supported catalog request/reopen; there is no invented push
refresh. Promotion-specific binding and complete mission-rank gates remain
unverified/unimplemented; ordinary non-costume level-equipment binding is retained.

**Auctions:** player escrow, bids, fees, cancellation, watches, expiry and mail
settlement stay durable and atomic. Existing native rounded 5% bid increments,
4/12/24/48-hour player durations, fee calculation and sale fee/refund behavior are
preserved. Bid/buy ordering now compares price per item, and old active listings
cannot disappear behind a 5,000-row history cap before filtering/pagination.
Featured bid auctions and finite fixed-price stock retain native total/remaining
fields. Scheduled publication is a **Tortodrone administration extension**: it
uses the existing listing format, hides future listings and rejects early bids.
There is no randomized official rotation. The panel retains delivery inspection,
stock/status/time display and confirmed cancellation. New escrow records retain
source item-instance IDs for inspection; older records without that field show 0.
Attachment grants retain the existing safe inventory-ID allocation behavior.

Online outbid refunds now credit the original bidder inside the bid transaction,
using a deployment-scoped server presence snapshot and rechecking recipient
authentication. Offline/stale-authentication recipients and overflowing balances
receive the existing refund mail. Disconnecting after the online decision retains
the committed credit and durable indicator for reconnect, without a second mail.
The existing world-state poller publishes balances, native `PlayerObject.outbid`
and `auction:m.note_outbid` with item/refund arguments after commit. My Bids clears
the indicator. Refund notices use integer metadata beside the auction records;
no database migration is inferred. System-info chat routing and My Bids
acknowledgement follow the archive's explicitly reconstructed notification behavior.

Native time-left categories use the archived wiki's approximate 5-minute, 1-hour,
4-hour and 24-hour boundaries; a two-day listing is Very Long, matching the archive
capture cited by `AuctionManager.timeLeft`. Exact last-minute extension duration
and destructive 30-day mail purging remain evidence blocked. The archive's
five-minute extension is explicitly interim and is not enabled here.
See the [evidence matrix](research.md#economy-parity-baseline-2026-10-18).

## Native map import

### Local Crash Site playtest

Use the normal **`build/` deployment**. This checkout's local build contains
Crash Site **398/version 2008859103** and initial Rescue Camp **217/version 373**.
New-knight onboarding is enabled when that verified pair is available. An
existing knight keeps its progression and is not automatically restarted.
The complete later route to Haven remains unfinished; see the
[current handoff](research.md#native-ready-room-and-authorized-opening-scope).

First build and check the actual deployment without starting a server:

```sh
./gradlew build
java -cp build/tortodrone.jar scripts/CheckOpeningDeployment.java build
```

The check must print `PASS: verified opening content`, scene 398 at entrance
2659 / `(-21, -69.5)`, and Camp 217 at entrance 2017 / `(0.5, -0.5)`.
It validates content and route prerequisites, not a gameplay playthrough.
Ordinary builds preserve the locally imported maps. Their original assets are
not committed to Git: a clean clone with only the seven bundled maps needs the
one-time imports below before new-knight creation can succeed.

With the server stopped, import **only a missing map** into this same build:

```sh
GAME="/path/to/Spiral Knights"
ARCHIVE="/path/to/reference-inputs"
java scripts/MapImport.java "$GAME" "$ARCHIVE/reference/observations/scenes-cache-2026-10-22/398" build crash_site 50332041 '%dungeon:m.tutorial_b'
java scripts/MapImport.java "$GAME" "$ARCHIVE/reference/observations/scenes-cache-2026-10-22/217" build rescue_camp 16777221 '%town:m.rescue_camp_state_1'
java -cp build/tortodrone.jar scripts/CheckOpeningDeployment.java build
```

The importer refuses duplicate maps and live deployments. Do not delete existing
content, databases or keys to make it run. If a map exists but preflight fails,
retain it and inspect its `native/import-report.txt` and the reported error.
Use the read-only installed stock client and the pinned archive sources; do not
substitute another scene revision or commit the imported assets.

For the manual playthrough, use a disposable test account in your normal server:

1. Start `java -jar build/tortodrone.jar --nogui` (or use the already running
   normal server; do not launch another on its ports).
2. In the **server console**, run `account create CrashTester` and follow its
   password prompt. A GM role is not required for this test.
3. Log in using the unmodified official client and create a **new knight**.
   Selection should automatically enter a private Crash Site, not Haven or the
   Ready Room. Do not reuse an old completed knight to test first-run routing.
4. Follow Rhendon's original conversations, move through the map, break objects,
   activate its mechanisms and pick up heat. Finish the ambush conversation to
   start the encounter; fight the Ironwood Giant with normal combat controls.
5. After the Giant is removed, wait approximately five seconds for both final
   doors to open. Walk through the passage, use the original final exit, and
   acknowledge the native floor-report prompt if shown. Arrival must be in
   initial Rescue Camp 217. Do not use a teleport to establish this result.
6. Log out and select the same knight again. It should enter the native Ready
   Room; choose Return to Game and confirm arrival in Camp without restarting
   the tutorial or granting heat/progression twice. An interrupted, incomplete
   tutorial restarts at its entrance; exact mid-floor resume is not implemented.

For a separate **GM preview** with an existing knight, grant its account GM in
the server console (`gm AccountName true`), enter the world, then type `/sgo 398`
in **in-game chat**. This uses the native GM movement handshake and original
entrance. `/sgo 217` previews Camp independently. Neither shortcut proves normal
onboarding or completion; use a fresh knight for that acceptance test.

Earlier isolated graphical checks established Crash Site → Camp → reconnect;
the latest build has not received a new complete graphical acceptance. Giant
targeting is capture-calibrated, and companion/parity limits remain documented
in research. Record the first failure, screenshot/client error if available,
and server logs under `build/data/logs/`. Avoid repeated graphical runs for
unrelated changes.

### Import procedure

Reuse the verified read-only native exporters, then validate and publish into the
same unified tree. From a built source checkout:

```sh
java scripts/MapImport.java /path/to/game /path/to/scene.dat /deployment map_name 1 %town:m.haven
```

The last two arguments are the verified zone ID and native zone name; cached
scenes do not establish zone ownership. The command invokes `StockTools scene`
with supported actor/collision extraction and writes only disposable staging
files. It then runs the packaged installer:

```sh
java -cp build/tortodrone.jar org.tortodrone.resources.MapImport \
  /prepared-native-map /deployment map_name /path/to/read-only-game
```

The installer retains native scene identity/version, derives the existing
walkable arrival, converts supported actors/NPCs and localization, validates a
complete candidate deployment, and publishes the map with one directory rename.
It refuses a live deployment, duplicate scene ID, unsafe name or occupied map
folder. New shared presets remain local to that imported map so publication does
not require changing global files. Review `native/import-report.txt` for source
limits/unresolved strings, then connect an existing compatible portal explicitly.
Current raw exporters cannot recover all portal actions or stripped server
scripts from a cached scene. In the available modern Laboratory capture, raw
re-extraction with the installed client refuses source key `1065` because its
Startup actor reference is absent. The verified prepared Laboratory remains
loadable; the importer does not guess a replacement for that missing reference. Missing actions, AI, collision, rewards and dialogue
are reported or rejected, never fabricated. Test imported layouts with an isolated
stock client. No proprietary assets/captures belong in Git.

## Upgrading

SQLite schema 16 requires an **explicit offline candidate** for known schemas
9–15. Normal startup refuses those predecessors and all unknown versions, leaving
saved state unchanged. Never reset the database or regenerate keys.

1. Stop the server and account manager; back up the complete `data/`, `content/`
   and `config/`, including WAL/SHM and keys. Keep the original Depot YAML until
   export: preparation refuses to replace it beside an existing database.
2. With the new JAR, export to a fresh directory outside the source deployment:

   ```sh
   java -cp /new/tortodrone.jar org.tortodrone.storage.EconomyUpgrade /old/deployment /backup/economy-candidate
   ```

3. Review the candidate DB and `economy-upgrade.txt`. The tool holds the deployment
   lock, uses SQLite `VACUUM INTO` for a consistent source copy, upgrades only that
   copy in one transaction and runs foreign-key/integrity checks before publication.
   Existing managed offers, IDs, enabled state and revision are retained, including
   an empty catalog; managed upgrades do not read obsolete Depot YAML. Revision-zero installs import their exact effective old YAML
   once, including local edits; missing legacy input is refused instead of replaced
   with stock defaults. ContentMigration's `legacy-depot-import.json` is also a
   migration-only input. Purchase history and unrelated player/auction state stay
   unchanged. Unknown schemas and failed imports publish no candidate.
4. Prepare new content in a separate directory, apply/review your overrides and
   remove the legacy Depot section from the new `shops.yml`. Validate with
   ContentManager. Install the reviewed candidate `tortodrone.db` while stopped,
   retaining the original keys and other runtime files. Do not mix an old DB's
   WAL/SHM with the candidate; retain those old files together in the stopped backup.

Build preparation replaces only recognized unmodified defaults and fills missing
files. Customized files receive upstream `.proposed` files plus `review.txt` under
**`content-updates/` beside content**, outside runtime inputs. These are review
candidates, not a semantic merge. Known unchanged renamed defaults may retire;
customized removed/renamed paths produce `conflicts.txt` and stop preparation
before runtime content changes. Prepare a fresh candidate and transfer the edits
manually. Validate the whole candidate, including native resources/descriptors,
before installation. Runtime `data/` is never copied as default content or packaged.

## Confetti and Auction House access

Arsenal → Usable → Use consumes one owned confetti and plays the stock activity
`267` with its item config reference. All 14 indexed variants are supported,
including Bubbles, Party Flare and the Storm/Rage/Frenzy callers. The client
provides the animation, particles and sound. Verified stock `AnimatedAction`
metadata supplies the 300 ms land time and 1,600/3,100 ms clears; Dark Harvest
chooses trick (3,500 ms clear) or treat (1,600 ms clear) from the activity timestamp,
using the same seeded selection as the stock client. Busy use returns native
`e.not_use_busy`. Consumption checks ownership, authentication and revision in
one SQLite write; failures roll back, and replayed listener receipts cannot spend
again. Scene presentation follows commit and clears on departure.

All 81 player fireworks and Jar O' Fireflies now use that owned consumable
workflow and native activity `267`. Fireworks launch after 300 ms and clear
their animation 500 ms later; fireflies release after 800 ms and clear 1,600 ms
later. Fireworks retain their configured size/color and six-second detached
effect; fireflies choose among the five equally weighted native colors. Damage,
shock and departure interrupt a pending release. An accepted use consumes once
before presentation; interruption does not refund it. This consumption policy
follows confetti; the archive's old non-consuming fallback did not establish
live firework consumption semantics.

`AnimatedItemSessionTest` and `SceneItemUseTest` cover ownership, revision,
rollback, replay, busy use, timing and interruption. The read-only
`scripts/StockTools.java animated-items-check` oracle compares all 82 definitions
with the installed client and reads timed bonus selection/expiry events.
`CheckCombatRendering.java --items-only` exercises the unchanged Arsenal Use
menu and captures all three firework sizes, fireflies and confetti in an isolated
Haven session. These checks establish local behavior and rendering, not current
live-server parity. Compatible plain gift/shop/crafting stacks merge up to 9,999;
timed/customized items retain their own state, and unwrap failures roll back the
box deletion and delivery together.

The Haven Auction House opens at its public counter through the captured
`forceClientAction` message: `ControllerAction("auction", "")` and placed entry
key `3733`. It uses the existing auction search/listing service. Its interaction
area now handles the native transformed capsule and uses the captured building's
unrotated heading, correcting the imported -90-degree actor-facing conversion.
The historical sparse-content comparison checks this one correction separately.

Evidence: the owner's private `Confetti.zip` protocol lines 732, 749, 1236–1245
and 1583–1586, the captured placed entry transform, and read-only installed
`ItemConfig.AnimatedAction`, `KnightAdvancer` and `KnightSprite` inspection.
`ConfettiSessionTest` covers consumption, rollback, replay, busy handling and
counter reachability; `scripts/compat/ConfettiCompatibility.java` checks all
variant timings and the native actor/controller-action layouts without launching
a graphical client. Captures and client assets are not distributed.
Confetti's original focused coverage also includes the public Auction counter;
the item graphical scenario does not exercise auction rendering.

## Lockboxes and Prize Boxes

Version 0.35.3 enables **243 explicitly labeled approximation pools**, covering
all indexed non-template stock boxes. The [support matrix](box-support.csv) audits
all 250 native definitions. Six internal base/parameter templates and the empty
Mystery placeholder remain disabled because they have no playable reward pool.
Every edition has a separate definition, using existing readable item IDs.

The original 12 wiki/announcement estimates remain documented below. Another
223 definitions preserve archived resource graph weights, choices, independent
rolls, bundles, quantities and branch-specific binding; their expected item
quantities match an independent historical frequency audit analytically. Eight
more definitions use disclosed wiki/audit estimates: AeroTech Mk II, Slime 00G and
six Forge tiers. Historical resource data and estimated rates are **not verified
current official server distributions**. No unavailable box awards fallback loot.

### Player interaction and current parity

Open Arsenal → Usable, select a box, and use its native Open/Unlock With action.
The client presents the original confirmation and animated reward window. For a
box with an enabled, validated definition, exactly one owned box is consumed and
all selected rewards are delivered to persistent Arsenal inventory. Missing,
incomplete and unsupported definitions return a failure without consuming items.
Ordinary boxes consume a Silver Key. All playable numbered/colored Slime boxes
accept the chosen Slime or Silver Key in native menu order. Prize Boxes remain
keyless, including seasonal, promotional, Lucky, Surprise, Forge, Anniversary and
furniture families. Historical editions retain separate definitions. Administrators can also author
explicitly `custom` distributions.

**Reward workflow limitation:** timed upgrades are delivered as native, inactive
`UpgradingItem` inventory records and persist with their correct identity and
binding. Weapon-slot and trinket-slot tickets now support native Use activation,
Extend, absolute deadlines, temporary slots and return-to-bag on slot expiry.
Opening does not activate them. Heat Amplifiers now use the same activation/extension
workflow (1, 2, 7 or 30 days), double awarded equipment heat, and allow one active
amplifier. Krogmo Coin Boosters activate for one day, stack up to six, and publish
the native bonus; Coliseum reward delivery remains unavailable.
Weapon-slot and standard trinket-slot tickets last 30 days after activation;
the short trinket ticket lasts two days. Each enables one slot, up to two extra
weapon slots and two trinket slots. Personal color, eye and height kits support
their separate Use workflow, durable saved styles and paid reapplication.
Guild Bonds still require guild services. The item support report excludes
abstract/internal definitions and distinguishes tickets consumed by existing
services from items needing a direct Use handler.
Furniture rewards use native undeployed `FurniItem` inventory records. Placement
and removal require the native design-room subsystem; none of the supported
non-guild scenes is a design room, so placement remains unavailable.
Four disguise prizes use verified 30-day `LevelItem` expiry.
Their absolute deadline persists through reconnect/restart, trade, gifts and
auction/mail escrow. Unwrap/claim never restarts it. Expired attachments are
removed on claim; active inventory expiry advances the world revision before
removal is published. A timed item cannot be listed when its deadline precedes
the selected auction end, a local delivery constraint. Untimely Scythe retains its
native custom color triplets and existing sword workflow. These boxes therefore
have supported limitations, even apart from estimated odds.

| Box/mechanic | Stock evidence | Tortodrone coverage |
| --- | --- | --- |
| Iron, Mirrored, Copper, Silver, Gold, Steel, Platinum, Titanium | Native Silver Key option; community lists lack official odds | Enabled approximations, with each box's own candidate pool |
| Each numbered/colored Slime Lockbox | Native Slime Key **or** Silver Key menu options | All playable editions enabled separately; 00G uses disclosed estimates |
| Seasonal/promotional/Anniversary Prize Boxes | Native `NONE` key classification | All indexed playable editions enabled with separate historical/estimated pools |
| Lucky Prize Box | March 2024 announcement: keyless, unbound; category probabilities | Enabled historical pool, including native furniture rewards |
| Forge Prize Boxes | Native keyless; community pool/quantity/odds research incomplete | Six tiers enabled with explicit estimated quantities/correlations; official pool remains incomplete |
| Surprise Boxes | Historical announcements describe another box, Confetti and independent Stately Cap chance | Indexed editions retain archived nested boxes and extra rolls; rewarded boxes stay unopened |
| Gift Box | Existing player-wrapped contents, separate native method 13 | Existing wrapping/unwrapping preserved |
| Shadow Key | Native `SHADOW_KEY`, distinct from `LOCKBOX_KEY` and `SLIME_KEY` | Never accepted as an opening key |

Silver and Slime Keys are ordinary authoritative inventory items, regardless of
whether they came from Depot, a merchant, trade, auction or administrator grant.
Opening has no dependency on a store offer, price or acquisition route. The native
client selects a **key type option**, not a specific inventory key instance. The
server consumes one from the lowest-ID eligible owned stack of that selected type.
It never changes the selected type to another available key. Keyless boxes require
option `-1` and do not consume either key even when both are present.

### Native protocol and evidence

The installed client uses `ItemMarshaller.b(long, int, A.c)`, method **12**, on the
existing Item service **9**, invocation object **3**. Arguments are
`[Long boxInstanceId, Integer keyOption, ResultMarshaller listener]`:

- Standard Lockbox: option `0` is Silver Key (`Ticket/Keys/Silver Key`).
- Slime Lockbox: option `0` is Slime Key (`Ticket/Keys/Slime Key`), option `1` is Silver Key.
- Keyless Prize Box: option `-1`.

`ItemConfig.Lockbox.KeyType` supplies those menu options. Without a required key,
the native menu shows a missing-key label instead of the corresponding unlock
action. Cancel completes locally, without sending an opening invocation. Forged
options and foreign, stale or absent inventory instances fail server validation.
Method 14 remains ordinary item Use and recipe/harness dispatch; method 13 remains
Gift Box unwrap. GM Spawn All uses native method 11 and enumerates reachable
reward alternatives without consuming the source box or keys; stored GM permission,
revision and capacity are checked in the same write. Method 6 returns the installed
stock spawn-table reference to the native calculator (246 of 250 box bindings have
one). This does not turn the shipped approximate pools into verified official odds.
No new packet or custom reward window was introduced.

After commit, existing `EntryRemovedEvent`, `EntryUpdatedEvent` and
`EntryAddedEvent` notifications update the native inventory DSet. The result
callback is native method `1`, one argument: `HashMultiset<Long>` of **actual
reward inventory instance IDs** and awarded quantities. Updated stack IDs remain
stable and the multiset reports only the awarded delta. Each non-stackable reward
has its own ID. Stock `item.client.H` passes that multiset to the animated
`item.client.Y` window, which resolves it from the already-published inventory.
Failure uses native method `0` and a reason string; the stock window exits its
waiting state. There is no client-supplied reward or random seed.

The owner's `source-archive.zip` official Dark Harvest Complete capture establishes the
shared opening presentation (original protocol lines 665–679, 919): method 12
with option `-1`, `itemAcquireDelay=7500` before inventory changes, actor activity
`265` with a list containing the box config followed by distinct reward configs,
then `itemAcquireDelay=0` before the successful reward-ID multiset callback.
The observed bundle was three Dark Harvest Confetti merged into an existing
stack and one Nova Crescent Helm with a new identity. Service/object numbers
are assigned by the advertised marshaller mapping; the capture's routing numbers
differ from Tortodrone's. The private capture is not distributed with the server.

All supported boxes now publish that acquisition-delay/reset sequence after the
atomic grant commits and start the reveal in the owning scene simulation.
Read-only stock `KnightAdvancer` (`projectx.util.j`/`n`) inspection establishes
the 8,250 ms stationary activity and south-facing pose; `KnightSprite.KD()`
requires a `List<ConfigReference<ItemConfig>>`, with the box first. Multiple copies
of a reward use one appearance reference while retaining their full quantities
in the callback. Input and animated emotes cannot replace the reveal; a second
opening is refused while busy, and receipt replay does not restart it. Leaving
the scene clears its presentation without changing the committed rewards.
This capture verifies presentation and delivery for one opening, not loot odds;
the existing historical/estimated distributions remain unchanged.

Verification for this presentation change passed `BoxOpeningTest`,
`BoxOpeningSessionTest`, `TownMovementTest` and `SceneSimulationTest`, plus the
stock `BoxCompatibility` reader. One isolated `--boxes-only` graphical run
passed seven openings, including Dark Harvest Complete, both Slime key options,
furniture and timed costumes; reviewed screenshots show the scene box animation
and native success/reward presentation. The full suite was not run for this fix.

Read-only class/config inspection and `scripts/compat/BoxCompatibility.java`
verify method/argument/listener/result layouts, the three key identities and all
250 indexed key classifications against the installed build. Its hashes are in
`content/_internal/provenance/boxes.tsv`; this newer inspection does not replace
older item-export provenance. `SessionDecoder` and `SessionCodec` already support
the required primitive arguments, listeners, multiset and inventory events.

One isolated graphical `--boxes-only` scenario exercised the unchanged Arsenal
panel/menu, cancel/confirm, a Silver Key Lockbox, both Slime key choices, keyless
Prize Box opening with both key types retained, and native animated success
windows. The original check used synthetic rewards; the updated `--boxes-only`
check uses the **shipped approximation tables unchanged**, including the guaranteed
multi-item Surge result. It verifies that reveal IDs/counts match committed
inventory deltas. The expanded check also opens Almire Furniture and Disguise
boxes, asserting native furniture and a future disguise deadline. All six openings and cancellation
passed with shipped tables unchanged; screenshots were reviewed.
This proves interaction and presentation, not official odds. Exact official full-inventory UI,
mail overflow delivery and historical reward windows remain unverified. Tortodrone
safely refuses delivery when the complete selected bundle cannot fit.

### Readable loot definitions

Definitions live in `content/loot/boxes/**/*.yml`. Each file has a `boxes` mapping
keyed by an existing readable item ID. Native bindings remain internal; ordinary
loot YAML contains no native paths, stock numbers or serialization metadata.
`coverage.yml` is an audit of disabled definitions, not a collection of guessed
loot tables. Edit or move an existing definition when supplying a pool; duplicate
IDs across files are rejected. Key options must exactly match
`_internal/box-bindings.json`, including their order.

The following is an **illustrative custom test distribution, not an official Iron
Lockbox pool**. It must never be labeled `verified` on the strength of this example.

```yaml
boxes:
  lockbox:
    kind: keyed
    acceptedKeys: [silver_key]
    status: custom
    evidence: "Administrator-authored example; not official Spiral Knights odds"
    guaranteed:
      - {item: brimstone, quantity: 2, binding: bound}
    groups:
      main:
        chance: "100%"
        rolls: 1
        entries:
          - weight: 3
            rewards:
              - {item: calibur, quantity: 1, binding: unbound}
          - weight: 1
            rewards:
              - {group: materials}
      materials:
        chance: "100%"
        rolls: 1
        entries:
          - weight: 1
            rewards:
              - {item: brimstone, quantity: {min: 3, max: 5}, binding: bound}
      bonus_sparks:
        chance: "2%"
        rolls: 1
        entries:
          - weight: 1
            rewards:
              - {item: spark_of_life, quantity: 1, binding: unbound}
```

`main` and every `bonus_` group are root rolls. Other named groups execute only
when referenced. Do not reference a root group from another group: that would
explicitly roll it again. `chance` is required, uses a percent string with up to
four decimal places, and gates the whole group once; if successful, `rolls`
independent weighted choices follow. Entries require positive integer weights;
probability is `weight / sum(weights)` **within that group only**. A selected entry
delivers every reward in its `rewards` bundle. The example therefore has 75%/25%
main alternatives and a separate 2% bonus; the bonus never dilutes the main pool.
Quantity ranges are inclusive and uniform. For `verified` distributions this rule
must be evidenced; for `approximate` distributions document the assumed range and
uniform quantity selection. Model arguments, variants and cosmetic colors come from the selected
item's existing hidden binding, never arbitrary loot fields.

`binding` is explicit: `bound`, `unbound`, or `catalog`. `catalog` retains native
locked/bind-on-equip defaults. `unbound` is rejected for native locked items; it
does not clear a normal item's future bind-on-equip flag. Reward grantability is
checked for the entire definition at startup. Accessories, costumes, equipment,
keys/tickets and other boxes use ordinary persisted item records where the item
catalog supports them. Inactive timed upgrades use the stock `UpgradingItem`
subclass with the common Item payload, no extra fields and no active timer.
Their eight playable configurations have verified inventory bindings; the internal
base remains ungrantable. Furniture, unsupported seed representations and Gift
Boxes without wrapped contents are rejected. The 75 playable personal color, eye
and height kits now support native Use: consume one, apply the style and save its
unlock atomically. Internal Parts/Base configurations remain unsupported.
The native Style Kit reapplies an unlocked style for 1,000 Crowns; already worn
or locked styles fail without a charge. Unlocks persist in per-world integer
metadata, alongside the existing roster appearance and revisions, without schema
migration. `seeds/style-kits.tsv` records only factual config-to-value mappings
from reference inputs `rsrc/config/item.xml` and `ProjectXCodes`; behavior was
checked against `UseLogic.Style`, `KnightManager.applyStyle` and the native
`ItemConfig` UI rules. No archive implementation or client assets were copied.
`StyleSessionTest` verifies native dispatch, ownership and transactional rollback;
`scripts/compat/StyleCompatibility.java` verifies native marshalling and all saved
style arrays with the stock reader. Visual appearance has not been verified.
Nested prize boxes remain in inventory for later manual opening. Recursive box or
group cycles, missing groups, empty main outcomes and excessive expansion fail
validation; nothing is silently dropped or replaced.

Statuses are `verified` (complete sourced distribution), `approximate` (documented
candidates with explicit estimated rates/rules), `custom` (administrator rules),
`incomplete`, `unsupported`, and `unresearched`. The first three enable opening and
require a complete validated configured main pool. A citation does not
supply missing weights automatically. Startup validates all IDs, key identities,
key order, quantities, bindings, weights, chances and group/box dependencies
before publishing the immutable content snapshot.

### Transactions, persistence and verification

The opening service uses the existing schema-16 SQLite database with **no schema
change or new inventory**. One `BEGIN IMMEDIATE` transaction rechecks account,
knight, authentication revision and expected inventory revision, resolves the
owned box and chosen key, selects and validates rewards, projects capacity after
consumption and compatible stack merging, consumes one box/key, grants all
rewards, audits grants and increments the world revision. Any exception rolls
back the whole transaction, including already-updated stacks. Publication occurs
after commit and outside the storage lock. A disconnect cannot undo or partially
repeat a committed grant; normal reconnect/restart loads the saved inventory.

Recent native listener tokens (256 per connection/knight) cache success/failure
receipts. A repeated token cannot spend again or select another box. A fresh
listener may legitimately open the next item from a stack; expected inventory
revisions reject simultaneous stale intent. Native short listener IDs are not
durable operation IDs: reconnects and token reuse after cache eviction are new
intents, which must still consume owned items transactionally. There is no promise
of cross-connection request deduplication that the native protocol cannot express.
Trade sessions are rejected; items removed to auction escrow or transferred to
another owner are absent from the transaction's authoritative inventory.

Focused checks include `BoxCatalogTest`, `BoxOpeningTest`,
`BoxPoolsTest`, `BoxOpeningSessionTest`, `GiftWrappingTest`, `TownServicesSessionTest`,
`ContentManagerTest`, `DepotStoreTest` and `AuctionParityTest`. They cover weighted
boundaries, independent bonuses, quantities, binding, key selection, ownership,
authentication, full inventory, stack capacity, failed grants, races, replay,
persistence, recipe/Gift preservation and economy identity compatibility. All ten
focused programs passed for this follow-up. The native reader also checked all
250 key bindings and inactive upgrade encoding. The full build passed for the
preceding opening-engine commit; it was not repeated for this content follow-up.
The immutable Depot seed retains its disabled timed-upgrade offers even though
inactive item grants are now representable; existing SQLite offers are unchanged.

From a separate read-only installation, with a freshly built server JAR:

```sh
java --enable-native-access=ALL-UNNAMED \
  -cp 'build/tortodrone.jar:/path/to/Spiral Knights/code/*' \
  scripts/compat/BoxCompatibility.java '/path/to/Spiral Knights'
java --enable-native-access=ALL-UNNAMED \
  -Dtortodrone.deployment=/tmp/tortodrone-check \
  -cp /tmp/tortodrone-check/tortodrone.jar \
  scripts/compat/CheckCombatRendering.java '/path/to/Spiral Knights' \
  /tmp/tortodrone-box-graphics-fresh --boxes-only
python3 scripts/content/index_box_support.py
```

### Shipped approximation policy and sources

Every enabled file has `status: approximate` and evidence describing its source
and estimates. Historical input hashes are pinned internally; wiki-based estimates
include source URLs. A list of candidates is never presented as evidence of equal odds.
Uniform choices below are explicit local assumptions authorized for these tables.
No unsupported entry is dropped to increase the remaining probabilities.

| Enabled definition | Evidence and retained structure | Explicit estimates |
| --- | --- | --- |
| Iron and six monthly metal boxes | [Lockbox wiki](https://wiki.spiralknights.com/Lockbox): separate accessory families, Shadow Key reward, box-specific auras; Iron also includes Mirrored candidates | Shadow Key 1%; aura category 1% when listed; Iron modifiers 5%; remaining chance accessories. Equal families; ordinary colors standard/Divine/Volcanic/Prismatic 90/4/4/2. Standard colors equally selected. Prismatic Glow-Eyes is one fixed variant. |
| Mirrored | Same wiki: 16 eyes, Extra Tall and Extra Short | Equal choice among those 18 items; no other appearance modifiers imported. |
| Titanium Vial Bandolier family | [Individual wiki](https://wiki.spiralknights.com/Vial_Bandolier) lists seven status styles from Titanium | Equal seven-way choice. Luck is a later Supply Depot style and is **excluded**. |
| Slime serial 000 | [Slime wiki](https://wiki.spiralknights.com/Slime_Lockbox), [Node Slime Mask](https://wiki.spiralknights.com/Node_Slime_Mask): seven original families and historical palette; fireworks, confetti and bound 2-day trinket upgrade bonuses | Costumes/accessories/equipment 50/30/20, equally within each category; Divine 4%, six original colors 16% each. Independent small/medium/large fireworks 20/10/5% (one each), confetti 50% (uniform 1–3), bound upgrade 2%. Fancy and later auras/trinkets/nested colors are excluded from 000. Later serials retain distinct archived or disclosed estimated definitions. |
| Surge | [Wiki](https://wiki.spiralknights.com/Surge_Prize_Box), [linked 2012 promotion](https://wiki.spiralknights.com/Surge_Prize_Box_Promotion_September_2012): four 25% main categories; separate 5% upgrade roll with 62/31/6/1 weights; confetti | Uniform cosmetic choices; 1/2/3-ticket selection 60/30/10. Published main categories plus Hacked Aura total 101.5%: retain four main quarters and **interpret** aura as independent 1.5% extra. Confetti 1/2/3 uses 45/50/5 reconciliation. These interpretations are estimates, not additional verified mechanics. |
| Complete Solstice | [Cronus announcement supplied by the owner](https://forums.spiralknights.com/en/node/125417), [linked June 2026 promotion](https://wiki.spiralknights.com/Solstice_Prize_Box_Promotion_June_2026): main categories, independent 40% bonus and at least one of each confetti | Published nominal main percentages already total 100% before `<1%` wings. Assume wings 0.5%, then explicitly normalize the resulting 100.5% relative total; do not claim all nominal figures as exact effective rates. Paired cosmetics and five bonus alternatives equally selected. Exactly one of each confetti guaranteed. Other season/year bindings retain separate historical graphs. |
| Complete Katastrophe (2019) | [Wiki](https://wiki.spiralknights.com/Katastrophe_Prize_Box), [August 2019 promotion](https://wiki.spiralknights.com/Katastrophe_Prize_Box_Promotion_August_2019): all 56 items, category weights 0.5/1.5/4/10/20/22/20/22, unbound delivery, no additional pool | Equal choices within each category only. Earlier Katastrophe/Returns identities use their own historical graphs. |

Native configuration names sometimes differ from wiki names: Dapper Combo uses
the existing `pipe_and_mustache_*` IDs; Bomb Bandolier uses `bomb_bundle_*`,
Helm-Mounted Display uses `power_scanner_*`, and Dread/Seraph Wings use their
existing Hexagram/Seraphynx bindings. These are the original items, not replacement
rewards. Ordinary YAML contains only existing readable IDs.

The official forum also contains empirical reports. In [Forging Data](https://forums.spiralknights.com/en/node/93101),
posts 18 and 35 describe actual multi-item Forge openings, and post 33 reports only
six recorded openings at that time and warns against inferring a complete table.
Those reports support bound bundles containing crystals, sparks, an orb and a
variant ticket/Shadow Key. They do **not** establish complete conditional weights;
the much larger forging-attempt count measures acquisition, not opening outcomes.
The [Forge wiki](https://wiki.spiralknights.com/Forge_Prize_Box) also explicitly
marks quantities and pools incomplete. Enabled Forge definitions are local
approximations based on audited candidate means. Crystals use quantities `base`,
`2 × base`, `3 × base` with estimated weights 50/49/1; the six tier bases are
5/10/15/20/25/25. Sparks are fixed 0/2/4/6/8/10. Variant/orb alternatives retain
audited mean ratios; Dim's orb chance is 33.3%. An independent estimated 1% upgrade
roll uses 100/50/10/1 weights. Radiant additionally rolls a bound Shadow Key at an
estimated 0.1%. All Forge rewards bind. Independence, fixed spark quantities and
crystal distributions are assumptions, not reconstructed official drop tables.

[Lucky March 2024](https://wiki.spiralknights.com/Lucky_Prize_Box_Promotion_March_2024)
publishes category weights 40/30/5/8/8/7/2, keyless opening and unbound delivery,
and its complete archived pool now includes furniture. The historical
[April 2014 Steam announcement archive](https://store.steampowered.com/news/posts/?appids=99900&enddate=1396561916)
describes Surprise boxes with confetti and an independent Stately Cap chance;
indexed Surprise/Anniversary editions now retain separate archived selection
graphs and nested-box rewards. The support matrix identifies the seven internal
or empty definitions that remain disabled.

The [June 2016 Golden Slime Casino announcement](https://store.steampowered.com/news/app/99900/view/2882822940992219027)
confirms alternative keys but no complete numbered-box odds. Native config
inspection independently verifies option order. No pity counters, acquisition
NPCs or private client assets are included.


### Expansion evidence and probability boundaries

Bulk research used a read-only archived stock `spawn_table.xml` resource export
and the independent `prize-boxes-game-2026-09-25.tsv` numerical audit. Input hashes
are in `content/_internal/provenance/box-expansion.tsv`; private resources are not
shipped. The audit is a separate calculation of archived data, not official pull
reports or published current-server rates. Additional expectations, indexed by readable IDs in
`content/_internal/box-expectations.json`, are verified by `BoxExpansionTest`.
Derived-table parameters were resolved before import. Weighted alternatives retain
relative weights; uniform choices are used where the archived resource explicitly
uses that native operator. Optional/null branches use explicit `rewards: []`
alternatives. Every complete opening must still guarantee a reward. Compound
children all grant; repeated table rolls remain independent. A locked item entry
binds only its branch, not every item in that box.

This is historical approximation evidence, not a capture of current server-side
randomness. The original 12 estimates remain distinct and can differ from the
archived historical tables. A candidate list or small observed sample is never
presented as official probabilities.

[AeroTech Mk II August 2026](https://wiki.spiralknights.com/AeroTech_Mk_II_Prize_Box_Promotion_August_2026)
and the [official announcement](https://forums.spiralknights.com/en/node/125556)
provide category percentages 1/2/5/5/8/15/15/30/19. The explicit distribution list's
Tech Red/Violet/Yellow/Azure palette overrides conflicting introductory text.
Uniform within-category selection is estimated. Rewards are unbound.

[Slime Lockbox](https://wiki.spiralknights.com/Slime_Lockbox) documents 00G's distinct
Crow/Rabbit masks and back accessories in Late Harvest/Frosty/Autumn/Shadow/
Prismatic. Category weights 66/66/32/32/1/3 and shared aura/trinket branches are
explicitly estimated from archived 00F; equal edition-color odds are another
estimate. The wiki does not verify these rates or inherited extras.

`BoxPoolsTest` opens every enabled definition on disposable SQLite, checks key
consumption and reloads after reconnect/database reopen. `InventoryExpiryTest`
covers deadlines, transfers, gifts, escrow encoding, expiry rollback and revision
publication. Schema 16 is unchanged by box opening: absolute deadlines use the existing integer
metadata namespace `item.expires.<id>`. No owner database is migrated. Native
reader checks cover furniture's undeployed count, common item `Long` expiry and
custom color triplets. All 5,918 distinct candidate reward configurations match
the installed native item factory subtype, including all 244 furniture bindings.
The broader stock item sampling oracle reported an Artifact seed mismatch outside
these reward types and is not counted as a pass.
