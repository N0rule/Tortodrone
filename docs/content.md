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
`./gradlew buildWithoutTests` prepares `build/content`, `build/config` and
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
and equipment membership. `_internal/item-arguments.json` retains typed model
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
`shared/combat.yml` owns global player/scaling/status parameters and sparse gear
defenses; omitted gear values default to zero. Shared `trap-cycles.yml` owns
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
featured goods use `RankGood.special`. No new packet or countdown was introduced.

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

Outbid refunds remain durable mail for both online/offline players. Historical
online direct refunds/chat are a known parity gap; presence/notification handoff
has not been verified sufficiently to replace the safe path. Exact current
last-minute extension timing and destructive 30-day mail purging are deferred.
See the [evidence matrix](research.md#economy-parity-baseline-2026-10-18).

## Native map import

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

SQLite schema 15 requires an **explicit offline candidate** for known schemas
9–14. Normal startup refuses those predecessors and all unknown versions, leaving
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
