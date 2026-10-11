# Packet and online-session audit (0.24.1)

Audit date: 2026-10-11. Baseline: `fe04cde` / 0.24.0 on `dev`. Evidence comes from the read-only local Spiral archive at `566636e022c7356c62ea6fcc1aba37aa4e9e38dc`, unchanged installed client classes, current Tortodrone source and disposable tests. No online reference or production service was used.

The installed client reports build `20261006114613`; `code/projectx-pcode.jar` SHA-256 is `391a51cef0446f55d461bdb8f942ca9dba44e6e4138acdcd69ab2024297f543c`. This is newer than some archive prose and examples. Matching installed bytecode takes precedence over historical method tables and reconstructed archive server behavior.

## Confirmed defect and repair

Two legitimate stock requests disconnected the client before dispatch:

- Knight method **17**: `(Pref, Object, ConfirmListener)`, with `Pref.LOOT_CHAT_FILTER` and a `LootReportLevel` value (`ALL`, `PRO`, `NONE`).
- Social method **4**: `(boolean, DungeonCodes.Difficulty, boolean, ConfirmListener)`, with difficulty `EASY`, `MEDIUM` or `HARD`.

`SessionDecoder.value` supported `Pref` but omitted both value enums. A temporary probe serialized the real stock `ForwardEventRequest` / `InvocationRequestEvent` using `com.threerings.io.w`; both packets produced `Rejected: Unsupported event value` against 0.24.0. `ClientSession` consequently exited and closed the connection. This establishes a wire compatibility defect independently of a hand-built test packet.

0.24.1 explicitly reads these two three-value ordinal enums, preserving the parser whitelist, nesting/count bounds and rejection of unknown classes. Unsupported requests now reach the existing listener failure callback instead of disconnecting. This does not implement preference persistence, matchmaking or party settings. `OptionRefusalTest` checks every valid ordinal, invalid ordinals 3/255, correlated refusal, mapping reuse, a subsequent heartbeat and clean logoff. `WorldConnectionCheck` calls the real stock service senders before selection and after scene readiness, checks every choice, retained player state and a subsequent supported name-check RPC.

Local evidence: Tortodrone `src/main/java/org/tortodrone/protocol/SessionDecoder.java:142`; archive `vendor/reference-server/compat/com/threerings/projectx/data/KnightMarshaller.java:585` and `vendor/reference-server/compat/com/threerings/projectx/social/data/SocialMarshaller.java:187`. Installed `javap -c -p` confirms the two IDs/argument arrays; `javap -p` confirms both enum constant sets. The installed `Pref` also contains `PARTY_WITH_ANY`, so the archive's older two-value `Pref` statement must not be copied.

The stock suite also exposed two fixture problems. `GameMasterConnectionCheck.Endpoint` applied already queued source-scene deltas after clearing its actor for travel; it now filters by the current scene and stops observation on close. `TellConnectionCheck` posted its broadcast barrier before the stock client's asynchronous unsubscribe action had run, intermittently allowing the supposedly offline tell. The fixture now dispatches that local action before posting the server barrier, and reports the actual refusal/result on failure. Archive Narya `vendor/narya-spiralstudio/core/src/main/java/com/threerings/presents/client/ClientDObjectMgr.java:242` documents the queued action; unchanged installed bytecode confirms `SceneDeltaEvent.aee()` returns `_sceneOid`. These are test-tool corrections; the server's tell routing was unchanged.

## Audited contracts and invariants

Service codes are handles carried in bootstrap/scene/trade objects, not fixed global stock numbers. Tortodrone assigns its own codes and dispatches the matching client marshaller. A retail capture having a different code/order is not by itself a mismatch. Method IDs, argument types and callback layouts must match the installed marshaller.

