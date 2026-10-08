# First-creation movement investigation

Checkpoint reviewed on 2026-10-08: clean `d1e57ac`, release 0.7.0. The reported
symptom is intermittent missing movement animation and movement/rotation stutter
immediately after character creation; relogging helps. Graphical reruns of the
0.7.1 corrections show animated walking after creation and reconnect. The checks
and their desktop limitations are recorded below.

## Reproduced defect and 0.7.1 correction

The earlier corrections remain present: a nonzero verified movement limit,
timestamped future input, elapsed-time movement, readiness after `enteredPlace`,
and omission of unchanged actor configuration in ordinary deltas. The baseline
unit/integration suite and installed-client creation/reconnect checks passed.

An expanded real networking fixture then delayed all scene input/acknowledgements
for 45 updates, slightly over two seconds. On 0.7.0, its installed reader observed
44 repeated actor additions and 44 new model configuration objects. The server
used reference zero until input acknowledged a retained timestamp, and reverted
to full actor additions when the acknowledged timestamp aged out of its 40-entry
history. Scene loading can delay that input. Replacing these configurations is a
plausible contributor to animation resets; it does not prove the reported symptom's
complete cause.

[Clyde's ClientLiaison](https://github.com/threerings/clyde/blob/23758c9ff6b67e5c1b5b940e25670de09ef8efd2/core/src/main/java/com/threerings/tudey/server/ClientLiaison.java)
uses the last sent tick as the next reference when datagrams are unavailable.
Tortodrone supplies TCP only, with one ordered writer and a bounded queue.
0.7.1 therefore retains one successfully queued actor snapshot as its baseline.
Only the initial snapshot adds existing actors. Later updates retain unchanged
model configuration, while genuine equipment changes still replace it. Incoming
scene acknowledgements do not rewind this reliable stream. Resubscription starts
a fresh baseline; duplicate timestamps and ticks from a previous subscription
generation cannot create an unusable reference.

No changes were made to movement speed, appearance, accounts, items, currencies,
database schema, server keys, the installed game or launcher.

## Inventory activity retained in the first snapshot

Graphical testing found a second independent protocol defect. Clyde's
`TudeySceneController.controlledActorAdded` creates its predictor directly on the
first received actor. Opening the stock entry HUD can therefore change that
snapshot to inventory activity 3. Later deltas previously omitted both activity
fields, so they inherited the locally predicted state. Closing the HUD then
repeatedly started activity 4 from each new authoritative snapshot. This activity
blocks movement prediction and overrides the walking animation; server position
corrections still moved the knight, producing a fixed pose and jumps.

0.7.1 now owns these activities on the server. Input flag 2 means **use inventory**,
not shield. Opening retains one activity-start timestamp; closing starts activity
4 once and returns to idle after the stock 889 ms duration. Movement and rotation
pause during these activities, matching the stock advancer. Scene re-entry clears
the activity. Every actor delta explicitly supplies activity and start time,
including unchanged idle values, so local prediction cannot pollute the reference.
Unchanged model configurations are still retained. Combat remains unsupported.

## Automated evidence

`WorldSessionTest` uses disposable SQLite state and independently reads queued
frame headers. It covers 45 updates without acknowledgements, then stale, zero
and fabricated acknowledgements, positive/ordered timestamps, scene readiness,
resubscription and controller cleanup. Run it through `make test`.

`java scripts/compat/CheckClientWorld.java` uses a temporary copy of both JARs, two disposable
normal accounts and isolated preferences. It reads unchanged installed-client
classes. Its networking fixture now reconstructs actors from their actual scene
references instead of applying a delta to a fabricated actor. Creation and
reconnect verify retained model reference identity during delayed input,
movement/facing, genuine equipment model changes followed by stable references,
live currency polling, peer actor additions, movement-animation flags, and peer
removal on disconnect. The temporary server is stopped in `finally`.

These checks establish protocol compatibility and model retention. They do not
exercise pixel rendering or show that a walking animation actually plays.

The installed reader fixture also deliberately changes the first actor to
inventory activity before applying the next delta. It fails against the previous
serializer and passes with explicit authoritative activity fields. The networking
fixture checks stable inventory timestamps, blocked movement, the closing activity
and its exact idle deadline on both creation and reconnect. `TownMovementTest`
checks movement resuming partway through a tick at that deadline.

## Graphical acceptance and next milestone

Testing uses the installed ProjectXApp directly, without changing the client or
launcher. The server JAR, accounts, keys, deployment configuration, preferences,
downloaded scene cache and native extraction directory live under `/tmp`.
The game installation is mounted read-only, with private loopback networking.
On niri, `_JAVA_AWT_WM_NONREPARENTING=1` is set. Native XTest input checks the target
process window and focus; screenshots come from `niri screenshot-window`.

An initial run stalled in native buffer swaps at roughly one frame per second.
Disabling vertical sync through the disposable client preferences removed that
desktop rendering stall; it is not a server correction. Physical/virtual joystick
devices are excluded from the final temporary environment because this installation
can mistake virtual pointer devices for gamepads. GPU devices remain available.
Read-only debugger inspection was used to trace controller and animation fields;
the fresh-creation capture uses the stock process without a debugger.

Accepted graphical reruns on 2026-10-08:

- Fresh `Gammathree`, created through the stock creator with disposable account
  `FreshThree`: 24 window captures show changing walking poses and continuous
  displacement immediately after closing the entry HUD, without relogging.
- Stop/start and reverse direction, mouse facing on both sides, then open and
  close Current Equipment: walking poses continue in a further 12 captures.
- Reconnect to `Gammathree` in a new stock JVM with joystick devices excluded:
  another 24 captures show animated walking across town. No debugger was attached.

Earlier attempts with exposed virtual controllers and displaced mouse events were
inconclusive for input readiness. Default vertical-sync performance on this desktop
is also outside the server acceptance result. Two-account synchronization and
equip/unequip pass the networking fixture; simultaneous two-window animation and
graphical equipment changes have not been independently accepted. All temporary
clients and servers were stopped. Real accounts, characters, keys and preferences
were preserved.

Repeat fresh creation with both a cold and warm town load, immediately move and
face the mouse, stop/start, change direction, and equip/unequip a starter item.
Compare with selecting an existing knight after relogging. Check two players
walking and turning together. Record whether animations remain active and whether
either view stutters. Leave the test server stopped.

Once that entry checkpoint is confirmed stable, the next concrete feature is
**server-owned town position persistence**. Save only finite, walkable town
coordinates and facing, guarded by account authentication revision and knight
ownership. Use a transactional schema upgrade with a spawn default for existing
knights; checkpoint during play and flush on disconnect/clean shutdown. Verify
restart, revocation/deletion, invalid saved coordinates and concurrent equipment/
currency edits on disposable data. Unknown scenes should fall back to the known
town spawn. Do not accept positions supplied by the client or invent mission
completion flags. A clean return to the picker follows as a separate protocol
milestone, then one verified portal/connected scene before combat.
