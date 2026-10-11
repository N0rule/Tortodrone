# Local Spiral archive development reference

Inspected **2026-10-11** for Tortodrone **0.24.0**, baseline `5ae58ac`, on `dev`.
Archive root: `/home/n0rule/Projects/reference-inputs` (read-only reference).
Local HEAD: `566636e022c7356c62ea6fcc1aba37aa4e9e38dc`; working tree clean at inspection.
This matches the supplied `TortoDrone_AI_Development_Map.zip` snapshot. No online references were consulted.
The archive contains 46,224 files outside `.git`; this is a curated retrieval guide, not a claim that every file was reviewed.

The [machine-readable index](reference-inputs-index.json) preserves all 13 supplied feature routes, adds local verification and Tortodrone pointers. Every original source/check path exists locally. Paths below are relative to the archive unless explicitly labelled Tortodrone. No archive code, game assets, recordings, credentials, or databases are copied into this repository.

## Evidence and compatibility

- **VERIFIED_SOURCE** means a file or source behavior was inspected, not verified gameplay parity. Match client JAR hash/build, method ID, argument types/count, callback, streamed field order, and subscribed events before implementation.
- **RECONSTRUCTED** means authored compatibility or inferred behavior. ReferenceServer managers and `vendor/reference-server/compat/` contain both recovery and reconstruction; classify individual decisions, not whole directories.
- **HYPOTHESIS** means an untested interpretation. **UNKNOWN** means required evidence is absent.
- Prefer unchanged matching client bytecode and reproducible captures for wire contracts; BSD Narya/Clyde sources for engine internals; historical server code for state-machine leads. Read license/provenance before porting ideas; do not copy an entire subsystem.
- `docs/PROJECT-MAP.md` identifies deployment/source divergence and stale narrative. `docs/16-the-protocol.md` is investigation history; `docs/18-playing-together.md` describes later corrections. Archive worklog, percentages and passing check reports are historical claims, not Tortodrone test results.
- The archive's server/client goal and contributor instructions do not override Tortodrone rules. Preserve Java 25, normal credentials, schema refusal, transactions, connection-local wire mappings, and unchanged client testing.

## Start with current Tortodrone

Source is ahead of the cautious PROJECT handoff in some areas: `server/network/WorldSession.java` contains trade and inspection handling; `server/network/PlayerTrades.java`, `character/CharacterStore.java` and their tests are relevant. [Town interactions](town-interactions.md) records verified trade/inspection and seven-scene scope. Do not restart login, inventory, towns or solo interiors from the archive's suggested phase zero.

Immediate research routes:

- **Trade notification and settlement:** archive `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/trade/TradeProvider.java`, `TradeManager.java`; matching `vendor/reference-server/compat/com/threerings/projectx/trade/` and client call sites. Compare invitation callback/notification, trade-object subscription, accept/reject/hide, disconnect cancellation and atomic item/currency transfer with Tortodrone `PlayerTrades.java` and `PlayerTradeTest.java`.
- **Inspection/privacy:** archive `KnightManager.java` around `set...` privacy behavior (line 2012 describes flag 1024), `KnightProvider.java`, client `KnightMarshaller.java`. Tortodrone `WorldSession.java`, `InspectionPreferenceTest.java`, `SessionCodec.java`: verify flag semantics and equipment visibility against the stock caller, not a UI guess.
- **Travel races:** zone move handler, scene registry and Narya subscriptions below; compare Tortodrone `SceneRegistry`, `SceneSimulation` and `WorldSession`. Trace old input invalidation, new snapshot registration and two simultaneous travelers; a successful scene load alone does not prove movement resumes.
- **Reconnect location:** archive `ReferenceServerSession.java:174` clears `player.location`; `KnightManager.java:623` comments on scene/zone cleanup. These are archive implementation observations, **not proof of retail return-location semantics**. Search matching client/capture lifecycle before adding coordinates or schema changes.

## Feature retrieval routes

All indexed paths and checks below were checked for local existence. Listed check scripts were not executed: many assume archive builds, live services, deployment state or writable resources. Tortodrone Java paths are relative to `src/main/java/org/tortodrone/`.

### P0 · Transport, invocations, and method IDs (`wire`)

What EXACT request does the client send, and where is it dispatched?

Tortodrone: protocol/SessionDecoder.java, protocol/SessionCodec.java.

Open in this order:

