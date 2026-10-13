# Modern capture integration, 0.27.0

The map milestone replaces all seven previous default scene binaries with the original payloads
from the 2026-10-12 local official-gameplay recordings. Recovery used the scene index and original
`_data64` fields, verified source chunk hashes, binary size, Clyde header and every scene SHA-256,
then decoded all 14 scenes using the unchanged installed stock client. No capture streams,
authentication records, client binaries, meshes, textures or audio are shipped.

The production server loads the prepared packs and never reads raw capture streams.

## Recovered scenes

| Captured scene | Version | Bytes | SHA-256 | Integrated |
| --- | ---: | ---: | --- | --- |
| 1 — Town Square | 104080137 | 82299 | `83115fa639bb1e55c93827772d2c46b929282c3641854c1ea5422e8cb0949ac2` | Yes |
| 2 — Bazaar | -1181438827 | 67823 | `799ce57af9293c83828ae13f19d0ed0ef197940432eaf370c2b99180b57916f9` | Yes |
| 3 — Arcade | 1393276398 | 25814 | `414a4d1dba16160de20c1fc8e19961291edcbc5e47a7427f4dfc3d6bf35b1701` | Yes |
| 217 — Rescue Camp | 375 | 33691 | `2745c6c81c4c2df678beefdaa40cc20cfbc24aa88f9eb802c3e9bd18301ddfec` | No |
| 294 — Advanced Training | 359 | 25625 | `bbf967c39b3883e438fd8165fba74c3be45cfcbecdec9c585f1edaa21d1cc144` | Yes |
| 318 — Hall of Heroes (four-star) | 801483335 | 16939 | `3e4242d1bc28f94b757cdd2b81f7d08531e496b617dcaaee82e26c9bb146a21c` | Yes |
| 378 — Jigsaw Valley / The Jade Tangle 2 | 361 | 17340 | `3dc70e61487dd5b2ef2faec0ac5ea74dfc0664c853c77a6ac0672f18c893007a` | No |
| 398 — Tutorial B | 2008861103 | 55487 | `1d395e7781d4f9c3ce9cdb8b7a12eb5f53014583ef3a9b2d2d16e848429f73ff` | No |
| 445 — Garrison | -1605005030 | 41901 | `73bf3e35098998d660faf7d068aa5af663832d79f59588541721478183700a65` | Yes |
| 528 — Private guild hall (excluded) | 301 | 50700 | `d0de94709c1644803c58334825edf8f9988856ba781469e41ca0d7c563d755c7` | No |
| 30893 — Laboratory | 817806710 | 20542 | `2fd25972c6ab80c67bc042c477c3d7ec176d6c4556e752f228e02d20a578f04f` | Yes |
| 1073741853 — Generated gremlin floor | 1 | 32658 | `00e69674213212d2e52a772abbfb29cf2f13c86a1468eb18bd6f3ab9db83ef1f` | No |
| 1073741854 — Generated Deconstruction Zone floor | 1 | 19525 | `6cb247eab0f1ce359b671f0bc07296540e60d3e9e91756ebf2bc05378973710c` | No |
| 1073741855 — Generated gremlin floor | 1 | 30353 | `3b53184bd3cf3cb1905f550f8d5807aa8cc902237be5443857f7eb938bfbac72` | No |

All seven old scene resources were replaced in their existing pack folders. Laboratory changed
from 437 to 30893; Hall of Heroes changed from 449 to the captured four-star variant 318.
The former archive source pins and both historical exporters were removed. Old `owned.*` digest
records remain solely to recognize and safely upgrade deployments of previously shipped defaults.

Collision was rederived for every replacement using the native PC shape and quarter-unit grid.
Some masks retain identical bytes where the changed scene leaves collision unchanged. No old
collision mask was copied onto a new scene without rederivation. Authored default entrance positions
and facing replace the former nearest-origin town spawn policy. The exporter refuses an unwalkable
authored arrival. Custom descriptors lacking `spawn-rotation` retain their prior zero-facing behavior.

There are 12 directed town routes, including the modern northwest Bazaar–Arcade pair, plus six
interior entry/return routes. Trigger IDs, polygons, tagged arrivals and default markers are read from
the replacement scenes. The stock-reader oracle checks all 18 sensors and all seven default spawns.
Town Scene IDs and native specialized interior route bindings remain distinct from runtime object IDs.

The original modern protocol records distinguish ZoneSummary's qualified Name
`%town:m.haven` from TownSummary's string key `m.haven`, with icon `town` and default scene 1.
Descriptors and serialization now preserve that distinction instead of sending the literal
`Haven` in both fields. This corrects the stock town-name lookup, including OptionsDialog;
capture-specific runtime zone/object IDs are not reused. Legacy unqualified custom names keep
their existing serialization.

## Scope and evidence limits

The captures establish the four-star Hall of Heroes layout, not rank-dependent selection or
promotion rewards. Its native visual layout is available with the existing peaceful interior transport.
Rescue Camp, Tutorial B and The Jade Tangle require their authored scenario/objective systems before
being offered as playable missions. Generated runtime scene IDs are observations, not permanent
mission template IDs. The private guild hall is excluded from shipped resources.

Mapped static decorations and client-rendered props are retained in the original binaries. Dynamic
NPC actors, interaction offers, dialogue execution, shops, crafting, missions, monsters and rewards
require additional server behavior; replacing geometry does not implement them.

