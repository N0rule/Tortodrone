# Server desktop control window

The server can open a local Swing control window while using the same listener,
authentication, storage, simulation and command services as terminal mode.
[ServerOptions](../src/main/java/org/tortodrone/server/ServerOptions.java) parses
startup choices; [ServerWindow](../src/main/java/org/tortodrone/server/ServerWindow.java)
observes service snapshots and structured log events.

```sh
java -jar build/tortodrone.jar
java -jar build/tortodrone.jar --gui
java -jar build/tortodrone.jar --nogui
java -jar build/tortodrone.jar --nogui --config /path/to/server.properties
```

Without a mode flag, an available graphical desktop opens the window; a headless
environment starts terminal mode. `--nogui` selects terminal mode without desktop
initialization. `--gui` requires an available desktop and reports an error on
headless systems. Choose at most one mode flag, including repeated flags.
`--config` accepts one path and can be combined with either mode; it selects
properties, not a different deployment data directory. Data remains beside the
JAR. `--help` and `--version` work alone and return without opening a GUI.
If toolkit/window initialization fails, the server records a warning and falls
back to terminal mode. GUI flags do not change headless protocol or database rules.

## Overview and lifecycle

The overview shows version, lifecycle state, actual bound endpoint, connected
sessions, selected players and run duration. Recent significant warnings/errors
appear separately. Start, Stop and Restart use the shared lifecycle owner.
Stop/Restart ask for confirmation and report connected session count; stopping
drains server resources and disconnects clients. In graphical mode the stopped
window remains available so Start can run the server again. Restart waits for
shutdown before requesting Start. Configuration is loaded at process startup;
edit properties and relaunch the process to apply changed settings.

Closing the window asks to stop the server and exit. Accepting ends hosting;
there is no background-hosting tray mode. Terminal `stop` ends terminal hosting.
Account/data/key preservation and schema refusal rules remain unchanged.

## Players, scenes and performance

The Players table lists live session IDs, account IDs/names, selected knight names
and global IDs, scene locations, instance IDs, remote endpoints, durations and
state. Selection shows roster ID, template ID, connection time, last non-heartbeat
request time and client protocol build. Unauthenticated sessions have no verified
account/knight identity. These fields come from session snapshots, not parsed logs.

**Open knight in account manager** opens the selected saved knight in the local
account manager. **Account manager** opens it without a selected knight. Its
account → knight → inventory view supports searchable inventory/catalogue,
validated unequipped item grants, confirmed quantity/removal operations and
Add/Remove/Set balances. Unsupported mutations return the shared service refusal;
protected starter/permanent/equipped items cannot be removed or changed through
quantity operations. Passwords, roles and confirmed deletions remain local account
operations. No last-login or invented online field is inferred from database rows.

Scenes shows loaded templates separately from runtime instances and their player
counts/state. Performance shows measured heap used/max/committed, logical CPUs,
process CPU when available, JVM uptime, sessions, instances, resource counts,
database diagnostics and recent significant failure. It retains at most 120 metric
samples and shows their heap range. These are operational measurements, not
benchmark results or a guarantee of player capacity.

The desktop defaults to FlatLaf Dark; **View** switches between dark and light
themes in both the server window and account manager. The Performance tab keeps
the text statistics and adds one-second CPU, heap (MiB) and authenticated-player
charts. Select 1m, 5m, 30m or 1h; the in-memory history is capped at 3,600
samples. Hover a point for its timestamp and full measured value. Pause graphs
freezes their display while sampling continues; resuming refreshes from the
retained history. CPU gaps remain gaps when the JVM cannot report that metric.
Charts are not initialized in `--nogui` mode and samples are not written to the
database.

## Docked console and logs

The lower console has severity colors, case-insensitive text filtering, a Debug
visibility checkbox, Pause, Auto-scroll, Clear view, Copy and Open log directory.
It keeps 1500 recent events and consumes a bounded 4096-event queue; overflow drops
older queued events. Pause freezes rendering while collection continues, so paused
history can still roll over. Clear view clears retained UI history, not disk files.
Copy copies selected rows, or all currently visible rows when none are selected.
Auto-scroll follows newly rendered rows. Debug changes visibility only; it does not
turn on server debug logging. Unsupported desktop directory opening reports a
warning with the log directory; it does not prevent hosting.

The command field dispatches the same [local commands](commands.md) off the Swing
thread. Up/Down recalls 100 entries; Tab completes a uniquely matching command
name. History is in memory and includes invalid input. The GUI command field
cannot prompt for passwords or exact deletion confirmations; it reports that those
operations must use the account manager. Do not enter passwords into command text.
The local command field is not an in-game chat interface.

## Verification scope

Java 25 is required. Linux acceptance passed on a real niri/Wayland desktop:
live Swing windows, verified account rows, scene/performance views, structured
logs, commands, grants, balances, active-connection shutdown, start and exit.
Captures render the visible Swing content directly; they do not capture native
window decorations or prove compositor/focus behavior. The separate unchanged
stock-client rendering check passed its nine combat actions and scene round trip.
Windows/macOS execution remains unverified. See [validation and limits](administration-overhaul.md)
and [isolated graphical procedures](development.md); use disposable deployments,
accounts/preferences and stop only test processes.
