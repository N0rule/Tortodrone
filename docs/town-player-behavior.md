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

The 0.25.3 rendering fixes preserve each item's exported native configuration ID.
Previously every item was serialized with ID zero; the installed client's
`Item.o(Item)` / `LevelItem.a(...)` identity comparison therefore treated different
weapons as unchanged and kept the old weapon model and attack animations after an
Arsenal switch. Interior body models now omit weapon and shield arguments because
the stock Dungeoneer sprite attaches those models separately. This removes the sword
remaining visible alongside a newly equipped gun. Town appearance keeps its existing
composition. Fire delays and refractory periods now use the same captured equipment
attack-speed multiplier as the native animation, including the authored fire speed.

The unchanged stock reader checks configuration identity and the current client's
interior player-model builder. Archive corroboration is in
`analyzed-2011/com/threerings/projectx/dungeon/server/LevelSceneManager.java:124`
(body model without held equipment),
`analyzed-2011/com/threerings/projectx/dungeon/client/sprite/DungeoneerSprite.java:107`
(separate weapon/shield attachments) and
`analyzed-2011/com/threerings/projectx/dungeon/server/logic/AttackLogic.java:182`
(fire delay/refractory scaling). Current installed bytecode independently establishes
the item-ID comparison and animation/fire speed product; the historical item schema
alone does not establish the modern identity rule.

The 0.25.5 elevation fix keeps native Bullet/Bomb `_z` at negative infinity when
fired by a knight. This is the stock sentinel for an unspecified floor elevation,
which the client resolves from the scene. Previously the server wrote authored
projectile height into `_z`; the current Bullet sprite then added the configuration
height again, making gun particles travel above the barrel. Authored height remains
in the native actor configuration and is applied once. The installed
`com.threerings.projectx.dungeon.data.actor.Bullet` constructor and `Rf()` method
establish the sentinel; `com.threerings.projectx.dungeon.client.a.c.b(Actor)` adds
the authored height to an explicit source Z or to the resolved scene floor. Native
Dungeoneer/Knight/Pawn actors do not provide explicit Z. The historical Bullet
schema lacks this modern field, so current installed bytecode is authoritative here.

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
java scripts/compat/CheckCombatRendering.java "/path/to/Spiral Knights" /tmp/combat-render-check
```

The first command uses disposable server data and checks accepted object and item
schemas. Network or stock-reader checks alone do not establish graphical rendering.
The second launches the unchanged graphical client in an isolated deployment and
retains logs and screenshots; keep its test window focused and review the captures.
It stimulates only that application's native controller, without global keyboard or
mouse input. See [development](development.md) for isolation and acceptance limits.
The earlier 0.25.1 graphical launch established basic Haven rendering only.

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

0.25.3/0.25.4 combat rendering validation: the fire-timing regression failed against
the original unscaled implementation, and the native weapon-identity check failed
with shared zero IDs. After the fixes, the focused combat/body-model tests passed.
Before the elevation fix, `./gradlew build` passed all 67 tasks (3m 25s), including
the newer map preparation and cross-platform account changes. `WeaponCompatibility`
passed native sword/gun/bomb identity, actor state and projectile/effect deltas.
`ItemCatalogCompatibility ... --sample` passed all 9,348 metadata records, 113
selected native item identity/class/count/binding references, 85 model samples across
three colours, 73 starter fallbacks and the native interior body builder.
`bash /tmp/tortodrone-parity-stock.sh` passed 60 real fire reference shapes and native
Arsenal equip/input/emission for Calibur, Proto Gun and Blast Bomb. `git diff --check`
passed. Graphical validation uses the checked-in Java launcher, not that network
fixture; its captures and logs remain outside the repository.

0.25.5 final validation: the new floor-elevation regression failed against the
previous committed `SceneWeapons` implementation. `./gradlew unitworld_SceneWeaponsTest
unitworld_KnightCombatTest` passed after the fix, and the final `./gradlew build`
passed all 67 tasks (3m 28s). The disposable stock check passed all 60 fire reference
shapes, now also asserting the native unspecified-Z sentinel and `Rf()==false` for
bullets/bombs, and Calibur/Proto Gun/Blast Bomb native Arsenal equip/input/emission.
The graphical command was:

```sh
java scripts/compat/CheckCombatRendering.java "/path/to/Spiral Knights" /tmp/tortodrone-combat-muzzle-fixed
```

It passed native movement, sword/gun/bomb normal and charged actions, defense, bash,
dodge, activity-specific animation playback and owned server projectile sprites.
Captured pixels were inspected for the sword swing, gun without a sword attachment,
normal/charged gun particles at the corrected height, bomb detonation, shield effects,
dodge and rendered Haven/Training scenes. The return capture waits for Haven to
settle; charged releases have their own frame sequences. Earlier vsync stalls made
short presses and charge timing inconclusive; the fixture now disables vsync in its
disposable preferences before native startup and rejects unfocused combat checks.
The native UI logged a transient unmanaged-window removal warning during scene
changes; it did not prevent the completed checks. Logs and captures remain at the
output path above, and only spawned tests were stopped.

This establishes these local combat renderings with three representative weapons.
It does not establish peer combat views, keyboard bindings, walking through portals,
the full weapon catalogue, exact muzzle alignment at every facing/character height,
or duplicate-free local prediction under all network delays. Specialized Agent
weapons, Sealed Sword and modern loop semantics remain unsupported as described
above. No performance benchmark or exact-shape projectile collision check was run.
