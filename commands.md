# Commands

Type slash commands in the game chat box. `<value>` is required; `[value]` is optional. Names and aliases below use the English stock client. `/help` shows the client's localized help.

## Player commands

### Chat and availability

Type a message without a slash command to speak in the current scene.

| Command (aliases) | Example | What it does |
|---|---|---|
| `/help` | `/help` | Show command help. |
| `/shout message (/yell, /y)` | `/shout Over here!` | Shout in the current scene. |
| `/think message` | `/think Which way?` | Show a thought bubble. |
| `/emote message (/me, /em, /e)` | `/me checks the map` | Send a written emote in the current scene. |
| `/zone message (/z, /zonechat, /1)` | `/zone Anyone going to the Bazaar?` | Chat with subscribers across scenes in your current zone. |
| `/trade message (/tradechat, /2)` | `/trade Selling a Calibur` | Use the separate trade channel for your current zone. |
| `/join (zone trade)` | `/join trade` | Enable a zone or trade channel in the client. |
| `/leave (zone trade)` | `/leave trade` | Disable a zone or trade channel in the client. |
| `/party message (/p, /partychat)` | `/party Ready when you are` | Chat with your party. |
| `/tell player name message (/msg, /t, /whisper, /w)` | `/tell Jane Hello` | Whisper to an online knight. |
| `/respond message (/reply, /r)` | `/reply On my way` | Reply to the last knight who whispered to you. |
| `/away message (/afk)` | `/away Back in ten minutes` | Set an away message. Knights who whisper to you receive it automatically. |
| `/back` | `/back` | Clear your away message. |
| `/ignore player name (/mute)` | `/mute Jane` | Block a knight through the client mute list. |
| `/unignore player name (/unmute)` | `/unmute Jane` | Remove a knight from the client mute list. |
| `/clear` | `/clear` | Clear the local chat display and history. |

### Friends

Use the **Social** panel’s **Add Friend** action, or choose **Befriend** from a
knight’s menu, to send a friend request with an optional message. The recipient
accepts it in mail, or deletes the request to decline. Accepted friends appear
in both knights’ lists, with online/offline status and their current scene;
the lists survive reconnects and server restarts. Choose **Unfriend** from a
friend’s menu to remove the friendship from both lists. The limit is 250 friends
per knight. Joining a friend’s scene is not available yet.

### Party, inspection, and equipment

| Command (aliases) | Example | What it does |
|---|---|---|
| `/invite [player name]` | `/invite Jane` | Invite an online knight to your party. |
| `/inspect [player name]` | `/inspect Jane` | Inspect a knight available in the current scene; privacy rules still apply. |
| `/saveloadout <name>` | `/saveloadout Battle` | Save your current equipment, battle sprite, harness and perk. Up to ten case-insensitive names; saving the same name replaces it. |
| `/loadout [loadout]` | `/loadout Battle` | List loadouts with `/loadout`, or equip a saved name. Switching requires equipment access and no active trade. Missing items or locked slots refuse the whole switch. |

### Travel

These commands visit deployed destinations. The client may require mission or party prerequisites for interior destinations.

| Command (aliases) | Example | What it does |
|---|---|---|
| `/haven (/hh)` | `/haven` | Return to the server’s starting Haven scene. |
| `/townsquare (/ts)` | `/townsquare` | Visit Haven Town Square. |
| `/bazaar` | `/bazaar` | Visit the Bazaar. |
| `/arcade` | `/arcade` | Visit the Arcade. |
| `/garrison` | `/garrison` | Visit the Garrison. |
| `/training` | `/training` | Enter Advanced Training. |
| `/lab (/laboratory)` | `/lab` | Enter the Laboratory. |
| `/hoh (/heroes)` | `/hoh` | Enter the Hall of Heroes. |
| `/auction (/ah)` | `/auction` | Visit the Auction House destination. |
| `/alchemy (/craft)` | `/alchemy` | Visit the alchemy machine. |
| `/bechamel (/accessory)` | `/bechamel` | Visit Bechamel’s accessory station. |
| `/punch (/uv)` | `/punch` | Visit Punch’s station. |
| `/vise (/unbind)` | `/vise` | Visit Vise’s station. |
| `/sybil` | `/sybil` | Visit Sybil. |

