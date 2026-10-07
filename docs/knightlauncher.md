# Local KnightLauncher Integration

The local experimental KnightLauncher variant has a **TortodroneServer** entry
and a **Play locally** button. It launches the real installed Spiral Knights
client with our separate deployment configuration. The emulator remains Java;
Python only prepares the isolated environment and starts/stops the processes.

## Run it

The patched launcher and server are already built on this machine:

```sh
cd /home/n0rule/Projects/TortodroneServer
python3 tools/knight_launcher.py
```

Click **Play locally**. The listener starts automatically inside the same
isolated environment. Close KnightLauncher when finished; this also stops its
game process and the listener. No separate `make run` is needed.

The game uses fixed synthetic test inputs. It currently reaches the initial
handshake and then waits for a response. A login/connection error is expected:
the server still only receives bytes and closes idle connections after 15 seconds.
The launcher remains open until you close it. Close the game before using Play
again. Real account credentials are unnecessary.

The terminal prints a fresh `local/research/client-run-*` directory containing:

- `listener.log`: incoming protocol bytes.
- `launcher/knightlauncher.log`: launcher-ready/client-started markers.
- `launcher-console.log`: launcher JVM diagnostics.
- `console.log` and `app/projectx.log`: game diagnostics.
- `commands.json`: prepared launch arguments.

All these files remain ignored. Preferences and logs are separate from the
Steam installation and are fresh for each wrapper invocation.

## What is implemented

CONFIRMED (HIGH): the fork uses KnightLauncher's existing main window, server
switcher, selected-server model, and Play event. A local mode is enabled by
`tortodrone.launchSpec`, supplied by the wrapper. Its server entry does not
depend on Flamingo or a downloadable game bundle.

Local mode bypasses normal update/mod/bootstrap startup and launches the
prepared JVM command through `ProcessBuilder`. Settings, mods, editors, updater,
and alternate launch controls are disabled in this experiment. The ordinary
upstream startup path is retained when local mode is absent, but was not
exercised against the Steam installation.

The wrapper reads the current Getdown classpath, creates a deployment overlay
with `server_host=127.0.0.1`, and supplies separate application/preferences
directories. Bubblewrap mounts the host filesystem read-only and provides a
writable scratch directory, isolated loopback network, and PID namespace.
KnightLauncher, the game, and the listener run inside it. Desktop access uses
the existing X11/XWayland session.

The original public key is retained for this receive-only test. A local test
key and secure response are the next server milestone; authentication is not
implemented by this launcher integration.

## Local source and build

The untouched reference is `local/upstream/KnightLauncher` at commit
`066ccdfc2f3a26bbc1a738cde28b4cbbf6edca67`.
The separate experimental checkout is `local/experiments/KnightLauncher`.
Its built artifact is `target/KnightLauncher.jar`.

Changed files, under `src/main/java/com/lucasluqui/`:

| File | Purpose |
| --- | --- |
| `launcher/TortodroneLocal.java` | Local server model, UI, client launch and shutdown |
| `launcher/LauncherApp.java` | Local startup path and reduced logging |
| `launcher/flamingo/FlamingoManager.java` | Register/select the local server |
| `launcher/ui/handler/LauncherEventHandler.java` | Route Play into the local launch path |
| `util/JavaUtil.java` | Remove unused `sun.misc.Launcher` import that prevents compilation on JDK 25 |

The complete patch, including the new file, is saved at
`local/research/knightlauncher-local.patch`. It passed `git apply --check`
against the untouched pinned checkout. The fork, patch, dependencies, and build
output intentionally remain outside the main repository's tracked files.
Preserve them locally: a fresh TortodroneServer clone alone does not contain
this experimental fork.

Maven 3.9.11 is under `local/tools/`, with its archive checksum verified against
Maven Central. Maven's dependency cache is local too. The upstream POM uses
the game JAR as a compile-time dependency; `lib/projectx-pcode.jar` is a
read-only input symlink. Inspection confirmed that the assembled launcher
contains no `com/threerings/projectx/` classes. Keep research artifacts local.

To rebuild the existing experimental checkout:

```sh
export JAVA_HOME=/home/n0rule/.local/share/PrismLauncher/java/java-runtime-epsilon
local/tools/apache-maven-3.9.11/bin/mvn -B -ntp \
  -f local/experiments/KnightLauncher/pom.xml \
  -Dmaven.repo.local="$PWD/local/tools/m2" validate
local/tools/apache-maven-3.9.11/bin/mvn -B -ntp \
  -f local/experiments/KnightLauncher/pom.xml \
  -Dmaven.repo.local="$PWD/local/tools/m2" package
make build
```

Run `validate` separately before the first package build: upstream installs its
bundled Discord/image4j dependencies in that phase. JDK 25.0.1 was used.
The upstream GPL-3.0 license and notices remain in the separate checkout.
No modified upstream source was added to TortodroneServer's tracked code.

## Validation

```sh
python3 tools/knight_launcher.py --smoke --seconds 40
```

This opens the actual launcher UI, activates the same Play button automatically,
and exits after the real client sends bytes. It also saves `launcher.png` in
the run directory. It checks launcher-ready/client-started markers in addition
to received traffic. Manual mode omits `--smoke` and stays open.

CONFIRMED (HIGH): the Maven package build and upstream test passed. The local
Play path launched `ProjectXApp`, which sent a complete 437-byte `SecureRequest`
to `127.0.0.1:47624`. The direct observation tool also passed after the shared
wrapper changes. Neither test reached authentication or gameplay.

## Maintaining the integration

This is a local patch on a maintained upstream project, not an upstream-supported
TortodroneServer release. For updates, preserve the current experimental tree
and patch, apply it to a separate updated checkout, review conflicts, rebuild,
and repeat the smoke test. Keep the read-only client mount and configuration
overlay even if upstream launch paths change. No catalog service or game files
need to be hosted for this local integration.
