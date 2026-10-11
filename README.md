# Tortodrone

Clean-room **Java 25 Spiral Knights server emulator** plus local account manager. Stock game and KnightLauncher are separate, unmodified. Supports only normal username/password; no Steam/guest auth.

## Current state (verify `VERSION` and Git)

At **0.21.0 / SQLite schema 8**: login and account takeover protection; globally unique knight names; character creation, editing, selection and deletion; four external Haven towns (Town Square, Bazaar, Arcade, Garrison), ten native walking portals, authoritative movement/static collision, shared per-instance 50 ms simulation and multiplayer actor updates. Persistent inventory/equipment/costumes/trinkets/stacks/binding/currencies, local administration/GM roles and commands, live Arsenal updates, scene chat/private tells, sitting and stock character previews. Intro missions are bypassed without fabricated completion/rewards.

**Not implemented at the recorded checkpoint:** combat, missions, NPC services, shops, crafting/item use, dungeon/gate gameplay, position persistence, trade, custom news and broad social features. Some of these may be **in-progress in uncommitted source**; inspect Git before choosing work. The [current project handoff](docs/PROJECT.md) is the concise checkpoint; older milestone detail is available in Git history.

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

Create a local account with the admin application before connecting. Type `stop` or Ctrl+C to stop the server. Both executable JARs include SQLite JDBC 3.53.4.0 and JLine 4.4.7, their service providers, native libraries and license notices. No manual classpath is needed.

Use `./gradlew` below on Linux/macOS; substitute `.\gradlew.bat` on Windows:

```sh
./gradlew buildServer             # server JAR plus external defaults
./gradlew buildAdmin              # admin JAR
./gradlew runServer               # packaged server, data beside build JAR
./gradlew runAdmin                # packaged admin (graphical desktop required)
./gradlew runServer --args="--help"
./gradlew testUnit                # all standalone unit programs
./gradlew testIntegration         # disposable packaged server/admin and preparation tests
./gradlew test                    # both test groups; build also runs these
./gradlew prepareDefaults         # verify/copy local maps and item defaults
./gradlew prepareMaps             # verify/copy only local maps
./gradlew prepareInteriors -PgameDir="/path/to/Spiral Knights" # maintainer re-export of the interior packs into defaults/maps
./gradlew benchmark --args="--seconds 20 --instances 4 --clients 300"
./gradlew clean                   # removes generated artifacts; preserves persistent state
```

Benchmarks are never part of `build` or `test`. Resource preparation is offline and verifies source map checksums before copying. The checked-in `defaults/maps/` is the one map source, so `build`, `buildServer`, `prepareDefaults` and `prepareMaps` prepare all seven scenes under `build/defaults/maps` with no game path, archive or extra directory. Existing custom files are preserved; a pack this source published earlier is upgraded in place, and owner-edited files keep their bytes rather than failing preparation. Use `-PdefaultsOutput="/path/to/disposable/defaults"` to select a preparation destination (`prepareMaps` takes the maps directory itself). Missing optional source directories produce a warning; corrupt supplied maps fail preparation. `compileJava` and `buildAdmin` do not require optional maps or downloads for maps.

**Offline builds:** the first build needs access to the Gradle distribution, plugin portal and Maven Central. Once the Wrapper, plugin, dependency artifacts and metadata are cached, `./gradlew --offline build` works with a local JDK 25. Legacy `build/deps` files alone do not populate Gradle's cache. Preparing local defaults needs the Wrapper/build tooling cached but no third-party runtime dependencies. The optional server startup recovery URL is unchanged and remains a placeholder; use local `defaults/` rather than relying on it.

`build.gradle.kts` defines compilation, executable packaging, preparation, tests and runs; `settings.gradle.kts` names the project. `gradlew`, `gradlew.bat` and `gradle/wrapper/` pin and bootstrap Gradle, with a distribution checksum. `gradle/verification-metadata.xml` pins SHA-256 checksums for dependencies and plugins (including the original runtime JAR checksums); review changes before accepting new hashes. Gradle caches live in its user home and `.gradle/`; compiler and packaging output lives in `.build/`. `gradle.properties` enables Gradle’s configuration cache so repeated builds can reuse the configured task graph. `clean` deletes that scratch tree, the old `build/.build/`, Gradle dependency-verification reports and the two generated JARs, while preserving `build/data/`, `build/defaults/`, `build/deps/` and other deployment files.

The [migration record](docs/gradle-migration.md) maps every old Make target and records verification.

Advanced Training Hall, Laboratory and Hall of Heroes are part of the checked-in defaults, so ordinary builds prepare all seven scenes in `build/defaults/maps/` with verified native entry/return links. `prepareInteriors` and `prepareArchive` are maintainer re-exports that publish fresh packs into `defaults/maps/`. See [map preparation](docs/maps.md) for first-time setup and protection of custom files.

Both JARs keep `data/` beside themselves; copy `build/defaults/` beside them when deploying. Preserve `data/` (especially keys/DB) and customized `defaults/`. The optional startup default-resource downloader points to a placeholder repository; missing maps prevent world entry but not login/admin. See [maps](docs/maps.md).

KnightLauncher settings: `game.endpoint=127.0.0.1` (or reachable server IP), `game.port=47624`, `game.publicKey` = entire contents of `build/data/keys/public.txt`. [Connection help](docs/client-redirection.md).

**Stock F3 GM dashboard:** grant the role server-side (`--set-gm Alice true` or the terminal `gm Alice`), log in with that account, press **F3** and use the client's own **ItemCreator** to create real, persistent inventory items. Nothing grants this automatically; plain accounts are refused. [Admin details](docs/administration.md).

Stop the server before editing `build/data/server.properties`:

```properties
server-ip=0.0.0.0
server-port=47624
max-clients=16
log-mode=normal
```

`max-clients` range 1–1024; `--config` changes the settings-file path, **not** data directory. Logs: `data/logs/latest.log`. Admin manager accepts `--data /path/to/server/data`; it opens no network admin port. `help` in the server terminal lists commands. [Admin details](docs/administration.md), [item data](docs/game-data.md).

## Backups, development and references

Stop server and manager; copy the **whole `data/` directory** (SQLite WAL and keys). Only current schema 8 and JSON item catalogue format v1 are supported; no old-DB imports/migrations. Never discard original state while testing.

Start coding with [AGENTS.md](AGENTS.md) and [project handoff](docs/PROJECT.md); consult [architecture](docs/server-architecture.md) and [development](docs/development.md) for invariants, tests, stock-client checks, benchmarks and cleanup. See [upstream research](docs/server-research.md) only when the task needs it. Do not load all historical documents into agent context.