- `vendor/narya-spiralstudio/core/src/main/java/com/threerings/presents/server/InvocationManager.java`
- `contrib/threerings/narya/core/src/main/java/com/threerings/presents/server/InvocationManager.java`
- `contrib/threerings/narya/core/src/main/java/com/threerings/presents/data/InvocationMarshaller.java`
- `contrib/client/joined/com/threerings/projectx/data/KnightMarshaller.java`
- `vendor/reference-server/compat/com/threerings/projectx/data/KnightMarshaller.java`
- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/KnightProvider.java`
- `docs/16-the-protocol.md`

Reference checks/probes: `contrib/check_provider_binding.py`, `contrib/check_capture_service_calls.py`.

Provider registration can silently omit methods or bind the wrong numeric ID. Confirm ID, argument COUNT, parameter TYPES, callback type and provider implementation against client bytecode.

### P0 · Login, authentication, bootstrap (`session`)

How does a connection become an authenticated player with subscribed objects?

Tortodrone: protocol/HandshakeCodec.java, server/network/ClientSession.java.

Open in this order:

- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/ReferenceServerAuthenticator.java`
- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/ReferenceServerSession.java`
- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/ReferenceServerServerModule.java`
- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/ReferenceServerServer.java`
- `contrib/client/joined/com/threerings/projectx/data/ProjectXBootstrapData.java`
- `docs/24-pointing-a-client-at-it.md`

Reference checks/probes: `contrib/check_bootstrap_shapes.py`, `contrib/check_custom_streaming.py`, `contrib/client/ServerProbe.java`.

2026 and 2011 streamed class shapes differ; old narrative docs describe issues since resolved. Inspect effective streaming fields and actual runtime evidence.

### P0 · Knight selection, creation, character lifecycle (`knights`)

What character state is created, selected, streamed, and saved?

Tortodrone: character/CharacterStore.java, server/network/ClientSession.java.

Open in this order:

- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/KnightManager.java`
- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/KnightProvider.java`
- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/persist/KnightRepository.java`
- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/persist/KnightRecord.java`
- `vendor/reference-server/compat/com/threerings/projectx/data/KnightMarshaller.java`
- `contrib/client/CreateKnightProbe.java`

Reference checks/probes: `contrib/check_provider_binding.py`, `contrib/check_bootstrap_shapes.py`.

SPECIAL HAZARD: client request 3 is checkKnightName in the documented 2026 contract; historical 2011 mapping associated ID 3 with deleteKnight. Never infer IDs from 2011 order.

### P0 · World zones, scenes, distributed actors (`scene`)

How is the world loaded, how are players moved, and what scene state is authoritative?

Tortodrone: world/SceneSimulation.java, world/SceneRegistry.java, server/network/WorldSession.java.

Open in this order:

- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/zone/ReferenceServerZoneManager.java`
- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/zone/ReferenceServerZoneMoveHandler.java`
- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/zone/ReferenceServerZoneRegistry.java`
- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/scene/ReferenceServerSceneManager.java`
- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/scene/ReferenceServerSceneRegistry.java`
- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/scene/ProjectXSceneProvider.java`
- `contrib/clyde-master/core/src/main/java/com/threerings/tudey/data/TudeySceneModel.java`

Reference checks/probes: `contrib/check_scene_cast.py`, `contrib/client/SceneLoadProbe.java`, `reference/observations/sidebyside.py`.

Clyde/Tudey source defines scene packing and updates. Distinguish static scene data from runtime actor state, subscriptions and authoritative transitions.

### P0 · DObjects, subscriptions, replication, serialization (`distributed`)

What mutable object and events must each client receive?

Tortodrone: protocol/SessionCodec.java, server/network/WorldSession.java.

Open in this order:

- `contrib/threerings/narya/core/src/main/java/com/threerings/presents/dobj/DObject.java`
- `contrib/threerings/narya/core/src/main/java/com/threerings/presents/dobj/DObjectManager.java`
- `contrib/threerings/narya/core/src/main/java/com/threerings/presents/dobj/DEvent.java`
- `contrib/threerings/narya/core/src/main/java/com/threerings/presents/dobj/AttributeChangedEvent.java`
- `contrib/threerings/narya/core/src/main/java/com/threerings/presents/dobj/EntryAddedEvent.java`
- `contrib/client/joined/com/threerings/projectx/data/ProjectXBootstrapData.java`

Reference checks/probes: `contrib/check_bootstrap_shapes.py`, `contrib/check_devent_apply.py`, `contrib/check_custom_streaming.py`.

Presents sends distributed object updates, not merely request/response. A successful RPC with no matching subscribed DObject changes can still break client behavior.

### P1 · Dungeon gates, parties, elevators, descent (`parties`)

How are parties assembled and moved to the next dungeon floor?

Tortodrone: server/network/WorldSession.java; currently solo interiors, not general parties.

Open in this order:

- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/dungeon/DungeonManager.java`
- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/dungeon/Gate.java`
- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/dungeon/Party.java`
- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/dungeon/LevelParty.java`
- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/dungeon/LevelPartyProvider.java`
- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/dungeon/LevelSceneManager.java`
- `analyzed-2011/com/threerings/projectx/dungeon/server/random/RandomLevelCreator.java`

Reference checks/probes: `contrib/check_board_gate_id.py`, `contrib/client/FloorProbe.java`, `contrib/check_arcade_descent.py`.

A scene appearing does not prove that party state, pending-floor naming, advancement votes, entrance routing or objective completion work.

### P1 · Mission/Adventure Board workflow (`missions`)

What happens from selecting a mission to completion/rewards?

Tortodrone: No general mission implementation; intro bypass is not completion.

Open in this order:

- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/mission/MissionProvider.java`
- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/mission/MissionManager.java`
- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/mission/MissionObjectives.java`
- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/board/BoardManager.java`
- `vendor/reference-server/compat/com/threerings/projectx/mission/data/MissionMarshaller.java`
- `contrib/reference/mission-handoffs.tsv`

Reference checks/probes: `contrib/check_d2_mission_objective.py`, `contrib/client/MissionWedgeProbe.java`, `contrib/check_conversation_reachability.py`.

Mission service did not exist in early 2011 archive; this tree includes newer compatibility implementation. Verify refusal/error behavior, scene transition and durable completion separately.

### P1 · Damage, attacks, hits, defence and statuses (`combat`)

What is validated and simulated server-side when players or monsters attack?

Tortodrone: No general combat implementation; visual emotes are not damage.

Open in this order:

- `contrib/reference/combat.md`
- `docs/08-game-mechanics.md`
- `analyzed-2011/com/threerings/projectx/dungeon/server/logic/AttackLogic.java`
- `analyzed-2011/com/threerings/projectx/dungeon/server/logic/DamageLogic.java`
- `analyzed-2011/com/threerings/projectx/dungeon/server/logic/DefenseLogic.java`
- `vendor/reference-server/compat/com/threerings/projectx/dungeon/server/logic/BulletLogic.java`
- `contrib/client/DamageEventProbe.java`

Reference checks/probes: `contrib/check_damage_events.py`, `contrib/check_bullet_contact.py`, `contrib/check_bomb_blast_region.py`.

Config .dat omits constructor-default values. Do not interpret missing damage, timing or flags as zero. Validate collision/damage server-side; do not trust client claims.

### P2 · Enemy behavior and spawns (`ai`)

Which monster behavior can be measured and what must be reconstructed?

Tortodrone: No general enemy simulation.

Open in this order:

- `docs/19-monster-behaviour-reconstructed.md`
- `docs/11-monster-behaviour.md`
- `contrib/reference/behaviour-evidence.jsonl`
- `contrib/reference/family-live-behaviour.json`
- `analyzed-2011/com/threerings/projectx/dungeon/server/logic/DungeonBehaviorLogic.java`
- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/dungeon/LevelSceneManager.java`

