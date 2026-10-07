# TortodroneServer Architecture

## Language choice

Decision: Java, initially targeting Java 17+. The installed client needs Java
25, but the receive-only emulator uses only JDK networking and can target 17.
Kotlin would interoperate with the same libraries, but would add a compiler,
runtime dependency, and build plugin before providing a benefit to this small
listener. Reconsider if later project needs warrant it.

CONFIRMED (HIGH): Narya/Presents is a Java object/streaming protocol, and public
Java implementations expose its primitives. Java makes future compatibility
experiments straightforward. No third-party or proprietary implementation is
linked into the emulator. The headless project's original API code is useful
research; its game-specific `core/` files are not assumed independently licensed
just because its top-level project declares Apache-2.0.

## Implemented scope

`server/src/main/java/org/tortodrone/server/ConnectionLogger.java` is the entire
server. It binds `127.0.0.1:47624`, accepts concurrent connections, assigns IDs,
and logs UTC timestamps, peer addresses, byte counts, stream offsets, hex, and
printable ASCII. It sends no response. EOF, reset, and idle timeout close a
connection cleanly. Shutdown closes active sockets.

For this observation tool, binding is restricted to loopback; there are at
most 16 simultaneous clients, a 15-second idle timeout, and a 64 KiB capture
limit per connection. These are local experiment limits, **not protocol constants**.
Reads are reported as chunks, never claimed to be frames. `--port 0` is
available for collision-free synthetic testing, and `--bind` accepts loopback
addresses only. The production-derived default is documented in
[network-architecture.md](network-architecture.md).

The server builds without any file in `local/` and without network dependency
resolution. `Makefile` invokes `javac --release 17`; Gradle/Maven would add no
value to this dependency-free milestone. Build output lives under ignored
`build/`. JARs are intentionally not globally ignored.

## Build and validation

```sh
make build
make test
make run
```

Set `JAVA_HOME` to a JDK if needed. `make test` uses synthetic local sockets to
check two simultaneous clients, binary/ASCII logging, byte totals, empty and
normal disconnects, no unexpected responses, and rejection of public binding.
Tests choose an ephemeral port and clean up their process. Python 3.9+ is
needed for the research tools; Java 17+ and make are sufficient for the server.

CONFIRMED (HIGH): compilation and integration tests passed using JDK 25.0.1,
with Java 17 target classes. The full installed app also connected and sent
its initial handshake using `tools/observe_client.py`. Neither test requires
real game credentials. Java 17 runtime execution itself was not separately
tested on this machine.

## Next implementation boundary

The user-facing launcher is a tested local KnightLauncher integration for
TortodroneServer. Its adaptation stays under ignored `local/`, outside the
server implementation. The original Spiral Knights client remains the protocol
peer. Python prepares the isolated test environment; the server uses Java.
See [launcher instructions](knightlauncher.md).

The first milestone is complete: full client -> local listener -> protocol
bytes. The current receive-only server intentionally leaves the client waiting
for its secure response. Advancing requires a local authentication key and a
compatible serialized response, not character/world scaffolding.

Next implement only: bounded TCP frame accumulation, the observed secure-request
subset, and `SecureResponse` with a local test keypair. Verify it causes the
client to send `AESAuthRequest` before implementing session behavior. Keep the
client build/version and dynamic class mappings explicit; do not freeze the
observed 437-byte size or mapping code into a universal packet format.

Later `protocol/` can house the independently implemented codec. Login/session,
redirect, characters, and zone components should be introduced only as observed
traffic requires them. No database, service cluster, game simulation, or future
packet registry is justified yet.

## Reuse and provenance

CONFIRMED: the implementation uses only the JDK. Public projects were read as
references; no source was copied into the tracked server or tools. Bytecode
disassembly and experimental probes remain under `local/`. Public Narya uses
LGPL-2.1; KnightLauncher declares GPL-3.0; SKapsule/bootstrap declare MIT.
Any future actual reuse needs a separate file-level license/provenance decision.
See [research-sources.md](research-sources.md).
