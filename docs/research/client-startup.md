# Spiral Knights Client Startup

Historical client research. Earlier launcher commands and `local/` artifacts
are no longer active project requirements. The client/launcher is maintained
separately; see [client configuration](../client-redirection.md).

Research date: 2026-10-07. Installed build: `20261006114613`.
`CONFIRMED` means source, bytecode, configuration, or observation supports the
specific claim; it does not imply a successful official-server login.
Evidence identifiers refer to [research-sources.md](research-sources.md).

## Steam entry point

CONFIRMED (HIGH, S1): Steam's local `appcache/appinfo.vdf`, app ID `99900`,
launch entry `3` (`oslist=linux`), specifies:

```text
java_vm/bin/java -Dcheck_unpacked=true -jar getdown-pro.jar . client
```

The stored executable uses backslashes; the Linux installation contains the
corresponding slash-separated path. The app's installation directory is
`Spiral Knights`; no separate working-directory field is present.

LIKELY (HIGH): Steam uses the installation directory as working directory,
as required by these relative paths. We did not launch through Steam during
this investigation: that would allow its updater to write to the installation.
Per-user Steam launch-option overrides were not inspected.

## Launcher/bootstrap

CONFIRMED (HIGH, S1): The JAR manifest names
`com.threerings.getdown.launcher.GetdownApp`. `getdown.txt` supplies versioned
update URLs, ordered code resources, native resources, JVM options, and both
`class` and `client.class`. Both class settings name
`com.threerings.projectx.client.ProjectXApp`.

Getdown's content base is `http://gamemedia2.spiralknights.com/spiral/%VERSION%`;
its latest descriptor is `/spiral/latest/getdown.txt` on that host. These are
update endpoints, not the binary login server.

CONFIRMED (HIGH, R1): Invoking `ProjectXApp` directly with the installed code
and resources works on this Linux desktop and reaches the local TCP listener.
Our experiment bypasses Getdown, places writes in a separate app directory,
and prevents external network access. See [client-redirection.md](../client-redirection.md).

## Java runtime

CONFIRMED (HIGH, S1): `java_vm/release` reports Java `25.0.1`. The Java
executable is x86-64 ELF. Getdown requires `java_version = 25000000` and
specifies the Linux runtime download `/spiral/client/java_linux.jar`.
The bundled image has `java` and `keytool`, but no `javac`, `javap`, or `jar`.

For research and emulator compilation, a separate installed Microsoft
OpenJDK `25.0.1` JDK was found at
`/home/n0rule/.local/share/PrismLauncher/java/java-runtime-epsilon`.
It is an environment convenience, not a hardcoded build dependency.

CONFIRMED (HIGH, R3): our launcher experiment now uses separately installed
Adoptium Temurin 25 for all JVMs. The current `ProjectXApp` has class-file major
69 (Java 25); Java 21 cannot load it. This does not change Steam's original
entry point or bundled runtime. See [Java setup](../server-architecture.md).

## JVM arguments

CONFIRMED (HIGH, S1): Linux-relevant options in this installation's
`getdown.txt` are:

```text
-Djava.library.path=%APPDIR%/native
-Dorg.lwjgl.util.NoChecks=true
-Dsun.java2d.d3d=false
-Dappdir=%APPDIR%
-Dresource_dir=%APPDIR%/rsrc
-XX:-CreateCoredumpOnCrash
-XX:+SuppressFatalErrorMessage
--add-opens=java.base/java.lang=ALL-UNNAMED
--add-opens=java.base/java.util=ALL-UNNAMED
--enable-native-access=ALL-UNNAMED
-Dcrucible.dir=../crucible
```

There are no heap-size options in that descriptor. Extra JVM arguments from
other launchers or user overrides are UNKNOWN. Our test invocation is recorded
as `commands.json` in each ignored run directory; it additionally isolates
`user.home`, disables Getdown and native Steam initialization, and provides
only a synthetic username/password.

## Main class and arguments

CONFIRMED (HIGH, S1, C1, R1): The main class is
`com.threerings.projectx.client.ProjectXApp`.
Its `main` accepts positional username, password, knight, action, and action
argument, with corresponding system-property fallbacks. It also reads
`encrypted` and `sessionKey`, and recognizes an argument starting `+connect=`.
The latter's complete meaning is UNKNOWN; it is not treated as a host override.
Never put real account credentials in launch commands or our logs.

CONFIRMED (HIGH, C1): `main` sets `com.threerings.io.enumPolicy=ORDINAL`.
This matters to later serializer compatibility.

## Classpath

CONFIRMED (HIGH, S1): In order, the Linux code entries are:

```text
code/config.jar
code/projectx-config.jar
code/projectx-pcode.jar
code/lwjgl.jar
code/lwjgl-opengl.jar
code/lwjgl-openal.jar
code/lwjgl-glfw.jar
code/lwjgl-jawt.jar
code/lwjgl3-awt.jar
code/lwjgl-natives-linux.jar
code/lwjgl-opengl-natives-linux.jar
code/lwjgl-openal-natives-linux.jar
code/lwjgl-glfw-natives-linux.jar
code/commons-beanutils.jar
code/commons-digester.jar
code/commons-logging.jar
```

`code/getdown-pro-new.jar` is an update resource, not a code entry.
`tools/observe_client.py` reads the actual descriptor rather than duplicating
this list. It prepends only the experimental configuration directory.

## Native libraries

CONFIRMED (HIGH, S1): LWJGL 3 loads platform native JARs from the classpath.
`java.library.path` points to `native/` for other libraries.
The installed `libsteam_api.so` and `libdiscord_partner_sdk.so` are x86-64;
`libkeybd.so` is 32-bit i386. The latter was not necessary for our successful
initial-connection experiment. Its current use is UNKNOWN.

## Authentication bootstrap

CONFIRMED (HIGH, C1): `ProjectXCredentials` contains username/password ancestry
and fields for `sessionId`, `ticket`, `steamClient`, `region`, language, and
other partner metadata. The client contains a Steam partner director and
native Steam API bindings. This does not establish which fields are required
for every login mode.

CONFIRMED (HIGH, K1): KnightLauncher normally delegates its Steam path to
Steam app `99900`. Its standalone path invokes Getdown. Its code-mod path
invokes `com.lucasluqui.bootstrap.Bootstrap`, which eventually starts the
game and can create/remove `steam_appid.txt` based on the installation path.
We did not run it against the installation.

CONFIRMED (HIGH, A1): SKapsule is currently an Android port, not a Linux Steam
launcher. Native `sklauncher.c` creates a JVM, and `SkBootstrap` runs
`HeadlessGetdown`, constructs a separate game classloader, and reflectively
invokes `ProjectXApp.main`. Its optional Frenchpress integration supplies
Steam authentication through a shim. This verifies the entry point, not the
desktop Steam ticket lifecycle. Android flags and native shims are not copied
into our Linux launch command.

## Unknowns

- UNKNOWN: exact live Steam ticket acquisition/renewal and account-linking flow.
- UNKNOWN: current official login success, server health, and production DNS/IPs.
- UNKNOWN: all auxiliary HTTP calls after successful authentication.
- CONFIRMED: our test used a fresh preference directory, synthetic credentials,
  and no Steam session; it stopped after the first request, before authentication.