### Emotes and signals

A knight name is optional for targetable emotes and must refer to someone in your scene. Signals without a target affect only your own knight.

| Command (aliases) | Example | What it does |
|---|---|---|
| `/amaze [player name] (/amazed)` | `/amaze Jane` | Targetable emote. |
| `/attack` | `/attack` | Emote or party signal. |
| `/beg [player name]` | `/beg Jane` | Targetable emote. |
| `/belch` | `/belch` | Emote or party signal. |
| `/bored [player name] (/boring)` | `/bored Jane` | Targetable emote. |
| `/bow [player name]` | `/bow Jane` | Targetable emote. |
| `/burp` | `/burp` | Emote or party signal. |
| `/cheer [player name] (/hooray)` | `/cheer Jane` | Targetable emote. |
| `/clap [player name] (/applaud)` | `/clap Jane` | Targetable emote. |
| `/come (/here, /comehere, /interest, /beckon)` | `/come` | Emote or party signal. |
| `/congratulate [player name] (/grats)` | `/congratulate Jane` | Targetable emote. |
| `/crazy [player name]` | `/crazy Jane` | Targetable emote. |
| `/cry [player name] (/weep)` | `/cry Jane` | Targetable emote. |
| `/dance [player name]` | `/dance Jane` | Targetable emote. |
| `/derp (/herp)` | `/derp` | Emote or party signal. |
| `/epicfail [player name]` | `/epicfail Jane` | Targetable emote. |
| `/fail [player name]` | `/fail Jane` | Targetable emote. |
| `/fallback (/retreat)` | `/fallback` | Emote or party signal. |
| `/flex` | `/flex` | Emote or party signal. |
| `/flirt [player name]` | `/flirt Jane` | Targetable emote. |
| `/follow (/followme)` | `/follow` | Emote or party signal. |
| `/frown [player name]` | `/frown Jane` | Targetable emote. |
| `/gaze` | `/gaze` | Emote or party signal. |
| `/giggle [player name]` | `/giggle Jane` | Targetable emote. |
| `/goodbye [player name] (/bye, /farewell)` | `/goodbye Jane` | Targetable emote. |
| `/grin [player name]` | `/grin Jane` | Targetable emote. |
| `/happy [player name]` | `/happy Jane` | Targetable emote. |
| `/helpme (/assistance, /aid)` | `/helpme` | Emote or party signal. |
| `/hi [player name] (/hello, /greet)` | `/hi Jane` | Targetable emote. |
| `/hug [player name]` | `/hug Jane` | Targetable emote. |
| `/hungry` | `/hungry` | Emote or party signal. |
| `/impatient [player name]` | `/impatient Jane` | Targetable emote. |
| `/joy [player name] (/overjoyed, /yay)` | `/joy Jane` | Targetable emote. |
| `/kidding [player name] (/jk)` | `/kidding Jane` | Targetable emote. |
| `/kiss [player name] (/blowkiss)` | `/kiss Jane` | Targetable emote. |
| `/laugh [player name] (/haha, /lol)` | `/laugh Jane` | Targetable emote. |
| `/lonely` | `/lonely` | Emote or party signal. |
| `/look [player name]` | `/look Jane` | Targetable emote. |
| `/love [player name]` | `/love Jane` | Targetable emote. |
| `/no [player name] (/disagree)` | `/no Jane` | Targetable emote. |
| `/point [player name] (/direct)` | `/point Jane` | Targetable emote. |
| `/poke [player name]` | `/poke Jane` | Targetable emote. |
| `/ready (/rdy)` | `/ready` | Emote or party signal. |
| `/rude [player name]` | `/rude Jane` | Targetable emote. |
| `/sad [player name] (/depressed)` | `/sad Jane` | Targetable emote. |
| `/salute [player name]` | `/salute Jane` | Targetable emote. |
| `/shrug [player name]` | `/shrug Jane` | Targetable emote. |
| `/sigh [player name]` | `/sigh Jane` | Targetable emote. |
| `/sit (/sitdown)` | `/sit` | Emote or party signal. |
| `/sleep (/doze)` | `/sleep` | Emote or party signal. |
| `/sleepy [player name] (/drowsy)` | `/sleepy Jane` | Targetable emote. |
| `/smell [player name] (/stink)` | `/smell Jane` | Targetable emote. |
| `/smile [player name] (/beam)` | `/smile Jane` | Targetable emote. |
| `/sniff [player name]` | `/sniff Jane` | Targetable emote. |
| `/sorry [player name] (/apologize)` | `/sorry Jane` | Targetable emote. |
| `/stare [player name]` | `/stare Jane` | Targetable emote. |
| `/thank [player name] (/thanks, /ty, /thx)` | `/thank Jane` | Targetable emote. |
| `/thirsty` | `/thirsty` | Emote or party signal. |
| `/tickle [player name]` | `/tickle Jane` | Targetable emote. |
| `/tired [player name]` | `/tired Jane` | Targetable emote. |
| `/wait` | `/wait` | Emote or party signal. |
| `/wave [player name]` | `/wave Jane` | Targetable emote. |
| `/whine [player name]` | `/whine Jane` | Targetable emote. |
| `/wince [player name]` | `/wince Jane` | Targetable emote. |
| `/wink [player name]` | `/wink Jane` | Targetable emote. |
| `/yawn` | `/yawn` | Emote or party signal. |
| `/yes [player name] (/agree, /nod)` | `/yes Jane` | Targetable emote. |