| Area | Tortodrone entry point | Evidence and assessment |
| --- | --- | --- |
| Framing and class maps | `protocol/HandshakeCodec.java`, `protocol/SessionDecoder.java` | Explicit length prefix, fragmented/coalesced reads, clean EOF vs truncation, sequential per-connection incoming maps; no peer-driven class loading. Narya `io/ObjectInputStream.java` uses sequential class maps. Incoming frame limit is 64 KiB. |
| Secure/normal login | `server/network/SecureHandshake.java`, `protocol/HandshakeCodec.java` | RSA masked-secret/salt exchange, AES credential envelope, distinct invalid-user/password callbacks; stock auth response reader checks `ident == null`. One active account lease; failed authentication does not take over. |
| Bootstrap and subscribed objects | `protocol/SessionCodec.java`, `server/network/ClientSession.java` | Installed stock reader accepts bootstrap, PlayerObject, ServerObject, DConfigObject and native scene/party snapshots. Advertised optional service handles do not mean every operation is implemented. |
| Character lifecycle | `server/network/ClientSession.java` | Knight methods 3/4/5/6 are name check/select/create/delete. They match current bytecode, avoiding the archive's destructive 2011 name-check/delete drift. Ownership, global-name races and durable reconnect are tested. |
| Inventory/equipment | `server/network/WorldSession.java`, `protocol/SessionCodec.java` | Item method 3 equips `(long,int,Confirm)`; method 9 moves `(long,Location,int,Confirm)`. ARSENAL unequip and current-loadout/slot events precede confirmation. QUICKBAR/UNKNOWN are explicit unsupported destinations. Sharing the internal equip helper is not evidence of an ID mix-up. |
| Inspection | `server/network/WorldSession.java`, `protocol/SessionCodec.java` | Scene method 2 `(Name,Result)` returns stock PlayerEquipment; Knight method 13 controls privacy flag 1024. Only ready same-scene targets, equipment/privacy guards and stock reader/network checks. |
| Emotes and chat | `server/network/WorldSession.java`, `world/SceneChat.java` | Scene method 3 `(String,Name,Confirm)`, scene-local Speak method 1 `(String,byte)`, global Chat methods 2/3 for broadcast/tell. Native success/failure shapes, Unicode, sender identity, per-instance/privacy/rate and live GM checks are covered. |
| Trade | `server/network/PlayerTrades.java`, `protocol/SessionCodec.java` | Scene invite method 4, Social response method 5 and trade-object methods 1–5 match stock signatures. Invitation notifications, dialogs, offer revisions, ownership, atomic transfer and cancellation are exercised. |
| Travel/readiness | `server/network/WorldSession.java`, `world/SceneRegistry.java` | Scene/Zone method 1 use their native callback shapes. Server chooses instance/arrival, pending travel constrains destination, readiness gates deltas and old-source input grace is bounded to the departing scene. Cache version cannot authorize another destination. |
| Replication | `world/SceneSimulation.java`, `server/network/WorldSession.java` | Once-per-instance tick, immutable snapshots, recipient baseline advances only after queuing. Stock readers apply actor adds/updates/removes and previous-snapshot references, including delayed input acknowledgement and stable model identity. |
| Slow/disconnected peers | `server/network/SessionOutput.java` | Codec mapping and queue insertion are serialized; one writer and 64 pending frames. Queue/encoding failure closes the stream rather than reusing unsent class mappings; separate peers continue. |
| Revocation and durability | `account/AccountStore.java`, `server/network/WorldStatePoller.java` | Session takeover, reset/delete, stale cleanup, transactional ownership and schema refusal have disposable regression coverage. Persistent state is committed before supported notifications. |

Archive source routes are in [the local retrieval guide](reference-inputs.md). Narya/Clyde code describes engine mechanics; ReferenceServer reconstruction and historical passing reports do not prove retail semantic parity.

## Remaining limits

- This validates implemented local TCP flows, not the complete stock game. General combat, NPC interactions, missions, shops/crafting/auction, friends/guild/mail, general parties and quickbar remain outside the supported scope.
- Complex argument classes outside the decoder whitelist still fail closed. The two repaired settings do not establish clean refusal for every unimplemented stock RPC. Extending the whitelist requires a matching client contract and a bounded parser.
- UDP is not advertised or implemented. Town/interior inputs use TCP fallback; the interior throttle of 100 messages/s is Tortodrone's transport policy. Local tests cannot establish packet-loss/WAN jitter behavior or parity with stock UDP.
- Socket timeouts are per-read inactivity limits (15 s handshake, 60 s authenticated session), not absolute handshake/frame deadlines or general invocation flood control. Client count, frame/parser bounds, movement replay limits, chat rates and output queues provide separate bounds; this audit does not certify resistance to sustained hostile traffic.
- Reconnect returns through the configured entry scene. Exact retail last-town/last-position semantics remain unverified; no coordinate persistence or database migration was introduced.
- Network/reader checks do not prove rendering/animations. This repair changes enum decoding and refusal only, so no new graphical acceptance is claimed. Previously recorded graphical coverage is in [town interactions](town-interactions.md).
- Short synthetic load tests do not prove long-run stability, Internet performance or live-service parity. No fresh authorized retail gameplay capture was collected; the committed failed-logon capture cannot settle those semantics.

