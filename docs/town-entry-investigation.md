# First-creation movement investigation

Checkpoint reviewed on 2026-10-08: clean `d1e57ac`, release 0.7.0. The reported
symptom is intermittent missing movement animation and movement/rotation stutter
immediately after character creation; relogging helps. Its graphical resolution
has **not** been confirmed.

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

## Graphical acceptance and next milestone

Use a disposable deployment/account and the separately maintained launcher.
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
