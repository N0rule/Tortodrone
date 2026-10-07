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

`server/src/main/java/org/tortodrone/server/ConnectionLogger.java` is the server
entry point. It binds `127.0.0.1:47624`, accepts concurrent connections, assigns IDs,
and logs UTC timestamps, peer addresses, byte counts, stream offsets, hex, and
printable ASCII. By default it sends no response. EOF, reset, and idle timeout close a
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

The client connection and initial secure-response milestones are complete.
`--handshake-key <private.pk8>` enables one secure exchange. `HandshakeCodec`
under `protocol/` reads bounded frames and the verified request subset;
`SecureHandshake` validates the RSA secret/salt and sends the minimal response.
It identifies the following `AESAuthRequest`, then closes without logging its
payload. No game libraries are linked. `HandshakeKeys` generates per-run local
2048-bit RSA keys with private filesystem permissions; the wrapper stores them
under ignored `local/research/` and overlays the public key only.

The frame limit is 64 KiB and the socket idle timeout remains 15 seconds. The
codec handles fragmented and coalesced TCP data, rejects invalid lengths and
unexpected fields, and maintains incoming class mappings per connection.
It supports only the observed handshake schema, not arbitrary Narya objects.

Validation: synthetic tests cover truncated/oversized frames, fragmentation,
coalescing, RSA exchange, invalid salt, response correlation, and next-message
identification. The real KnightLauncher Play path also passed: 437-byte request,
139-byte response, 348-byte `AESAuthRequest`. See R2 in the source register.

Next implement only: inspect and decode the synthetic `AESAuthRequest` with the
negotiated secret, determine the minimum compatible authentication response,
and verify the client's next message. The current server discards its per-run
secret when the connection ends; a follow-up implementation must retain it only
for the connection lifetime. Account persistence, character lists, zones, and
other game systems remain outside this milestone.

## Reuse and provenance

CONFIRMED: the implementation uses only the JDK. Public projects were read as
references; no source was copied into the tracked server or tools. Bytecode
disassembly and experimental probes remain under `local/`. Public Narya uses
LGPL-2.1; KnightLauncher declares GPL-3.0; SKapsule/bootstrap declare MIT.
Any future actual reuse needs a separate file-level license/provenance decision.
See [research-sources.md](research-sources.md).
