# TortodroneServer

Experimental clean-room server emulator for Spiral Knights.

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
