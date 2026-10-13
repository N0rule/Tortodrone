# Town interactions and solo interiors

This milestone extends the existing four Haven towns with verified player interactions and three private interior areas. Protocol behavior is derived from the unchanged installed client. Current checked-in layouts come from official gameplay captures dated 2026-10-12; the seven capture hashes are pinned in `defaults/maps/scene-source.properties`. It does not add a general party system or new quest content.

## Player emotes

The server recognizes the 68 names in the stock `EmoteConfig` catalog and uses their native movement, target, duration, activity, and transient-effect metadata. Twenty entries have animated actor activities; the remaining entries are chat-only or configured poses. Chat output uses the stock localization message keys, including targeted forms only for targetable actions. The server resolves a target against current scene occupants and emits its canonical knight name. Sit and Sleep use their configured persistent pose activities; Sit has no stock chat format and returns to its configured end activity when movement interrupts it.

An emote cannot be used before scene entry or after departure. Targeting a missing or self knight fails before changing the actor's activity. The active scene owns the emote state, so actions and events stay with that scene instance.

Attack, Follow, Helpme, and Fallback trigger the stock client-local transient effects from their animation configuration. Their effects require no additional server actors.

## Player trade

Trade invitations are limited to two authenticated knights in the same scene. Accepting creates a revisioned offer dialog; each edit invalidates previous acceptances, and both participants must accept the same current revision. Rejecting or leaving cancels the invitation or trade. Offers are limited to valid quantities of owned, unequipped, unbound, non-starter items and available Crowns or Energy.

The notification's Hide button dismisses its client popup while leaving the request pending; the stock client sends no trade response for Hide. Accept and Reject use the native response choices 1 and 0. Invitations expire after 60 seconds and incomplete trades after five minutes. Inventory changes or authentication revocation cancel an incomplete trade. Received stacks retain separate identities rather than merging with existing recipient stacks; capacity checks account for these slots.

In the current installed client, incoming requests produce a chat notice and a
trade notification icon in the HUD. Click that icon to open the native popup:
**No thanks** rejects and **Let's trade!** accepts. This client does not open the
popup automatically when the request arrives. Closing the popup leaves the
invitation pending.

Version 0.26.1 fixes the hidden notification controls. The stock HUD requires
`PlayerObject.flags` bit 32 (the native tutorial-completion/HUD gate). The server
now includes that bit in selected-world flag updates to match its existing direct
entry into Haven, and preserves it across GM and inspection-privacy changes.
This is a client UI flag for the intro bypass; it writes no mission completion,
quest reward, or database schema change. Wire evidence: installed
`com.threerings.projectx.client.hud.a.bv(boolean)` returns after hiding the
controls when `PlayerObject.bR(32)` is false; notification director
`com.threerings.projectx.social.client.m.Yq()` posts chat, and `ce(Class)` opens
the popup from the icon. Installed `projectx-pcode.jar` SHA-256:
`391a51cef0446f55d461bdb8f942ca9dba44e6e4138acdcd69ab2024297f543c`.

When both sides accept, item and currency transfer for both owners runs in one SQLite `BEGIN IMMEDIATE` transaction. The server rechecks account ownership, inventory revisions, balances, and recipient capacity in that transaction. A failure rolls back both sides; trade completion does not partially deliver an offer.

## Inspection privacy

Inspection is available for a knight who is online and ready in the same scene. The response includes the knight's appearance and equipped gear, not their inventory. Each account has a persistent hide-inspection preference. When it is enabled, other players receive the visible costume/appearance representation instead of full equipped item details.

The preference is stored in the existing metadata table under SQLite schema 8 and is checked against the authenticated account revision. It defaults to visible, is isolated per account, and requires no schema migration. Unsupported database schemas remain refused without modification.

## Seven prepared areas

The four bundled town areas are Town Square (scene 1), Bazaar (2), Arcade (3), and Garrison (445). The additional stock areas are Advanced Training (294), Laboratory (30893), and the four-star Hall of Heroes variant (318):

| Town entry | Interior | Stock exit |
| --- | --- | --- |
| Town Square, area 3922 | Advanced Training, scene 294 | Area 1526 to Town Square |
| Garrison, area 3770 | Laboratory, scene 30893 | Area 945 to Garrison |
| Garrison, area 3568 | Hall of Heroes, scene 318 | Area 2812 to Garrison |

The current interior layouts are scenes 294, 30893, and 318; their hashes and capture versions are pinned in the source manifest. Each knight receives a private instance keyed by owner and interior template. The stock client expects native dungeon-party transport during interior loading, so the server supplies a one-knight `LevelPartyObject` with its native party identifier and exit operation. This supports loading and returning from a solo interior; it does not implement social parties, invitations, or shared multiplayer dungeon groups. At most 360 private interiors are active at once. Exiting or disconnecting releases that owner's instance for reuse.