### Player features not available yet

These depend on systems the server does not currently provide. They return an unavailable response where the client sends a request; they do not perform the described action.

| Command (aliases) | Example | What it does |
|---|---|---|
| `/bug description` | `/bug Stuck after entering a scene` | Bug-report delivery service. |
| `/complain player name (/report)` | `/complain Jane` | Player-report submission service. |
| `/game message` | `/game Ready to start` | PvP game chat. |
| `/guild message (/g, /guildchat)` | `/guild Hello everyone` | Guild membership and guild chat. |
| `/guildhall (/gh)` | `/guildhall` | Guild-owned halls. |
| `/joinknight [player name]` | `/joinknight Jane` | Social whereabouts and join permissions. |
| `/officer message (/o)` | `/officer Meeting in Haven` | Guild officer permissions and officer chat. |
| `/readyroom (/rr)` | `/readyroom` | Ready-room and dungeon matchmaking. |
| `/recharge [player name]` | `/recharge Jane` | Legacy client command without a current server handler. |
| `/set-ident` | `/set-ident` | Forum identity linking. |
| `/team message` | `/team Defend this side` | PvP team membership and chat. |

## GM-only commands

These require a GM account. The server checks permissions independently of the client's command menu. Some developer tools also require a higher native client permission tier and may be hidden by the stock client.

### Player lookup and moderation

| Command (aliases) | Example | What it does |
|---|---|---|
| `/who player name` | `/who Jane` | Show an online knight’s world ID, scene and instance. |
| `/kick player name` | `/kick Jane` | Disconnect only that knight’s active session. Does not delete the account or ban it. |
| `/broadcast message` | `/broadcast Server restart in five minutes` | Send a server-wide message to ready online knights. |

### GM travel

Travel follows the normal scene transition and ownership checks. `/pgo` and `/summon` work in towns and accessible shared instances; they cannot cross another knight’s private-instance ownership. Finish active trades first.

| Command (aliases) | Example | What it does |
|---|---|---|
| `/pgo player name` | `/pgo Jane` | Go to an online knight’s server-observed location. |
| `/summon player name` | `/summon Jane` | Bring an online knight to your server-observed location. |
| `/sgo scene name` | `/sgo 30893` | Go to a deployed scene by its native name or numeric scene ID. |
| `/zgo zone name` | `/zgo haven` | Go to a deployed zone by its name or numeric zone ID. |
| `/move x y` | `/move 10 5` | Move within your current scene. Blocked coordinates are refused. |

