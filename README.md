<p align="center">
  <img src="docs/assets/banner.png" alt="Tortodrone" width="100%">
</p>

<h1 align="center">Tortodrone</h1>

<p align="center">
  <strong>Run your own Spiral Knights world with the unmodified official client.</strong>
</p>

<p align="center">
  <a href="https://github.com/N0rule/Tortodrone/releases"><img alt="Latest release" src="https://img.shields.io/github/v/release/N0rule/Tortodrone?include_prereleases&label=release&style=flat-square"></a>
  <a href="https://adoptium.net/temurin/releases/?version=25"><img alt="Java 25" src="https://img.shields.io/badge/Java-25-orange?style=flat-square"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-PolyForm%20Noncommercial%201.0.0-blue?style=flat-square"></a>
</p>

Tortodrone is an independent Java 25 server for **Spiral Knights**. It hosts a persistent world for the unmodified official client, with authoritative movement and combat plus both console and browser-based administration. Accounts use normal usernames and passwords; Steam and guest login are not supported. Tortodrone is not affiliated with or endorsed by the owners of Spiral Knights.

> [!IMPORTANT]
> **Development status:** this is an active development release, not a complete replacement for the official server. Login, the native Ready Room, Haven, supported interiors, and many town and player systems work. The full opening route to Haven, general missions, guilds, PvP, Arcade play, and some item effects are still unfinished. See [Supported scope](#supported-scope) before inviting players.

## Highlights

- **Unmodified client** — connect with your own official installation through KnightLauncher. No client patches are required.
- **Portable server** — one JAR plus `config/`, `content/`, `webui/`, and `plugins/`; no external database or runtime services.
- **Built-in administration** — manage accounts, knights, inventory, economy, and News from the terminal or the WebUI.
- **Opening content included** — nine bundled maps, including Crash Site 398 and initial Rescue Camp 217. New knights start in Crash Site; no separate import is needed.
- **Source-available** — build, inspect, and modify the server under the project license.

**Jump to:** [Quick start](#quick-start-release-zip) · [Build from source](#run-from-source) · [Configuration](#configuration) · [Troubleshooting](#troubleshooting) · [Documentation](#documentation)

## Requirements

| Requirement | Details |
| --- | --- |
| **Java** | JDK 25 (Temurin recommended). A JRE alone is not enough for first-start WebUI artwork preparation. |
| **Spiral Knights** | Your own official installation. The client and launcher are not included. |
| **Launcher** | KnightLauncher, configured to connect to your server. |
| **Network** | TCP `47624` for the game. HTTP `8080` for the WebUI and public News. |
| **Source builds** | Git and JDK 25. No system Gradle, Node.js, Python, or database server is required. |

## Quick start (release ZIP)

The portable release ZIP is the fastest way to run Tortodrone. It contains the server, configuration, content, WebUI, documentation, and license notices.

Playing alone on the same computer? Steps 1–5 are all you need; step 6 adds browser administration. After the first setup, start the server again with `java -jar tortodrone.jar`.

### 1. Install Java 25

Windows (PowerShell):

```powershell
winget install EclipseAdoptium.Temurin.25.JDK
```

If `winget` is unavailable, download **Temurin 25** from [Adoptium](https://adoptium.net/temurin/releases/?version=25).

Linux: install OpenJDK 25 with your package manager if available, or download **Temurin 25** from the same Adoptium page.

Open a new terminal and confirm the version:

```sh
java -version
```

The output must report Java **25**. If another version appears, fix `JAVA_HOME` or `PATH` before continuing.

### 2. Download and extract

Download the latest release ZIP from the [Releases page](https://github.com/N0rule/Tortodrone/releases), then extract the **complete archive** into its own folder. Do not run the server from inside the ZIP. Keep `config/`, `content/`, `webui/`, and `plugins/` beside the JAR.

Linux example (use the version you downloaded):

```sh
unzip Tortodrone-v0.40.2.zip -d tortodrone
cd tortodrone
```

On Windows, use your archive tool's **Extract All** action and open the extracted folder in PowerShell or Command Prompt.

### 3. Start the server

From the extracted folder:

```sh
java -jar tortodrone.jar
```

The first start creates `data/`. With the WebUI enabled (the default), it also tries to prepare artwork from your installed game client, searching the standard Steam locations first. If the client is not found, enter its installation folder (the one containing `code/projectx-pcode.jar` and `rsrc/`), or leave the prompt blank to skip. You can also select it explicitly:

```sh
java -jar tortodrone.jar --client "/path/to/Spiral Knights"
```

For an unattended server without a client installation, use:

```sh
java -jar tortodrone.jar --skip-client-assets
```

Hosting still works without a client; the WebUI falls back to built-in icons.

> [!NOTE]
> Keep the terminal open while the server is running. To stop it cleanly, type `shutdown` in the server console and confirm.

### 4. Create a game account

In the **server console**, enter:

```text
account create Alice
```

Follow the password prompts. There are no default game accounts. Type `help` to list the available console commands.

### 5. Connect the game client

Use your own unmodified Spiral Knights installation with KnightLauncher. Set these launcher properties:

```properties
game.endpoint=127.0.0.1
game.port=47624
game.publicKey=<complete contents of data/keys/public.txt>
```

- Paste the **entire public value**, including its `#` separator — not the filename.
- Use `127.0.0.1` when the client runs on the server computer. For another computer, use the server's reachable LAN or public address. `0.0.0.0` is a bind address, not a client destination.
- Never share the `data/keys/` directory. It contains the matching private key.
- Restart the client after changing launcher settings.

A new knight enters Crash Site automatically. An existing completed knight returns to the native Ready Room, where **Return to Game** uses its saved destination.

### 6. Open the WebUI (optional)

On the server computer, visit [http://localhost:8080/admin/](http://localhost:8080/admin/). Startup also prints the available WebUI addresses for LAN access.

The first visit asks you to create an administrator. Enter the eight-digit one-time setup code printed in the **server console** after startup. WebUI accounts are separate from game accounts and GM roles. See [Operations](docs/operations.md#secure-webui-setup) for LAN access and HTTPS proxy setup.

## Playing with others

1. Keep `server-ip: 0.0.0.0` in `config/server.yml` so the server accepts LAN connections.
2. Allow TCP `47624` (game) through the server firewall. Allow TCP `8080` only if the WebUI or News must be reachable from other computers. For internet play, forward the game port on your router.
3. Create a game account for each player with `account create <name>` in the console or through the WebUI.
4. Give players your reachable server address for `game.endpoint` and the **public** key from `data/keys/public.txt`.
5. Restart the server after changing configuration.

> [!WARNING]
> The WebUI defaults to unencrypted HTTP on port `8080`. Use it only on a trusted network, or configure the [documented HTTPS reverse proxy](docs/operations.md#secure-webui-setup) before exposing administration publicly. The game port should also be exposed only when you intend to accept outside players.

## Run from source

Use this path to build the server yourself, modify it, or contribute.

### Build

You need **Git** and **JDK 25** on `PATH`. The included Gradle wrapper downloads build dependencies on first use; no system Gradle is required.

```sh
git clone https://github.com/N0rule/Tortodrone.git
cd Tortodrone
./gradlew build
```

On Windows, use `.\gradlew.bat build`. The first build downloads Gradle and the project dependencies. Later builds can use `--offline` once the cache is populated.

The build produces one deployment under `build/`:

```text
build/
  tortodrone.jar
  config/          server and combat settings
  content/         maps, items, and town definitions
  webui/           administration frontend
  plugins/         reserved; no plugin loader yet
  data/            created at runtime: accounts, databases, keys, and logs
```

Start it with:

```sh
java -jar build/tortodrone.jar
```

Then create an account and connect the client as described in [Quick start](#quick-start-release-zip). For a source build, the public key is at `build/data/keys/public.txt`. A release ZIP uses the same layout, with these files directly beside the JAR instead of under `build/`.

### Verify bundled opening content (optional)

The build includes Crash Site **398** and initial Rescue Camp **217** with their original layouts, collision, actors, and conversations. To check the deployment before starting:

```sh
java -cp build/tortodrone.jar scripts/CheckOpeningDeployment.java build
```

This validates the pinned scenes, entrances, and route prerequisites. It does **not** prove a full gameplay playthrough; see the [Crash Site playtest](docs/content.md#local-crash-site-playtest) for manual acceptance.

### Build without tests

`./gradlew build` packages the server and does not run the test suite. For focused checks:

```sh
./gradlew testUnit -PunitTests=SceneSimulationTest,MovementCollisionTest
```

Replace the example names with the tests relevant to your change. `./gradlew testAll` is the expensive full suite and is not part of the normal workflow. See [Development](docs/development.md) before contributing.

## Configuration

Settings live in `config/server.yml` in a release ZIP, or `build/config/server.yml` in a source build. Stop the server before editing; restart to apply changes.

| Setting | Default | Purpose |
| --- | --- | --- |
| `server-ip` | `0.0.0.0` | Bind address. `0.0.0.0` accepts local and LAN connections. |
| `server-port` | `47624` | Game TCP port. |
| `webui-enabled` | `true` | Enables administration and WebUI routes. |
| `webui/content_url-port` | `8080` | Shared HTTP port for the WebUI and public News. |
| `webui-public-url` | `0.0.0.0` | Automatic address selection; use a concrete address for a fixed public URL. |
| `webui-tls-mode` | `http` | Direct HTTP on a trusted network, or `reverse-proxy` for HTTPS. |
| `max-clients` | `16` | Maximum simultaneous game clients. |
| `startup-map` | `haven` | Installed map folder used by the startup flow; it must be a town. |

Unrecognized settings are refused rather than silently ignored. See [Operations](docs/operations.md) for the full reference, WebUI security, and lifecycle commands.

## Data, updates, and backups

All runtime state lives in **`data/` beside the JAR**, regardless of the working directory:

| Path | Contents |
| --- | --- |
| `data/tortodrone.db` | Game accounts, knights, inventory, currencies, progression, and economy records. |
| `data/webui.sqlite` | WebUI identities and audit records. |
| `data/keys/` | The server key pair. Preserve it across updates. |
| `data/logs/` | Rotated server logs. |
| `data/client-artwork/` | Cached WebUI artwork prepared from your client. |

To back up or move a deployment, stop the server and copy the **whole `data/` tree**, plus `config/` and any customized `content/`. Keep SQLite WAL/SHM files with the database.

To update:

1. Stop the server and back up `data/`, `config/`, and `content/`.
2. Replace the program files with the new release, but **keep your existing `data/`, `config/`, and customized `content/`**.
3. Review any proposed content changes under `content-updates/` before accepting them.
4. Start the new version and verify login and the WebUI.

> [!CAUTION]
> Do not delete the database or regenerate keys to fix a login problem. Unknown database schemas are refused instead of migrated automatically. Older schemas require the explicit [offline upgrade](docs/content.md#upgrading).

## Supported scope

| Area | Status |
| --- | --- |
| **Accounts and world** | Supported: knight management, native Ready Room, authoritative movement and collision, nine bundled maps. |
| **Opening route** | Partial: Crash Site 398 → initial Rescue Camp 217 is implemented; use the [manual playtest](docs/content.md#local-crash-site-playtest) for acceptance. The later route to Haven is unfinished; Ancient Generator still needs a full-floor playthrough, and Crossing the Chasm is incomplete. |
| **Player systems** | Partial: persistent inventory and currencies, equipment and costumes, weapon combat, heat, and battle sprites. Individual item and action support varies. |
| **Town and social** | Partial: supported shops and crafting, auctions, Energy Exchange, chat, friends, parties, and eligible friend travel. |
| **Administration** | Supported: game account management, inventory and economy tools, News, monitoring, and lifecycle controls through the console and WebUI. |
| **Missions, guilds, PvP, Arcade** | Incomplete or not implemented. |

Imported definitions and passing serialization tests do not establish full gameplay or visual parity. See [Content](docs/content.md), the [item support inventory](docs/item-support.csv), and the [box support inventory](docs/box-support.csv) for current limits.

## Troubleshooting

| Symptom | First check |
| --- | --- |
| `java` is not recognized or reports the wrong version | Install JDK 25, open a new terminal, and run `java -version`. |
| Client cannot connect | Confirm the server is running, `game.endpoint` and `game.port` are correct, and TCP `47624` is allowed through the firewall. |
| Secure exchange rejected | `game.publicKey` must be the exact contents of `data/keys/public.txt`, including the `#` separator. |
| Login refused | Create the account in the server console or verify its password. Game accounts and WebUI accounts are separate. |
| WebUI does not load | Check `webui-enabled: true` and open `http://localhost:8080/admin/` on the server computer. |
| WebUI setup code is missing | The code is printed in the server console after startup. Restarting the process generates a new code. |
| Startup asks for a client folder | Enter the folder containing `code/projectx-pcode.jar` and `rsrc/`, or leave it blank. Use `--skip-client-assets` for unattended hosting. |
| Port already in use | Stop the other process or change the relevant port in `config/server.yml`. |
| Startup rejects settings or the database schema | Restore the previous working configuration or backup. Do not hand-edit saved state to force startup. |
| Changed map content is not appearing | Validate and restart the server, then review `content-updates/` for conflicts. See [Content](docs/content.md). |

More diagnostic guidance is in [Operations](docs/operations.md#diagnosis). Logs are written to `data/logs/latest.log`; set `log-mode: debug` while stopped for more detail.

## Documentation

| Guide | Use it for |
| --- | --- |
| [Operations](docs/operations.md) | Hosting, WebUI security, backups, updates, and troubleshooting |
| [Commands](docs/commands.md) | Console and player/GM command syntax |
| [Content](docs/content.md) | Opening playtests, maps, items, and content editing |
| [Development](docs/development.md) | Focused tests, packaging, and client compatibility checks |
| [Tools](scripts/README.md) | Importers, exporters, and diagnostic tools |
| [Architecture](docs/architecture.md) | Identity, storage, protocol, and simulation boundaries |
| [Licensing and provenance](docs/provenance.md) | Project licensing and third-party content rights |
| [Contributing](CONTRIBUTING.md) | Contribution requirements and verification expectations |

## Project layout

```text
src/main/java/    server implementation
src/test/         regression programs and fixtures
src/webui/        administration frontend
config/           default configuration
content/          maintained server definitions and bundled maps
scripts/          import, inspection, and compatibility tools
docs/             operator guides and technical references
gradle/           pinned wrapper and dependency verification
licenses/         third-party license texts
```

## License

Original Tortodrone code and documentation are licensed under [PolyForm Noncommercial 1.0.0](LICENSE): noncommercial use and modification are permitted under its terms, and redistributed copies must retain the [N0rule/Tortodrone attribution notice](NOTICE). This is source-available, not OSI open source.

Spiral Knights, its original content, third-party dependencies, bundled scene data, and extracted artwork retain their own rights. The project license does not grant rights to those materials. See [licensing and third-party content](docs/provenance.md), the [dependency notices](src/main/resources/THIRD-PARTY.md), and [contribution rules](CONTRIBUTING.md).
