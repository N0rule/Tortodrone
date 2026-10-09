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
