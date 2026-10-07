# Research Sources

Inspected on 2026-10-07. `CONFIRMED` is scoped to the named evidence;
`LIKELY` is an inference; `UNKNOWN` has not been established. Confidence is
HIGH/MEDIUM/LOW. No upstream source is copied into the tracked implementation.

## Repository snapshots and licenses

These checkouts are ignored under `local/upstream/`. Only SKapsule's useful
bootstrap submodule was initialized; unrelated native submodules were not.
Fresh shallow clones were sufficient; no existing checkout was overwritten.

| Repository | Inspected commit | License inspected |
| --- | --- | --- |
| [KnightLauncher](https://github.com/lucasluqui/KnightLauncher/tree/066ccdfc2f3a26bbc1a738cde28b4cbbf6edca67) | `066ccdfc2f3a26bbc1a738cde28b4cbbf6edca67` | GPL-3.0, `LICENSE` |
| [spiral-knights-headless-client](https://github.com/azzerial/spiral-knights-headless-client/tree/b78d71c828af6104e015bcc1a7aac79d523e6e04) | `b78d71c828af6104e015bcc1a7aac79d523e6e04` | Top-level Apache-2.0; legacy Narya files retain LGPL-2.1-or-later notices |
| [SKapsule](https://github.com/SKonstruct/SKapsule/tree/1d0143a8591237156f7d96d7ef3a5eaa96b74d5f) | `1d0143a8591237156f7d96d7ef3a5eaa96b74d5f` | MIT, `LICENSE` |
| [SKonstruct/bootstrap](https://github.com/SKonstruct/bootstrap/tree/84d2aba64bec91cdf0a3294081ce0820a7e34063) | `84d2aba64bec91cdf0a3294081ce0820a7e34063` | MIT, `LICENSE` |
| [Three Rings Narya](https://github.com/threerings/narya/tree/296182516e48a90140027c00fd8cec3206bd77df) | `296182516e48a90140027c00fd8cec3206bd77df` | LGPL-2.1, `LICENSE` |
| [samskivert](https://github.com/samskivert/samskivert/tree/0b9dc93bbe2e7bac69d10ebe31c4735ede1cee43) | `0b9dc93bbe2e7bac69d10ebe31c4735ede1cee43` | LGPL-2.1, `COPYING` |

The headless repository's game-specific `core/` sources include files without
individual license/provenance headers. Their independent origin is UNKNOWN.
Its top-level Apache declaration is not treated as permission to import
proprietary-derived game code. Use original Three Rings upstreams for library
research and independently implement the observed protocol.

READMEs, build files, and relevant source comments were inspected. No issue
archive was present locally; GitHub issues/comments were not comprehensively
surveyed. Claims below rely on pinned source and local verification, not issue
rumors or README feature promises.

## S1 — Installed client and Steam bootstrap metadata

Repository: local read-only Spiral Knights installation; Steam app metadata.

Relevant files/classes: `getdown.txt`, `getdown-pro.jar!/META-INF/MANIFEST.MF`,
`java_vm/release`, `code/config.jar!/{deployment,build}.properties`,
`native/`, and Steam `appcache/appinfo.vdf` app `99900` only.

What was learned: exact Linux Steam executable/arguments; Java 25 runtime;
Getdown main class; ordered Linux classpath/JVM options; `ProjectXApp` entry
point; build version; initial game hostname and TCP/UDP port 47624; four
configured regions; distinction between updater/content and game endpoints.

Verified against official client: YES (direct files and runtime experiment).
Confidence: HIGH; CONFIRMED configuration, not production service reachability.

Notes: A small local parser read only this app's launch configuration and
wrote `local/research/steam-launch-config.json`. The binary metadata format
was cross-checked against [SteamAppInfo's format description](https://github.com/xPaw/SteamAppInfo).
Steam launch-option/account files were not read. The working directory is
inferred from relative launch paths, not an explicit metadata field.

Reproducibility fingerprints (SHA-256; identifiers, not redistributed files):

```text
getdown.txt
547e17b49c95b85fe374c246650772a05aa41c64337625fa0acf19519971b6f8
code/config.jar
d28f8097a45def2f09640aa99183acd2b4e3281df9a6f838ee1afad08a0d275d
code/projectx-pcode.jar
391a51cef0446f55d461bdb8f942ca9dba44e6e4138acdcd69ab2024297f543c
getdown-pro.jar
31d5864c5777d21c39781b4fe613546ed9994a3eee41d561c826d615066ce2ce
```

## K1 — KnightLauncher

Repository: KnightLauncher, pinned above.

Relevant files/classes (under `src/main/java/`):

- `com/lucasluqui/launcher/LauncherGlobals.java`: working-directory root and
  Getdown/default/alternate launch commands.
- `com/lucasluqui/util/JavaUtil.java`: runtime selection, `java_vm`, executable
  detection, and version handling.
- `com/lucasluqui/launcher/ui/handler/LauncherEventHandler.java`: Steam vs
  standalone vs code-mod and third-party launch branches; explicit classpaths,
  heap/GC/additional args, native/resource paths, main classes.
- `com/lucasluqui/launcher/setting/SettingsManager.java`,
  `setting/ui/handler/SettingsEventHandler.java`, `setting/ui/SettingsUI.java`:
  connection controls and the actual deployment JAR rewrite.
- `com/lucasluqui/launcher/flamingo/FlamingoManager.java`, `flamingo/data/Server.java`:
  remote server catalog, enabled/beta/version metadata, install directories.
- `com/lucasluqui/util/DeployConfig.java`: launcher `dev`/`prod` configuration
  selects Flamingo address/port; it is not the game's deployment environment.
- `com/lucasluqui/bootstrap/Bootstrap.java`: client/bootstrap/Steam-app-ID setup.
- `pom.xml`: Java 8 source target and local `projectx-pcode.jar` build dependency.

What was learned: there is concrete custom connection configuration, but it
patches the installed configuration JAR; third-party support is catalog and
deployment machinery rather than a simple endpoint field in `Server`.
Some direct Linux launch paths lag the current client, while other common
paths have updated Java/LWJGL handling. Normal installation/update/mod/settings
actions write into the game tree, so this launcher was not run there.

Verified against official client: YES for endpoint keys, main class, Java path,
and missing bundled JAR tool; NO for running the launcher/catalog/third-party
branch on the current client. Confidence: HIGH for source behavior. The separate
local integration below has now been tested.

Notes: the README restriction on third-party availability is not proof the
implementation is absent. No hidden feature was enabled. Launcher dev/prod
settings and its service port are not game ports.

## K2 — Local KnightLauncher experiment

Repository: ignored `local/experiments/KnightLauncher`, based on K1's revision.

Relevant files/classes: `TortodroneLocal`, local startup in `LauncherApp`,
`FlamingoManager`, and `LauncherEventHandler`; `tools/knight_launcher.py` and
`tools/observe_client.py` in this repository.

What was learned: the existing launcher window and Play event can launch the
installed client through a separate deployment overlay without a catalog or
an installation rewrite. Maven packaging and its upstream test passed.

Verified against official client: YES. The actual Play path launched
`ProjectXApp` and the listener received its 437-byte initial request.
Confidence: HIGH for this connection test; authentication remains unimplemented.

Notes: smoke evidence is under
`local/research/client-run-20261007T035528856736Z/`. The upstream reference
checkout remains unchanged. See [local launcher instructions](knightlauncher.md).

## H1 — Headless client

Repository: spiral-knights-headless-client, pinned above.

Relevant files/classes:

- `src/main/java/net/azzerial/skhc/SKClientImpl.java`: connection construction,
  historical fixed host/port/build, public-key setup, secure-auth requirement,
  synchronous wait loops, disconnect/reset.
- `SKClientBuilder.java`, `ConnectionListener.java`: credential construction
  and observer-to-connection-status handling.
- `enums/Region.java`: two region names, not dynamic discovery.
- `services/Service.java`, `services/exchange/ExchangeService.java`: exchange
  object ID from bootstrap, subscription and market events.
- `legacy/src/main/java/com/threerings/presents/client/BlockingCommunicator.java`
  and `Client.java`: socket initialization, secure auth, delayed UDP, keepalive.
- `legacy/.../io/{FramingOutputStream,ObjectInputStream,ObjectOutputStream}.java`:
  custom framing and serialization.
- `core/.../projectx/data/{ProjectXCredentials,ProjectXAuthResponseData,ProjectXBootstrapData}.java`:
  hints about game-specific fields; provenance not assumed.
- Root/module `build.gradle.kts`: original API plus core/legacy modules and
  Guava/samskivert/Guice/other dependencies. The full stack was not imported.

What was learned: the original API wraps Presents, rather than defining a
separate packet protocol. It hardcodes `18.233.212.89:47624` and build
`20220124075521`; it explicitly requires secure authentication. Its implemented
service is the energy exchange market. Redirect support is incomplete; its
observer has no full game redirect logic.

Verified against official client: YES for port, framing, secure-request branch,
credential field names; NO for current fixed IP usability, login success, or
the full copied schema. Confidence: HIGH for source constants/behavior,
LOW for using them unchanged against current production.

Notes: old region choices and timings differ from current sources. For example,
the headless legacy ping constant is 60 seconds; current Narya and installed
client keepalive code use 30 seconds. Do not treat its README or bundled legacy
library as the authoritative current version.

## N1 — Original Narya/Presents

Repository: threerings/narya, pinned above.

Relevant files/classes, under `core/src/main/java/com/threerings/`:

- `presents/client/{Client,Communicator,BlockingCommunicator}.java`.
- `io/{FramingOutputStream,FramedInputStream,ObjectInputStream,ObjectOutputStream,Streamer,FieldMarshaller}.java`.
- `presents/net/{UpstreamMessage,AuthRequest,PublicKeyCredentials,SecureRequest,SecureResponse,AESAuthRequest,PingRequest,LogoffRequest}.java`.
- `presents/util/SecureUtil.java`.

What was learned: total-length framing, dynamic class codes, custom streamers,
sequence IDs, RSA secret/salt exchange, AES authentication, ping/clock-sync,
logoff, and UDP after bootstrap. This is the primary public implementation
reference and avoids rediscovering the library from game bytecode.

Verified against official client: YES for initial connection, frame prefix,
serializer type introduction, secure request/response branch, crypto helper
identifiers, and delayed UDP. NO for every current upstream change or all
post-login behavior. Confidence: HIGH for corroborated initial protocol;
MEDIUM for unexercised later behavior.

Notes: our server is independently written and does not link Narya. A future
library import requires license compliance and a compatibility decision.

## A1 — SKapsule and bootstrap

Repository: SKonstruct/SKapsule and its pinned bootstrap submodule.

Relevant files/classes: `README.md`, `launcher/app/build.gradle.kts`,
`launcher/app/src/main/cpp/sklauncher.c`, Kotlin installer/authentication
classes, and `external/bootstrap/src/main/java/com/skarm/launcher/bootstrap/`
`{SkBootstrap,HeadlessGetdown}.java`.

What was learned: current Android Java 25/LWJGL 3 bootstrap, JVM creation and
native/environment setup, Getdown update-before-launch flow, separate ordered
classloader, and reflective `ProjectXApp.main` invocation. Frenchpress is an
optional Steam-login shim, not the desktop's native Steam authentication path.
The Steam-native-disable system property is useful for our isolated experiment.

Verified against official client: YES for entry point, Java/LWJGL generation,
resource/native flags, and successful isolated launch with native Steam disabled;
NO for Android runtime/shims or Steam login. Confidence: HIGH for source flow;
MEDIUM for transferring any untested Android-specific setting to Linux.

Notes: no native submodule build, APK, or authentication attempt was needed.

## U1 — samskivert configuration loader

Repository: samskivert/samskivert, pinned above.

Relevant files/classes: `src/main/java/com/samskivert/util/{Config,ConfigUtil}.java`.

What was learned: public classpath properties/configuration conventions. The
current public implementation has capabilities beyond the small loader retained
in the installed game, so override behavior was verified from installed
`com.samskivert.util.p`/`q`, rather than assumed.

Verified against official client: YES, targeted bytecode and config-only probes.
Confidence: HIGH for first-resource selection and absence of direct `-D` overrides.

## C1 — Targeted installed bytecode

Repository: official client, read-only research only.

Relevant classes: `ProjectXApp`; `projectx.util.a`; `projectx.client.bN`;
`samskivert.util.p`/`q`; Presents `Client`, `BlockingCommunicator` and its
reader/datagram inner classes; `io.s`/`v`/`w`; `PublicKeyCredentials`,
`SecureResponse`, `ProjectXCredentials`, and crypto helper `presents.b.d`.

What was learned: current obfuscated mappings and correspondence to public
Narya; exact deployment accessors; login endpoint use; secure handshake;
`m.peer_redirect` host/port parsing; classpath loading; positional/property
startup arguments and credential fields. The obfuscated names are build-specific.

Verified against official client: YES. Confidence: HIGH for instructions read;
runtime reachability is established only where R1 also applies.

Notes: used `javap -p -c -constants`; outputs are exclusively in
`local/research/javap/`. No wholesale decompiler was installed or run, and no
proprietary source/disassembly is included in tracked files. The scanner report
is `local/research/client-constants.jsonl`; matches are leads, not facts.

## R1 — Isolated runtime experiments

Repository: TortodroneServer listener plus local installed client.

Relevant tools/files: `tools/observe_client.py`, the independent Java listener,
`local/research/probe/` (temporary config/network/decode probes), ignored
`client-run-*` directories, and `local/captures/initial-secure-request.bin`.

What was learned, CONFIRMED:

1. Direct JVM host/port properties did not override the installed deployment.
2. Prepending the separate deployment resource changed the endpoint getter.
3. Installed networking classes connected to our listener and sent 437 bytes.
4. The full, unmodified `ProjectXApp` connected to the same local listener and
   sent the same initial message type/length. The reusable tool reproduced it.
5. Installed deserialization read the frame as `SecureRequest`, with sequence
   1, build string, timezone, empty bootstrap groups, secure version 1, 256-byte
   encrypted-secret array, and four-byte salt. Random bytes are not published.

Verified against official client: YES, actual local execution.
Confidence: HIGH. Limits: no server response, authentication, character list,
or zone entry was claimed. No real user credentials/session were supplied.

Notes: bubblewrap enforced read-only installation access and isolated networking.
Only loopback was available to the experiment. Runtime logs and generated
configuration remain ignored. The experiment cleans up its client and listener.

## R2 — Secure-response round trip (2026-10-07)

Sources: public Narya `SecureResponse`, `PublicKeyCredentials`, `SecureUtil`,
`AuthResponseData`, `DObject`, `FieldMarshaller`, `Streamer`, and `ArrayMask`;
installed client serializer and full application.

What was learned: non-null final fields use a boolean marker; empty final arrays
include a zero-length null mask. The secure response carries the correlated
message ID, response-data object ID, and XOR-masked secret as hexadecimal text.
RSA plaintext is the client secret followed by verification salt.

Verified against official client: YES. An ignored `ResponseProbe.java` serialized
and deserialized a synthetic response with the installed classes. The independently
written server then sent its own response through the local KnightLauncher test.
The real client advanced to `AESAuthRequest`. Confidence: HIGH for this boundary;
its authentication payload and subsequent server response remain UNKNOWN.

Evidence: `local/research/probe/response.bin` (synthetic probe), and
`local/research/client-run-20261007T041806362428Z/listener.log` (437/139/348-byte
exchange). Runtime mode logs no raw authentication payload. Keys and generated
research stay ignored. No proprietary or upstream implementation was copied into
the server; the tracked codec is an independent, limited protocol implementation.
