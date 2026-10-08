# First-entry movement fix: 0.7.1

Reported at 0.7.0 (`d1e57ac`): intermittent missing walking animations and
movement/rotation stutter just after character creation, with relogging helping.
Two server defects were corrected in 0.7.1; graphical creation/reconnect checks
passed. Fixes are in `main` through `32629e2`. No client/launcher changes or schema
migration were needed.

## TCP baseline

A stock-reader networking fixture withheld scene input/acknowledgements for 45
updates. Version 0.7.0 emitted 44 repeated actor additions/model configuration
replacements: it kept reference zero until an input acknowledgement arrived and
fell back to full additions when its 40-entry history expired.

[Clyde's ClientLiaison](https://github.com/threerings/clyde/blob/23758c9ff6b67e5c1b5b940e25670de09ef8efd2/core/src/main/java/com/threerings/tudey/server/ClientLiaison.java)
uses the preceding sent tick for reliable transport. Tortodrone is TCP only.
`WorldSession` now retains the preceding successfully queued snapshot, independent
of input acknowledgements. Only initial/new actors are added; ordinary deltas
retain model configuration. Gear changes still replace it. Re-entry resets the
baseline, duplicate timestamps are skipped, and generation checks prevent old
ticks crossing subscriptions.

## Predicted inventory activity

Graphical testing still found frozen walking after the TCP correction. The stock
`TudeySceneController.controlledActorAdded` predicts directly on its first received
actor. Opening the entry HUD changes that snapshot to inventory activity 3.
Deltas omitted activity fields, inheriting that mutation. After HUD closing,
each authoritative snapshot restarted closing activity 4, blocking prediction
and overriding walking while server corrections moved the knight in jumps.

`TownMovement` now owns inventory activity/start time. Input flag 2 is inventory,
not shield. Opening retains a stable timestamp; closing starts once, lasts the
stock 889 ms, then returns to idle. Movement/facing pause during inventory/closing
and resume at the deadline. Every actor delta explicitly writes activity/start
fields, including unchanged idle, replacing polluted predicted state. Scene
re-entry resets activity. Unchanged model configs remain retained; combat is unsupported.

## Regression evidence

- `WorldSessionTest`: 45 updates without acknowledgements, stale/zero/fabricated
  acknowledgements, scene readiness, timestamp ordering, re-entry and cleanup.
- `TownMovementTest`: stable activity timestamps, blocked movement/facing, exact
  closing deadline including movement resuming within a tick, and reset.
- `WorldCompatibility`: deliberately mutates the first decoded actor to inventory
  before applying a delta. It fails with the previous serializer and passes now.
- `WorldConnectionCheck`: stock networking/reader/predictor checks creation and
  reconnect, retained models, delayed acknowledgements, inventory timing,
  equipment changes, currency polling and two-account movement/removal.
- `make build test`: both JARs rebuilt, 22 unit and three integration checks passed.

Run installed checks through `java scripts/compat/CheckClientWorld.java "/path/to/Spiral Knights"`.
They use temporary JAR copies, accounts and preferences and stop their server.
Network results alone cannot prove pixel rendering or animation.

## Graphical acceptance, 2026-10-08

Stock `ProjectXApp` read the installed game through a read-only mount. Server,
accounts, keys, deployment, home/preferences, caches and native extraction used
`/tmp`, with private loopback networking. Niri screenshots and target/focus-checked
XTest events were used. Accepted fresh/reconnect runs had no attached debugger.

- Fresh disposable knight `Gammathree`: 24 captures showed changing walking poses
  and continuous movement after entry HUD closing, without relogging.
- Stop/start/reverse and mouse facing passed. After opening/closing Current
  Equipment, 12 more captures showed continued walking animation.
- Same knight reconnected in a new stock JVM: 24 captures again showed animated
  walking across town.

Default vertical sync stalled native swaps on this desktop; disabling it only
in disposable preferences removed that stall. Virtual pointer devices could be
seen as gamepads; final runs excluded joystick devices while retaining GPU access.
Earlier attempts with exposed controllers/focus displacement were inconclusive.
These accommodations are outside the server fix.

Graphical simultaneous two-window animation and equip/unequip were not separately
accepted; their networking checks passed. All test clients/servers stopped; real
accounts, characters, keys, preferences and game files were preserved. Repeat
relevant graphical checks after future movement/scene changes; do not assume all
possible intermittent symptoms are ruled out. See [workflow](development.md) and
[next milestone](world-entry-plan.md).
