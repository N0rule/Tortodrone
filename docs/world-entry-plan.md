# World entry in small milestones

Character creation currently saves a knight and returns to a reconnectable roster.
It does not begin the introductory missions or enter a game world. Version
`0.2.0` completes the server's saved-character management: create, edit, delete,
slot reuse and persistence. The installed client's networking classes accepted
these operations; graphical/world behavior remains a separate check.

## LAN foundations before world entry

Version `0.3.0` adds persistent normal accounts and separate saved rosters so LAN
players have their own identities. Version `0.4.0` adds live
roster updates across connections belonging to the same account, ordered output
queues and subscription cleanup.
Version `0.5.0` adds SQLite account/character persistence, local desktop account
administration, running-server terminal commands and stored Admin flags. These
foundations do not acknowledge character selection or mark introductory missions complete.

## Next: selection and one starting area

1. Verify the installed client's selection path and the full selected-player,
   inventory, location and scene schemas using read-only class inspection and
   synthetic fixtures. Do not make the client advance by reporting selection
   success before the required objects/events exist.
2. Establish globally unique world character identities before sharing a scene;
   current knight counters are account-local. Define migration and save recovery
   before exposing existing characters to other accounts. Then implement the
   selected character's identity, saved appearance and minimum
   starter inventory. Preserve account ownership and reject selection of a missing
   or other account's character.
3. Implement a single compatible starting scene and the subscriptions/transitions
   needed to enter it. Use existing game resources through server references;
   do not copy game assets into this repository or patch the client.
4. Verify entry, disconnect/reconnect, and returning to the same character/location
   before adding movement, combat or mission progression.

The first world checkpoint should be one small starting area. Introduction
missions need scripted events, NPCs, combat, rewards and persisted progress, so
implementing the entire sequence alongside first world entry would obscure
protocol and state failures. Investigate a server-controlled development spawn
using the client's existing selection/location flow. Any route around the intro
must use verified server state; no client patches or guessed mission flags.

## Later: introductory missions

Add one mission at a time after the scene and progression protocol are verified.
Define how completed steps, rewards and reconnects are persisted before claiming
that a mission is playable. Keep normal account authentication as the only login
path. Intro missions, tutorial bypass, world entry and gameplay are not yet
implemented in this release.