Reference checks/probes: `contrib/check_behaviour_engine_coverage.py`, `contrib/check_enemy_ai_execution.py`.

Critical distinction: the client behavior config group is stripped; behavior reconstruction mixes measured, deduced, bounded and invented values. Preserve provenance per decision.

### P1 · Inventory, equipment and atomic persistence (`items`)

What is the authoritative inventory transaction and equipment state?

Tortodrone: character/CharacterStore.java, character/WorldProfile.java.

Open in this order:

- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/item/ItemProvider.java`
- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/item/ItemManager.java`
- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/persist/ItemRepository.java`
- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/persist/ItemRecord.java`
- `vendor/reference-server/compat/com/threerings/projectx/item/data/ItemMarshaller.java`
- `docs/10-item-data-model.md`

Reference checks/probes: `contrib/check_equipped_ids.py`, `contrib/check_item_assign.py`, `contrib/check_equip_binds.py`.

Treat requests as server-authoritative inventory mutations. Validate ownership, counts, slot constraints, atomicity, replay and reconnection.

### P2 · Craft, vendors, auction, exchange (`economy`)

Which purchases/exchanges mutate persistent state and which calls show UI only?

Tortodrone: No general shops/crafting/auction implementation.

Open in this order:

- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/craft/CraftManager.java`
- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/craft/CraftProvider.java`
- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/auction/AuctionManager.java`
- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/auction/AuctionProvider.java`
- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/exchange/ReferenceServerCoinExchangeManager.java`
- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/shop/ShopProvider.java`

