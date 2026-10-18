# Tortodrone

A **Java 25 clean-room Spiral Knights server emulator** and local account manager for an **unmodified** stock client. Tortodrone is under active development, **not** a complete vanilla server. Normal username/password accounts are supported; Steam and guest login are not.

## What works today

The currently implemented subset includes local login/accounts and GM roles, knight creation/selection/editing/deletion, persistent inventory and currencies, equipment/costumes, item grants, character preview, trading, inspection/privacy, chat/tells/emotes/sitting, and four connected Haven towns (Town Square, Bazaar, Arcade and Garrison). Three additional solo interiors (Advanced Training Hall, Laboratory, Hall of Heroes) have movement and limited stock-derived weapon/defense/projectile systems. Training Hall now also supports prepared scene actors and bounded interactions (test cubes, switches/doors, traps, pickups, blocks and conversation). The seven bundled scenes use verified modern stock-capture layouts, including Laboratory scene 30893 and Hall of Heroes scene 318. Scenes use external validated layouts/collision and server-authoritative movement.

**Not full vanilla parity:** general dungeon/gate gameplay, broadly reusable monster AI and NPC services, missions, complete shop/crafting coverage, full party/guild/social systems, world-position persistence and much of combat are not implemented. The intro is bypassed without granting fictional mission progress. Actual supported scope is determined by source/tests, not by how much data exists in content definitions. Supported Town services include merchant purchases, recipe learning/crafting, sprite progression, auctions and the Energy Exchange; available stock catalogs and behaviors remain incomplete.

## Quick start

Install **JDK 25** and ensure `java` is on PATH. The repository includes the Gradle 9.5.1 wrapper; no system Gradle, separate SQLite service or game installation is needed for normal builds.

From the repository root:

```sh
./gradlew build
java -jar build/tortodrone-admin.jar
java -jar build/tortodrone.jar
```

On Windows use `.\gradlew.bat build` instead of `./gradlew build`. The admin program creates the normal username/password account; use it before connecting the client. The server opens a GUI when a desktop is available; use `--nogui` for headless hosting or `--gui` to explicitly request a desktop.

Useful tasks (same Windows wrapper substitution):

```sh
./gradlew buildWithoutTests   # compile main, tests and build tools; package both JARs; run no tests
./gradlew buildServer         # server JAR + unified content
./gradlew buildAdmin          # account-manager JAR
./gradlew testUnit
./gradlew testIntegration
./gradlew prepareDefaults     # offline source validation/copy
./gradlew prepareMaps         # maps only
./gradlew benchmark --args="--seconds 20 --instances 4 --clients 300"
```

`./gradlew build` runs unit/integration checks. For **everyday development**, use `buildWithoutTests` to compile/package without running tests, then execute only the affected standalone tests; see [Fast focused testing](docs/development.md#running-just-the-affected-tests). Reserve full `build` and isolated graphical checks for changes that require them, not every edit. First use needs downloads of the Gradle distribution and verified dependencies; cached dependencies support `--offline` builds. Benchmarks are opt-in and synthetic, not proof of live-client performance.

For portable deployments place these **beside one another**:

```text
Tortodrone/
  tortodrone.jar
  tortodrone-admin.jar
  config/                # server.yml and combat.yml
  content/               # maps/, items/, town/, shared/, _internal/
  plugins/README.md      # future API placeholder
  data/                  # keep across updates
```

The build prepares `build/{config,content,plugins}/` beside both JARs. Deploy these
complete directories. Release archives include defaults and omit private `data/`.
Tortodrone has no public releases before 1.0.0, so there are no earlier published
versions to upgrade from and no migration procedure is provided; deploy the current
defaults directly.

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

`--config path/to/server.yml` changes only the settings file. Existing explicitly
selected `.properties` listener settings remain compatible. `data/` stays beside
the JAR. Type `help` in the server terminal/window for commands; `stop` shuts down
terminal hosting. Logs are under `data/logs/`.

Open `content/maps/advanced_training/npcs/konway.yml` to edit Konway's readable
stock dialogue selections (literal text is restricted by the unchanged client),
`content/items/weapons.yml` for weapon controls, or `content/town/shops.yml`
for catalog goods. Save, validate and restart; no manifests or manual hashes.
See [Content](docs/content.md) for schemas, imports and native limits.
Back up the whole stopped `data/`, `config/` and `content/` before changing the deployment.

## Documentation

- [Operations](docs/operations.md): deployment, accounts, commands, client troubleshooting, backup and releases.
- [Content](docs/content.md): maps, items, combat defaults, verified imports and current extension limits.
- [Architecture](docs/architecture.md): identity/storage, protocol, simulation and security invariants.
- [Development](docs/development.md): build/tests, isolated stock-client and GUI checks.
- [Research](docs/research.md): reference sources, protocol evidence and historical investigation pointers.
- [Modern captured scenes](docs/modern-captures.md) and [Training Hall evidence](docs/training-hall.md): version-specific validation retained from newer gameplay commits.
- [AGENTS.md](AGENTS.md): short rules for coding agents.

Older milestone reports and investigations remain in Git history; they are not maintained product documentation. Licenses and third-party notices are bundled in the JARs.
