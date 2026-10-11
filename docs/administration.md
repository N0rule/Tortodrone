# Local administration

Run `java -jar build/tortodrone-admin.jar` on the server PC. It manages accounts
and per-knight inventory/currencies in the same SQLite database as the server, with no
remote admin endpoint. It can run alongside the server once the database is
initialized. Use `--data /path/to/server/data` if the JARs are stored separately.
On niri, the GUI sets its Java window-manager compatibility flag automatically.

## Server terminal

The server opens its local control window by default when a desktop is available;
headless hosts use terminal mode. `--nogui` forces terminal hosting; `--gui`
requires a desktop. Both accept `--config`; help/version do not open a window.
See [server GUI and lifecycle](server-gui.md), [all local commands](commands.md)
and [logging](logging.md). The GUI command field shares terminal command services,
but password and confirmed deletion commands direct you to the account manager.


Enter commands in the running server, not the shell. A leading `/` is optional. `ac` is an alias for `account` (for example, `ac list`).

| Command | Action |
| --- | --- |
| `players`, `session <session-id>`, `inspect <knight-name>` | Live sessions, verified identities/locations and stored knight state |
| `grant Saved-Knight crowns -25`, `set Saved-Knight energy 500` | Recommended relative/absolute balance changes by globally unique name |
| `help`, `status`, `stop` | Commands, status/account count, clean shutdown |
| `account list` | IDs, usernames, Game Master roles, character counts |
| `account create Alice [gm]` | Create; prompt twice for a password |
| `account password Alice` | Reset password; prompt twice |
| `account check Alice` | Check a supplied password |
| `gm Alice`, `account gm Alice` | Toggle Game Master; append `true` or `false` to set explicitly |
| `account delete Alice` | Delete account/characters after exact-username confirmation |
| `knight list Alice` | Saved knights and account-local roster IDs |
| `knight info Alice Saved-Knight` | Global identity, balances, items and equipped slots |
| `knight delete Alice Saved-Knight` | Exact knight-name confirmation; delete owned state and disconnect that account |
| `currency give Alice Saved-Knight crowns 100` | Add currency |
| `currency withdraw Alice Saved-Knight energy 5` | Withdraw; reject insufficient funds |
| `items Calibur` | Find item references (first five matches) |
| `giveitem Saved-Knight 2 Weapon/Sword/Calibur` | Grant distinct unequipped equipment instances |

Use `help` for a grouped overview, `help account create` for specific usage and examples,
or type a command such as `account` without arguments to see its available forms.
`account help`, `account create --help`, and `gm --help` also work.

Interactive Up/Down recalls the last 100 typed commands in memory, including typos,
incomplete commands and repeated commands. History is never written to disk.
Password/confirmation prompts never enter history. Passwords are hidden in a terminal
and never command arguments. Redirected stdin supplies complete UTF-8 lines;
EOF leaves the server running. Logs preserve the active prompt.

Game Masters use `!help` to list supported chat commands, normal scene chat `!items Calibur` and
`!giveitem Saved-Knight 2 Weapon/Sword/Calibur`. Targets use globally unique knight names;
the previous account-qualified `giveitem` form remains supported in console and chat. The result is private to the
sender. An unknown GM `!` command returns “Unknown command. Use !help for available
commands.” Normal players, including staff whose role was revoked, send all `!`
text as ordinary scene chat; it never invokes a staff command or shows its help.
Console/CLI commands are local administration and do not require an account
role. The CLI accepts `--items Calibur` and
`--give-item Alice Saved-Knight 2 "Weapon/Sword/Calibur"`; only shell commands quote
multiword references. See [catalogue preparation and supported item limits](game-data.md).
The account list and `--list-accounts` show saved knight names. In the Swing
manager, selecting an account opens its knight list; selecting a knight shows
owner/roster/world IDs, balances and searchable inventory. Inventory rows show
friendly names, actual catalogue types, canonical paths, instance IDs, quantities,
binding and equipped slots. Add item searches the catalogue with category filters
and paging, explains unavailable definitions and grants 1–20 unequipped items.
Set quantity and Remove item require confirmation and use transactional identity
checks; starter, permanent and equipped instances are protected. Currency supports
Add, Remove and Set, including setting a balance to zero. Account/password/role and
exact-name deletion actions retain their existing services and safeguards.