Reference checks/probes: `contrib/check_craft_wire.py`, `contrib/check_depot_purchase.py`, `contrib/check_auction_binding.py`, `contrib/check_economy_e2e.py`.

Beware historical docs stating auction is absent; newer source contains an auction implementation. Existence is not parity; inspect bindings, transactionality and playtest.

### P0 · Database models, schema and durability (`persist`)

Which state survives logout/crash/transfer, and how is migration handled?

Tortodrone: storage/Database.java, character/CharacterStore.java.

Open in this order:

- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/persist/PlayerRepository.java`
- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/persist/KnightRepository.java`
- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/persist/ItemRepository.java`
- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/persist/MissionCompletionRecord.java`
- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/ReferenceServerSession.java`
- `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/ReferenceServerServerModule.java`

Reference checks/probes: `contrib/check_db_identity_pin.py`, `contrib/check_progress_persist.py`, `contrib/check_recipe_persist.py`.

Avoid copying a particular SQL schema blindly. Translate invariants, transactions, and persistence lifecycle to TortoDrone storage.

### P0 · Live wire evidence and differential testing (`capture`)

How can we prove parity to the retail client rather than guess?

Tortodrone: scripts/compat/; unchanged stock reader is the oracle.

Open in this order:

- `contrib/client/wiretap.py`
- `contrib/client/WireSink.java`
- `reference/observations/sidebyside.py`
- `contrib/check_capture_service_calls.py`
- `docs/CAPTURE-ROUTE.md`
- `contrib/capture-app/README.md`

Reference checks/probes: `contrib/check_capture_service_calls.py --selftest`, `reference/observations/sidebyside.py --selftest`.

Use only authorized captures. Redact credentials/player identifiers. Event classes and field deltas in live vs TortoDrone are stronger evidence than UI appearance alone.

## Additional useful material outside the supplied index

- **Social/chat/guild/mail:** `vendor/reference-server/reference-server-server-development/src/com/github/reference-server/server/social/{SocialProvider,SocialManager}.java`, `guild/`, `persist/{FriendRepository,GuildRepository,MailRecord}.java`; `contrib/check_actor_chat.py`, `check_chat_command_gate.py`, `check_mail_claim.py`, `check_guild_hall.py`. Leads for ownership, visibility, membership and durable transfers; service presence does not establish completeness.
- **NPC/conversation/quest routing:** `contrib/reference/conversation-openers.tsv`, `conversation-reachability.tsv`, `dangling-conversation-targets.tsv`, `mission-handoffs.tsv`, `story-seed-sourcing.tsv`, `story-capture-list.tsv`; `contrib/check_conversation_reachability.py`. Keep native conversation transitions separate from invented quests/rewards.
- **Configuration and default values:** `contrib/clyde_dat.py`, `contrib/knightviewer/configs.py`, `contrib/reference/combat.md`, Clyde `export/{BinaryImporter,FieldMarshaller,Exporter}.java`. Omitted `.dat` fields inherit prototype/constructor defaults; they are not automatically zero. Inspect matching bytecode constructors.
- **Scene provenance:** `contrib/reference/scene-names.tsv`, `scenes.jsonl`, `docs/SCENE-CONFIG-NAMES.md`, `contrib/scene_from_wire.py`, `check_scene_export.py`. Reuse Tortodrone `scripts/StockTools.java` and checked map packs; archive scene exporters are evidence/tools to inspect, not replacement deployment commands.
- **AI limits:** `docs/19-monster-behaviour-reconstructed.md`, `docs/BEHAVIOUR-CATALOGUE.md`, `contrib/reference/behaviour-evidence.jsonl`, `behaviour-reconstructed.json`, `behaviour-captures-wanted.json`, `family-live-behaviour.json`. The client strips server behavior catalogs. Preserve measured/deduced/bounded/invented provenance per value; authored AI estimates are unsuitable as stock rules.
- **Refusals and gaps:** `docs/LIVE-PARITY.md`, `LIVE-MEASUREMENTS-NEEDED.md`, `CONSTANT-DIVERGENCES.md`, `SECURITY-FINDINGS.md`, `PERFORMANCE-FINDINGS.md`; `contrib/reference/{stripped-refusals,use-logic-refusals,unported-handlers}.tsv`. Use as questions to investigate, never automatic requirements or permission to change data.
- **Navigation:** `docs/CHECKS.tsv`, `TOOLS-COVERAGE.md` and `SK_CODE_ATLAS.csv` locate focused checks/classes. Search the atlas for one symbol instead of loading its 1.9 MB CSV/2.6 MB HTML. `docs/WORKLOG.md` is useful for targeted chronology, not wholesale context.

## Tools and hazards

| Path | Purpose / interface (from source or docs; not executed) | Dependencies and hazards |
|---|---|---|
| `contrib/client/wiretap.py` | Client-side Narya message recorder. Actions `check`, `record`, `build`; options include `--live`, `--client-dir`, `--dump`, `--connect HOST[:PORT]`, `--depth`, `--width`, `--flushms`, `--dprop`. `--connect` stages an edited `deployment.properties`; `record` launches a game JVM. | Python stdlib plus neighboring `skpaths`, `launch`, `live_guard`; Java/Javap 25 and client jars. `--live` finds/launches Steam's installed game; the README says Steam must be running/signed in. `--connect` changes staged client configuration and makes a network connection. `--user/--password` are supported CLI flags and should not be used for credentials. It writes dump/build artifacts under archive paths by default. Not an offline-safe utility. |
| `contrib/capture-app/` (`README.md`, `build.py`, Java sources, `dist/`) | Standalone Windows GUI recorder; Record/Stop package for a live player session. Emits a zip containing redacted wire and frames. Build options: `--selftest`, `--desktop`, `--check-redactor WIRE`. | Build compiles Java recorder and packages it; Java compiler/Python, and game-bundled Java 25 for selftest. `--desktop` writes to Desktop. Actual Record path starts the installed client, requires Steam, reads `getdown.txt`, installs shim ahead of game jars, captures frames and wire; raw wire and console are deleted after redaction. Does not edit client bytecode according to docs, but launches a modified classpath/shim and interacts with installed game. |
| `reference/observations/capture_run.py` | Autonomous wire-guided floor capture. CLI `--pid`, `--dump`, `--floors`, `--engage`; `--replay-check DUMP` is documented as offline replay. | Python modules `orient`, `glfw_key`, `pilot`, `live_guard`; capture mode sends keyboard/mouse input to an existing game window, moves, attacks and enters elevators. Source documents guard/refusal behavior, but this is still live client interaction and must not be run casually. Replay mode reads a dump and sends nothing. |
| `reference/observations/capture_nav.py` | Tile-map/A* successor for floor capture; CLI supports `--pid`, `--dump`, `--floors`, `--engage`, `--steps`, `--selftest`. | Same live input stack plus `navmap`, `breaker`; capture mode moves, attacks and rides elevators. `--selftest` is described as a fake mover with no client/dump/server, but I did not execute it. |
| `reference/observations/sidebyside.py` | Offline comparator for two `record_session` directories or wire-only dumps. CLI `--live`, `--ours`, `--control`, `--md`, `--json`, `--wire-only`, `--selftest` and related filters. | Python stdlib plus local `correlate`, `live_scenes`, optional `numpy` and `cv2` for image metrics. Reads supplied records; report options write outputs. Inspect only relevant fields in private disposable copies; never publish raw session payloads. |
| `reference/observations/capture_run.py`, `capture_nav.py`, `reference/observations/plan-2026-08-22/capture.py` | Live route/combat capture scripts, not protocol parsers. | They can automate game actions and reach the network when paired with a running client. Treat as mutation-capable even where comments call them capture-only. |

Other source-level helpers named in the reference map include `contrib/check_capture_service_calls.py`, `contrib/check_bootstrap_shapes.py`, `contrib/check_custom_streaming.py`, `contrib/check_scene_cast.py`, `contrib/client/ServerProbe.java`, `CreateKnightProbe.java`, and `SceneLoadProbe.java`. These inspect captures/client classes or exercise a client/server, depending on the helper; check source before use. Their presence alone does not prove a capture exists.

## Offline sniff readers and interpretation traps

These interfaces were read from source; commands are examples, not passing test claims. Use copies under `/tmp` for outputs, and do not invoke archive defaults that write back into the reference tree.

- `contrib/client/WireSink.java` is the JDK-only reflective JSONL writer behind the generated tracker shim. It records message direction/class/fields; this is decoded client traffic, not a passive pcap or independent stock-client run. Reflection depth/width limits can omit data.
- `contrib/check_capture_service_calls.py --capture <copy.jsonl> --service <name>` learns invocation codes from streamed marshallers, then counts actual `InvocationRequestEvent` method IDs. Receiving a marshaller is not invoking it. Exit 0 means called, 1 means selected service uncalled while other requests were observed, 2 means not judged (absent/unreadable/no requests). `--selftest` exercises the scanner.
- `contrib/wire_redact.py <copy.jsonl>` reports identifying fields; `--write` writes an adjacent `.redacted.jsonl`; `--selftest` checks planted identities/secrets and idempotency. Python stdlib. It masks known names/chat/guild/credential fields and byte arrays. Redaction success is not permission to commit a recording; decoded blobs need separate inspection.
- `contrib/scene_from_wire.py <copy.jsonl> --out /tmp/<new-scenes> --ids <ids>` uses `_data64` exports and `contrib/scene_convert.py`. Generated scene IDs may be reused: use scene ID plus place object ID and record occurrence, not scene ID alone. Base64 scene bodies can bypass string redaction. Output is an asset requiring private handling and stock-client validation, not a proven map or committed fixture.
- `contrib/check_provider_binding.py` compares real client sender IDs/arity with effective server marshaller constants and registration. It uses `javap`, matching client JARs and archive build/classpath order. Matching arity still does not prove parameter types match; the script's preamble explicitly calls out this limit.

## Capture presence, verified without reading payloads

- `reference/observations/wiretap-2026-08-22/logon-attempt.jsonl` exists (1,227 bytes) and its neighboring `README.md` documents it as four handshake messages ending in a version refusal, with no gameplay. The README is provenance, not an independent validation of every payload claim.
- `reference/observations/wiretap-2026-08-22/console.txt` exists (2,167 bytes). Do not treat it as wire evidence; its README records historical credential/identity redaction concerns.
- Numerous live-derived structured artifacts exist, including session, scene, combat, gate and server-object JSON/JSONL under `reference/observations/`. They are extracted datasets, not packet captures. Sensitive payloads were not opened.
- `find run -name '*.jsonl'` found no working-tree run dumps; `find contrib -name '*.pcap*'` found no packet captures. No conclusion that no ignored files exist outside these searched paths.
- Important staleness: the opening prose in `contrib/client/wiretap.py` claims there is no wire capture anywhere in the tree, but the committed `reference/observations/wiretap-2026-08-22/logon-attempt.jsonl` and its README plainly establish one. Treat that opening claim as stale. Its sample commands also include a local-server credential example; do not copy credentials into CLI arguments.

For Tortodrone's Java 25 stock-client server, the safe immediate evidence is source inspection plus already-committed derived data. Any new capture requires a deliberate disposable environment and awareness that archive capture paths can start/automate the installed game, alter staged preferences/configuration, write artifacts, and connect to a server.


## What to defer or exclude

Exclude launcher/client rewriting, real preference redirection, archive migrations/seeding/deploy commands, Steam/guest auth, wiki-derived rewards and invented monster tuning. Textures/models, wiki crawls, full loot catalogs, `vendor/scene-archive`, editor screenshots and visual-mod tooling are low priority until a concrete server task needs their metadata. Existing Java map/item exporters remain the persistent tooling boundary. A capture app being Java does not make its client instrumentation acceptable.

## Evidence record for each future milestone

Record archive commit, exact file and line/symbol, client JAR hash, request ID/types/callback, owner authorization, state preconditions, refusal/replay behavior, durable transaction, response/event order and subscriptions. Label each unknown. Add matching Tortodrone tests and compare with unchanged client readers; use isolated two-client graphical tests for rendering/animation/travel. No automatic database migration and no new rewards without stock evidence.

## Inventory validation

This documentation milestone checks indexed path existence and JSON validity, reviews the Git diff and runs `./gradlew build`. It does not validate archive scripts, historical captures, retail service availability or new gameplay. No graphical check is required for documentation-only changes; future gameplay work must follow [development](development.md).

Validation on 2026-10-11: JSON parsing and 132 indexed source/check paths passed; `git diff --check` passed; `./gradlew build` passed on Temurin 25.0.4.1 (59 actionable tasks, full standalone unit and integration suites). Archive Git status remained clean. No archive scripts, client sessions or graphical tests were run. `VERSION` remains 0.24.0 because this milestone changes documentation only.