### Items, equipment, and progression

| Command (aliases) | Example | What it does |
|---|---|---|
| `/item name attr value attr value...` | `/item Weapon/Sword/Proto Sword` | Create one catalog item for yourself. Use the native Item Creator for its other creation options. |
| `/grant number` | `/grant 1000` | Add a nonnegative number of crowns to your knight. |
| `/set_rank rank subrank` | `/set_rank 4 2` | Set your saved rank and subrank, without awarding mission rewards. |
| `/level (weapon armor helmet shield) [level]` | `/level weapon 5` | Set equipped gear level within its progression table. |
| `/heat (weapon armor helmet shield sprite) [heat]` | `/heat weapon 50` | Set equipped gear heat progress as a percentage from 0 to 100. The stock client only distinguishes the weapon group, so `/heat weapon` applies to every equipped weapon slot. |
| `/level_up (weapon armor helmet shield) [crystals]` | `/level_up weapon 4` | Attempt the native forge operation using the configured crystal-tier chance. |
| `/accessorize (armor helmet) [clear \| accessory name]` | `/accessorize armor clear` | Clear accessories, or add a verified accessory config to equipped armor/helmet. Conflicting accessories are refused. |

The stock client echoes the typed value in its own confirmation, for example
`Set weapon heat to 22.0.`. That text is rendered by the unmodified client; the
server cannot change it to `22%`.

### Battle sprites

Sprite IDs come from `/sprite_list`. Skill, power and ultimate indices use the native command’s numbering; invalid or locked entries are refused.

| Command (aliases) | Example | What it does |
|---|---|---|
| `/sprite_list` | `/sprite_list` | List your owned battle sprites and their IDs in the client. |
| `/sprite_equip [id]` | `/sprite_equip 1` | Equip an owned sprite; omit the ID to use the client’s default selection. |
| `/sprite_level [level]` | `/sprite_level 10` | Set the equipped sprite’s displayed level. |
| `/sprite_power skill power value` | `/sprite_power 0 0 3` | Set a skill power value on the equipped sprite. |
| `/sprite_ultimate skill ultimate` | `/sprite_ultimate 0 1` | Set a skill ultimate on the equipped sprite. |

### Local client debugging

| Command (aliases) | Example | What it does |
|---|---|---|
| `/sound` | `/sound` | Toggle client sound volume. |
| `/dump (actors sprites influences)` | `/dump actors` | Dump client scene diagnostics to the client log; native developer permissions apply. |

### GM tools not available yet

These require runtime systems, event definitions, or a dedicated administration backend that has not been implemented. They remain explicit placeholders rather than reporting fake success.
Examples containing `<...>` need a verified definition from that missing system; these are argument templates, not deployed content IDs.

