# Town checkpoint and next milestones

## Completed in 0.7.0

Normal login → create/select a saved knight → Haven Town Square → movement and
mouse facing → starter inventory equip/unequip → persistent crowns and energy.
Intro missions are skipped through the verified zone/scene transition. The server
sends no tutorial-completion flags and grants no introductory-mission rewards.

The town is scene 1 from the [Spiral Knights Scene Archive](https://github.com/reference-source/SpiralKnightsSceneArchive).
The first build fetches its pinned original layout; see [provenance](dependencies.md).
The installed client supplies its own meshes, textures and audio. Server code,
protocol serializers, collision samples and tests run without a game installation.

Selection sends the owned world ID, appearance, actual item set, equipment,
balances and home town before forcing the stock zone receiver to town. Zone and
scene callbacks return the layout and explicit scene object. Scene subscriptions
receive occupant metadata; actor updates start after `enteredPlace` and continue
at 20 Hz. In 0.7.1, reliable TCP deltas reference the preceding queued snapshot,
retaining actor/model identity even while loading delays input acknowledgements. Client input
provides direction/facing only; the server computes position and checks a sampled
static collision map. Empty acknowledgement batches and the normal ProjectX input
extension are supported. Future input is queued until due, and elapsed scene time
controls movement speed. A verified movement limit permits client prediction;
unchanged actor model configurations are retained across ordinary updates. Inventory
activity and its closing duration are synchronized with stable start times; actor
deltas explicitly replace predicted activity fields. Combat actions are ignored in this town checkpoint.

Only one connection can control a given knight. Disconnect releases it. Different
knights can share town; actor identity is global, while roster IDs stay local to
an account. Equip requests check authentication revision, item ownership and slot
compatibility in their database transaction. Local currency changes are polled
into an online character within about 250 ms.

## Present limits

- One scene and one spawn; positions are not persisted.
- Static collision is sampled on a quarter-unit grid, with simple wall sliding.
  There is no server physics simulation for dynamic scenery.
- Five starter items only; no drops, crafting, trade, loadouts or costume equipment.
- Crowns and energy are per knight and start at zero. No regeneration or economy.
- News and social feeds are empty. Their handles let the stock town HUD initialize.
  Mail sending, social actions and other unsupported operations return failure.
- Reconnect to switch characters. Online knights cannot be edited/deleted in the picker.
- Visual NPCs/portals have no dialogue or travel implementation. No combat or quests.

## Next priorities

The TCP actor replacement and leaked inventory activity defects are fixed and
regression-tested in 0.7.1. Mapped-window reruns show animated walking after creation
and reconnect. Keep graphical entry checks alongside network checks as new gameplay
is added. See [investigation and acceptance checks](town-entry-investigation.md).

1. Persist position and support clean return to the character picker.
2. Implement one additional connected scene and verified portal transitions.
3. Generalize item instances beyond the starter kit, then add item grants and
   weapon selection with ownership checks and persistence.
4. Add a small combat test area: one enemy, damage, death and reconnect behavior.
5. Add one mission with persisted steps and once-only rewards before attempting
   the full introduction. NPC dialogue and scripted events belong to that work.
6. Expose a limited set of in-game admin commands using the current database role.

## Verification

`make test` uses disposable state and requires no game files. Optional
`java scripts/compat/CheckClientWorld.java "/path/to/Spiral Knights"` checks the stock reader
and its movement predictor, plus real networking/marshaller classes against a temporary localhost server, covering both creation and reconnect. It
requires a JDK and a build made with `make build`, reads installed game JARs,
checks delayed acknowledgements, model retention, equipment changes and two-account
multiplayer, and stops its server on completion. Graphical testing is a separate check because
headless network success cannot prove that the client renders the scene and HUD.
