# Commands

Enter slash commands in the unmodified client's chat box. Names below use the installed English bundle; aliases share the same command. `<value>` is required and `[value]` is optional. Use `/help` for the client's localized syntax and slot/flag choices. Commands can require a selected knight, a ready scene, party membership, GM permission, or deployed content.

**Supported** means the server has the corresponding behavior. **Client-side** runs in the stock client. **Placeholder** reserves an unavailable feature: it grants nothing, changes nothing, and must not be treated as success. Placeholder examples deliberately invoke the command without action arguments to show usage; they are not working gameplay examples. The server returns a correlated failure for unsupported RPCs, or chat feedback for calls without listeners. Client-side validation may stop a command before it reaches the server.

The inventory below includes stock English command aliases, including legacy/reserved bundle entries. A bundle entry does not prove that the current client registers it. GM-only commands remain protected even if a modified caller sends their RPC directly. Unsupported social channels, moderation, events, quests and rewards are placeholders; no quest or reward definitions are fabricated.

## Stock slash commands

| Syntax | Other aliases | Status | Example | Use / limitation |
|---|---|---|---|---|
| `/accessorize (armor helmet) [clear \| accessory name]` |  | Placeholder | `/accessorize` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/action command argument` |  | Placeholder | `/action` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/admin message` |  | Placeholder | `/admin` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/advance [number of floors]` |  | Placeholder | `/advance` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/alchemy` | `/craft` | Supported when deployed | `/alchemy` | Travel to the configured destination; an absent destination is refused. |
| `/amaze [player name]` | `/amazed` | Supported | `/amaze` | Native emote; an optional target must be in the same scene. |
| `/arcade` |  | Supported when deployed | `/arcade` | Travel to the configured destination; an absent destination is refused. |
| `/attack` |  | Supported | `/attack` | Native emote; an optional target must be in the same scene. |
| `/auction` | `/ah` | Supported when deployed | `/auction` | Travel to the configured destination; an absent destination is refused. |
| `/away message` | `/afk` | Placeholder | `/away` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/back` |  | Placeholder | `/back` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/bazaar` |  | Supported when deployed | `/bazaar` | Travel to the configured destination; an absent destination is refused. |
| `/bechamel` | `/accessory` | Supported when deployed | `/bechamel` | Travel to the configured destination; an absent destination is refused. |
| `/beg [player name]` |  | Supported | `/beg` | Native emote; an optional target must be in the same scene. |
| `/belch` |  | Supported | `/belch` | Native emote; an optional target must be in the same scene. |
| `/bored [player name]` | `/boring` | Supported | `/bored` | Native emote; an optional target must be in the same scene. |
| `/bow [player name]` |  | Supported | `/bow` | Native emote; an optional target must be in the same scene. |
| `/broadcast message` |  | Supported | `/broadcast Server restart soon` | GM: broadcast to ready online players. |
| `/bug description` |  | Placeholder | `/bug` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/burp` |  | Supported | `/burp` | Native emote; an optional target must be in the same scene. |
| `/cheer [player name]` | `/hooray` | Supported | `/cheer` | Native emote; an optional target must be in the same scene. |
| `/clap [player name]` | `/applaud` | Supported | `/clap` | Native emote; an optional target must be in the same scene. |
| `/clear` |  | Client-side | `/clear` | Handled by the stock client; local UI/configuration requirements still apply. |
| `/come` | `/here`, `/comehere`, `/interest`, `/beckon` | Supported | `/come` | Native emote; an optional target must be in the same scene. |
| `/complain player name` | `/report` | Placeholder | `/complain` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/congratulate` | `/grats` | Supported | `/congratulate` | Native emote; an optional target must be in the same scene. |
| `/crazy [player name]` |  | Supported | `/crazy` | Native emote; an optional target must be in the same scene. |
| `/cry [player name]` | `/weep` | Supported | `/cry` | Native emote; an optional target must be in the same scene. |
| `/dance` |  | Supported | `/dance` | Native emote; an optional target must be in the same scene. |
| `/debug (command line)` |  | Placeholder | `/debug` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/depth [depth]` |  | Placeholder | `/depth` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/derp` | `/herp` | Supported | `/derp` | Native emote; an optional target must be in the same scene. |
| `/drain [player name]` |  | Placeholder | `/drain` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/dump (actors sprites influences)` |  | Client-side | `/dump` | Handled by the stock client; local UI/configuration requirements still apply. |
| `/emote message` | `/me`, `/em`, `/e` | Placeholder | `/emote` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/epicfail [player name]` |  | Supported | `/epicfail` | Native emote; an optional target must be in the same scene. |
| `/fail [player name]` |  | Supported | `/fail` | Native emote; an optional target must be in the same scene. |
| `/fallback` | `/retreat` | Supported | `/fallback` | Native emote; an optional target must be in the same scene. |
| `/fire name attr value attr value...` |  | Placeholder | `/fire` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/flex` |  | Supported | `/flex` | Native emote; an optional target must be in the same scene. |
| `/flirt [player name]` |  | Supported | `/flirt` | Native emote; an optional target must be in the same scene. |
| `/follow` | `/followme` | Supported | `/follow` | Native emote; an optional target must be in the same scene. |
| `/force_bug_report <knight name> [description]` |  | Placeholder | `/force_bug_report` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/frown [player name]` |  | Supported | `/frown` | Native emote; an optional target must be in the same scene. |
| `/game message` |  | Placeholder | `/game` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/garrison` |  | Supported when deployed | `/garrison` | Travel to the configured destination; an absent destination is refused. |
| `/gaze` |  | Supported | `/gaze` | Native emote; an optional target must be in the same scene. |
| `/get_values` |  | Client-side | `/get_values` | Handled by the stock client; local UI/configuration requirements still apply. |
| `/giggle [player name]` |  | Supported | `/giggle` | Native emote; an optional target must be in the same scene. |
| `/goodbye [player name]` | `/bye`, `/farewell` | Supported | `/goodbye` | Native emote; an optional target must be in the same scene. |
| `/grant number` |  | Supported | `/grant 100` | GM: add nonnegative crowns to your knight. |
| `/grant_gift <account name> name attr value attr value...` |  | Placeholder | `/grant_gift` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/grant_reward <account name> <reward id> [reward param string]` |  | Placeholder | `/grant_reward` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/grin [player name]` |  | Supported | `/grin` | Native emote; an optional target must be in the same scene. |
| `/guild message` | `/g`, `/guildchat` | Placeholder | `/guild` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/guildhall` | `/gh` | Placeholder | `/guildhall` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/guildinfo guild name` |  | Placeholder | `/guildinfo` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/happy [player name]` |  | Supported | `/happy` | Native emote; an optional target must be in the same scene. |
| `/haven` | `/hh` | Supported when deployed | `/haven` | Travel to the configured destination; an absent destination is refused. |
| `/heat (weapon armor helmet shield sprite) [heat]` |  | Supported | `/heat weapon 0.5` | GM: set equipped item heat; use native help for optional slot syntax. |
| `/hi [player name]` | `/hello`, `/greet` | Supported | `/hi` | Native emote; an optional target must be in the same scene. |
| `/help` |  | Client-side | `/help` | Handled by the stock client; local UI/configuration requirements still apply. |
| `/helpme` | `/assistance`, `/aid` | Supported | `/helpme` | Native emote; an optional target must be in the same scene. |
| `/hoh` | `/heroes` | Supported when deployed | `/hoh` | Travel to the configured destination; an absent destination is refused. |
| `/hug [player name]` |  | Supported | `/hug` | Native emote; an optional target must be in the same scene. |
| `/hungry` |  | Supported | `/hungry` | Native emote; an optional target must be in the same scene. |
| `/impatient [player name]` |  | Supported | `/impatient` | Native emote; an optional target must be in the same scene. |
| `/inspect [player name]` |  | Supported | `/inspect Jane` | Inspect a visible/available knight. |
| `/invite [player name]` |  | Supported | `/invite Jane` | Invite an online knight to a party. |
| `/item name attr value attr value...` |  | Supported | `/item Weapon/Sword/Proto Sword` | GM: create one item; use Item Creator for quantity, binding and expiry. |
| `/item_pack pack [player name]` |  | Placeholder | `/item_pack` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/join (zone trade)` |  | Placeholder | `/join` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/joinknight [player name]` |  | Placeholder | `/joinknight` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/joy [player name]` | `/overjoyed`, `/yay` | Supported | `/joy` | Native emote; an optional target must be in the same scene. |
| `/kick player name` |  | Placeholder | `/kick` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/kidding [player name]` | `/jk` | Supported | `/kidding` | Native emote; an optional target must be in the same scene. |
| `/kiss [player name]` | `/blowkiss` | Supported | `/kiss` | Native emote; an optional target must be in the same scene. |
| `/lab` | `/laboratory` | Placeholder | `/lab` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/laugh [player name]` | `/haha`, `/lol` | Supported | `/laugh` | Native emote; an optional target must be in the same scene. |
| `/lbroadcast (en fr it de es) message` |  | Placeholder | `/lbroadcast` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/leave (zone trade)` |  | Placeholder | `/leave` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/level (weapon armor helmet shield) [level]` |  | Supported | `/level weapon 5` | GM: set equipped item level within its progression table. |
| `/level_up (weapon armor helmet shield) [crystals]` |  | Supported | `/level_up weapon 4` | GM: native forge operation; uses the verified progression table. |
| `/loadout [loadout]` |  | Partial | `/loadout` | Client lists the current loadout; switching saved loadouts remains a placeholder. |
| `/lonely` |  | Supported | `/lonely` | Native emote; an optional target must be in the same scene. |
| `/look` |  | Supported | `/look` | Native emote; an optional target must be in the same scene. |
| `/love [player name]` |  | Supported | `/love` | Native emote; an optional target must be in the same scene. |
| `/mission missionId` |  | Placeholder | `/mission` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/missions` |  | Placeholder | `/missions` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/move x y` |  | Placeholder | `/move` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/ignore player name` | `/mute` | Placeholder | `/ignore` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/no [player name]` | `/disagree` | Supported | `/no` | Native emote; an optional target must be in the same scene. |
| `/officer message` | `/o` | Placeholder | `/officer` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/party message` | `/p`, `/partychat` | Supported | `/party Ready` | Current party chat. |
| `/pbroadcast message` |  | Placeholder | `/pbroadcast` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/pgo player name` |  | Placeholder | `/pgo` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/point [player name]` | `/direct` | Supported | `/point` | Native emote; an optional target must be in the same scene. |
| `/poke [player name]` |  | Supported | `/poke` | Native emote; an optional target must be in the same scene. |
| `/punch` | `/uv` | Supported when deployed | `/punch` | Travel to the configured destination; an absent destination is refused. |
| `/ready` | `/rdy` | Supported | `/ready` | Native emote; an optional target must be in the same scene. |
| `/readyroom` | `/rr` | Placeholder | `/readyroom` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/reboot minutes message` |  | Placeholder | `/reboot` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/recharge [player name]` |  | Placeholder | `/recharge` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/rename_guild <old name> : <new name>` |  | Placeholder | `/rename_guild` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/respond message` | `/reply`, `/r` | Supported | `/respond Hello` | Reply to the last tell. |
| `/revoke_gift <account name> name attr value attr value...` |  | Placeholder | `/revoke_gift` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/rude [player name]` |  | Supported | `/rude` | Native emote; an optional target must be in the same scene. |
| `/sad [player name]` | `/depressed` | Supported | `/sad` | Native emote; an optional target must be in the same scene. |
| `/salute` |  | Supported | `/salute` | Native emote; an optional target must be in the same scene. |
| `/saveloadout <name>` |  | Placeholder | `/saveloadout` | Saving named loadouts is unavailable. |
| `/set-ident` |  | Placeholder | `/set-ident` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/set_rank rank subrank` |  | Supported | `/set_rank 4 2` | GM: save rank/subrank; no mission rewards are generated. |
| `/sgo scene name` |  | Placeholder | `/sgo` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/shout message` | `/yell`, `/y` | Supported | `/shout Hello` | Scene chat in shout mode. |
| `/shrug` |  | Supported | `/shrug` | Native emote; an optional target must be in the same scene. |
| `/sigh [player name]` |  | Supported | `/sigh` | Native emote; an optional target must be in the same scene. |
| `/sit` | `/sitdown` | Supported | `/sit` | Native emote; an optional target must be in the same scene. |
| `/sleep` | `/doze` | Supported | `/sleep` | Native emote; an optional target must be in the same scene. |
| `/sleepy [player name]` | `/drowsy` | Supported | `/sleepy` | Native emote; an optional target must be in the same scene. |
| `/smell [player name]` | `/stink` | Supported | `/smell` | Native emote; an optional target must be in the same scene. |
| `/smile [player name]` | `/beam` | Supported | `/smile` | Native emote; an optional target must be in the same scene. |
| `/sniff` |  | Supported | `/sniff` | Native emote; an optional target must be in the same scene. |
| `/sorry` | `/apologize` | Supported | `/sorry` | Native emote; an optional target must be in the same scene. |
| `/sound` |  | Client-side | `/sound` | Handled by the stock client; local UI/configuration requirements still apply. |
| `/spawn name attr value attr value...` |  | Placeholder | `/spawn` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/sprite_equip [id]` |  | Supported | `/sprite_equip` | GM: equip an owned sprite; optional ID. |
| `/sprite_level [level]` |  | Supported | `/sprite_level 10` | GM: set equipped sprite level. |
| `/sprite_list` |  | Client-side | `/sprite_list` | Handled by the stock client; local UI/configuration requirements still apply. |
| `/sprite_power skill power value` |  | Supported | `/sprite_power 0 0 3` | GM: set a sprite skill power. |
| `/sprite_ultimate skill ultimate` |  | Supported | `/sprite_ultimate 0 1` | GM: set a sprite ultimate. |
| `/stare` |  | Supported | `/stare` | Native emote; an optional target must be in the same scene. |
| `/summon player name` |  | Placeholder | `/summon` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/sybil` |  | Supported when deployed | `/sybil` | Travel to the configured destination; an absent destination is refused. |
| `/team message` |  | Placeholder | `/team` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/tell player name message` | `/msg`, `/t`, `/whisper`, `/w` | Supported | `/tell Jane Hello` | Whisper to an online knight. |
| `/thank [player name]` | `/thanks`, `/ty`, `/thx` | Supported | `/thank` | Native emote; an optional target must be in the same scene. |
| `/think message` |  | Placeholder | `/think` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/thirsty` |  | Supported | `/thirsty` | Native emote; an optional target must be in the same scene. |
| `/tickle [player name]` |  | Supported | `/tickle` | Native emote; an optional target must be in the same scene. |
| `/tired [player name]` |  | Supported | `/tired` | Native emote; an optional target must be in the same scene. |
| `/toggle ({1})` |  | Placeholder | `/toggle` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/toggle_progress ({1})` |  | Placeholder | `/toggle_progress` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/townsquare` | `/ts` | Supported when deployed | `/townsquare` | Travel to the configured destination; an absent destination is refused. |
| `/trade message` | `/tradechat`, `/2` | Placeholder | `/trade` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/training` |  | Supported when deployed | `/training` | Travel to the configured destination; an absent destination is refused. |
| `/unlock_achievement <account name> <achievement name>` |  | Placeholder | `/unlock_achievement` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/unignore player name` | `/unmute` | Placeholder | `/unignore` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/vise` | `/unbind` | Supported when deployed | `/vise` | Travel to the configured destination; an absent destination is refused. |
| `/wait` |  | Supported | `/wait` | Native emote; an optional target must be in the same scene. |
| `/wave [player name]` |  | Supported | `/wave` | Native emote; an optional target must be in the same scene. |
| `/whine [player name]` |  | Supported | `/whine` | Native emote; an optional target must be in the same scene. |
| `/who player name` |  | Placeholder | `/who` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/wince [player name]` |  | Supported | `/wince` | Native emote; an optional target must be in the same scene. |
| `/wink [player name]` |  | Supported | `/wink` | Native emote; an optional target must be in the same scene. |
| `/yawn` |  | Supported | `/yawn` | Native emote; an optional target must be in the same scene. |
| `/yes [player name]` | `/agree`, `/nod` | Supported | `/yes` | Native emote; an optional target must be in the same scene. |
| `/zgo zone name` |  | Placeholder | `/zgo` | Reserved. No complete server implementation; native usage or an explicit placeholder response is expected. |
| `/zone message` | `/z`, `/zonechat`, `/1` | Supported | `/zone Hello` | Current scene chat; cross-zone routing is unavailable. |

## Tortodrone GM chat extensions

These extensions use `!` because the unmodified client consumes slash commands locally. Only authenticated GMs can execute them. The server console accepts the same item syntax without `!`.

| Command | Use | Example |
|---|---|---|
| `!help` | List available GM extensions. | `!help` |
| `!items <search> [--page N]` | Search names, readable IDs, stock IDs and exact configs. | `!items Proto --page 1` |
| `!items category <type> [--page N]` | Filter a catalog category. | `!items category Sword` |
| `!items info <reference>` | Inspect availability, flags and native config. | `!items info proto_sword` |
| `!giveitem <knight-name> <1-20> <reference>` | Grant unequipped items; recipient may be offline. | `!giveitem Jane 2 Weapon/Sword/Proto Sword` |
| `!giveitem <account> <knight-name> <1-20> <reference>` | Legacy account-qualified grant. | `!giveitem Alice Jane 1 proto_sword` |

## Expiring items and slot upgrades

The native GM Item Creator accepts quantity 1–20, Locked and Expire Days 0–3650. A positive value starts an absolute timer when the grant commits, including for weapons and trinkets. Zero uses the catalog default duration. Permanent items cannot expire. Example: create `Weapon/Sword/Proto Sword`, quantity `1`, Expire Days `2` for a two-day weapon. `/item Weapon/Sword/Proto Sword` creates one item with its catalog defaults; the slash command does not expose the dialog's expiry field.

Grant `weapon_slot`, `trinket_slot`, or `trinket_slot_2_days` with `!giveitem Jane 1 weapon_slot`. An unused slot ticket has no running timer. Use it from the Arsenal to bind and activate it: weapon slots last 30 days, standard trinket slots 30 days, and short trinket slots two days. Each unlocks one slot, up to two extra weapon slots and two trinket slots. Use the native Extend menu to consume a matching unused ticket and add its duration to an active deadline. Reconnects, server restarts and transfers never restart a timer. When a slot expires, equipment in the lost slot returns to the bag; it is not destroyed. Activated tickets are bound and cannot be traded or wrapped.

## Local server console

The terminal and server desktop command field accept these forms with an optional leading `/`. These are local administration commands, not additional player slash commands. `ac` aliases `account`. Passwords are entered at hidden prompts; account and knight deletion require confirmation.

| Syntax | Use | Example |
|---|---|---|
| `rank <knight-name> <1-10> <subrank 1-4>` | Set saved mission rank without awarding missions or items. | `rank Jane 10 3` |
| `prestige <knight-name> <total>` | Set a saved nonnegative prestige total. | `prestige Jane 5000` |
| `help [command] [action]` | Show all commands or help for one command. | `help account create` |
| `status` | Show version, uptime, sessions, resources and memory. | `status` |
| `players` | List current TCP sessions, verified identities and locations. | `players` |
| `session <session-id>` | Inspect an active connection. | `session 1` |
| `inspect <knight-name>` | Inspect persistent identity, balances and inventory count. | `inspect Jane` |
| `grant <knight-name> <crowns\|energy> <signed-amount>` | Add/subtract atomically; insufficient funds and overflow are refused. | `grant Jane crowns -25` |
| `set <knight-name> <crowns\|energy> <balance>` | Set an absolute balance atomically, from 0 to 2147483647. | `set Jane crowns 5000` |
| `stop` | Shut down the server cleanly. | `stop` |
| `account list` | List accounts and Game Master roles. | `ac list` |
| `account create <username> [gm]` | Create an account; enter its password at the prompts. | `account create Alice` |
| `account password <username>` | Change a password; existing sessions disconnect. | `ac password Alice` |
| `account check <username>` | Check a password entered at the prompt. | `ac check Alice` |
| `account gm <username> [true\|false]` | Toggle the Game Master role, or set it explicitly. | `ac gm Alice true` |
| `account delete <username>` | Delete an account and its knights after confirmation. | `account delete Alice` |
| `gm <username> [true\|false]` | Toggle the Game Master role, or set it explicitly. | `gm Alice` |
| `knight list <username>` | List an account's saved knights. | `knight list Alice` |
| `knight info <username> <knight-name>` | Show a knight's identity, balances and inventory. | `knight info Alice Jane` |
| `knight delete <username> <knight-name>` | Delete a knight after exact-name confirmation. | `knight delete Alice Jane` |
| `currency give <username> <knight-name> <crowns\|energy> <amount>` | Add currency to a knight. | `currency give Alice Jane crowns 100` |
| `currency withdraw <username> <knight-name> <crowns\|energy> <amount>` | Withdraw currency; the balance cannot become negative. | `currency withdraw Alice Jane energy 5` |
| `items [search \| category <type> \| info <reference>] [--page N]` | Search and inspect names, aliases, stock IDs, references and availability. | `items Proto Sword` |
| `giveitem <knight-name> <1-20> <catalogue ID or exact item config>` | Grant unequipped items by unique knight name. The account-qualified form also works. | `giveitem Jane 2 Weapon/Sword/Proto Sword` |
