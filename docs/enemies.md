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
