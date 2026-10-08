# Current server feature research

Implementation record for the owner's ordered tasks, begun from `b025566` / 0.7.1
on 2026-10-08. The [checkpoint](world-entry-plan.md) owns current feature status;
this file keeps evidence, decisions and limitations needed by later agents.

## 1. Item binding (0.7.2)

The stock installed `Item.writeObject/readObject` packs binding into its final
flags byte: bit 2 (`aTY`) is mutable binding; bit 1 (`bwa`) is permanent locking.
`Cs()` tests either; the tooltip and Arsenal equip helper use it. LevelItem's
configuration has `lockOnEquip`. Verification reads installed classes unchanged;
no client implementation is imported.

The stock Arsenal helper `item.client.M.e(context,item,slot)` calls
`ItemService.a(long,int,ConfirmListener)`, marshaller method **3**. The server
previously handled only **9**, `moveItem(long,Location,int,ConfirmListener)`.
A network check of method 9 therefore missed the actual Arsenal equip operation.
Both now route to the same transaction and publish items/equipment/loadout before
confirmation. No unbinding, trade, economy or item grant service is introduced.

Schema 4 adds independent `inventory_items.bound`. New equipped starters are
bound; equipping binds, removing/displacing preserves binding. All existing items
are starter items originally granted equipped, so migration binds even currently
unequipped items without modifying IDs, appearance, heat, equipped slots or balances.
The serializer emits binding bit 2, leaving permanent-lock/expiry flags unchanged.

`ItemBindingTest` checks binding, re-equipping, reopen, schema-3 migration and forced
rollback. `WorldSessionTest` exercises method 3 and rejects incompatible slots.
Installed-client fixtures check `Cs()` and use the actual Arsenal method on both
creation and reconnect. Graphical Arsenal behavior is not independently reaccepted
in this milestone; protocol and stock-client decision fields are tested.

## 2. Scenes: researched, implementation next

The [pinned archive index](https://github.com/reference-source/SpiralKnightsSceneArchive/blob/ae49faf06788dd83b35a959306c9fe4c25688004/Master/Static/README.md)
identifies Town Square as scene 1, Bazaar as 2 and Arcade as 3. Original compressed
scene exports supply geometry/configuration references; they do not supply server
NPC, mission, combat or portal rules. Static templates and runtime instance/object
IDs must be separate. Reuse Vilya zone callbacks and Clyde scene readiness/TCP
baseline rules from [resource research](server-research.md).

The practical preparation path should fetch pinned, checked archive exports before
startup and derive collision using read-only installed configurations when needed.
Do not hand-create layouts or silently allow unchecked collision. Keep runtime
maps external to JARs and fail clearly for missing/corrupt prepared packs.

## 3–6. Remaining research boundaries

Duplicate-login replacement timing requires verification; do not equate account
characters with separate accounts. Knight deletion must preserve monotonic IDs,
remove associated owned data and prevent stale sessions from resurrecting a save.
Scene chat should use verified Crowd marshallers and message events. The installed
PlayerObject.Type includes `GAME_MASTER`; verify its icon/title consumer rather
than inventing a prestige enum value (the prestige enum only has RANK_0–RANK_5).
