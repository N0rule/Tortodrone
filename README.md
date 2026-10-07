# TortodroneServer

Experimental clean-room server emulator for Spiral Knights.

## Current status

The installed Linux client (build `20261006114613`) has connected to our
loopback TCP listener on port `47624` and sent its 437-byte initial
`SecureRequest`. This was tested with the full `ProjectXApp`, synthetic inputs,
a read-only installation, and isolated networking. Optional local-authentication
mode now decrypts the synthetic credentials, sends a success response, and
observes the client's first authenticated `PingRequest`. Bootstrap, character
selection, and gameplay remain unimplemented.

## Build and test

Requires a JDK 17+ and GNU make; tests/tools also need Python 3.9+.
Adoptium Temurin 25 is installed on this workstation and supports the server,
launcher, and current game client. Set `JAVA_HOME` if the JDK is not on `PATH`.
See [Java setup](docs/java-setup.md).

```sh
make test
make run
```

The server has no dependencies on game files, upstream repositories, or Maven
downloads. It logs incoming bytes to stdout and restricts binding to loopback.
Store any redirected logs under ignored `local/`.

To reproduce the real client observation on this Linux desktop:

```sh
make build
python3 tools/observe_client.py
```

This requires `local/client`, an external Java 25 JDK, `bubblewrap`, and an
active X11/XWayland desktop. It opens the client briefly, starts the listener
inside the same isolated network namespace, saves evidence under `local/`,
and stops both processes after receiving bytes. See the
[redirection instructions](docs/client-redirection.md) before changing the setup.

To open the prepared local KnightLauncher variant and launch manually:

```sh
python3 tools/knight_launcher.py
```

To test local authentication with the synthetic account, use:

```sh
python3 tools/knight_launcher.py --local-auth
```

Click **Play locally**. Close KnightLauncher to stop the client and listener.
The client accepts synthetic authentication, then the experiment closes before
bootstrap; login cannot yet reach character selection. The experimental
launcher source and build stay under ignored `local/`. See
[KnightLauncher setup and rebuilding](docs/knightlauncher.md).

## Research

- [External Java setup](docs/java-setup.md)
- [Client startup](docs/client-startup.md)
- [Network architecture](docs/network-architecture.md)
- [Protocol and observed handshake](docs/protocol.md)
- [Source provenance and verification](docs/research-sources.md)
- [Client redirection](docs/client-redirection.md)
- [Server architecture and next step](docs/server-architecture.md)
- [Local KnightLauncher integration](docs/knightlauncher.md)

`python3 tools/scan_client.py` scans JAR constant pools read-only and writes
candidate strings/integers to `local/research/client-constants.jsonl`.
Candidates are not automatically verified hosts or ports.

## Goals

Initial milestones:

1. Understand client startup and network architecture.
2. Redirect a local test client to our server.
3. Accept the initial client connection.
4. Decode the initial protocol handshake.
5. Implement minimal authentication/session handling.
6. Reach character selection.
7. Load a basic game zone.

## Repository Structure

- `server/` - server implementation
- `protocol/` - protocol definitions and codecs
- `tools/` - reverse-engineering utilities
- `docs/` - protocol and architecture documentation
- `local/` - local research files, ignored by Git

## Important

Original Spiral Knights client files, assets, extracted code, packet captures,
credentials, and other proprietary material are not stored in this repository.
The local installation is research input only. Never modify `local/client`,
stage `local/`, or publish raw protocol logs. The next milestone is understanding the
bootstrap notification and required services that follow authentication, before
character selection.