For headless administration, run `java -jar build/tortodrone-admin.jar --help`.
Its CLI supports list/create/check/reset/delete/role actions, with `--data` before
the command. Without a terminal, supply password and confirmation as two complete
stdin lines, or one for a check. Old account flags on the server JAR are unsupported.

Use `--create-account Alice --gm` or `--set-gm Alice true` in the local tool.
Only the `gm`, `--gm` and `--set-gm` role spellings are supported.

`--list-knights Alice` shows names/IDs/balances. `--delete-knight Alice Saved-Knight`
requires one stdin line containing the exact saved knight name. The desktop's
**Delete knight** action uses the same service and identity checks. Local deletion
can remove an online knight: its account is revoked and must reconnect. Other
knights, passwords and keys are retained; that knight's item/balance/currency-audit
rows are removed. Picker deletion still refuses online knights.

## Account and character rules

- Usernames: 3–32 ASCII characters, a letter first, then letters/digits/underscore/
  hyphen; case insensitive. Passwords: 8–128 characters, resettable but not recoverable.
- Three knight slots per account. Names: 3–16 letters with optional single hyphens,
  globally unique across all accounts ignoring case. Creation and renaming check
  availability; simultaneous requests cannot claim the same name. IDs are not recycled.
- Picker edits/deletion refuse online knights. Local deletion revokes the account.
  Reconnect to select another knight.
- One connection per account. A successful new password login replaces the old
  connection, releasing its knight before another can enter. Failed authentication
  leaves it connected. EOF/timeout releases ownership; reconnect normally afterward.
  Login returns the stock “No such user” code for an unknown username and the
  stock invalid-password code for an existing account with the wrong password.
- Crowns/energy belong to each knight, start at zero and range from 0 to
  2,147,483,647. Changes are atomic, audited and visible online within about 250 ms.
  No earning, spending or regeneration rules exist yet.
- Game Masters receive the stock nameplate subtitle and support icon, replacing
  the prestige icon without changing prestige rank. Stock `/broadcast message`
  reaches all ready scenes/instances and requires the current stored GM role.
  Limits match scene chat; item search/grants use the same private scene-chat command
  path described above. Other staff gameplay commands remain unavailable. Role
  changes update online player/occupant metadata within about 250 ms; permission
  checks read storage immediately. Normal players cannot broadcast or grant roles.
  Password reset/deletion revokes sessions within about 250 ms and blocks stale saves.
