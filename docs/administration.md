# Local administration

Run `java -jar build/tortodrone-admin.jar` on the server PC. It manages accounts
and per-knight currencies in the same SQLite database as the server, with no
remote admin endpoint. It can run alongside the server once the database is
initialized. Use `--data /path/to/server/data` if the JARs are stored separately.
On niri, the GUI sets its Java window-manager compatibility flag automatically.

## Server terminal

Enter commands in the running server, not the shell. A leading `/` is optional.

| Command | Action |
| --- | --- |
| `help`, `status`, `stop` | Commands, status/account count, clean shutdown |
| `account list` | IDs, usernames, Admin flags, character counts |
| `account create Alice [admin]` | Create; prompt twice for a password |
| `account password Alice` | Reset password; prompt twice |
| `account check Alice` | Check a supplied password |
| `account admin Alice true` | Set Admin; `false` clears it |
| `account delete Alice` | Delete account/characters after exact-username confirmation |
| `knight list Alice` | Saved knights and account-local roster IDs |
| `knight info Alice Saved-Knight` | Global identity, balances, items and equipped slots |
| `knight delete Alice Saved-Knight` | Exact knight-name confirmation; delete owned state and disconnect that account |
| `currency give Alice Saved-Knight crowns 100` | Add currency |
| `currency withdraw Alice Saved-Knight energy 5` | Withdraw; reject insufficient funds |

Interactive Up/Down recalls 100 commands in memory. Password/confirmation prompts
and unrecognized input never enter history. Passwords are hidden in a terminal
and never command arguments. Redirected stdin supplies complete UTF-8 lines;
EOF leaves the server running. Logs preserve the active prompt.

For headless administration, run `java -jar build/tortodrone-admin.jar --help`.
Its CLI supports list/create/check/reset/delete/role actions, with `--data` before
the command. Without a terminal, supply password and confirmation as two complete
stdin lines, or one for a check. Old account flags on the server JAR are unsupported.

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
  unique within the account ignoring case. IDs are not recycled.
- Picker edits/deletion refuse online knights. Local deletion revokes the account.
  Reconnect to select another knight.
- One connection per account. A successful new password login replaces the old
  connection, releasing its knight before another can enter. Failed authentication
  leaves it connected. EOF/timeout releases ownership; reconnect normally afterward.
- Crowns/energy belong to each knight, start at zero and range from 0 to
  2,147,483,647. Changes are atomic, audited and visible online within about 250 ms.
  No earning, spending or regeneration rules exist yet.
- Admin is stored for future permissions; there are no in-game admin commands.
  Password reset/deletion revokes sessions within about 250 ms and blocks stale saves.
- Password storage uses a random salt and PBKDF2-HMAC-SHA256, 600,000 iterations,
  over the stock client's password transform. Neither plaintext nor reusable
  transform is stored. Keys are independent of accounts.

## State, upgrades and backup

`data/` beside the server JAR contains `server.properties`, `tortodrone.db`,
optional SQLite `-wal`/`-shm` files, `keys/private.pk8`, `keys/public.txt`,
`logs/latest.log` and `server.lock`. Preserve database and keypair together.
Stop the server and close the manager before copying the whole directory or upgrading.
Keep both JARs on the same release; do not run an older release against upgraded state.

Current schema is 4. Version 0.7.2 adds persistent item binding. All existing
starter items were originally granted equipped and remain bound after migration,
including items now unequipped; their loadout, IDs and balances are preserved.
Equipping binds an item; unequipping never clears binding. Schema 2 added
global world/item identities and the starter kit; schema 3 added balances, state
revision, equipped slots and currency audit events. Upgrades are transactional,
retain account/roster IDs and credentials, and do not regrant kits or re-equip
items on reopening. Existing schema-2 kits are equipped once during upgrade.

Pre-SQLite registries/profiles are imported once, retaining identities, verifiers,
appearance, timestamps and counters. Imported Admin defaults to false; the old
profile-only 0.2 installation retains its fixed development login. Fresh installs
create none. Legacy files remain untouched as backups and are no longer active.
Invalid sources fail migration rather than inventing replacement identities.

Older root-level data must be moved beside the JAR while stopped, without
overwriting an existing deployment. Remove obsolete `data-directory` settings.
Never copy only a live database: pending changes may reside in its WAL.
