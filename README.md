# Tortodrone

Tortodrone is a standalone Java server emulator for Spiral Knights. Development
is limited to the server and normal username/password login. The game and
KnightLauncher are separate projects; Steam and guest login are unsupported.

The server supports normal login, saved characters, and a first playable town
checkpoint: create or select a knight to enter **Haven Town Square**, move and face
the mouse, and equip or unequip the five starter items. Crowns and energy are
persistent counters that the local account manager or server terminal can change.
Intro missions are skipped by directly entering town. Combat, quests, NPC logic,
shops and travel to other scenes are still outside this checkpoint.

## Build on a fresh install

Install Git, GNU Make, JDK 17 or newer, `curl`, and `sha256sum` (GNU coreutils).
The JDK must provide `java`, `javac`, and `jar` on `PATH`. No Maven, Gradle, game
installation, or database server is needed. The first build downloads pinned,
checksum-verified SQLite JDBC and JLine terminal libraries from Maven Central.
The build also downloads a pinned Town Square layout from the
[Spiral Knights Scene Archive](https://github.com/reference-source/SpiralKnightsSceneArchive).
Later builds use cached copies in `build/deps/`. Both executable JARs bundle the
libraries and layout; game rendering assets stay in the separate installed client.

Clone using the repository's actual URL, or enter your existing checkout:

```sh
cd ~/Projects/Tortodrone
make build
```

This creates:

| File | Purpose |
| --- | --- |
| `build/tortodrone.jar` | Game server, including terminal commands |
| `build/tortodrone-admin.jar` | Local desktop account manager |

To build only one, use `make build-server` or `make build-admin`.

## Create an account, then start the server

On the server PC's desktop:

```sh
java -jar build/tortodrone-admin.jar
```

Click **Create account**, enter a username and password, optionally enable
**Admin**, and save. Fresh installations have no automatic account or default
password. The manager shows IDs, usernames, Admin flags, character counts and
creation times. It also supports searching, changing passwords, checking a
supplied password, deleting accounts and refreshing the list. Select an account
and click **Currency**, choose its knight, then give or withdraw crowns or energy.
Changes appear in an online knight’s counters within about 250 ms.

Then run:

```sh
java -jar build/tortodrone.jar
```

`make admin` builds and opens the manager; `make server` builds and starts the
server from `build/`. `make build` only compiles. Stop the server with `stop` in
its terminal or Ctrl+C. No game installation is required to run either JAR.

For deployment, copy both JARs into the same directory. They use `data/` beside
their own JAR, regardless of the shell's current directory. Keep this directory
when replacing the JARs. If the manager is stored elsewhere, point it at the
server's existing data directory:

```sh
java -jar /path/to/tortodrone-admin.jar --data /path/to/server/data
```

On niri, the manager automatically applies Java's window-manager compatibility
setting before opening the desktop window. No desktop configuration changes are needed.

The manager runs locally on the server PC and opens no remote administration port.
Once the database is initialized, it can manage accounts while the server runs.

## Server terminal commands

Type these into the **running server terminal**, without restarting it:

| Command | Action |
| --- | --- |
| `help` | Show available commands |
| `status` | Show status and account count |
| `stop` | Close the listener and clients, then exit |
| `account list` | List IDs, usernames, Admin flags and character counts |
| `knight list Alice` | List Alice’s saved knights and roster IDs |
| `knight info Alice Saved-Knight` | Inspect global identity, balances, item IDs/configurations and equipped slots |
| `currency give Alice Saved-Knight crowns 100` | Add 100 crowns to that knight |
| `currency withdraw Alice Saved-Knight energy 5` | Remove 5 energy; reject insufficient funds |
| `account create Alice` | Create an account; prompt for a password twice |
| `account create Alice admin` | Create with Admin enabled |
| `account password Alice` | Change/reset the password; prompt twice |
| `account check Alice` | Check a supplied password |
| `account admin Alice true` | Enable Admin (`false` disables it) |
| `account delete Alice` | Delete the account and its characters after typing its exact username |

In an interactive terminal, **Up/Down** recall the last **100 commands**. You can
edit a recalled command before pressing Enter. History is kept in memory for the
current server run; consecutive duplicates are skipped. Passwords, deletion
confirmations and unrecognized input are excluded. Logs redraw above the prompt
without losing the command you are typing.

A leading `/` is optional. Passwords are entered at the separate prompts and are
hidden in a real terminal. They are never command arguments or log messages.
Redirected stdin can supply complete UTF-8 lines for commands and prompts. Closing
stdin leaves the server running, useful when starting it as a background service.
The server JAR's old `--create-account`, `--set-password`, and `--list-accounts`
options have been removed.

A forgotten password cannot be recovered: **Check password** verifies a candidate;
**Change password** sets a replacement. Password changes preserve characters.
Password reset or account deletion disconnects existing sessions, checked every
250 ms. Character saves also check the account's authentication revision inside
their database transaction. Admin defaults to false, is stored on the server, and
is reserved for future permissions and in-game commands; setting it does not
unlock gameplay or commands in the client.

Usernames need 3–32 ASCII characters: a letter first, then letters, digits,
underscores or hyphens. They are case insensitive. Passwords need 8–128 characters.
Storage uses a random salt and PBKDF2-HMAC-SHA256 with 600,000 iterations, applied
to the legacy client's password transform. Neither plaintext passwords nor the
reusable client transform are saved.

For a PC without a desktop, the **admin JAR** also offers terminal actions listed
by `java -jar build/tortodrone-admin.jar --help`. These operate on the same local
database. Password prompts are hidden with a terminal; without one, supply the
password and confirmation as two complete stdin lines (one line for a check).

## Files and settings

```text
build/
  tortodrone.jar
  tortodrone-admin.jar
  .build/                       disposable classes and packaging output
  deps/                         cached libraries and Town Square layout
  data/
    server.properties           server settings
    tortodrone.db               accounts, Admin flags, knights, items and balances
    tortodrone.db-wal/-shm       transient SQLite files when in use
    keys/private.pk8            private server identity; keep secret
    keys/public.txt             public key for launcher settings
    logs/latest.log             appended server output
    server.lock                 prevents two servers using this deployment
```

Stop the server before editing `build/data/server.properties`, then restart:

```properties
server-ip=0.0.0.0
server-port=47624
log-mode=normal
```

| Setting | Meaning |
| --- | --- |
| `server-ip` | Local listening interface; `0.0.0.0` accepts all IPv4 interfaces |
| `server-port` | TCP port, default `47624` |
| `log-mode` | `normal` for operational messages; `debug` adds protocol diagnostics |

There is no `data-directory` or `client-host` setting. `--config /path/to/server.properties`
selects a settings file without moving state. First startup creates missing
configuration and keys; existing configuration and identity are preserved.

Startup displays the version bundled from `VERSION`, currently `0.7.0`:

```text
[19:30:01] [ServerMain/INFO]: Starting Tortodrone server version 0.7.0
[19:30:01] [ServerMain/INFO]: Loading properties
[19:30:01] [ServerMain/INFO]: Log mode: normal
[19:30:01] [ServerMain/INFO]: Generating server keypair
[19:30:01] [ServerMain/INFO]: Loading normal accounts and saved characters
[19:30:01] [ServerMain/INFO]: Loaded accounts: 1
[19:30:01] [ServerMain/INFO]: Starting Tortodrone on *:47624
[19:30:01] [ServerMain/INFO]: Preparing Haven Town Square
[19:30:01] [ServerMain/INFO]: Normal login, town movement and starter inventory available.
[19:30:01] [ServerMain/INFO]: Done! Listening on 0.0.0.0:47624 (TCP)
[19:30:01] [ServerMain/INFO]: Type help for server commands.
```

Normal mode includes startup, connection/login events, character saves, commands,
warnings, errors and shutdown. Debug adds protocol diagnostics. Console and
`data/logs/latest.log` share the format and filtering. Neither logs secrets or raw
payloads. Timestamps use local machine time. `--version` shows either JAR's release.

## Upgrade and back up

Stop the old server before upgrading to 0.7.0 or opening the new account manager.
Schema version 3 preserves the global character identities and starter inventories
from 0.6.0, and adds `crowns`, `energy` and `state_revision` to `world_characters`,
plus nullable `equipped_slot` to `inventory_items`. Existing knights start with
zero counters and their existing kit equipped. Reopening does not grant another
kit or re-equip items you removed. A schema 1 database first receives the global
identities from schema 2. The upgrade is transactional and requires the old
server to be stopped. Keep both JARs on the same release; do not run older JARs
against the upgraded database.

For pre-SQLite installations, the first open automatically imports
`data/accounts/users.properties` and the account profiles into SQLite in one
transaction. Account IDs, password verifiers,
knight IDs, appearance, timestamps and next-ID counters are preserved; imported
accounts have Admin=false. The older 0.2.0 profile-only installation retains its
fixed `tortodrone` / `tortodrone` login. New installations do not create that login.

Legacy files stay untouched as backups, and are no longer read or written after
a successful migration. Do not switch back to an older server against those
stale files. Invalid migration sources stop migration instead of assigning new
identities; correct/restore them and retry.

For a simple backup, stop the server and close the manager, then copy the complete
`data/` directory. Do not copy only a live database while its WAL may contain
uncheckpointed changes. Keep the keypair as well as the database. Older runtime
data at the repository root must be moved beside the JAR while stopped, without
overwriting an existing deployment. Remove obsolete `data-directory` settings.

## Connect and create a knight

Configure the separately maintained KnightLauncher:

| Setting | This computer |
| --- | --- |
| `game.endpoint` | `127.0.0.1` |
| `game.port` | `47624` |
| `game.publicKey` | Entire value from `build/data/keys/public.txt` |

On another computer, use the server's reachable LAN/public address. Log in through
the normal username/password form using an account you created. All accounts use
the same endpoint and public key. See [connection guidance](docs/client-redirection.md).

Each account has three character slots. Names contain 3–16 letters with optional
single hyphens and must be unique within that account, ignoring case. Create,
edit and delete save before publishing roster updates to that account's sessions.
Edits preserve IDs and creation times; deletion frees a slot without reusing IDs.
Other accounts cannot receive or alter the roster. Connections have ordered queues
of at most 64 pending frames; a stalled recipient is disconnected.

Creation automatically selects the saved knight and loads Town Square. Selecting
an existing knight loads the same town. Each knight has a server-owned global
identity and five durable starter items: chosen helm and armor, Proto Sword,
Proto Gun and Proto Shield. Open the client’s Arsenal to equip or unequip owned
items. Their slots and balances survive logout and server restarts. Editing or
deleting an online knight is rejected; disconnect it before making picker edits.
Reconnect to change characters in this release.

Town movement runs at 20 updates per second. The server accepts movement and
facing input, checks static walkable terrain, and publishes nearby town knights.
Everyone currently starts at the same town spawn; position is not yet saved.
There are no introductory missions, tutorial rewards or invented completion flags.
The layout can include visual NPCs and portals, but their gameplay is not implemented.

Crowns and energy both belong to **each knight**. They start at zero, have a range
of 0–2,147,483,647, and have no earning, spending or regeneration rules yet.
Give/withdraw actions are atomic, reject overflow and insufficient funds, and
record an audit row in `currency_events`. The manager and terminal operate locally;
Admin is stored for future in-game permissions, with no in-game commands yet.

## Contribute

Read [AGENTS.md](AGENTS.md) and the [development guide](docs/development.md).
Create a branch, change the server, and run checks against disposable data:

```sh
git switch -c describe-your-change
make test
```

`make test-unit` covers storage, desktop table behavior, protocol and sessions.
`make test-integration` starts temporary copies of the JARs and checks live
administration and server commands. No game files or external services are needed
once the driver is cached. `make clean` removes compiled output and both JARs,
preserving runtime data and cached dependencies. Leave test servers stopped.

Review `git diff`, commit your changes and submit the branch for review through
the repository's hosting service. Include what changed and which checks passed.
Never commit build output, runtime data, passwords, private keys or game assets.

See [server architecture](docs/server-architecture.md), the
[world entry plan](docs/world-entry-plan.md), and [dependency notices](docs/dependencies.md).
[Historical research](docs/research/README.md) is evidence of past experiments,
not current setup or client modification instructions.