- The original stock F3 Game Master dashboard opens for such accounts; see
  [Game Master dashboard](#game-master-dashboard) below.

## Game Master dashboard

The unmodified client's own **F3** dashboard, `AdminDashboard`, is served by the stock
`com.threerings.projectx.admin.data.ProjectXAdminMarshaller`. The server advertises it as
service 13 in the bootstrap notification, so `requireService` resolves it exactly as the
shipped client expects. No custom panel, endpoint or protocol is involved.

### Give your own account the role

Roles live on the **account**, never on a knight, and are only assigned explicitly from the
server side. Choose one:

```sh
# While the server is stopped (local tool, disposable data for tests):
java -jar build/tortodrone-admin.jar --create-account Alice --gm
java -jar build/tortodrone-admin.jar --set-gm Alice true

# In the running server's terminal:
gm Alice                 # or: account gm Alice true
```

Revoke with `gm Alice false`, `--set-gm Alice false`, or `account gm Alice false`. The change
takes effect for a logged-in account within about 250 ms; a knight keeps its appearance and
position. Nothing grants the role automatically, and ordinary accounts never see the dashboard.

### Open ItemCreator

1. Point the stock client at this server (`game.endpoint`, `game.port`,
   `game.publicKey` = `build/data/keys/public.txt`).
2. Log in with the Game Master account and select a knight.
3. Press **F3**. The dashboard lists the tools the account's token tier unlocks.
4. Choose **ItemCreator**, browse the client's own item configurations, set *Count*,
   leave *Locked* and *Expire Days* clear, and press **Create**.
5. The item appears in the knight's inventory immediately, is unequipped, and is stored in
   `tortodrone.db`. Reconnect to verify it persists.

Failures are reported in the admin chat panel: unknown references, a quantity above the
server's 20-per-grant limit, expiry and locked modifiers that this server does not store.

### What the server checks

Every administrative call is authorized again on the server, even though the client already
hides tools the token does not unlock. A grant resolves the reference through the deployed
item catalogue, re-reads the caller's stored Game Master role inside the same SQLite
transaction, inserts the items for the caller's own knight, writes an item audit event and
publishes the resulting inventory change. A revoked account, a plain account and an unknown
reference are each refused with a reason; none of them changes the inventory.

### Supported and unsupported tools

| Dashboard tool | Stock RPC id | Status |
| --- | --- | --- |
| ItemCreator | 7 `createItem` | Supported; real, persistent, unequipped grant |
| SpriteCreator | 8 `createSprite` | Refused: no battle-sprite storage on this server |
| ActorSpawner | 47 `spawnActor` | Refused: needs the stock actor-subsystem |
| EffectFirer | 9 `fireEffect` | Refused: accounts are not granted the ADMIN token ring |
| PlaceBrowser, PrefabTester, SceneTester, SceneSaver | 14, 50, 51, 48 | Refused: those distributed objects do not exist here |
| NodeCommander, ServerConfigurator, RuntimeAdjuster, BulkMailer, PeerAdministrator, LoadTester | various | Refused: multi-node administration is out of scope |

Anything unsupported answers the stock confirm listener with
“This admin tool is not available yet”, so the dashboard never waits for a missing callback.
The client's own permission model is unchanged: the account token ring still gates the
dashboard (a support-plus account, never the ADMIN token ring), and the per-knight Game
Master nameplate badge remains independent of these powers.
- Password storage uses a random salt and PBKDF2-HMAC-SHA256, 600,000 iterations,
  over the stock client's password transform. Neither plaintext nor reusable
  transform is stored. Keys are independent of accounts.

## Server logs

`normal` records measured startup/readiness, connections, verified account/knight
identities, scene transitions, administration outcomes, warnings and errors.
Unavailable maps/items produce one resource-fallback warning describing the effect.
`debug` also records configuration/storage paths, default-resource recovery details,
scene/resource timings, storage diagnostics and protocol message types. Each connection logs
its disconnection once. Neither mode records passwords or authentication payloads.
Set `log-mode=normal` or `log-mode=debug` in `data/server.properties` and restart.
UTF-8 `latest.log` rotates to numbered `server-N.log` files: `log-max-mib=10`
(default, range 1–1024), `log-retention=5` (default, range 1–100). The GUI filter,
pause, Clear view and Debug checkbox do not change disk logging. See
[logging settings, ID scopes and privacy](logging.md).

## State and backup

`data/` beside the server JAR contains `server.properties`, `tortodrone.db`,
optional SQLite `-wal`/`-shm` files, `keys/private.pk8`, `keys/public.txt`,
`logs/latest.log` and `server.lock`. Preserve database and keypair together.
Stop the server and close the manager before copying the whole directory or replacing the JARs.
Keep both JARs on the same release; only matching database schemas are supported.

Current schema is 8. Fresh databases are created directly with the current tables,
indexes and constraints. Only that exact schema is accepted: older, future,
unversioned and corrupt databases are refused without replacement. There are no
schema upgrades, account/profile property readers, imports or automatic development
accounts. Use a fresh data directory when adopting this release from an older
schema; preserve the original data and keys as backups. No data is discarded by
the tools.

Item catalogues are exported as version 2 JSON; version 1 reading remains supported.
Derived friendly names, category aliases and inspection/search do not add JSON or
wire fields. See [catalogue identity and availability](game-data.md).
Equipped starters are bound; equipping applies the stored bind-on-equip rule and
unequipping retains binding. Reopening never regrants kits or re-equips items.
Preserve external `defaults/` and optional custom `items/` and `maps/` with the deployment.

Never copy only a live database: pending changes may reside in its WAL.
