# Tortodrone

Clean-room **Java 25 Spiral Knights server emulator** plus local account manager. Stock game and KnightLauncher are separate, unmodified. Supports only normal username/password; no Steam/guest auth.

## Current state (verify `VERSION` and Git)

At **0.29.0 / SQLite schema 8**: login and account takeover protection; globally unique knight names; character creation, editing, selection and deletion; four external Haven towns (Town Square, Bazaar, Arcade, Garrison), twelve directed town portals and six directed interior links, authoritative movement/static collision, shared per-instance 50 ms simulation and multiplayer actor updates. The checked-in scenes use verified official gameplay captures dated 2026-10-12, including Laboratory scene 30893 and the four-star Hall of Heroes scene 318. Persistent inventory/equipment/costumes/trinkets/stacks/binding/currencies, local administration/GM roles and commands, live Arsenal updates, scene chat/private tells, sitting and stock character previews. Player trading and inspection/privacy are supported. Three solo Haven interiors support authored weapon controls, shielding, dodge and projectile emissions within the [recorded combat limits](docs/town-player-behavior.md). Intro missions are bypassed without fabricated completion/rewards.

**Not implemented at the recorded checkpoint:** general missions, NPC services, shops, crafting/item use, dungeon/gate gameplay, position persistence, custom news and broad social features. The [current project handoff](docs/PROJECT.md) records the supported scope and remaining limits; inspect Git before choosing work. Older milestone detail is available in Git history.

## Build, run and connect

Install **JDK 25** and put its `bin` directory on `PATH` (or set `JAVA_HOME` to the JDK). The checked-in Gradle **9.5.1** Wrapper supplies Gradle; no Make, separately installed Gradle, database service or game installation is needed. Compilation uses a Java 25 toolchain and `--release 25`.

On **Windows PowerShell or CMD**, from the repository root:

```powershell
.\gradlew.bat build
java -jar build/tortodrone-admin.jar
java -jar build/tortodrone.jar
```

On **Linux**:

```sh
./gradlew build
java -jar build/tortodrone-admin.jar
java -jar build/tortodrone.jar
```

