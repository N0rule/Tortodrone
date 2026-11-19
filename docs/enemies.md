# Enemy families and stock roster

This reference lists the monster families and every enemy description entry
shipped in the installed stock client. It is a data inventory, not a statement
that Tortodrone implements these monsters, their AI, attacks, rewards or scenes.
Imported names do not establish gameplay support.

Client build: `20261110044614`.

## Families

The client's `com.threerings.projectx.dungeon.data.Theme` enum defines six
monster families plus separate status themes:

`SLIME`, `BEAST`, `UNDEAD`, `FIEND`, `GREMLIN`, `CONSTRUCT`.

Entities filed under `Monster/No Family/...` have no family theme. The stock
training bags confirm the six-family split.

## Roster

Display names below are the client's own localized names from
`rsrc/i18n/monster.properties`. Variants that are separate client configs are
listed separately. `(Base)` family templates and training/utility entries are
excluded; see the notes.

| Family | Count |
|---|---:|
| Slime | 45 |
| Beast | 26 |
| Undead | 46 |
| Fiend | 29 |
| Gremlin | 33 |
| Construct | 40 |
| No Family (combat) | 34 |
| **Total** | **253** |

| Family | Enemies |
|---|---|
| **Slime** | Baby Mimic, Ice Queen, Royal Jelly, Companion Cube, Germ Drop, Glop Drop, Power Drop, Snow Drop, Spice Drop, Ice Mini, Blast Cube, Giant Toxoil, Ice Cube, Impostocube, Jelly Cube, Oiler, Quicksilver, Rock Jelly, Sloom, Sloombargo, Soul Jelly, Super Blast Cube, Toxigel, Toxilargo, Toxoil, Ultra Blast Cube, Void Gel, Jelly Green Giant, Jelly Green Mini, Giant Lichen Colony, Large Lichen Colony, Lichen, Lichen Colony, Treasure Mimic, Tentacle, Caustic Polyp, Compound Polyp, Polar Polyp, Polyp, Royal Polar Polyp, Royal Polyp, Shadow Polyp, Silver Polyp, Slick Polyp, Royal Mini |
| **Beast** | Alpha Ash Tail, Alpha Frostifur, Alpha Voltail, Alpha Wolver, Rabid Alpha Wolver, Rabid Snarbolax, Snarbolax, Chromalisk, Chromalisk Whelp, Electrolisk, Kleptolisk, Salamander, Tundralisk, Virulisk, Blech Bunny, Dust Bunny, Jolt Bunny, Snow Bunny, Toast Bunny, Perma-Frostifur, Ash Tail, Frostifur, Rabid Wolver, Void Wolver, Voltail, Wolver |
| **Undead** | Almirian Royal Guard, Almirian Shadow Guard, Bomb Gourdling, Bombie, Burning Bombie, Freezing Bombie, Choking Bombie, Surging Bombie, Margrel, Deadnaught, Burning Deadnaught, Frozen Deadnaught, Vile Deadnaught, Static Deadnaught, Gourdling, Grim Gourdling, Grim Scarab, Grimalkin, Chilling Howlitzer, Howlitzer, Shocking Howlitzer, Smoking Howlitzer, Vile Howlitzer, Phantom, Punkin King, Grave Scarab, Pale Scarab, Plague Scarab, Silver Scarab, Sun Scarab, Slag Guard, Almirian Crusader, Black Kat, Bloogato, Hurkat, Mewkat, Pepper Kat, Spookat, Statikat, Carnavon, Droul, Dust Zombie, Frankenzom, Frozen Shambler, Slag Walker, Void Zombie |
| **Fiend** | Arkus, Darkfire Lord Vanaduke, Lord Vanaduke, Blarful, Blarful Overtimer, Devil-IT, Devil-IT Overtimer, Devilite, Devilite Overtimer, Firebrander, Firebrander Overtimer, Layoafer, Layoafer Overtimer, Pit Boss, Yesman, Firegut, Gorgo, Guster, Storm Belly, Waster, Beryl Greaver, Greaver, Humbug, Jade Greaver, Pearl Greaver, Ruby Greaver, Maulos, Silkwing, Trojan |
| **Gremlin** | Darkfang Demo, Ironclaw Demo, Tenderfoot Demo, Groat, Herex, Gremlin Choker, Gremlin Cooler, Gremlin Jumper, Gremlin Knocker, Gremlin Lighter, Darkfang Mender, Ironclaw Mender, Tenderfoot Mender, Blazing Mortafire, Gremlin Mortafire, Icy Mortafire, Static Mortafire, Toxic Mortafire, Razwog, Darkfang Scorcher, Gremlin Incinerator, Grinchlin Scorcher, Ironclaw Scorcher, Tenderfoot Scorcher, Sputterspark, Ghostmane Stalker, Grinchlin Stalker, Darkfang Thwacker, Grinchlin Thwacker, Ironclaw Thwacker, Tenderfoot Thwacker, Void Thwacker, Warmaster Seerus |
| **Construct** | Battlepod, The Big Iron, Red Roarmulus Twins, Roarmulus Twins, Collector, Gold Puppy, Gun Puppy, Love Puppy, Plasma Dog, Red Rover, Rocket Puppy, Sick Puppy, Slush Puppy, Sparky, Electreant, Ironwood Sentinel, Lumber, Redward, Silversap, Vilewood, Void Lumber, Cryo Knight, Firo Knight, Mecha Knight, Poison Knight, Volt Knight, Hotrode, Isotrode, Retrode, Sleetrode, Voltrode, Brumabot, Cinderbot, Hazbot, Scuttlebot, Surgebot, Shufflebot, Tentacle, Tortodrone, Tortodrone (Corrupted) |
| **No Family** | Batterbot, Creep Cake, Mini Creep Cake, Current Cake, Dread Velvet, Apocrean Harvester, Fire Soul, Frozen Soul, Lost Soul, Poison Soul, Static Soul, Freed Soul, Void Soul, Sage Shankle, Golden Sage Shankle, Golden Sharp Shankle, Golden Dark Shankle, Golden Shankle, Sharp Shankle, Dark Shankle, Shankle, Swarm Seed, Swarm Turret, Wheel Launcher, Bog Wisp, Dream Wisp, Golden Bog Wisp, Golden Dream Wisp, Golden Storm Wisp, Golden Volcanic Wisp, Golden Winter Wisp, Storm Wisp, Volcanic Wisp, Winter Wisp |

