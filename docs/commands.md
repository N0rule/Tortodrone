# Local commands and supported chat commands

The running server terminal and the server window's command field share
[ServerConsole](../src/main/java/org/tortodrone/server/ServerConsole.java).
These are trusted local administration interfaces: they do not require a Game
Master account. Keep access to the server desktop, terminal and data restricted.
The account manager has its own local GUI and CLI; there is no remote admin endpoint.

Use `help`, `help account create`, `account help`, or `account create --help` for
usage. `ac` aliases `account`; a leading `/` is optional in the **local console**.
Commands are limited to 4096 characters. Whitespace separates parameters; item
references may contain spaces as the final parameter. Terminal commands do not
use shell quoting. Up/Down recalls 100 commands, including unsuccessful input;
password and confirmation prompts are separate. History is held in memory.
Terminal EOF leaves the listener running; Ctrl+C requests shutdown.

## Server and identity inspection

| Command | Parameters and result | Example |
| --- | --- | --- |
| `help [command] [action]` | Grouped overview or usage; no mutation | `help knight delete` |
| `status` | Version, state, endpoint, uptime, sessions/players, resources, heap and accounts | `status` |
| `players` | Current TCP sessions, verified identities and scene/instance location | `players` |
| `session <session-id>` | One active session; unknown IDs are refused | `session 1` |
| `inspect <knight-name>` | Persistent global/roster/account IDs, balances, item count and matching online session | `inspect Jane` |
| `stop` | Clean listener and session shutdown | `stop` |

Names identify saved knights globally, ignoring case. Session IDs identify
connections; they are not knight or account IDs. See [logging](logging.md) for ID
scopes. Inspection reads storage; it does not authenticate a player or grant roles.

## Accounts and knights

| Command | Behavior | Example |
| --- | --- | --- |
| `account list` | IDs, names, Game Master roles and character counts | `ac list` |
| `account create <username> [gm]` | Password and repeated-password prompts; optional explicit GM role | `account create Alice gm` |
| `account password <username>` | Password reset with repeated-password prompt; revokes sessions | `ac password Alice` |
| `account check <username>` | Checks a prompted password without revealing it | `ac check Alice` |
| `account gm <username> [true\|false]` | Toggle role if omitted; otherwise set explicitly | `account gm Alice true` |
| `gm <username> [true\|false]` | Same role operation | `gm Alice false` |
| `account delete <username>` | Exact stored username confirmation; removes owned knights/items/balances and revokes sessions | `account delete Alice` |
| `knight list <username>` | Saved names and account-local roster IDs | `knight list Alice` |
| `knight info <username> <knight-name>` | Owner/world/roster IDs, balances and inventory/equipment | `knight info Alice Jane` |
| `knight delete <username> <knight-name>` | Exact saved knight-name confirmation; removes owned items/balances and revokes that account's sessions | `knight delete Alice Jane` |

Usernames are 3–32 ASCII characters, beginning with a letter, followed by letters,
digits, underscore or hyphen. Passwords are 8–128 characters; they are never command
arguments. Knight names are 3–16 letters with optional single hyphens, unique
ignoring case; each account has three roster slots. Invalid names, unavailable
accounts/knights, mismatched passwords and confirmations are refused. Captured
identity checks prevent confirmed actions from targeting a recreated identity.

The graphical server command field cannot collect password or deletion
confirmation prompts. Those commands report that the account manager must be
opened; use its buttons for those operations. Role and other noninteractive
commands remain available in the command field.

## Balances

| Command | Meaning | Example |
| --- | --- | --- |
| `grant <knight-name> <crowns\|energy> <signed-amount>` | Recommended atomic relative adjustment; positive adds, negative removes | `grant Jane crowns -25` |
| `set <knight-name> <crowns\|energy> <balance>` | Recommended atomic absolute balance | `set Jane energy 500` |
| `currency give <username> <knight-name> <crowns\|energy> <amount>` | Retained account-qualified addition | `currency give Alice Jane crowns 100` |
| `currency withdraw <username> <knight-name> <crowns\|energy> <amount>` | Retained account-qualified subtraction | `currency withdraw Alice Jane energy 5` |

Balances belong to knights and range from 0 to 2147483647. Relative changes require
a nonzero amount within ±2147483647; the account-qualified forms require a positive
amount. `set` accepts zero. Whole-number parsing, insufficient funds, overflow and
stale identities are checked before committing. Changes are audited and published
through the existing live-state polling path. No earning or regeneration rule is
implied by these administration commands.

## Items

`items [search] [--page N]` searches derived names, aliases, canonical references,
stock IDs, name keys and extracted types. `items category <type> [--page N]`
filters an exact extracted type. `items info <reference>` inspects availability and
metadata, including indexed entries that cannot be granted. Pages contain five
results; page numbers must be positive. Examples:

```text
items Calibur --page 2
items category HelmCostume
items info Weapon/Sword/Proto Sword
giveitem Jane 2 Weapon/Sword/Proto Sword
giveitem Alice Jane 2 Weapon/Sword/Proto Sword
```

`giveitem <knight-name> <1-20> <reference>` grants unequipped items. The
account-qualified form remains supported. References resolve by canonical path,
derived alias, stock ID or exact friendly name. Unknown, ambiguous and unsupported
references are refused with guidance; numeric ID zero requires a canonical path or
alias for grants. Quantity and inventory limits are enforced transactionally.
Search and inspection never grant items. See [item identities and catalogue
limits](game-data.md); derived friendly names are not localized stock text.

## Other interfaces and permissions

Equipment changes use the unchanged client's Arsenal equip/unequip requests.
The local inventory browser shows slots and binding, but cannot force equipment
changes. Costume helmet/armor use slots 3/4; shield costume uses player equipment
slot 15, outside the native twelve-slot saved loadout. Protected equipped items
must be unequipped through the client before local removal or quantity changes.

Scene templates and runtime instances are inspected in the server window.
`status`, `players` and `session` show the relevant measured counts or locations;
there is no local scene teleport/create command. Stock walking portals and
supported client scene requests retain their existing behavior.

The unchanged client's supported staff chat transport is `!help`, `!items`,
`!giveitem` and stock `/broadcast <message>`. These require the current stored Game
Master role; ordinary and revoked accounts send `!` text as ordinary chat.
There are no promised in-game slash equivalents for local administration commands.
The stock F3 ItemCreator is a separate independently authorized service; see
[administration](administration.md).

Run `java -jar build/tortodrone-admin.jar --help` for the local CLI. Put
`--data /path/to/data` before its command. Supported commands are
`--list-accounts`, `--create-account <username> [--gm]`, `--set-password <username>`,
`--check-password <username>`, `--set-gm <username> true|false`,
`--delete-account <username>`, `--list-knights <username>`,
`--delete-knight <username> <knight-name>`, `--items [search]` and
`--give-item <username> <knight-name> <quantity> <reference>`.
Shell users quote multiword references. Passwords and exact deletion confirmations
come from prompts or complete stdin lines, never arguments. Omitting the command opens the
account manager GUI; `--version` and `--help` print information.