## Validation

Changed files: `src/main/java/org/tortodrone/protocol/SessionDecoder.java`, `src/test/java/org/tortodrone/server/network/OptionRefusalTest.java`, `scripts/compat/{WorldConnectionCheck,GameMasterConnectionCheck,TellConnectionCheck}.java`, `VERSION`, `docs/PROJECT.md` and this audit. Database schemas, owner data and installed client files were unchanged. Test processes and deployments belong to these isolated fixtures.

- `./gradlew unitserver_network_OptionRefusalTest`: passed, including all six valid choices, ordinal bounds, correlated callbacks and continued heartbeat/logoff.
- `./gradlew build`: passed on Java 25 (60 tasks, 54 executed), including the full standalone unit/integration suite. `java -jar build/tortodrone.jar --version` reports `Tortodrone 0.24.1`.
- Stock-writer `/tmp/StockOptionDecodeProbe.java`: reproduced both decoder refusals on 0.24.0; the same real stock serialization decodes both enum arguments/listeners on 0.24.1.
- Read-only `javap -p` checks confirm all six whitelisted enum bounds match the installed client: KnightHeight 5, EyeType 47, Item Location 4, Pref 3, LootReportLevel 3 and Difficulty 3.
- A temporary `/tmp/CheckClientWorld.java` wrapper ran the corrected `TellConnectionCheck` ten times consecutively against one disposable server with fresh client logins: all ten passed. Before the ordering correction, the same repetition failed on run three with a successful tell (`reason=null, idle=0`) after the fixture's premature barrier. No source-scene null-actor warning occurred after filtering the helper's events.
- `java scripts/compat/CheckClientWorld.java '/home/n0rule/.local/share/Steam/steamapps/common/Spiral Knights'`: final run passed against 0.24.1, using the unchanged installed stock classes/resources and disposable servers, accounts, preferences, maps and keys. Covered normal login/create/reconnect, the new option refusals before selection and after entry, equipment/Arsenal/model updates, movement/facing/sit, Unicode chat and tells, GM role revocation, all ten Haven walking links, inspection/privacy, all 68 emote RPCs, all six interior links, concurrent training isolation, native interior party exit, same-account takeover/rapid reconnect, two-instance isolation, item grants and persistence, and native trade invitation/accept/reject/commit/cancellation. Reader checks accepted bootstrap/scene/party/delta/item schemas and all 8,454 grantable item references. The earlier 0.24.1 run stopped at the subsequently reproduced tell fixture race described above; it was not counted as passing.
- `./gradlew benchmark --args='--seconds 20 --clients 300 --instances 1'` and `./gradlew benchmark --args='--seconds 20 --clients 300 --instances 4'`: both passed, each with 300 ready/completed, zero failures and 6,000 measured pings.

| 300-client town movement, 20 s after ramp | Instances | Failed | Login p95 (ms) | Ping p95 / p99 (ms) | Deltas/client/s | Downstream (MiB total) | CPU cores | Peak RSS (MiB) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Crowded Haven | 1 | 0 | 2289.23 | 1.82 / 7.82 | 19.65 | 1292.04 | 1.44 | 407.64 |
| Four Haven instances | 4 | 0 | 1924.98 | 1.68 / 2.75 | 19.85 | 330.76 | 0.85 | 383.47 |

Load reports: `/tmp/tortodrone-load-12648320719446794603/results.csv` (single instance) and `/tmp/tortodrone-load-17398771958287699043/results.csv` (four instances). The benchmark reads frame/event headers and scene/timestamp fields rather than decoding complete actor payloads; full stock reader/network checks provide the separate payload evidence. Dense-scene bandwidth is a material limit: the single-instance run emitted about **64.6 MiB/s aggregate** (roughly 542 Mbit/s) downstream; four instances reduced it to **16.5 MiB/s** aggregate. Low loopback ping and zero short-run disconnects do not prove that an Internet link or graphical client can sustain it. All benchmark accounts/private keys and owned servers are removed/stopped by the fixture; reports/logs remain in `/tmp`.
