# Operating Tortodrone

This is the operator reference for the **current implementation**, not a promise of full vanilla functionality. See [README](../README.md) for a quick start and [Content](content.md) for maps and catalogs.

## Deployment and lifecycle

Install Java 25. Run `java -jar tortodrone-admin.jar --create-account Alice` to create accounts, then `java -jar tortodrone.jar` to host; deploy both JARs beside `config/`, `content/` and `plugins/`. Server settings live in `config/server.yml`. The server stores persistent state in **`data/` beside its own JAR**, regardless of your current working directory. For a source checkout, `./gradlew build` produces `build/tortodrone.jar`, `build/tortodrone-admin.jar` and `build/{config,content,plugins,webui}/`.

- Hosting is always headless; `--nogui` remains accepted and `--gui` is rejected.
- `--config path/to/server.yml` selects settings without moving `data/`. Restart the process to apply settings changes.
- One shared HTTP listener uses `webui/content_url-port` (8080 by default; legacy `news-port` is still accepted). `/news/` is public; `/admin/` and `/admin/api/` exist only when enabled.
- With WebUI enabled, stopping the game listener disconnects its clients but keeps HTTP available for restart. Full process shutdown is a separate confirmed WebUI action. With WebUI disabled, terminal `stop` ends the process.
- The admin JAR remains a local CLI. Use `--help` or `--data /path/to/server/data` before a command; it never starts another HTTP service.

Edit `config/server.yml` while stopped:

```yaml
server-ip: 0.0.0.0
server-port: 47624
webui/content_url-port: 8080
webui-enabled: false
webui-public-url: ""
webui-tls-mode: reverse-proxy
max-clients: 16
log-mode: normal
log-max-mib: 10
log-retention: 5
startup-map: haven
```

`max-clients` accepts 1–1024; `log-mode` accepts `normal` or `debug`; `log-max-mib` accepts 1–1024; `log-retention` accepts 1–100 rotated files. Config file options not recognized by the current server are refused. Runtime logs are in `data/logs/latest.log` with size-based rotation.

## Secure WebUI setup

The default `webui-tls-mode: reverse-proxy` uses an HTTPS reverse proxy **on the same host**, forwarding to the existing HTTP port. Explicit `webui-tls-mode: http` permits direct HTTP access for local or LAN testing. Both modes use the same listener and keep login, roles, session expiration, CSRF and Host/Origin validation. The shared port is configured once with `webui/content_url-port`; it also serves News when WebUI is disabled. In direct HTTP mode, leave the port out of `webui-public-url` and it is filled from that shared setting. Existing complete HTTP origins still work. Proxy mode uses the exact external HTTPS origin, which may have a different port from the backend listener. Conflicting legacy `news-port` and shared-port values are rejected rather than opening another listener.

Create credentials locally (passwords are prompted, never command arguments):

```sh
java -jar tortodrone-admin.jar --webui-user operator ADMINISTRATOR
```

For direct local/LAN access with automatic server addresses:

```yaml
server-ip: 0.0.0.0
webui-enabled: true
webui-public-url: http://0.0.0.0
webui/content_url-port: 8080
webui-tls-mode: http
```

Restart the server process and open `http://YOUR_SERVER_IP:8080/admin/` (for example `http://192.168.1.50:8080/admin/`), or `http://localhost:8080/admin/` on the server PC. Here `http://0.0.0.0` is an automatic-address configuration value, not a browser destination: Host must match the connection's concrete local IP and actual listening port; localhost is also accepted for loopback connections. Origin must match that validated Host. Arbitrary DNS hostnames are rejected in automatic mode. Set a concrete `webui-public-url`, such as `http://192.168.1.50`, if you want one fixed allowed address. No domain, certificate, proxy or proxy key is needed. HTTP does not encrypt passwords or session traffic; choose it only on a network you trust. If a browser previously received HSTS for the same hostname, use an IP address or clear that hostname's HSTS setting before testing HTTP.

For the default HTTPS proxy mode, also create a proxy key:

```sh
java -jar tortodrone-admin.jar --webui-proxy-key
```

