# Local administration

Run `java -jar build/tortodrone-admin.jar` on the server PC. It manages accounts
and per-knight currencies in the same SQLite database as the server, with no
remote admin endpoint. It can run alongside the server once the database is
initialized. Use `--data /path/to/server/data` if the JARs are stored separately.
On niri, the GUI sets its Java window-manager compatibility flag automatically.

## Server terminal

Enter commands in the running server, not the shell. A leading `/` is optional. `ac` is an alias for `account` (for example, `ac list`).

| Command | Action |
| --- | --- |
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

Interactive Up/Down recalls 100 commands in memory. Password/confirmation prompts
and unrecognized input never enter history. Passwords are hidden in a terminal
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
The Swing manager and `--list-accounts` list each account’s knight names, separated by commas. It does not yet have an item-grant dialog.

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
- Password storage uses a random salt and PBKDF2-HMAC-SHA256, 600,000 iterations,
  over the stock client's password transform. Neither plaintext nor reusable
  transform is stored. Keys are independent of accounts.

## Server logs

`normal` records startup/readiness, account/character lifecycle, warnings and errors.
Unavailable maps/items produce one resource-fallback warning describing the effect.
`debug` also records configuration/storage paths, default-resource recovery details,
scene IDs, connection lifecycle and protocol message types. Each connection logs
its disconnection once. Neither mode records passwords or authentication payloads.
Set `log-mode=normal` or `log-mode=debug` in `data/server.properties` and restart.

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

Item catalogues must use version 1 JSON. Regenerate an older catalogue with the Java exporter.
Equipped starters are bound; equipping applies the stored bind-on-equip rule and
unequipping retains binding. Reopening never regrants kits or re-equips items.
Preserve external `defaults/` and optional custom `items/` and `maps/` with the deployment.

Never copy only a live database: pending changes may reside in its WAL.
