# Client Redirection

## Preferred approach

Project direction: use KnightLauncher as the user-facing launcher and
keep TortodroneServer focused on the server. KnightLauncher starts the original
Spiral Knights client; it does not replace the game client or its protocol.
The existing Python launcher remains a development observation tool.

The implemented integration is a small, local KnightLauncher adaptation under
`local/`: an explicit TortodroneServer entry, separate scratch preferences/logs,
and the verified configuration overlay below. Its Play path has reached the
local listener with the full client. See [knightlauncher.md](knightlauncher.md)
for the manual launch command, source location, and build instructions.

CONFIRMED (HIGH, R1): put a separate `deployment.properties` resource before
the installed `config.jar` on the classpath. Copy the installed resource only
under ignored `local/extracted/`, change `server_host` to `127.0.0.1`, and
retain its confirmed TCP/UDP port `47624`. No JAR patching is required.

The full `ProjectXApp` has been launched this way and sent its 437-byte
`SecureRequest` to TortodroneServer. A separate property-only test showed
that `-Dserver_host` and `-Dserver_ports` do **not** override this loader.
Evidence and pinned upstream references: [research-sources.md](research-sources.md).

## KnightLauncher support

CONFIRMED (HIGH, K1): there are two distinct mechanisms:

1. Advanced connection settings: `game.endpoint`, `game.port`,
   `game.publicKey`, `game.getdownURL`, and `game.getdownFullURL`.
   `SettingsEventHandler.saveConnectionSettings()` saves them;
   `SettingsManager.applyConnectionSettings()` modifies deployment values,
   writes a temporary properties file, then invokes `jar uf code/config.jar`.
   It is called for the Official selection while applying game settings.
   The method explicitly notes that the JAR tool is no longer bundled.
2. Third-party catalog/install support: `FlamingoManager` obtains server
   metadata from its configured `/server-list/` service. `Server` models
   deployment URLs/methods, versions, enablement, beta/access metadata, and
   presentation data; the model itself has no game host/port fields.
   Installations use `thirdparty/<sanitized-name>/`. Non-official launch
   paths invoke `ProjectXApp` using those installations.

The README states a policy of withholding third-party support while official
servers remain online absent permission/legal allowance. Source nonetheless
contains catalog and launch machinery, with availability also controlled by
service metadata, enabled state, and beta selection. We did not enable hidden
features, contact the catalog, or equate code existence with an available
public service.

CONFIRMED (HIGH): KnightLauncher is a useful research reference but its
in-place connection-setting writer is unsuitable for the read-only Steam
installation. Some direct launch branches also retain older `java/bin/java`
paths and LWJGL 2-era classpaths, despite other branches being updated.
Do not assume all branches support the current Java 25/LWJGL 3 installation.

CONFIRMED (HIGH): the copy under `local/experiments/KnightLauncher` now uses
the same configuration-overlay approach. The standalone research launcher
remains useful for protocol tests.

CONFIRMED (HIGH, K1): the server switcher's Add Server icon is explicitly hidden
with `setVisible(false)`, and its click handler only displays an informational
dialog. Revealing that icon alone will not provide local server registration.
The current third-party catalog comes from Flamingo, and its installation path
expects downloadable client bundles. A local entry needs an actual local launch
path that uses the user's installed files without downloading/distributing a
proprietary bundle. Serving a replacement Flamingo catalog is unnecessary for
this first integration.

## Required configuration and exact steps

Requirements: Linux, Python 3.9+, GNU make, JDK 17+ for compilation,
`bubblewrap` (`bwrap`), an active X11/XWayland desktop, and this installed client
with its Java 25 runtime. The listener itself has no client dependency.

From the repository root:

```sh
# Set JAVA_HOME to an installed JDK if javac is not on PATH.
export JAVA_HOME=/home/n0rule/.local/share/PrismLauncher/java/java-runtime-epsilon
make test
python3 tools/observe_client.py
```

The JDK path above is the one found on this machine; another JDK 17+ works for
the emulator. The observation tool uses `local/client/java_vm/bin/java` at
runtime. It prints the ignored directory containing the evidence and reports
success only after the real app sends bytes to our listener.

CONFIRMED (HIGH): the tool performs these operations:

- Reads the installed deployment resource and ordered Linux Getdown code entries.
- Creates the overlay under `local/extracted/client-run-*`, with the game host
  changed to loopback. The original public key is retained for this receive-only run.
- Creates a fresh scratch application and preference home under
  `local/research/client-run-*`; code/resources/native links point to read-only
  installed files. The copied Getdown descriptor and scratch marker stay local.
- Starts bubblewrap with the host filesystem read-only, a writable scratch
  mount and temporary directory, and a new network namespace with loopback.
  The listener and client run together inside that namespace; neither can
  reach production servers through the host network.
- Launches the main class directly, bypasses Getdown, disables native Steam
  initialization, removes inherited Steam/Java injection variables, and uses
  fixed synthetic credentials with a fresh preference home.
- Stops the client and listener after bytes arrive or the deadline expires.
  Both processes are cleaned up; no server is left running on the host.

No Steam launch options, hosts files, game JARs, or installed configuration are
changed. The tool refuses to fall back to an unprotected launch when bubblewrap
or the desktop prerequisite is missing. It opens a brief game window.

## Expected destination

CONFIRMED (HIGH): TCP `127.0.0.1:47624` inside the experiment's network
namespace. A separately started host listener will not receive this isolated
client. UDP `47624` remains in the overlay but is never reached before bootstrap.

For synthetic tests of the standalone listener only:

```sh
make run
```

This binds the host's loopback address. It is not the command used by the
isolated full-client observation tool.

## Limitations

- CONFIRMED: changing the destination is sufficient to receive the initial
  request; it is insufficient to finish secure authentication with the official key.
- UNKNOWN: a complete local authentication/bootstrap response. Next use a locally
  generated keypair and overlay public key, never an official account/session.
- CONFIRMED: the tool is deliberately tied to the verified `47624` deployment
  and refuses a changed configured TCP port until it is reviewed.
- UNKNOWN: operation on headless Linux or a desktop without XWayland. This
  experiment passed on the supplied desktop and current installed build.
- Logs include raw protocol data and stay ignored, even with synthetic inputs.

## Alternatives evaluated

| Option | Result |
| --- | --- |
| Upstream KnightLauncher environment/settings | Rewrites `config.jar`; not used. Our local adaptation uses the separate overlay instead |
| JVM endpoint properties | Tested; no effect on deployment accessors |
| Separate client configuration | **Verified:** classpath resource overlay |
| Main-class command-line endpoint | No general host/port option confirmed; `+connect=` not assumed equivalent |
| Launcher dependency injection | Possible but unnecessary; would add client-version coupling |
| Hosts-file redirection | Unnecessary, machine-wide, and cannot solve the key mismatch |
| Proprietary binary patch | Unnecessary; no client class or JAR was modified |
