# Operational logging and identity scopes

[ServerLog](../src/main/java/org/tortodrone/server/ServerLog.java) writes UTF-8
operational records to `data/logs/latest.log`, beside the deployed server JAR.
Records contain local time, thread, severity and message. Structured events feed
the server window independently of its state snapshots; log text is not the source
of player or lifecycle state.

`log-mode=normal` records startup/readiness, resource summaries, connection and
account/character lifecycle, saves, warnings and errors. Startup reports the
actual OS/JVM, logical CPU count, measured heap usage/capacity and SQLite WAL/schema
information. `log-mode=debug` adds configuration/data paths, resource recovery,
storage timing, scene/protocol and connection details, and optional measured host
memory/process CPU information. Unavailable optional metrics use an unavailable
value; these measurements are not a capacity promise. GUI performance samples show
JVM heap, CPU and uptime plus resource/session/instance counts and database
diagnostics. See [server GUI](server-gui.md).

## Files, rotation and configuration

Edit the server's properties and restart to apply:

| Setting | Default | Accepted values |
| --- | --- | --- |
| `log-mode` | `normal` | `normal`, `debug` |
| `log-max-mib` | `10` | Integers 1–1024 MiB per file |
| `log-retention` | `5` | Integers 1–100 rotated files |

When the next record exceeds the configured threshold, `latest.log` becomes
`server-1.log`; older rotations shift toward `server-N.log` and the oldest retained
rotation is removed. Existing `latest.log` is appended at startup. Retention counts
rotated files in addition to the current file; records are written as complete
lines. Rotation is size based, not date based. The logger sanitizes newlines and
escape characters and bounds individual messages to 8192 characters plus a
truncation marker.

On filesystems supporting POSIX permissions, the log directory is set to `0700`
and the current log file to `0600`; rotated files retain their file permissions.
Other platforms use filesystem/OS permissions. Rotation protects log growth, not
privacy by itself. Restrict deployment and backup access.

## IDs have separate scopes

| Identifier | Scope and interpretation |
| --- | --- |
| Session ID | One accepted connection; live inspection uses `session <id>` |
| Account ID | Persistent account identity and ownership/role boundary |
| Global knight/world ID | Persistent knight identity used in world/session inspection |
| Roster ID | Account-local knight slot identity; never globally unique |
| Item stock ID | Extracted catalogue definition ID; may be ambiguous or zero |
| Item instance ID | Owned inventory instance; not a stock definition ID |
| Scene template ID | Loaded authored map definition |
| Scene instance ID | Live runtime instance of a template |
| Distributed object ID | Connection/protocol object identity; not a persistent database key |

The account manager and `knight info` show roster and world IDs separately.
`players` shows live scene template/instance locations, while `inspect` reads saved
state. A TCP session without authenticated or selected identity is not a confirmed
player. Do not infer online status from stored account or inventory rows.

## Storage diagnostics and privacy

SQLite uses WAL and the exact supported schema (currently 8); unsupported schemas are refused
without migration. Successful balance/item mutations commit before revision
publication. Diagnostics report operation durations/counts/failures rather than
SQL arguments or credential values. Keep `tortodrone.db`, its WAL/SHM state, keys
and accounts protected; see [administration backup rules](administration.md).
A log is not a database backup, and copying only a live database can miss WAL data.

Diagnostics count completed database-backed operations in this JVM, including
validation refusals in the failure count. They are not a count of SQL corruption
or a cross-process administrator event feed. Slow means at least 100 milliseconds;
the maximum duration is measured across observed operations.

Passwords, reusable authentication transforms, private keys and authentication payloads
must never appear in logs. The command handlers do not echo raw submitted command
lines; password and confirmation prompts are separate from command history.
Operational logs can still contain usernames, knight names, remote endpoints,
scene locations, paths, item references and administration outcomes. Treat logs,
GUI clipboard exports and support captures as private. The GUI's Clear view,
Pause, filter and Debug checkbox do not erase or reconfigure disk logging.

The public key is printed when a new keypair is generated for client connection
setup; it is public configuration, separate from the protected private key.