## Notes

- **Bosses stay under their family**: Snarbolax and Rabid Snarbolax under Beast;
  Royal Jelly and Ice Queen under Slime; Roarmulus Twins and Red Roarmulus Twins
  under Construct; Lord Vanaduke and Darkfire Lord Vanaduke under Fiend; Margrel
  under Undead.
- **Variants are distinct entries**: elemental (Burning, Freezing, Choking,
  Surging), Overtimer, Alpha and Rabid, Golden, and Mk I–III prefixes/suffixes
  are separate client configs, so they are not merged.
- **No Family** contains event, drone and special enemies (cakes, souls,
  shankles, wisps, swarm pieces, wheel launcher) as well as the non-combat
  `Training Bag` entries (Beast, Construct, Fiend, Gremlin, None, Slime, Undead)
  and `Training Target`. The 34 count excludes those eight training/utility
  entries.
- **Name differences**: some config names differ from their displayed name, for
  example `Soldier of Almire` displays as **Almirian Crusader**, `Mimic` as
  **Treasure Mimic**, `Harvester` as **Apocrean Harvester**, `Gold Gun Puppy` as
  **Gold Puppy**, and `Perma Frostifur` as **Perma-Frostifur**. The corrupted
  Tortodrone config shares the display name **Tortodrone**.
- **Excluded**: abstract `(Base)` family entries. `actor.dat` also contains
  dev/unshipped monster configs with no shipped description or localized name
  (for example `Barlok`, `Dybbuk`, `Gold Trojan`, `Dreadnaught`); they are not
  part of this roster.

## Generated catalogue

`content/monsters/` holds the generated, read-only type catalogue:

- `catalog.json` — one entry per roster type, including unsupported ones.
- `catalog.sha256` — SHA-256 fingerprint of `catalog.json`; a mismatch is refused at startup.
- `overrides.yml` — the only hand-authored file: AI scheduling knobs, never stats.

Each type carries the native description config, display name, family, selected actor
config, an archetype default and, for supported types, the prepared attack fields the scene
runtime already understands. Unsupported types stay listed with their exact failing action
kinds; they cannot be spawned or referenced by a scene. The catalogue is parsed once at
startup and shared by every scene instance. `generatedFrom` records the SHA-256 of the
installed client's `actor.dat`, `description.dat` and `projectx-config.jar`.

Coverage of the 253 roster entries (Phase 0 gate: at least 60%):

| Family | Types | Supported | Unsupported |
|---|---:|---:|---:|
| Beast | 26 | 25 | 1 |
| Construct | 40 | 37 | 3 |
| Fiend | 29 | 29 | 0 |
| Gremlin | 33 | 33 | 0 |
| No Family | 34 | 31 | 3 |
| Slime | 45 | 33 | 12 |
| Undead | 46 | 34 | 12 |
| **Total** | **253** | **222 (87.7%)** | **31** |

Unsupported reasons are the client action kinds the fail-closed exporter still rejects:
`DungeonActionConfig$DamageTarget` (6), `$ImpulseTarget` (6),
`ProjectXActionConfig$StartConversation` (1), `ActionConfig$RotateActor` (1),
`ActionConfig$Switch` (1), `DungeonActionConfig$TableSpawn` (1),
`ConditionConfig$Action` chains (2), eight descriptions with no matching actor config, and
five whose verified relative-fire export changes shape. Treat this list as the remaining
exporter workstream, not as gameplay policy.

### Regenerating

The exporter is read-only against the installed client and refuses an existing output
directory, so review the output before copying it in:

```
java -cp build/tortodrone.jar scripts/StockTools.java monster-roster "/path/to/Spiral Knights" /tmp/monster-roster
java -cp build/tortodrone.jar scripts/StockTools.java monster-catalog "/path/to/Spiral Knights" /tmp/monster-catalog --catalog
```

