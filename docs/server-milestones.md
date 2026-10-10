# Current server feature research

Implementation record for the owner's ordered tasks, begun from `b025566` / 0.7.1
on 2026-10-08. The [checkpoint](PROJECT.md) owns current feature status;
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

Inventory stores independent binding. New equipped starters are bound; equipping
binds, removing/displacing preserves binding. The serializer emits binding bit 2,
leaving permanent-lock/expiry flags unchanged.

`ItemBindingTest` checks binding, re-equipping and reopen. `WorldSessionTest`
exercises method 3 and rejects incompatible slots.
Installed-client fixtures check `Cs()` and use the actual Arsenal method on both
creation and reconnect. Graphical Arsenal behavior is not independently reaccepted
in this milestone; protocol and stock-client decision fields are tested.

## 2. External scenes (0.8.0)

The [pinned archive index](https://github.com/reference-source/SpiralKnightsSceneArchive/blob/ae49faf06788dd83b35a959306c9fe4c25688004/Master/Static/README.md)
identifies Town Square as scene 1, Bazaar as 2 and Arcade as 3. Original compressed
scene exports supply geometry/configuration references; they do not supply server
NPC, mission, combat or portal rules. Static templates and runtime instance/object
IDs must be separate. Reuse Vilya zone callbacks and Clyde scene readiness/TCP
baseline rules from [resource research](server-research.md).

`make maps` fetches checked exports before startup; the Java scene importer derives
collision using unchanged installed configurations. Runtime packs are external
and validated. Templates share immutable data; instances have distinct object IDs,
occupants and readiness/baselines. Only verified town kinds are accepted initially.
Vilya's `ZoneService.moveTo(zoneId,sceneId,version,listener)` treats its third integer
as a cache version. Instance assignment belongs to the server. The stock reader
also requires neighbor/neighbor-direction arrays on **every** SceneSummary.

See [map format and preparation](maps.md). Network fixtures exercise Town Square →
Bazaar → Town Square and two Haven instances; graphical Bazaar/portal acceptance
is not claimed. Config warnings from older archive references do not establish NPC
behavior. Positions reset to each pack's validated spawn on travel/reconnect.

## 3. Single-account sessions (0.8.1)

Primary [Narya ClientManager](https://github.com/threerings/narya/blob/master/core/src/main/java/com/threerings/presents/server/ClientManager.java)
reuses the authenticated identity's PresentsSession. Its
[resumeSession](https://github.com/threerings/narya/blob/master/core/src/main/java/com/threerings/presents/server/PresentsSession.java)
explicitly closes the old connection before replacing it and clearing subscriptions.
Tortodrone follows the verified replacement rule with one socket lease per account,
including picker connections. Authentication failure never acquires a lease.

The exact current official Spiral Knights takeover message, grace timeout and
character-restoration behavior could not be independently verified. No official
server was contacted and no unsupported error packet is invented. Tortodrone closes
the old TCP connection, releases its controller synchronously and bootstraps the
new password login normally. Network EOF/60-second idle timeout releases the lease;
position persistence and credential-based session resumption remain unsupported.

Tests cover concurrent/rapid replacement, revision races, late cleanup, independent
accounts and unexpected disconnect/reconnect. The installed client fixture selects
two different knights on successive connections and checks that only the new actor
exists, then rapidly reselects the same knight.

## 4. Local knight deletion (0.8.2)

No new client protocol is needed. The existing picker owns roster deletion;
local tools now capture the account ID and global world ID before confirmation
and recheck both inside SQLite. Cascades remove world state/inventory; that knight's
currency audit rows are explicitly removed. Account/world/item allocation counters
are retained, so a recreated name is a different identity and stale confirmation fails.

External deletion increments authentication and roster revisions atomically. It
revokes only the affected account; login refreshes its cached roster. CharacterStore
reads roster/counter/revision together and compares revision before replacing rows,
preventing cross-process stale saves from resurrecting deleted owned state. Local
deletion may remove an online knight; picker deletion keeps its online restriction.
Tests cover cascade failure rollback, ownership/identity races, survivors, restart,
stale saves, CLI confirmation, terminal commands and live reconnect after deletion.

## 5. Scene chat (0.9.0)

Primary [SpeakMarshaller](https://github.com/threerings/narya/blob/master/core/src/main/java/com/threerings/crowd/chat/data/SpeakMarshaller.java)
and [SpeakUtil](https://github.com/threerings/narya/blob/master/core/src/main/java/com/threerings/crowd/chat/server/SpeakUtil.java)
define `speak(String,byte)` method 1 and MessageEvent `crowd.chat` carrying UserMessage.
The installed stock classes confirm PlaceObject.speakService, String/Byte arguments,
message/bundle/speaker/mode field order and SystemMessage attention-level feedback.
The server supplies the service handle; its integer code is local routing metadata.

Local chat is currently **scene-wide**, including sender echo. Exact official
proximity filtering is not verified and is not invented. Ready membership and scene
generation checks isolate instances/transitions. No messages are persisted or logged;
private tells, guild/party channels and broadcast are not implemented in this milestone.
The router, membership and retained rate budget are reusable for later channels.
Tests cover Unicode/size/mode bounds, rate replenishment, stale memberships and
failed recipients; installed fixtures send/receive through the stock marshaller.

## 6. Game Master (0.9.1)

The installed PlayerObject.Nb() derives GAME_MASTER from flags bit **256**;
PlayerObject has no streamed Type field. ProjectXOccupantInfo does stream Type,
whose GAME_MASTER ordinal is **2**. KnightSprite's nameplate builder translates
`projectx:m.game_master` into `character/label.dat` **Subtext** and uses
ProjectXOccupantInfo.getIcon() for **Prestige Icon**. Type.GAME_MASTER supplies
`support.png`, overriding the rank icon. PrestigeRank stays RANK_0; no enum is invented.

PlayerObject's permission policy checks ProjectXTokenRing.Nl() for Crowd broadcast
access; bit **8** satisfies it without granting TokenRing.isAdmin() (bit 1) or OOO
branding. The exact symbolic name of bit 8 is not available; only the verified
consumer behavior is relied upon. Client affordances are advisory: server permission
checks read the current stored role and authenticated identity on each broadcast.

Primary [ChatMarshaller](https://github.com/threerings/narya/blob/master/core/src/main/java/com/threerings/crowd/chat/data/ChatMarshaller.java)
defines broadcast(String,InvocationListener) method **2**. Its ListenerMarshaller
supports failure only; successful broadcasts require no success callback. The
stock ChatDirector exposes `/broadcast` with that permission check. UserMessage
mode **4** reaches ready players across maps/instances; bounds and budgets match
scene chat. Other staff, moderation, tells and party/guild commands are unavailable.

Local commands/UI use Game Master terminology; obsolete CLI aliases were removed
in 0.12.0. Tests verify durable
roles, cross-process/live grant/revocation, stale auth denial, stock reader role/icon,
normal-player denial and unchanged actor models/positions on role changes.
Narya OccupantDirector forwards EntryUpdatedEvent to occupant observers; a DSet
AttributeChangedEvent alone bypasses those consumers. Live roles use verified
entry updates and the stock fixture asserts their old/new types and callbacks.

An isolated unmodified ProjectXApp on niri also rendered the GM subtitle beneath
the peer's name and the support badge. Live removal hid both without disconnecting
the knight. The viewer used disposable always-visible nameplate preferences;
the stock client hides its own nameplate. Files/assets remained read-only and
all accounts, keys, deployment, preferences and extraction directories were temporary.
This accepts the GM nameplate boundary; it is not a new two-window movement,
graphical Bazaar or Arsenal acceptance claim.

## Character private tells (0.18.0)

Primary [ChatMarshaller](https://github.com/threerings/narya/blob/master/core/src/main/java/com/threerings/crowd/chat/data/ChatMarshaller.java)
defines tell(Name,String,TellListener) method **3**; TellMarshaller success method
**1** carries **Long idle duration, String away message**. Zero/null correctly
omits unsupported idle/away feedback. The installed `ChatMarshaller` and nested
TellMarshaller bytecode independently confirm these fields and dispatch IDs.
[ChatDirector](https://github.com/threerings/narya/blob/master/core/src/main/java/com/threerings/crowd/chat/client/ChatDirector.java)
listens to `crowd.chat` on the private client object as user chat. Default-mode
UserMessage is an incoming tell; success locally creates TellFeedbackMessage.
Sending a scene message or a second server echo would give the wrong UI behavior.

The installed `ProjectXChatDirector` registers `tell` and `respond`; its `$aa`
respond handler uses the last incoming speaker and sends another normal tell.
Read-only `javap -c -p` checks and `rsrc/i18n/chat.properties` in the installed
projectx-config JAR verify English aliases `tell msg t whisper w` and
`respond reply r`. No extra server command parser or invented tell mode is needed.

Ready membership resolves globally unique knight names case-insensitively across
maps/instances. The same rate budget covers scene messages, broadcasts, commands
and tells. Both endpoints recheck authenticated ownership; the recipient's captured
scene generation must still match before its private event is queued. The router
does not hold its lock or the sender's session lock while calling the recipient.
Offline/loading/departed recipients and failed queues return failure, with no
success feedback or message storage. Away/idle, block lists and guild/party/global
chat channels remain outside this milestone.

## Sitting (0.19.0)

Installed `ProjectXChatDirector.mw()` registers commands dynamically from
EmoteConfig names; `$j` sends `ProjectXSceneMarshaller.b(String,Name,ConfirmListener)`.
Read-only `javap -c -p` verifies service 6/method 3 and the listener schema.
The English stock bundle supplies `/sit` and `/sitdown`. The sorted animated
EmoteConfig list has 20 entries; Sit index 17 maps to activity 9+17=26 and its
end maps to 9+20+17=46. Duration is zero (persistent), allowsMovement=false,
endDuration=1333, targetable=false and balloonless=true. No `m.sit_format` exists.

Installed KnightAdvancer (`com.threerings.projectx.util.j`) builds those handlers
from the same configuration; `util.p.NO()` changes a stationary emote to its end
activity on movement bit 1. The end handler blocks movement/facing until its
deadline. Inventory input can interrupt the pose. The installed-reader fixture
compares these configuration facts and runs the original predictor; the network
fixture calls the original RPC on creation/reconnect and checks the actor timeline.
Only Sit is accepted; unsupported action names cannot supply arbitrary activities.
The per-membership simulation owns state and immediate ownership/readiness/auth
checks protect the RPC. No saved state or schema changes are required.

## Automatic town portals (0.20.0)

Verified primary flow:
[Clyde IntersectionStart handler](https://github.com/threerings/clyde/blob/23758c9ff6b67e5c1b5b940e25670de09ef8efd2/core/src/main/java/com/threerings/tudey/server/logic/HandlerLogic.java#L549)
activates the intersecting pawn;
[MoveBody](https://github.com/threerings/clyde/blob/23758c9ff6b67e5c1b5b940e25670de09ef8efd2/core/src/main/java/com/threerings/tudey/server/logic/ActionLogic.java#L581)
selects the configured destination and server portal key.
[Tudey entry](https://github.com/threerings/clyde/blob/23758c9ff6b67e5c1b5b940e25670de09ef8efd2/core/src/main/java/com/threerings/tudey/server/TudeySceneManager.java#L984)
uses that key's tagged transform for position and facing.
[Vilya forced move](https://github.com/threerings/vilya/blob/af199f6eae7569b03ec1b74b585b615871f165f7/core/src/main/java/com/threerings/whirled/zone/client/ZoneDirector.java#L256)
leaves the old controller and invokes the same-zone scene move for these Haven maps.
The request's second integer is the cached scene version, not a portal/instance ID.

The pinned regular Town Square layout has AreaEntry 6071 targeting scene 2,
`from_town_square_west`; Bazaar has AreaEntry 3096 targeting scene 1,
`from_bazaar_east`. Their exact rectangle vertices and destination entries
1550/3874 were read with unchanged installed scene/configuration classes.
Both sensors have IntersectionStart mask 65535 and expansion 0. The installed
Character/PC/Default shape is a circle of radius .333333 with collision flags 4.
Server-only MoveBody configuration is stripped from client exports; the client's
SpawnActor(null) placeholder is expected stripping, not missing portal intent.
Only these two verified rectangle sensors are enabled in this release.

## Connected Haven (0.21.0)

The same pinned archive supplies regular Arcade scene 3 (`%town:m.haven_arcade`)
and Garrison scene 445 (`%town:m.haven_eastside`). Both decode as TownSceneConfig
in the installed reader and use the existing peaceful town actor/schema. Collision
packs are imported with the stock PC circle; no NPC handlers are executed.

| Source | Area entry | Destination | Destination tag |
| --- | --- | --- | --- |
| Town Square | 6071 | Bazaar | from_town_square_west |
| Bazaar | 3096 | Town Square | from_bazaar_east |
| Town Square | 3540 | Arcade | from_town_square_north |
| Arcade | 933 | Town Square | from_arcade_central |
| Town Square | 6514 | Garrison | from_town_square_east |
| Garrison | 3560 | Town Square | from_garrison_west |
| Bazaar | 2742 | Arcade | from_bazaar_west |
| Arcade | 934 | Bazaar | from_arcade_west |
| Arcade | 2194 | Garrison | from_arcade_east |
| Garrison | 3313 | Arcade | from_garrison_north |

Every source reference is `Portals/Portal`, with Scene ID and the original
ActionConfig.TaggedPortal argument. The installed common `area.dat` has
IntersectionStart mask 65535 and DefaultShape expansion 0; its four parameters
are Tag, Scene ID, Portal and Minimap Icon. None of these ten references override
sensor settings or add a condition. The original quadrilaterals include a
trapezoid and skewed edges; metadata format 2 preserves them. Format 1 rectangles
remain supported. `HavenCompatibility` compares all native source vertices,
destination placeable keys/tags/transforms and sensor settings, then compares
native circle intersection at outward edge/corner samples.

Buildings and doors without these standard direct links remain unavailable,
including guild/hall/laboratory services and Arcade dungeon gates. Loading their
geometry does not implement an NPC, guild, dungeon or quest service.

## Remaining character protocol research (not implemented)

The installed client routes TrainingSceneConfig through its dungeon controller,
not the peaceful town controller. Archived `Master/Static/446` at the same pinned
revision decodes as TrainingSceneConfig (version -1172213676). A combat milestone
needs a subscribed PartyObject before place entry and the native Dungeoneer actor;
the exact training party subtype, complete streaming fields and entry route still
need verification. DungeonInputFrame carries held attack in flag mask 4 and an
actionChanged timestamp. Proto Sword and Calibur configurations contain three-hit
normal combos and a 3000 ms charge. Their configuration alone does not establish
server timing, effects or damage. Preserve peaceful towns and do not activate
training NPC handlers or invent targets/rewards to demonstrate combat.

The stock trade invitation uses ProjectXSceneMarshaller service 6, method 4,
with Name and ConfirmListener. IncomingNotification.TradeRequest carries Name,
with key `TRADE_REQ:<name>`; SocialMarshaller method 5 responds with key, choice
(0 reject, 1 accept) and ConfirmListener. TradeDialogInfo adds Name[] and tradeOid
after its inherited DialogInfo fields. The subscribed TradeObject contains
tradeService, firstItems, secondItems, orderings, crowns, energy, revision,
accepted and completed after its object ID. TradeMarshaller methods 1–5 are
accept(revision), add(itemId,count), remove(itemId,count), crowns(amount) and
energy(amount), each with a confirm listener. Installed UI calls support transfer
quantities for add/remove, but contain no server-side arithmetic implementation.

Trade UI acceptance compares each side's accepted value to the current revision;
completion refreshes its summary without automatically closing. Dialog close
passes sceneDialogCreated to ProjectXSceneMarshaller method 1, so stale closes
must not cancel newer dialogs. Native bag listeners handle item-set removals;
the current server inventory codec will need removal events. Runtime trade OID
allocation, service registration, request/dialog lifecycle and atomic storage
remain unimplemented and require primary-agent design and regression coverage.
