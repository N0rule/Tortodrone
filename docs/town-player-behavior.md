# Town and interior player behavior

The peaceful Haven towns and combat interiors use different stock actor types.
Town scenes retain the peaceful `Knight` controller: combat input does not turn on
there. The Training Hall, Laboratory and Hall of Heroes use the interior
`Dungeoneer` controls and require the locally prepared seven-scene map set. The
archive preparation source is ignored local data; preparation checks the four Haven
town packs against the regular checked-in geometry, preserving the owner's regular
Town Square and other Haven layouts.

When the seven maps are prepared, the server handles the stock interior controls for
normal attacks, charge and charged attacks, chained attacks, and authored cooldown
attacks. The selected weapon is one of the two unlocked weapon slots (6 or 7). Shield
defense slows movement according to the shield definition; bash uses the authored
attack and consumes shield health, which regenerates after the configured delay and
period. Dodge applies the authored cooldown and short movement impulse. These controls
include stock level-zero gear/weapon speed, attack-speed and charge-time bonuses,
with the native modifier objects sent to the client. Older deployments with only a
weapon catalogue retain their verified shield slowdown. Controls are driven by
stock input state; the server advances their timing on its scene clock.

The checked local export contains **382 weapon definitions and 123 shields**. Its
authored fire catalogue covers **1,827 attacks, 2,667 fire references and 2,120
projectile emissions**. Supported emissions preserve stock bullet, bomb and orbital
projectile references, including the base and compound orbital forms. Wrench Wand's
equal-weight sound variations and Pollinator's seeded pollen colour are supported. Numeric
configuration and reference data are used; client models, textures, effects and other
game assets remain outside the repository.

This is a limited player-combat implementation. There are no hostile actors or
player damage targets, so projectiles do not create hits, health loss, loot, drops or
rewards. Projectile travel traverses the imported sampled static collision grid,
checking every crossed cell instead of only movement endpoints or fixed-distance
samples. This prevents bullets with large authored movement steps and short orbital
diagonal crossings from skipping blocked cells. The grid still approximates the
original collision shapes; projectile radius, exact impact position and timing, and
moving-knight projectile hit detection remain unsupported. Town and interior NPC
services, shops, crafting, missions and general dungeon progression are outside this
scope.

Some authored fire graphs remain unsupported: the Conditional action on the Sealed
Sword and 20 legacy `Agent` projectile emissions, including the Fiery and Static
Diskgun families and the old Mixmaster and Orbitgun families. These require native
moving carriers with scripted startup spawns and source-following orbital children;
treating the carriers as ordinary bullets would change their motion. Modern
`maxLoop`, `successIncrement` and `incrementReduction` metadata is exported, but
loop caps, end actions and increasing-loop semantics are not implemented. The
archive's compatibility implementation also leaves those fields unused, and the
available client schema does not establish their server execution rules.
Unsupported actions are counted in the export report instead of being silently
treated as a successful shot.

Sealed Sword requires more than a conditional wrapper. Inspection of the unchanged
2026 client configuration found an empty `ConditionConfig.Tagged` condition and a
true branch of `DungeonActionConfig.TableSpawn`, referencing
`Monster/Attacks/Sword(BreakSeal)` (arguments `Chance=0.25`, `Power=60`), with no else
branch. The archive's restored `attack.xml` records a `Random` condition with
probability `0.85`, but that historical server overlay is not the installed client's
complete execution graph. Monster spawn tables and their actors are outside this
interior player-control milestone. Neither the stripped condition nor the table
is treated as an ordinary projectile or an unconditional successful shot.

Evidence: archive HEAD `566636e022c7356c62ea6fcc1aba37aa4e9e38dc`,
`vendor/clyde-spiralstudio/core/src/main/java/com/threerings/tudey/config/ActionConfig.java:579`
and `core/src/main/java/com/threerings/tudey/server/logic/ActionLogic.java:655`
under the same Clyde root establish generic branching;
`vendor/reference-server/reference-server-server-development/rsrc/rsrc/config/attack.xml:50947`
and `docs/WORKLOG.md:18522` identify the restored condition. The installed
`projectx-pcode.jar` SHA-256 was
`391a51cef0446f55d461bdb8f942ca9dba44e6e4138acdcd69ab2024297f543c`.

Focused regressions can be run with:

```sh
./gradlew unitworld_CollisionMapTest unitworld_KnightCombatTest unitworld_SceneWeaponsTest unitworld_WeaponCatalogTest unitworld_FireCatalogTest
./gradlew unitcharacter_ItemCatalogTest unitserver_ItemCommandsTest unitworld_EquipmentCatalogTest
```

The broader unchanged-client check is:

```sh
java scripts/compat/CheckClientWorld.java "/path/to/Spiral Knights"
```

It uses disposable server data and checks accepted object and item schemas. Passing
network or stock-reader checks does not establish graphical rendering, animations or
complete combat behavior; no graphical combat acceptance is claimed here. The first
isolated graphical launch reached 16% loading within 35 seconds. A second isolated
launch of the 0.25.1 baseline with a 180-second window authenticated and selected
regular Haven; its
1920x1080 framebuffer showed the rendered Town Square, fountain, scenery and game
HUD. The initial Spiral Uplink window remained open with missing news-image
placeholders. This establishes basic Haven rendering only; combat rendering,
animations, peer views and graphical transitions remain unverified. Each launch
used disposable server data, home/preferences and native extraction, and read the
installed resources without modification. Only the spawned tests were stopped.

The 0.25.1 small checks passed the focused controller, fire/equipment catalogue and
grant regressions; the stock reader preserved 60 representative real projectile/effect
argument shapes, including orbital actor deltas and equipment modifier deltas.
`InteriorConnectionCheck ... --weapons` passed native Arsenal equip/input/emission
for Calibur, Proto Gun and Blast Bomb against a disposable seven-map server.

0.25.1 release validation: the final `./gradlew build` passed (66 tasks, including the
owner's F3 admin regression). The weapon-only shield compatibility fix then passed
the focused controller/fire/equipment tests and stock modifier reader. No benchmarks
or full catalogue graphics runs were used.

0.25.2 validation: the new large-step bullet fixture failed against the original
implementation before the sweep fix. `./gradlew unitworld_CollisionMapTest
unitworld_SceneWeaponsTest` passed after the fix; the final `./gradlew build`
passed all 67 tasks, including the exact mixed-direction corner regression and
orbital collision/wall-passing flag checks. The disposable stock-client run of
`FireCompatibility defaults/combat/fires.json` passed all 60 representative
projectile/effect shapes; `InteriorConnectionCheck port public-key 127.0.0.1
maps-dir --weapons` passed Calibur, Proto Gun and Blast Bomb equip/input/emission.
`git diff --check` passed. The graphical baseline launch used
`bash /tmp/tortodrone-parity-graphical.sh`; its screenshot stayed outside the
repository at `/tmp/tortodrone-parity-graphical.png`. The focused network/reader
run used `bash /tmp/tortodrone-parity-stock.sh`. Their disposable servers and
spawned client processes were stopped; no owner sessions were touched. No
benchmark, full catalogue graphical check or exact-shape collision comparison was
run.