On **macOS** (install a JDK 25 build for your Mac's architecture):

```sh
./gradlew build
java -jar build/tortodrone-admin.jar
java -jar build/tortodrone.jar
```

Create a local account with the admin application before connecting. The server opens its local control window when a desktop is available, and uses terminal mode on headless systems. Use `--nogui` for terminal hosting or `--gui` to require a desktop; either accepts `--config path/to/server.properties`. Toolkit initialization failure falls back to terminal mode. `--help` and `--version` print information without a window. In terminal mode, type `stop` or Ctrl+C to stop hosting. In graphical mode, Stop leaves the window available for Start; closing it confirms server shutdown and exit. See [desktop controls](docs/server-gui.md). Both executable JARs include SQLite JDBC 3.53.4.0 and JLine 4.4.7, their service providers, native libraries and license notices. No manual classpath is needed.

Use `./gradlew` below on Linux/macOS; substitute `.\gradlew.bat` on Windows:

```sh
./gradlew buildServer             # server JAR plus prepared versioned content
./gradlew buildAdmin              # admin JAR
./gradlew runServer               # packaged server, data beside build JAR
./gradlew runAdmin                # packaged admin (graphical desktop required)
./gradlew runServer --args="--help"
./gradlew runServer --args="--nogui" # terminal mode; uses real build/data
./gradlew testUnit                # all standalone unit programs
./gradlew testIntegration         # disposable packaged server/admin and preparation tests
./gradlew test                    # both test groups; build also runs these
./gradlew prepareContent          # prepare canonical content/packs/vanilla
./gradlew prepareDefaults         # deprecated alias for prepareContent
./gradlew prepareMaps             # prepare only vanilla maps
./gradlew benchmark --args="--seconds 20 --instances 4 --clients 300"
./gradlew clean                   # removes generated artifacts; preserves persistent state
```

Benchmarks are never part of `build` or `test`. Resource preparation is offline and verifies pinned source checksums before copying. `build`, `buildServer`, `buildAdmin` and `prepareContent` prepare the canonical `content/packs/vanilla/` tree under `build/content/packs/vanilla/`; `prepareMaps` prepares its maps subtree. Use `-PcontentOutput="/path/to/content/packs/vanilla"` for the complete pack output, or deprecated `-PdefaultsOutput` for compatibility. Preparation preserves owner-edited files and refuses planned changes while the target deployment lock is held. See [content packs and legacy migration](docs/content.md).

**Offline builds:** the first build needs access to the Gradle distribution, plugin portal and Maven Central. Once the Wrapper, plugin, dependency artifacts and metadata are cached, `./gradlew --offline build` works with a local JDK 25. Legacy `build/deps` files alone do not populate Gradle's cache. Preparing the canonical vanilla content needs cached Wrapper/build tooling but no third-party runtime dependencies. Server startup never downloads content: deploy the complete validated vanilla pack and explicitly selected custom packs.

`build.gradle.kts` defines compilation, executable packaging, preparation, tests and runs; `settings.gradle.kts` names the project. `gradlew`, `gradlew.bat` and `gradle/wrapper/` pin and bootstrap Gradle, with a distribution checksum. `gradle/verification-metadata.xml` pins SHA-256 checksums for dependencies and plugins (including the original runtime JAR checksums); review changes before accepting new hashes. Gradle caches live in its user home and `.gradle/`; compiler and packaging output lives in `.build/`. `gradle.properties` enables Gradle’s configuration cache so repeated builds can reuse the configured task graph. `clean` deletes that scratch tree, the old `build/.build/`, Gradle dependency-verification reports and the two generated JARs, while preserving `build/data/`, `build/content/`, `build/deps/` and other deployment files.

The [migration record](docs/gradle-migration.md) maps every old Make target and records verification.

Advanced Training Hall, Laboratory and Hall of Heroes are part of the canonical vanilla pack, so ordinary builds prepare all seven scenes in `build/content/packs/vanilla/maps/` with verified native entry/return links. `prepareHaven` can derive a fresh candidate tree from local, hash-verified captured scenes; it never publishes into `content/packs/vanilla/maps/`. See [map preparation](docs/maps.md) for the workflow and protection of custom files.

Both JARs keep `data/` beside themselves; deploy the complete `build/content/packs/vanilla/` tree plus `config/` and `custom/packs/`. Preserve `data/` (especially keys/DB) and operator-managed packs. Content is validated as a complete snapshot before keys or account storage open; corrupt/incomplete resources fail startup, with no download or partial fallback. See [content packs](docs/content.md) and [maps](docs/maps.md).

KnightLauncher settings: `game.endpoint=127.0.0.1` (or reachable server IP), `game.port=47624`, `game.publicKey` = entire contents of `build/data/keys/public.txt`. [Connection help](docs/client-redirection.md).

**Stock F3 GM dashboard:** grant the role server-side (`--set-gm Alice true` or the terminal `gm Alice`), log in with that account, press **F3** and use the client's own **ItemCreator** to create real, persistent inventory items. Nothing grants this automatically; plain accounts are refused. [Admin details](docs/administration.md).

Stop the server before editing `config/server.properties` beside the JAR (`build/config/server.properties` for source builds). An existing `data/server.properties` beside the JAR is still read in place when the canonical file is absent:

```properties
server-ip=0.0.0.0
server-port=47624
max-clients=16
log-mode=normal
log-max-mib=10
log-retention=5
```

`max-clients` range 1–1024; `--config` changes the settings-file path, **not** data directory. UTF-8 logs rotate from `data/logs/latest.log` to `server-1.log` and older numbered files; `log-max-mib` accepts 1–1024 and `log-retention` accepts 1–100. See [logging/privacy](docs/logging.md). Admin manager accepts `--data /path/to/server/data`; it opens no network admin port. `help` in the terminal or graphical command field lists commands. The account manager now browses accounts, knights and inventory, searches the item catalogue, grants unequipped items and adjusts item quantities and balances through the shared storage services. The server panel shows live sessions, scene templates/instances and measured performance, with docked logs. [Commands](docs/commands.md), [Admin details](docs/administration.md), [item data](docs/game-data.md).

## Backups, development and references

Stop server and manager; copy the **whole `data/` directory** (SQLite WAL and keys). Only current schema 8 is supported; JSON item catalogue v2 is written, with v1 reading retained. Content packs and settings live outside `data/`. No old-DB imports/migrations. Never discard original state while testing.

Start coding with [AGENTS.md](AGENTS.md) and [project handoff](docs/PROJECT.md); consult [architecture](docs/server-architecture.md) and [development](docs/development.md) for invariants, tests, stock-client checks, benchmarks and cleanup. See [upstream research](docs/server-research.md) only when the task needs it. Do not load all historical documents into agent context.
