# Town interactions and solo interiors (0.22.0 scope)

This milestone extends the existing four Haven towns with verified player interactions and three private interior areas. Its protocol and map data are derived from the unchanged installed client and pinned scene exports. It does not add a general party system or new quest content.

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

The four bundled town areas are Town Square (scene 1), Bazaar (2), Arcade (3), and Garrison (445). The additional stock areas are Advanced Training (294), Laboratory (437), and Hall of Heroes (449):

| Town entry | Interior | Stock exit |
| --- | --- | --- |
| Town Square, area 3922 | Advanced Training, scene 294 | Area 1526 to Town Square |
| Garrison, area 3770 | Laboratory, scene 437 | Area 945 to Garrison |
| Garrison, area 3568 | Hall of Heroes, scene 449 | Area 2812 to Garrison |

The interior exports come from archive commit `ae49faf06788dd83b35a959306c9fe4c25688004`, scenes 294, 437, and 449. Each knight receives a private instance keyed by owner and interior template. The stock client expects native dungeon-party transport during interior loading, so the server supplies a one-knight `LevelPartyObject` with its native party identifier and exit operation. This supports loading and returning from a solo interior; it does not implement social parties, invitations, or shared multiplayer dungeon groups. At most 360 private interiors are active at once. Exiting or disconnecting releases that owner's instance for reuse.

Interior controllers also require health/status entries and an elevator return destination. These peaceful scenes use a normalized living health value of 1/1, empty status, and an empty gate summary linked to the existing Haven town. This keeps the native HUD alive and labels the exit; it implements no combat statistics, gate floors, matchmaking, or rewards.

The server currently uses TCP only. On the first interior entry, a native `UpdateThrottleMessage` raises that connection's client send limit to 100 messages per second. Native unreliable input otherwise falls back to the same TCP queue as travel and keepalive requests, behind the client's default ten-message limit. The stock acknowledgement is decoded without granting any service or changing player data. This is this server's bounded TCP transport policy, not an implementation of stock UDP or its separate flood guard.

The repository's `defaults/maps/` contains all seven packs: the four towns and the three interiors, together with `catalog.properties` and the `scene-source.properties` provenance manifest. Those compiled scene packs are committed; no game meshes, textures, audio or installed JARs are. Ordinary `buildServer`, `prepareDefaults` and `prepareMaps` copy all seven scenes into `build/defaults/maps/` with their matching portal metadata, needing no game path, archive or extra source. Custom files are preserved; a file the source published earlier is upgraded in place, while an owner-edited pack keeps its bytes instead of failing preparation.

To enable these rooms beside the local server JAR, nothing extra is needed: an ordinary build already prepares all seven scenes from the checked-in source. Re-deriving the interior packs from a stock client is maintainer work with `./gradlew prepareInteriors -PgameDir="/path/to/Spiral Knights"`, which republishes them into `defaults/maps/`; `-PinteriorsOutput` selects a separate staging tree and `-PsceneCache` a reusable source cache. The server loads `build/defaults/maps/` automatically unless a custom `build/maps/` overrides it.

To prepare a fresh local tree directly, use a new output path and a reusable source cache:

```sh
java scripts/StockTools.java interiors "/path/to/Spiral Knights" \
  /tmp/tortodrone-haven-seven-maps /tmp/tortodrone-scene-cache
```

The output directory must not already exist. The exporter copies the checked-in town packs verbatim, fetches and checks the three pinned archives into the source cache, re-derives the three interior packs from the stock client, then verifies the stock scene configuration and native portal routes. `build/` is ignored local output; inspect and deploy the generated tree without adding it to Git.

## Outside this milestone

NPC behavior, combat, quests, shops, crafting, gates, dungeon rewards, and newly invented rewards are not implemented. Guild Hall and Private Training Hall remain excluded. Friends, social parties, mail, auctions, chat channels, and multiplayer group interiors remain later work. The bundled areas and verified stock objects do not imply support for those systems.

## Required validation

Run `./gradlew build`, then the installed-reader and disposable network checks against the prepared seven-map tree:

```sh
java scripts/compat/CheckClientWorld.java "/path/to/Spiral Knights" \
  /tmp/tortodrone-haven-seven-maps
```

The optional map argument runs the stock-client interior travel check in a disposable server deployment. Graphical acceptance is a separate step: launch unchanged stock clients with disposable preferences, cache, home, and server accounts; keep installed resources read-only; and exercise the native client calls through its run queue. Wait for the stock loading fade to finish before inspecting screenshots. Network and stock-reader fixtures do not prove rendering or animations.

The isolated two-client graphical check on 2026-10-10 verified all seven areas rendering with their HUDs/minimaps, the sitting pose, peer Attack effect, inspection preview, and both trade views. All three native party exits received their destination scene models in about 1.2 seconds after the TCP fallback correction. Focus-checked keyboard input verified movement after returning to town and automatic walking entry/exit between Garrison and Hall of Heroes. Other portal routes are covered by the stock network fixture; this does not claim graphical coverage of every emote animation or every portal, or of the excluded systems.

For 0.26.1 on 2026-10-11, the unchanged installed-client suite passed with
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
