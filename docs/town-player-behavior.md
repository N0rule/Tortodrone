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
rewards. Projectile travel uses the imported sampled static collision map; it does
not implement exact moving-knight projectile hit detection. Town and interior NPC
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

Focused regressions can be run with:

```sh
./gradlew unitworld_KnightCombatTest unitworld_SceneWeaponsTest unitworld_WeaponCatalogTest unitworld_FireCatalogTest
./gradlew unitcharacter_ItemCatalogTest unitserver_ItemCommandsTest unitworld_EquipmentCatalogTest
```

The broader unchanged-client check is:

```sh
java scripts/compat/CheckClientWorld.java "/path/to/Spiral Knights"
```

It uses disposable server data and checks accepted object and item schemas. Passing
network or stock-reader checks does not establish graphical rendering, animations or
complete combat behavior; no graphical combat acceptance is claimed here. A bounded
isolated graphical launch authenticated and selected regular Haven; resource loading
reached 16% within the 35-second window, so scene rendering and animations were
inconclusive. Only the spawned tests were stopped.

The 0.25.1 small checks passed the focused controller, fire/equipment catalogue and
grant regressions; the stock reader preserved 60 representative real projectile/effect
argument shapes, including orbital actor deltas and equipment modifier deltas.
`InteriorConnectionCheck ... --weapons` passed native Arsenal equip/input/emission
for Calibur, Proto Gun and Blast Bomb against a disposable seven-map server.

Release validation: the final `./gradlew build` passed (66 tasks, including the
owner's F3 admin regression). The weapon-only shield compatibility fix then passed
the focused controller/fire/equipment tests and stock modifier reader. No benchmarks
or full catalogue graphics runs were used.