| Command (aliases) | Example | What it does |
|---|---|---|
| `/action command argument` | `/action <controller-action> <argument>` | Arbitrary scene-controller actions; only implemented NPC actions are dispatched. |
| `/admin message` | `/admin Checking the Bazaar` | Native administrator-object chat. |
| `/advance [number of floors]` | `/advance 1` | Dungeon floor progression. |
| `/debug (command line)` | `/debug <debug-command>` | Native server debug-command dispatcher. |
| `/depth [depth]` | `/depth 3` | Dungeon depth state. |
| `/drain [player name]` | `/drain Jane` | Legacy mist-energy administration. |
| `/fire name attr value attr value...` | `/fire <effect-config>` | Administrative effect creation and lifecycle handling. |
| `/force_bug_report <knight name> [description]` | `/force_bug_report Jane Describe the stuck screen` | Remote report collection service. |
| `/get_values` | `/get_values` | Native runtime-value inspection service. |
| `/grant_gift <account name> name attr value attr value...` | `/grant_gift Alice <gift-config>` | Administrative gift definitions and delivery. |
| `/grant_reward <account name> <reward id> [reward param string]` | `/grant_reward Alice <reward-id>` | Verified mission/reward definitions and delivery. |
| `/guildinfo guild name` | `/guildinfo <guild-name>` | Guild records. |
| `/item_pack pack [player name]` | `/item_pack <pack-config> Jane` | Native administrative item-pack definitions. |
| `/lbroadcast (en fr it de es) message` | `/lbroadcast en Welcome to Haven` | Localized broadcast selection. |
| `/mission missionId` | `/mission <mission-id>` | Mission administration and progression. |
| `/missions` | `/missions` | Mission administration and progression. |
| `/pbroadcast message` | `/pbroadcast Welcome to Haven` | Popup announcement delivery. |
| `/reboot minutes message` | `/reboot 5 Scheduled maintenance` | Cluster/node lifecycle control. Use the local server console for shutdown. |
| `/rename_guild <old name> : <new name>` | `/rename_guild <old-name> : <new-name>` | Guild ownership and persistence. |
| `/revoke_gift <account name> name attr value attr value...` | `/revoke_gift Alice <gift-config>` | Administrative gift-revocation records. |
| `/spawn name attr value attr value...` | `/spawn <actor-config>` | Persistent actor spawning and native actor configuration resolution. |
| `/toggle <flag>` | `/toggle invincible` | Native status flags, including `fast`, `invisible`, `stealth` and `invincible`. |
| `/toggle_progress <flag>` | `/toggle_progress tier_2_access` | Native progression flags, including tier access and wearable rarity. |
| `/unlock_achievement <account name> <achievement name>` | `/unlock_achievement Alice <achievement-name>` | Achievement definitions and persistence. |

### Tortodrone GM chat extensions

Tortodrone does not add GM chat commands. The stock client consumes slash
command names itself, and ordinary players' leading `!` remains normal chat.
Item grants are available through the local console, the account manager and
`tortodrone-admin.jar` (see below).

## Local server console

The terminal and server desktop command field accept these forms with an optional leading `/`. These are local administration commands, not additional player slash commands. `ac` aliases `account`. Passwords are entered at hidden prompts; account and knight deletion require confirmation.

| Syntax | Use | Example |
|---|---|---|
| `help [command] [action]` | Show all commands or help for one command. | `help account create` |
| `status` | Show version, uptime, sessions, resources and memory. | `status` |
| `players` | List current TCP sessions, verified identities and locations. | `players` |
| `session <session-id>` | Inspect an active connection. | `session 1` |
| `inspect <knight-name>` | Inspect persistent identity, balances and inventory count. | `inspect Jane` |
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
| `rank <knight-name> <1-10> <subrank 1-4>` | Set saved mission rank without awarding missions or items. | `rank Jane 10 3` |
| `prestige <knight-name> <total>` | Set a saved nonnegative prestige total. | `prestige Jane 5000` |
| `grant <knight-name> <crowns\|energy> <signed-amount>` | Add/subtract atomically; insufficient funds and overflow are refused. | `grant Jane crowns -25` |
| `set <knight-name> <crowns\|energy> <balance>` | Set an absolute balance atomically, from 0 to 2147483647. | `set Jane crowns 5000` |
| `currency give <username> <knight-name> <crowns\|energy> <amount>` | Add currency to a knight. | `currency give Alice Jane crowns 100` |
| `currency withdraw <username> <knight-name> <crowns\|energy> <amount>` | Withdraw currency; the balance cannot become negative. | `currency withdraw Alice Jane energy 5` |
| `items [search \| category <type> \| info <reference>] [--page N]` | Search and inspect names, aliases, stock IDs, references and availability. | `items Proto Sword` |
| `giveitem <knight-name> <1-20> <catalogue ID or exact item config>` | Grant unequipped items by unique knight name. The account-qualified form also works. | `giveitem Jane 2 Weapon/Sword/Proto Sword` |
| `stop` | Shut down the server cleanly. | `stop` |