The NPC evidence contains ConversationDialogInfo occurrences whose source recordings retain only
the class name and a `_privacy` omission marker. A full-private export cannot restore fields that
the recorder omitted. The response RPC signature alone does not establish dialogue actions,
initial nodes or trigger causality. The unchanged stock resource oracle resolves the Town Square Greeter at entry 5314,
actor configuration `Character/NPC/Haven/Stranger/Greeter`, and conversation
`Haven/Strangers/Greeter`. It exposes the original message/response keys and a ClearDialog
response, but its initial ConversationJump is blank. Local source marks ConversationJump
as strippable and explains that client exports replace server actions with prototypes;
the compatible donor refuses a blank jump and supplies no Greeter action binding.
Choosing a node or binding based only on similar names would invent missing behavior,
so Greeter conversation support is not enabled.

The shop observations likewise omit ShopDialogInfo contents. `ActionDesc$Shop` provides
only its class, UniqueShopInfo records good counts, and ConsolidatedOffer represents an
exchange observation rather than a permanent NPC price table. They do not establish a
complete merchant binding, offer price/currency and purchase rule. MissionObject observations
contain only expansion prices and runtime OID; MissionCompletion contains ID, completion
and difficulty, while MissionCompleteDialogInfo contents are omitted. These records do not
establish complete objectives, progression or rewards. No prices, missions or rewards were
invented, and the existing item catalogue remains intact.

Focused local combat review found no confirmed weapon-specific contradiction for the listed
weapons. In particular, keeping projectile origin at the source before applying the launch
offset agrees with the local native BulletAdvancer. Unsupported conditional/loop execution
remains a recorded gap; no specific Voltage, Amputator or other weapon fix is claimed.

## Validation

Acceptance completed on 2026-10-13 using Java 25.0.4.1 and the unchanged installed stock client.
`GAME` below denotes the read-only installation at
`/home/n0rule/.local/share/Steam/steamapps/common/Spiral Knights`.

| Command/check | Actual result |
| --- | --- |
| `./gradlew --offline build` | Passed in 4m 13s; 77 tasks, including standalone unit/integration, storage, authentication, resource ownership and lifecycle checks. |
| `java scripts/compat/CheckClientWorld.java "$GAME"` | Exit 0. Stock readers checked seven scene configurations, native spawns/facing and all 18 sensors. Native networking walked 12 town and six interior routes, checked concurrent private Training entries, login/takeover/reconnect, inventory/equipment, F3 authorization, Unicode chat/tells, inspection/privacy, 68 emotes, instance isolation and trade completion/cancellation. |
| `java scripts/compat/CheckCombatRendering.java "$GAME" /tmp/tortodrone-modern-graphics-20261013-c` | Exit 0. Two stock graphical clients rendered shared Haven and replicated movement. All seven modern maps and return transitions loaded; nine existing sword/gun/bomb/shield/bash/dodge actions passed native animation and projectile checks. |
| `./gradlew --offline testProcessCleanup prepareHaven -PgameDir="$GAME" -PcapturedScenes=/tmp/tortodrone-modern-sources-_2igqrng -PhavenOutput=/tmp/tortodrone-modern-reexport-20261013` | Passed. Cleanup confirmed child exit and interrupt preservation. Fresh export reproduced all 29 catalogue/map/collision/portal files byte-for-byte; the manifest is excluded because it records ownership history. |
| `./gradlew --offline benchmark --args='--instances 4 --clients 300 --seconds 10'` | Passed: 300 ready/completed, zero failures, 19.83 deltas/client/s, ping p95 1.62 ms and p99 2.26 ms. |
| `git diff --check` | Passed. |

The graphical run used Linux/niri and distinct disposable preferences, homes, extraction paths,
accounts and server data. Pixel review covered both Haven views, Bazaar, Arcade, Garrison,
Laboratory, four-star Hall of Heroes, Training and the bomb explosion. The peer's actual rendered
model moved from `(1,-30.5)` to `(1,-28.825003)`, separately from the authoritative actor check.
Client-rendered Laboratory characters/props appear with the captured map; that is not NPC service
acceptance. This was automated native-client testing, not manual gameplay or keyboard-binding testing.

Two preliminary graphical attempts failed in the fixture: the automatic Uplink window blocked
input, then a probe used the wrong root accessor. Normal Uplink dismissal through `app.getRoot()`
resolved both. The accepted run still logged occasional stock unmanaged-window warnings during
transitions; no claim of warning-free logs is made. All test children exited; the owner's processes,
preferences, client and data were untouched. Logs and reviewed PNGs are retained locally under the
ignored `.build/modern-validation/` tree and are not shipped.

The short localhost benchmark measured 165.19 MiB aggregate downstream in 10 seconds,
1.00 server CPU core, 457.05 MiB peak RSS and 642 peak threads. It is a synthetic town movement
check across four instances, not proof of WAN performance, crowded graphical play, NPC/combat
load or long-run stability.

Changed production files are the seven packs and provenance manifest, `SceneCatalog`,
`TownMovement`, `WorldSession`, `SessionCodec`, `VERSION` and the Gradle preparation wiring.
The existing stock tooling was adapted for local modern scenes; `ArchiveTownExport` and
`HavenInteriorsExport` were removed. Compatibility fixtures, movement/catalogue tests and current
deployment/development documentation were updated together. SQLite remains schema 8.
