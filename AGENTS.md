# Tortodrone contributor rules

## Scope

- This repository develops the Spiral Knights server. Keep implementation work on the server.
- Support normal username/password login only. Do not add Steam authentication, guest login, anonymous accounts, or official-account login.
- Future account work must use normal username/password authentication. The current fixed test account is an interim server implementation.
- Do not edit game clients, launchers, game archives, client controls, client home-directory state, or install client patches and workarounds.
- Solve compatibility problems through server behavior using verified protocol schemas. If a client issue prevents progress, document the server boundary rather than modifying the client.
- Client connection documentation may explain address, port, and public-key settings in a separately maintained launcher.
- Research documents describe historical observations, including earlier client experiments. They do not authorize new client changes.

## Build and runtime

- Use Java 17 and GNU Make. Keep the executable JAR independent of game files, launchers, external services, and third-party libraries.
- The project and executable are named Tortodrone and `tortodrone.jar`. Maintain the release version in `VERSION`; the build bundles it for startup and `--version` output.
- Keep generated files under `build/`: the JAR, disposable `build/.build/` classes, and persistent `build/data/`.
- All runtime state must live in `data/` beside the running JAR. A custom `--config` selects settings only and must not relocate state. Do not reintroduce `data-directory`.
- Preserve settings, account profiles, and the server keypair across builds, restarts, upgrades, and `make clean`. Never silently regenerate an existing or incomplete identity.
- `server-ip` is the listener's bind address; `client-host` is the reachable address advertised to clients. Keep that distinction explicit.

## Code and protocol

- Put wire encoding/decoding in `protocol`, connection handling in `server.network`, configuration in `config`, and character behavior/persistence in `character`.
- Keep parsers bounded and explicit. Do not deserialize arbitrary Java objects or load classes named by clients.
- Reject unsupported authentication and operations clearly. Do not report gameplay or world entry as implemented before it exists.
- Persist character changes before acknowledging them. Preserve saves when validation or writes fail.
- Keep `ProjectXAuthResponseData.ident` null for both accepted and rejected login responses so the server cannot alter the client's persisted identity.

## Logging and validation

- Use `[HH:mm:ss] [thread/LEVEL]: message` for console and file logs.
- `log-mode=normal` reports startup, connections, saves, warnings, errors, and shutdown. `log-mode=debug` also reports protocol diagnostics.
- Never log credentials, password hashes, private keys, negotiated secrets, session identifiers, raw packets, or client invocation values, including in debug mode.
- Use synthetic clients and temporary deployments in tests. Tests must not read or modify real game installations, credentials, keys, or account profiles.
- Run the existing checks appropriate to a change before committing. Add regression coverage for meaningful behavior changes, especially persistence, authentication, configuration, and protocol boundaries.
- Keep README and current development/runtime documentation consistent with code. Label historical research clearly.
- Never commit generated runtime data, build output, game assets, extracted game code, secrets, or raw captures.