Build the server JAR first (`./gradlew buildServer`). The report writes
`monster-roster.json` plus `monster-roster.txt` with per-family supported/unsupported counts
and every unsupported type. `monster-catalog` writes `catalog.json` and `catalog.sha256`.
After review, replace the two
files in `content/monsters/` and run `./gradlew validateContent` and
`./gradlew testUnit -PunitTests=MonsterCatalogTest`. Regenerating must produce byte-identical
output; sort-normalised object keys and descriptor field lists keep the file stable across
JVM runs.

### Overrides

Only these keys are accepted, per monster type id:

```yaml
monsters:
  darkfang_demo:
    activation-radius: 12
    leash-radius: 20
    think: alert
    archetype: melee-chaser
```

- `activation-radius` 1–512, `leash-radius` 1–1024; leash may not be smaller than activation.
- `think` is `dormant`, `alert` or `engaged`; `archetype` is a lowercase policy id.
- Unknown type ids, duplicate keys, unknown fields and identity/stat/timing fields fail
  startup rather than being ignored. `activation-radius` is applied to type-backed placements
  now; `leash-radius`, `think` and `archetype` are validated but applied by the later
  lifecycle and archetype phases.

The validator and scene loader understand a prepared `Monster` placement carrying
`monster-type: <id>`. Validation rejects an unknown or unsupported id, a non-monster
actor, or an actor config that differs from the catalogue type. At load time the reference
expands into the type's prepared attack graph and timing while the placement keeps its
native identity, geometry, health, speed and any explicit tuning field; the reference
itself is removed before the runtime sees the spec. The laboratory Companion Cube is
migrated this way (`actors.yml` alias `companion_cube_1_1`), preserving its committed
attack timings and `attack.0.blended: false` override. The exporter also surfaces
`monster-movement` and `attack.*.impulse-*` metadata that the older placement omitted;
those fields are not read by the current engine. `attack.*.blended` is read (attack-time
rotation), so the placement keeps the committed value until a gameplay-reviewed change
adopts the native one.

The three Crash Site `Monster`-kind placements are scripted tutorial Spiral Knights.
They have no `DescriptionConfig.Monster` roster entry, so they cannot become catalogue
types without inventing client data; they remain embedded prepared specs with their
scripted behaviour. No spawn command or new gameplay behaviour is provided by this
resolution step.

## Prepared actor sharing

`world/PreparedActor` parses a prepared spec once and caches the immutable result for
every identical `SceneContent.Spec`: movement script, script program, targeting, wander,
follow, attack motion, melee range caps, party platform, elevator, shutdown action and
scene-fire actions. `SceneEntities.Entity` keeps only per-instance mutable state (health,
activity, targets, paths, status and timers), so spawning many copies of one type does not
repeat graph parsing and cannot leak combat state between instances. The cache uses weak
keys, so it holds nothing after a scene's content is released.

## Activation and think ladder

Combat monsters are scheduled in three bands: `DORMANT`, `ALERT` and `ENGAGED`.
Dormant monsters remain in the scene and stay published to nearby clients, but skip
pursuit, pathing and attacks until an active player comes within their activation
radius. An alert monster reacquires a target every 250 ms; an engaged monster runs the
full per-tick movement and attack path. Dormant decisions use a stable id-staggered
1.0–2.0 s interval, while every tick still performs one cheap distance check so a
player who steps into range is noticed immediately.

A prepared monster may set `activation-radius` (world units); the default is 32. Damage
is authoritative: any landed hit forces `ENGAGED` for five seconds even without a
nearby player. Wander and party-follow behaviours are authored motion rather than
combat pursuit, so they keep their previous every-tick integration. Scripted tutorial
actors and an already-started attack sequence are never paused by the ladder.

The per-scene awake cap and leash are part of the later wave lifecycle work; this
phase only schedules existing placements.

## Perception and path budget

A clear line of sight is reused for at most 150 ms, or until the target moves more than
0.5 world units or the monster is damaged. Only the monster's current position is cached;
candidate points sampled inside a path search always evaluate collision afresh.

A scene admits at most four A* searches per tick across combat pursuit, idle follow and
scripted movement. A denied request leaves the current path and `nextPath` deadline in
place, so the monster retries on a later tick and never searches more often than the
existing 50 ms floor. Deferred ids wait in a bounded 128-entry burst queue; overflow
drops and counts the oldest request instead of growing without limit.

## Source and derivation

- Family/enemy names come from the shipped `rsrc/config/description.dat`
  entries named `Monster/<Family>/...`; their `dname` message keys were resolved
  through `projectx-config.jar!/rsrc/i18n/monster.properties`.
- Matching actor configs exist under `Character/NPC/Monster/<Family>/...` in
  `rsrc/config/actor.dat`.
- The six-family model was verified with `javap` against
  `com.threerings.projectx.dungeon.data.Theme` in `projectx-pcode.jar`.
- Client files are not checked into this repository. This page records only the
  resulting names and family grouping.
