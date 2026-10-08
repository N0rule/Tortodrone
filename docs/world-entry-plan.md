# Current checkpoint and next milestones

This is the authoritative feature/acceptance handoff. Read `VERSION` and Git for
the exact checkout; update this file when a milestone changes behavior or limits.

## Checkpoint: 0.7.1, schema 3

Normal login → create/select a saved knight → Haven Town Square → movement and
mouse facing → persistent starter equip/unequip and per-knight crowns/energy.
Different knights share the town and see peer movement. Intro missions are
skipped; no tutorial completion flags or rewards are invented.

The scene is archived town 1, bundled with a derived static collision mask; see
[provenance](dependencies.md). Stock clients provide rendering assets. Entry
publishes selected state, zone/scene callbacks, occupant metadata and scene object
10. Actors update at 20 Hz after `enteredPlace`. The server computes position
from timestamped input and collision, not client coordinates. Only one session
controls a global knight ID; disconnect releases it.

The 0.7.1 fixes are merged into `main` (`32629e2`): TCP deltas reference the prior
queued snapshot, retain unchanged models, and explicitly synchronize inventory
activity/start times. Inventory closing lasts 889 ms before movement resumes.
See [cause and evidence](town-entry-investigation.md).

## Acceptance and limits

- `make build test`: 22 unit and three packaged integration checks passed.
- Java installed-client fixture: creation/reconnect, delayed acknowledgements,
  predictor/activity timing, model retention, gear/currency and multiplayer passed.
- Isolated stock graphical client: fresh creation, stop/start/reverse movement,
  mouse facing, inventory closing and reconnect showed animated walking.
- Graphical simultaneous two-window animation and equip/unequip were not separately
  accepted; their networking checks passed. Desktop vsync/gamepad accommodations
  are recorded in the investigation. Do not treat one accepted run as proof that
  every intermittent symptom is impossible; repeat relevant checks after changes.
- One town/spawn; position resets on entry. Reconnect to switch characters.
- Sampled static collision only; no dynamic scenery physics.
- Five starter items only; no drops, trade, crafting, loadouts or costume equipment.
- Currencies start at zero; no economy/regeneration. Admin is local only.
- NPCs/portals may be visible but have no gameplay. No combat, quests or shops.
- News/social feeds are empty; unsupported services fail explicitly.

## Next milestone: town position persistence

Persist server-computed town coordinates and facing, with ownership and account
authentication-revision guards. Add a transactional schema upgrade and spawn
defaults for existing knights. Validate finite, walkable positions; unknown scenes
or invalid positions must fall back to the known town spawn. Checkpoint during play
and flush on disconnect/clean shutdown without overwriting currency/equipment edits.

Acceptance: disposable migration/reopen, logout/reconnect, clean server restart,
invalid state, revocation/deletion and concurrent currency/equipment checks; then
stock-client entry at the saved location with working prediction/animation. Keep
this separate from returning to the picker or portal travel.

## Later milestones

1. Verified clean return to the character picker.
2. One additional scene and a verified portal round trip with subscription cleanup.
3. General item instances, owned item grants and weapon selection.
4. A small combat area: one enemy, damage, death and reconnect behavior.
5. One persisted mission with idempotent rewards before broader introductions.
6. Limited in-game admin commands checking the current stored role.

Follow [AGENTS.md](../AGENTS.md) and [development workflow](development.md).
Primary protocol references are linked in [resource research](server-research.md).
