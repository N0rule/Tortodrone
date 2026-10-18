# Operating Tortodrone

This is the operator reference for the **current implementation**, not a promise of full vanilla functionality. See [README](../README.md) for a quick start and [Content](content.md) for maps and catalogs.

## Deployment and lifecycle

Install Java 25. Run `java -jar tortodrone-admin.jar` to create accounts, then `java -jar tortodrone.jar` to host; deploy both JARs beside `config/`, `content/` and `plugins/`. Server settings live in `config/server.yml`. The server stores persistent state in **`data/` beside its own JAR**, regardless of your current working directory. For a source checkout, `./gradlew build` produces `build/tortodrone.jar`, `build/tortodrone-admin.jar` and `build/{config,content,plugins}/`.

- `--nogui`: terminal hosting; `--gui`: require desktop; otherwise GUI when available, terminal fallback. Stop via the GUI or `stop` / Ctrl+C in terminal mode.
- `--config path/to/server.yml`: select a config file; **does not move `data/`**. Settings are read at process startup; stop/restart to apply changes.
- Server GUI: Start/Stop/Restart, sessions, scene templates and instances, performance and logs. Command field shares local terminal commands; commands requiring password/confirmation prompts belong in the account manager.
- Account manager: `java -jar tortodrone-admin.jar`; if separate from the deployed server, add `--data /path/to/server/data`. It has GUI/CLI, uses the same SQLite data and does not open a remote admin port.

Edit `config/server.yml` while stopped:

```yaml
server-ip: 0.0.0.0
server-port: 47624
max-clients: 16
log-mode: normal
log-max-mib: 10
log-retention: 5
startup-map: haven
```

`max-clients` accepts 1–1024; `log-mode` accepts `normal` or `debug`; `log-max-mib` accepts 1–1024; `log-retention` accepts 1–100 rotated files. Config file options not recognized by the current server are refused. Runtime logs are in `data/logs/latest.log` with size-based rotation.

## Connecting the unchanged client

Point your separately managed KnightLauncher to:

| Key | Value |
| --- | --- |
| `game.endpoint` | `127.0.0.1` or the reachable server host |
| `game.port` | `47624` (or your configured TCP port) |
| `game.publicKey` | Complete contents of `data/keys/public.txt` beside the deployed server JAR |

The public key is a hexadecimal modulus and exponent separated by `#`; paste the **value**, not a path, PEM header or private key. Preserve `data/keys/` between upgrades. `0.0.0.0` is a listener bind address, never a client destination. Use LAN addressing where appropriate; firewall/router forwarding is needed only when applicable. Restart the client after launcher changes. Use normal username/password; no Steam or guest sign-in.

## Local administration

Run `help` or `help account create` in the server terminal/GUI command field for exact current syntax. Terminal commands are **not** in-game slash commands, and do not require a GM login; restrict access to the host.

| Purpose | Example |
| --- | --- |
| Health and sessions | `status`, `players`, `session 1`, `inspect Jane` |
| Accounts | `account list`, `account create Alice`, `account password Alice`, `account delete Alice` |
| GM role | `gm Alice true` or `account gm Alice false` |
| Knights | `knight list Alice`, `knight info Alice Jane`, `knight delete Alice Jane` |
| Balances | `grant Jane crowns 100`, `set Jane energy 500` |
| Item lookup/grant | `items Calibur`, `items info Weapon/Sword/Calibur`, `giveitem Jane 2 Weapon/Sword/Calibur` |
| Shutdown | `stop` |

Account create/password and destructive deletion require interactive password/confirmation input; use the account manager GUI when the server window can't prompt. Knight names are globally unique without case; different IDs identify sessions, accounts, roster slots, world actors and inventory instances. Never treat them as interchangeable.

The account manager supports account/knight browsing and searchable inventory, item grants, quantity/removal confirmation and balance changes; equipped, starter and permanent item protections apply. For headless account administration use `java -jar tortodrone-admin.jar --help`; `--data` must appear before its command. Examples: `--create-account Alice` and `--set-gm Alice true`.

A saved GM can use the original client's F3 dashboard **ItemCreator** (other tools unsupported); server-side role checks still apply. Verified GM chat commands include `!help`, `!items` and `!giveitem`; ordinary players' leading `!` remains normal chat. Do not assume local admin commands also work inside the game.

## Data safety, updates and release builds

1. Stop both server and account manager before filesystem-level backup/restore. Copy the **whole `data/` tree**, including the SQLite WAL/SHM and persistent RSA keys. Also back up `config/` and the complete `content/` tree.
2. On update replace the JARs and shipped vanilla content as appropriate; **retain your current `data/`**. Never discard unknown schema state or generate replacement keys to repair a failed login. Unsupported database schemas are refused rather than altered automatically.
3. Check `VERSION` and full build/test results. The GitHub Actions release workflow builds on Java 25 and publishes a ZIP and tar.gz **only for version tags** matching `v<VERSION>` (or explicit retry of that existing tag). Packages include JARs, the vanilla content pack and a start note, not JRE, database or game assets. See `.github/workflows/release.yml`.

The server does not download missing content packs at startup. Ship the complete selected packs and configuration, especially on offline hosts.

## Diagnosis

Set `log-mode=debug` while stopped to inspect resource paths, protocol/scene checks and startup warnings. Logs deliberately do not contain passwords/private keys but can contain usernames, IPs, paths and item references; treat them as private.

After an account authenticates and obtains its session, debug mode prints `Client joined` with session/account IDs, username, GM role at login, remote IP/port, client build, timezone, language, client-reported region, transport and login duration. Region is a preference supplied by the client; it is **not verified country information**. Country is shown as `unavailable`; no GeoIP database, DNS query or external lookup is used.

When the unchanged client sends its existing hardware report, one `Client hardware (client-reported)` line adds OS/name/version, CPU identifier, Java version, architecture, desktop/window resolution, fullscreen mode and UI scale. Fields are bounded and escaped, tied to the authenticated account, and advisory only. The native client does not send GPU or RAM specifications here; those are shown as unavailable, and missing CPU/property values as unknown. The summary may arrive after login; clients that do not send it produce no hardware line. Normal mode emits neither summary. Passwords/transforms, installation identifiers, tickets, session tokens and raw authentication payloads remain excluded.

| Symptom | First check |
| --- | --- |
| No TCP connection | Listener, `game.endpoint`/port, firewall |
| Secure exchange rejected | Client has the exact deployed `public.txt` value |
| Login refused | Account exists; verify/reset through local manager |
| Knight/world load refused | Selected pack paths, hashes and scene catalog; see [Content](content.md) |
| Changed maps not appearing | Stop/restart, enabled pack selection and manifest hashes |
| Startup rejects settings/schema | Restore supported settings or backup; do not hand-edit saved state to force startup |
| Client renders or responds incorrectly | Run isolated stock-client compatibility/graphical checks; see [Development](development.md) |

CLI and GUI are local trusted surfaces. Do not expose them as an unauthenticated web admin interface.
