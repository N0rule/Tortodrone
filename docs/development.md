# Development guide

Tortodrone builds one executable Java 17 JAR with no external dependencies. GNU
Make, `javac`, and `jar` are the build toolchain; Java tools come from `PATH`.
Read [the contributor rules](../AGENTS.md) before making changes.

## Project scope

Develop the server and support normal username/password authentication only.
Do not add Steam or guest login, change clients or launchers, patch game archives,
or install client workarounds. Verify compatibility through server code and
synthetic clients. The current fixed test account will eventually be replaced by
persistent normal accounts; gameplay and world entry remain future work.

## Where code belongs

Packages live under `src/main/java/org/tortodrone/`:

| Package | Responsibility | Starting points |
| --- | --- | --- |
| `character` | Character choices, validation, atomic profile persistence | `Knight`, `CharacterStore` |
| `config` | Load defaults and validate listener, endpoint and logging settings | `ServerSettings` |
| `protocol` | Encode and decode verified wire schemas | `HandshakeCodec`, `SessionCodec`, `SessionDecoder` |
| `server` | Process startup, fixed runtime paths, listener and logging | `Server`, `ServerLog` |
| `server.network` | Secure exchange, normal authentication, subscriptions and invocations | `SecureHandshake`, `ClientSession`, `SessionLog` |
| `server.security` | Create, validate and preserve the RSA identity | `HandshakeKeys` |

For a new client operation, add its verified encoding/decoding to `protocol`,
route it in `ClientSession`, and keep behavior and persistence with the relevant
feature. Keep wire-specific parsing in codecs and sessions. Do not change the
client to compensate for a missing server feature.

`src/main/resources/server.properties` contains bundled defaults. First startup
copies them to `data/server.properties` beside the JAR. Existing configuration is
preserved. Settings do not choose where data lives: keys, accounts, logs, locks,
and connection exports always use the JAR's adjacent `data/` directory, even when
`--config` points elsewhere.

## Build and verify

```sh
make build             # build/tortodrone.jar
make server            # build and start inside build/
make test              # all checks
make test-unit         # character, protocol and socket/session checks
make test-integration  # temporary packaged-JAR deployments
make clean             # remove compiled output and JAR; preserve runtime data
```

Sources and resources are discovered recursively, so new packages need no build
list changes. Test classes have a `main` method and names ending in `Test`;
`make test-unit` discovers these outside the integration package. Tests mirror the
production package layout. Shared synthetic clients, character scenarios, and
wire helpers belong in `src/test/java/org/tortodrone/support/`.

Integration tests copy the JAR into temporary deployments, choose ephemeral ports,
and check normal/debug logging, release identification, JAR-relative state,
custom configuration, authentication, saves, restarts, keys, and startup errors.
Do not run tests against real saved characters or client installations.

## Version and logging

`VERSION` is the single release-version source. The build bundles it as
`version.txt`; startup and `--version` read that packaged value. Update `VERSION`
when preparing a release and rebuild. Moving the executable does not change its
version. The current initial release is `0.1.0`.

`ServerLog` formats console and file output as
`[HH:mm:ss] [thread/LEVEL]: message`. Main startup uses `ServerMain`; connection
threads use `Client-<number>` and shutdown uses `ServerShutdown`. Normal mode
prints operational INFO, WARN, and ERROR events. Debug mode includes DEBUG
protocol diagnostics. `SessionLog` keeps protocol details separate from normal
login/save events and warnings, while allowing synthetic tests to capture both.
Never log secrets, raw packets, or client argument values.

## Generated files and upgrades

`build/.build/` contains disposable compiled production/test classes and the
bundled version. `build/tortodrone.jar` is the distributable executable.
`build/data/` holds persistent settings, keys, profiles, connection exports, logs,
and the lock. These locations are ignored by Git. `make clean` also removes old
root `.build/` output and the old `tortodrone-server.jar`, but preserves data.

Upgrade by replacing the JAR while keeping `data/` beside it. Back up that directory.
For an old editable configuration, remove `data-directory` and add
`log-mode=normal` if desired; missing log-mode uses the bundled default. A custom
`--config` selects a settings file only. If old data lives elsewhere, stop the
server and move the complete directory beside the JAR without overwriting an
existing data directory. Preserve both keys and all account profiles.

Current behavior is described in [connection guidance](client-redirection.md)
and [server architecture](server-architecture.md). [Historical research](research/README.md)
records earlier experiments and must not be followed as current build/client
modification instructions. Ignored files under `local/` are not build dependencies.