Protect `data/webui-proxy.key` and the proxy configuration with owner/service-only filesystem permissions; on Windows set equivalent ACLs. Copy that secret into the proxy's `X-Tortodrone-Proxy` header configuration. Do not place it in frontend assets or client configuration. The server validates the loopback peer, proxy secret, public Host, HTTPS assertion and request Origin. The proxy must overwrite incoming authentication/forwarding headers, preserve Origin, and clear unsupported forwarded headers.

Example nginx location inside an existing TLS virtual host (configure certificates and `server_name` for your domain separately):

```nginx
location /admin/ {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host admin.example.org;
    proxy_set_header X-Forwarded-Proto https;
    proxy_set_header X-Tortodrone-Proxy "VALUE_FROM_LOCAL_SECRET_FILE";
    proxy_set_header Forwarded "";
    proxy_set_header X-Forwarded-For "";
    proxy_set_header X-Forwarded-Host "";
    client_max_body_size 64k;
}
location /news/ {
    proxy_pass http://127.0.0.1:8080;
}
```

Then set `webui-enabled: true` and `webui-public-url: https://admin.example.org`, restart the process, and visit `https://admin.example.org/admin/`. Public `/news/` remains available directly on 8080 without login; proxy mode rejects direct administrative requests. The reverse proxy is external infrastructure, not another Tortodrone listener. Deploy the generated `webui/` directory beside the server JAR; no Node runtime, CDN or client installation is needed.

WebUI identities are independent of game accounts and GM status. `VIEWER` can monitor status, sessions and logs; `MODERATOR` can also inspect accounts/knights and kick sessions; `ADMINISTRATOR` can mutate accounts/economy/content and control lifecycle. Re-run `--webui-user NAME ROLE` to change credentials/role and revoke existing sessions, or use `--webui-revoke NAME` to remove access. There are no default credentials. Passwords use independently salted PBKDF2-HMAC-SHA256 with 600,000 iterations; sessions expire after 30 minutes idle or eight hours absolute and are revoked on logout, identity changes or process restart.

The browser uses HttpOnly SameSite cookies and a session-bound CSRF token. HTTPS proxy mode adds the Secure cookie flag and HSTS; direct HTTP uses a separate cookie name without the Secure flag so LAN browsers can send it over HTTP. APIs enforce role, Origin, Host, size and concurrency limits. Login is globally limited to ten attempts per minute; HTTP request deadlines bound stalled requests. Privileged action attempts/outcomes are stored in `data/webui.sqlite`, separately from game data. A requested action without a final outcome indicates interruption and must be inspected before retrying. Audit failure prevents starting an action; the game services keep their own transactional ledgers. Do not delete either database to clear logs.

The UI includes monitoring charts, players/scenes, accounts and knights, inventory/modifiers/accessories, binding/expiry on grants, Depot offers/promotions/effective catalogue, auction escrow/deliveries, searchable logs/export, content validation and News preview/editing. News editing only replaces validated `news.yml`, using existing images; listener restart publishes changed pack selection. The native protocol selects a random pack, so array order is preserved without promising a rotation order. Item support remains constrained by the authoritative catalogue and services.

## Connecting the unchanged client

Point your separately managed KnightLauncher to:

| Key | Value |
| --- | --- |
| `game.endpoint` | `127.0.0.1` or the reachable server host |
| `game.port` | `47624` (or your configured TCP port) |
| `game.publicKey` | Complete contents of `data/keys/public.txt` beside the deployed server JAR |

The public key is a hexadecimal modulus and exponent separated by `#`; paste the **value**, not a path, PEM header or private key. Preserve `data/keys/` between upgrades. `0.0.0.0` is a listener bind address, never a client destination. Use LAN addressing where appropriate; firewall/router forwarding is needed only when applicable. Restart the client after launcher changes. Use normal username/password; no Steam or guest sign-in.

## Local administration

Run `help` or `help account create` in the server terminal for exact current syntax. Terminal commands are **not** in-game slash commands, and do not require a GM login; restrict access to the host.

| Purpose | Example |
| --- | --- |
| Health and sessions | `status`, `players`, `session 1`, `inspect Jane` |
| Accounts | `account list`, `account create Alice`, `account password Alice`, `account delete Alice` |
| GM role | `gm Alice true` or `account gm Alice false` |
| Knights | `knight list Alice`, `knight info Alice Jane`, `knight delete Alice Jane` |
| Balances | `grant Jane crowns 100`, `set Jane energy 500` |
| Item lookup/grant | `items Calibur`, `items info Weapon/Sword/Calibur`, `giveitem Jane 2 Weapon/Sword/Calibur` |
| Shutdown | `stop` |

