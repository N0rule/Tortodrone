# Editing server content

Version 0.33 uses one `content/` tree and readable YAML under `config/`. Edit a file,
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
    misc.yml                 every remaining verified definition
  town/
    shops.yml                merchant catalogs and initial Depot stock
    recipes.yml
    stations.yml
    sprites.yml
    variants.yml
    accessories.yml
    progression.yml          item prices, forge tables and gear progression
  shared/
    trap-cycles.yml
    statuses.yml
    combat.yml               shared numeric balance and sparse gear defenses
    news/news.yml            Uplink News banners and their images
  _internal/                 fire graphs, native item arguments and provenance
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
Economy rules already have concrete owners in Town files; no empty economy file
is shipped.

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

After a knight logs in the unmodified client requests Uplink News and shows the
banners it receives. `content/shared/news/news.yml` names up to three images; the
client fetches each one as `content_url + news/<file>`, so the launcher's
`content_url` must point at this server's `news-port`.

```yaml
enabled: true
featured:
  image: featured.png
  tooltip: Server news
  url: https://example.invalid/news
spotlights:
  left:
    image: left.png
  right:
    image: right.png
```

`featured` is the top banner; `spotlights.left` and `spotlights.right` are optional
side banners. Every banner needs a PNG or JPEG `image` stored directly in this
folder. The optional `tooltip` shows only when the same banner also sets a `url`;
the optional `url` opens in the player's browser. Images may not escape the folder,
and a missing, non-image or escaping file stops startup. An absent `news.yml`, or
`enabled: false`, answers the client with no News. The server serves this folder over
HTTP on `server-ip:news-port` and refuses traversal, non-image paths and unknown
files, so keep the launcher's `news-port` aligned with `config/server.yml`.

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

Each category has an `items` mapping keyed by the exact stock configuration path.
Search by the visible item's name. `stockId` is the retained catalog identifier;
it is neither the persistent inventory-row ID nor an invitation to replace saved
integer identities. Defaults omit irrelevant flags/zero modifiers. Required stock
model arguments have one owner in `_internal/item-arguments.json`.

A supported weapon's `combat` section exposes `chargeTimeMs`, `chainCooldownMs`,
`maxChain`, `cycle`, `blended`, `attackMovementSpeed`, `chargeMovementSpeed`, and its
ordered `attacks` plus supported `incomplete`, `charged` and `cooldown` stages.
Each stage exposes `landMs`, `rearmMs`, `clearMs`, optional blending and native
impulse timing/displacement. Speeds are the existing native control values:
**attackMovementSpeed controls movement while attacking, not attack rate**.
Equipment `modifiers` are keyed by `NONE`, `SWORD`, `HANDGUN`, `BOMB`; omitted
classes/values default to zero. `attackSpeed` there is the existing attack-speed
modifier. Shields expose health, regeneration/break delays, defending movement
modifier and the existing bash fraction/action.

Edit existing values and validate. Damage, elemental/status effects and staged
bomb behavior retain their verified fire/action graphs in `_internal/fires.json`.
No universal damage multiplier is exposed: applying one without family-specific
semantics could scale unrelated effects. New assets, missing AI, or arbitrary
new item behaviors are not implemented by adding a YAML row.

The installed common Item record has no equivalent of BattleSprite's
server-controlled name field; its visual name resolves through stock configs.
No unverified `displayName` setting is accepted. Config references and native
item variants remain compatible; server menu labels are not proof of client item
renaming support.

## Map identity, actors, portals and traps

`map.yml` preserves the native ID and type, provides readable administration
labels, and selects `defaultSpawn` by a named entrance. Names used in native
`Name` serialization, scene version, image and zone IDs stay in
`native/metadata.properties`; changing an administration label does not rename
client-localized maps. The `zone` field is descriptive; native zone membership
requires a verified reimport. `instances` remains bounded to 1–16 per template,
64 templates and 128 total configured instances.

`portals.yml` distinguishes `entrances` (arrival ID, x, y, facing in degrees) from
`portals` (native trigger ID, destination map folder, arrival entrance). Rectangular
and convex polygon geometry stays with verified native bindings. To redirect the
Advanced Training exit, change its existing portal's `destination` and `entrance`;
there is no need to copy vertices. Destination entrances must exist and be walkable;
arrivals intersecting a portal trigger are rejected. A coordinate-only default
point has no native `id`, is not a named travel arrival, and cannot be targeted by
a portal. This preserves older server spawn coordinates without inventing native
marker identities. A default entrance is used
only when no explicit arrival applies. Travel/subscription generations, parties
and scene ownership still use the existing runtime implementation.

`actors.yml` keys include readable config/source identifiers and the handler
index. A config path alone never identifies a placed actor. Edit supported
placement/gameplay fields, such as a training generator's `child.health`, its
position or a trap's `cycle`. `facing` is degrees in [-180,180]. Numeric and boolean
field types are checked; unknown native fields and actor additions/removals fail
binding validation. Source keys, handler bindings, native class/model data and
native-layout association remain pinned. A changed native scene requires a
reimport, not a guessed binding.

Optional `mechanics.yml` contains only each map's own rules; maps with no local rules omit it.
`shared/combat.yml` owns global player/scaling/status parameters and sparse gear
defenses; omitted gear values default to zero. Shared `trap-cycles.yml` owns
stock presets A/B/A2/B2, slow/cross variants and their inverse references.
`up`, `down`, `warn` and `offset` are **seconds**. An actor references its preset
by name. The engine's switch/signal transitions, offsets, collision and inverse
cycle behavior are retained. Shared statuses and complex native status actions
remain prepared data; their presence does not claim every original effect works.

## Town editing

`town/shops.yml` contains catalog goods and initial Depot offers; good entries
retain exact item references, count, Crown/Energy prices and purchase limits.
`recipes.yml` retains ingredients, counts, rarity-dependent costs, outputs and
verified variant tables. `stations.yml` owns global service definitions; map-local
NPC actions reference them. Sprite progression, accessories, variants and gear
progression each have their own files. Unresolved/random original stock is not
replaced with invented goods or probabilities.

Storage still rechecks authentication, item ownership, balances, quantities,
limits and stale quotes at the atomic transaction boundary. Player data does not
move into YAML. After the first admin-managed Supply Depot edit, that deployment's
managed offers live in SQLite; the YAML Depot entries remain the initial defaults.
Editing YAML does not overwrite a managed catalog. The account manager retains
its existing live Depot/auction controls.

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

Tortodrone has no public releases before 1.0.0: there are no earlier published
deployments, so no migration procedure exists or is needed. Keep the existing
`data/` directory with its SQLite state and keys. Back up the stopped `data/`,
configuration and content, then place the current JARs beside the prepared
`config/` and `content/` directories. Preparation fills missing files and
preserves edited/unknown files; it never overwrites administrator edits. Unknown
SQLite schemas are refused rather than modified.
