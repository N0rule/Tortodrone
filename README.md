<p align="center">
  <img src="docs/assets/banner.png" alt="Tortodrone banner" width="100%">
</p>

# Tortodrone

**A Java 25 clean-room Spiral Knights server emulator and local account manager for the unmodified stock client.**

Tortodrone is under active development. It is **not** a complete vanilla server. Normal username/password accounts work; Steam and guest login do not.

## Quick start

### 1. Build

Install **JDK 25** and make sure `java` is on `PATH`. The Gradle 9.5.1 wrapper is included, so no system Gradle or separate SQLite service is needed.

```sh
./gradlew build
```

`build` creates a working distribution without running tests. On Windows, use `.\gradlew.bat build`.

### 2. Create an account

```sh
java -jar build/tortodrone-admin.jar
```

### 3. Start the server

```sh
java -jar build/tortodrone.jar

# Headless hosting:
java -jar build/tortodrone.jar --nogui
```

The account manager creates the normal username/password account; use it before connecting the client. The server opens a GUI when a desktop is available. Use `--nogui` for headless hosting or `--gui` to explicitly require a desktop.

## What works today

- **Accounts and knights:** local username/password login, GM roles, knight creation, selection, editing and deletion.
- **Persistence and items:** inventory, currencies, equipment and costumes, item grants and character preview.
- **Social:** chat, tells, emotes, sitting, parties with open/private controls, and friend travel into eligible Town and shared Training scenes.
- **Haven:** four connected towns: Town Square, Bazaar, Arcade and Garrison.
- **Interiors:** Advanced Training Hall, Laboratory and Hall of Heroes, with movement and limited stock-derived weapon, defense and projectile systems.
- **Training Hall:** public shared instances, prepared scene actors and bounded interactions such as test cubes, switches/doors, traps, pickups, blocks and conversation.
- **Scenes:** seven bundled scenes with verified modern stock-capture layouts, including Laboratory scene 30893 and Hall of Heroes scene 318. Layouts and collision are external and validated; movement is server-authoritative.
- **Town services:** merchant purchases, recipe learning/crafting, sprite progression, auctions and the Energy Exchange. Available catalogs and behaviors remain incomplete.

### Not full vanilla parity

Tortodrone does not yet implement general dungeon/gate gameplay, broadly reusable monster AI or NPC services, missions, complete shop/crafting coverage, guilds, PvP, party matchmaking, world-position persistence, or much of combat. The intro is bypassed without granting fictional mission progress.

Actual supported scope is determined by source and tests, not by how much data exists in content definitions.

## Everyday Gradle tasks

```sh
./gradlew build          # both JARs + prepared content; no tests
./gradlew buildServer    # server JAR + unified content
./gradlew buildAdmin     # account-manager JAR
./gradlew testUnit       # all standalone unit tests
./gradlew testUnit -PunitTests=SceneSimulationTest,MovementCollisionTest  # focused
./gradlew testIntegration # packaged integration tests
./gradlew testAll        # unit + integration tests
./gradlew prepareContent # offline source validation/copy (prepareDefaults is deprecated)
./gradlew prepareMaps    # maps only
./gradlew benchmark --args="--seconds 20 --instances 4 --clients 300"
```

`buildWithoutTests` remains as a deprecated alias for `build`. Tests have explicit names: `testUnit`, `testIntegration` and `testAll`; `build` never runs them. For focused unit runs, pass `-PunitTests=...`; see [Fast focused testing](docs/development.md#running-just-the-affected-tests).

First use downloads the Gradle distribution and verified dependencies. Cached dependencies support `--offline` builds. Benchmarks are opt-in and synthetic; they are not proof of live-client performance.

## Portable deployment

Place these **beside one another**:

```text
Tortodrone/
  tortodrone.jar
  tortodrone-admin.jar
  config/                # server.yml and combat.yml
  content/               # maps/, items/, town/, shared/, _internal/
  plugins/README.md      # future API placeholder
  data/                  # keep across updates
```

The build prepares `build/{config,content,plugins}/` beside both JARs. Deploy these complete directories. Release archives include defaults and omit private `data/`.

Existing schemas 9–15 require an explicit offline economy upgrade candidate; see [Upgrading](docs/content.md#upgrading). Keep the original Depot YAML until export.

## Client connection

Use a **separate, unmodified** Spiral Knights installation and KnightLauncher. Set the launcher to:

```properties
game.endpoint=127.0.0.1
game.port=47624
game.publicKey=<entire value from data/keys/public.txt>
```

For a local build, the key is at `build/data/keys/public.txt` once the server has generated it. For remote hosting use the reachable server address, not `0.0.0.0`; expose the configured **TCP** port as appropriate. Restart the game after changing launcher settings. No default player account exists.

## Configuration and safety

Stop the server before editing `config/server.yml`:

```yaml
server-ip: 0.0.0.0
server-port: 47624
max-clients: 16
log-mode: normal
log-max-mib: 10
log-retention: 5
startup-map: haven
```

- `--config path/to/server.yml` changes only the settings file. Existing explicitly selected `.properties` listener settings remain compatible.
- `data/` stays beside the JAR. Type `help` in the server terminal/window for commands; `stop` shuts down terminal hosting. Logs are under `data/logs/`.
- To edit content, see `content/maps/advanced_training/npcs/konway.yml` for Konway's readable stock dialogue selections (literal text is restricted by the unchanged client), `content/items/weapons.yml` for weapon controls, or `content/town/shops.yml` for ordinary merchant goods.
- Actor/item YAML inherits native defaults and contains overrides. Save, validate and restart for static content; use the account manager to edit SQLite Depot offers/promotions and auctions live. No manual hashes.
- See [Content](docs/content.md) for schemas, imports and native limits. Back up the whole stopped `data/`, `config/` and `content/` before changing the deployment.

## Documentation

| Guide | What it covers |
| --- | --- |
| [Commands](docs/commands.md) | Player/GM slash commands, examples, console syntax and explicit placeholders. |
| [Parity](docs/parity.md) | Estimated official parity by system plus the prioritized now/later roadmap. |
| [Operations](docs/operations.md) | Deployment, accounts, commands, client troubleshooting, backup and releases. |
| [Content](docs/content.md) | Maps, items, combat defaults, verified imports and current extension limits. |
| [Architecture](docs/architecture.md) | Identity/storage, protocol, simulation and security invariants. |
| [Development](docs/development.md) | Build/tests, isolated stock-client and GUI checks. |
| [Research](docs/research.md) | Reference sources, protocol evidence, bundled scene provenance and historical investigation pointers. |
| [Training Hall](docs/training-hall.md) | Current scene behavior, verification and limits. |
| [AGENTS.md](AGENTS.md) | Short rules for coding agents. |

Older milestone reports and investigations remain in Git history; they are not maintained product documentation. Licenses and third-party notices are bundled in the JARs.