Account create/password and destructive deletion require interactive password/confirmation input. Knight names are globally unique without case; different IDs identify sessions, accounts, roster slots, world actors and inventory instances. Never treat them as interchangeable.

The WebUI supports account/knight browsing and searchable inventory, item grants, quantity/removal confirmation and balance changes; equipped, starter and permanent item protections apply. For headless account administration use `java -jar tortodrone-admin.jar --help`; `--data` must appear before its command. Examples: `--create-account Alice` and `--set-gm Alice true`.

A saved GM can use the original client's F3 dashboard **ItemCreator** (other tools unsupported); server-side role checks still apply. Tortodrone adds no GM chat commands, so ordinary players' leading `!` stays normal chat. Do not assume local admin commands also work inside the game; item grants run through the account manager, `tortodrone-admin.jar` or the server console.

## Data safety, updates and release builds

1. Stop both server and account manager before filesystem-level backup/restore. Copy the **whole `data/` tree**, including the SQLite WAL/SHM and persistent RSA keys. Also back up `config/` and the complete `content/` tree.
2. On update prepare and validate a separate content candidate; **retain your current `data/`**. Schemas 9–15 require [explicit economy upgrade/export](content.md#upgrading) before replacing the legacy Depot YAML. Never discard unknown schema state or generate replacement keys to repair a failed login. Unsupported database schemas are refused rather than altered automatically.
3. Check `VERSION` and run required tests before releasing. The GitHub Actions release workflow runs **only when a tag matching `v<VERSION>` is pushed** (for example `git tag v0.36.23` followed by `git push origin v0.36.23`). It uses Java 25 and `./gradlew build` to publish **one cross-platform ZIP** with all files and folders generated in build/ plus `README.md` and `docs/` at the ZIP root. The workflow does not run regression tests or bundle a JRE, databases, runtime data, keys or game assets. See `.github/workflows/release.yml`.

The server does not download missing content at startup. Ship complete unified content and configuration. Depot defaults are bundled inside the JAR; live offers and promotions are edited in the account manager. Customized files receive review proposals under content-updates/, never an automatic semantic merge.

## Diagnosis

Set `log-mode=debug` while stopped to inspect resource paths, protocol/scene checks and startup warnings. Logs deliberately do not contain passwords/private keys but can contain usernames, IPs, paths and item references; treat them as private.

After an account authenticates and obtains its session, debug mode prints `Client joined` with session/account IDs, username, GM role at login, remote IP/port, client build, timezone, language, client-reported region, transport and login duration. Region is a preference supplied by the client; it is **not verified country information**. Country is shown as `unavailable`; no GeoIP database, DNS query or external lookup is used.

When the unchanged client sends its existing hardware report, one `Client hardware (client-reported)` line adds OS/name/version, CPU identifier, Java version, architecture, desktop/window resolution, fullscreen mode and UI scale. Fields are bounded and escaped, tied to the authenticated account, and advisory only. The native client does not send GPU or RAM specifications here; those are shown as unavailable, and missing CPU/property values as unknown. The summary may arrive after login; clients that do not send it produce no hardware line. Normal mode emits neither summary. Passwords/transforms, installation identifiers, tickets, session tokens and raw authentication payloads remain excluded.

| Symptom | First check |
| --- | --- |
| No TCP connection | Listener, `game.endpoint`/port, firewall |
| Secure exchange rejected | Client has the exact deployed `public.txt` value |
| Login refused | Account exists; verify/reset through local manager |
| Knight/world load refused | Unified content validation and native scene bindings; see [Content](content.md) |
| Changed maps not appearing | Validate static content, then stop/restart; review content-updates conflicts |
| Startup rejects settings/schema | Restore supported settings or backup; do not hand-edit saved state to force startup |
| Client renders or responds incorrectly | Run isolated stock-client compatibility/graphical checks; see [Development](development.md) |

The CLI is a local trusted surface. The WebUI exposes only typed, authorized operations; arbitrary terminal commands are never available remotely.