Interior controllers also require health/status entries and an elevator return destination. These peaceful scenes use a normalized living health value of 1/1, empty status, and an empty gate summary linked to the existing Haven town. This keeps the native HUD alive and labels the exit; it implements no combat statistics, gate floors, matchmaking, or rewards.

The server currently uses TCP only. On the first interior entry, a native `UpdateThrottleMessage` raises that connection's client send limit to 100 messages per second. Native unreliable input otherwise falls back to the same TCP queue as travel and keepalive requests, behind the client's default ten-message limit. The stock acknowledgement is decoded without granting any service or changing player data. This is this server's bounded TCP transport policy, not an implementation of stock UDP or its separate flood guard.

The repository's `defaults/maps/` contains all seven packs: four towns and three interiors, with `catalog.properties` and the `scene-source.properties` provenance manifest. These scene packs are committed outside the JARs; game meshes, textures, audio and installed JARs are not. Ordinary `buildServer`, `prepareDefaults` and `prepareMaps` prepare all seven scenes offline. Custom files are preserved; only known prior default generations can be upgraded in place, while owner-edited packs keep their bytes.

An ordinary build prepares all seven scenes from the checked-in source. To derive a new candidate set, use `prepareHaven` with the installed client, all seven local captured scene binaries, and a fresh output path. It verifies each capture hash and writes staging output only; it does not republish into `defaults/maps/`. The server loads `build/defaults/maps/` unless a custom `build/maps/` overrides it. See [map preparation](maps.md) for the command and safeguards.

There is no map-specific task in `buildServer`; it prepares the checked-in source only. Maps contain geometry and verified portal metadata, not NPCs, shops, missions, combat behavior or rewards. Private guild scene 528 is excluded.

## Outside this milestone

NPC behavior, quests, shops, crafting, gate progression, dungeon rewards, and newly invented rewards are not implemented. Interior combat has the limits recorded in [town player behavior](town-player-behavior.md). Guild Hall and Private Training Hall remain excluded. Friends, social parties, mail, auctions, chat channels, and multiplayer group interiors remain later work. The bundled areas and verified stock objects do not imply support for those systems.

## Required validation

Run `./gradlew build`, then the installed-reader and disposable network checks against a prepared seven-map tree when validating map behavior:

```sh
java scripts/compat/CheckClientWorld.java "/path/to/Spiral Knights" \
  /tmp/tortodrone-haven-seven-maps
```

The optional map argument runs the stock-client interior travel check in a disposable server deployment. Graphical acceptance is a separate step: launch unchanged stock clients with disposable preferences, cache, home, and server accounts; keep installed resources read-only; and exercise the native client calls through its run queue. Wait for the stock loading fade to finish before inspecting screenshots. Network and stock-reader fixtures do not prove rendering or animations.

The isolated two-client graphical check on 2026-10-10 verified the then-current seven scene packs rendering with their HUDs/minimaps, the sitting pose, peer Attack effect, inspection preview, and both trade views. It predates the 2026-10-12 map capture replacement and does not validate the current scene binaries. All three native party exits received their destination scene models in about 1.2 seconds after the TCP fallback correction. Focus-checked keyboard input verified movement after returning to town and automatic walking entry/exit between Garrison and Hall of Heroes. Other portal routes were covered by the stock network fixture; this does not claim graphical coverage of every emote animation or every portal, or of the excluded systems.

The following 0.26.1 validation record is from 2026-10-11, before the current map capture
replacement; it is not validation of the refreshed scene packs. The unchanged installed-client suite passed with
`java scripts/compat/CheckClientWorld.java "/home/n0rule/.local/share/Steam/steamapps/common/Spiral Knights"`.
This includes the native flag decoder checks, two-player offer revisions and
atomic item/currency transfers, rejection, dialog cancellation, and disconnect
cleanup. An isolated Linux graphical recipient with a second native network
client verified the visible trade icon, real **No thanks / Let's trade!** button
actions, notification removal, and trade-dialog rendering after the loading
overlay finished. Throwaway Java probes were run with
`java /tmp/CheckTradeRendering.java "/home/n0rule/.local/share/Steam/steamapps/common/Spiral Knights" /tmp/tortodrone-trade-final-0261`;
logs and captures remain in that output folder. Both launchers cleaned up only
their disposable processes/data. This check does not establish Windows/macOS
rendering or automatic popup opening, which this installed client does not do.

The first full build exposed a callback-only admin test reader rejecting class
codes introduced inside inventory payloads it had skipped. Its helper now skips
those unrelated events while retaining callback identity, method, status, and
reason assertions; the native F3 authorization/grant checks also passed.
The final `./gradlew build` passed on Java 25 (74 actionable tasks), and
`git diff --check` passed.
