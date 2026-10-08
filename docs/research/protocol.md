# Spiral Knights Protocol Research

> Historical evidence. For current behavior and workflow, read [the research index](README.md)
> and [AGENTS.md](../../AGENTS.md). Past client experiments are not current instructions.

Historical observations, retained for protocol development. Launcher tools and
`local/` evidence directories referenced below are no longer part of the active
project. See [server architecture](../server-architecture.md) for current runtime
behavior and setup.

Build `20261006114613`; observations on 2026-10-07. This is a partial protocol
description, not a complete codec specification. Evidence IDs are defined in
[research-sources.md](research-sources.md).

## Connection lifecycle

CONFIRMED (HIGH, R1): the full installed `ProjectXApp`, with only its deployment
resource redirected, opens TCP `127.0.0.1:47624` and sends `SecureRequest`.
The receive-only server sends nothing, and the experiment terminates the app
after observing traffic. A separate probe using the installed networking
classes produced the same message type and length.

CONFIRMED by code (HIGH, N1, C1): the secure branch waits for `SecureResponse`,
constructs `AESAuthRequest`, then waits for authentication and bootstrap.
CONFIRMED (HIGH, R2): TortodroneServer now sends the secure response and the
installed client follows with `AESAuthRequest`.
CONFIRMED (HIGH, R3): local-authentication mode decrypts the synthetic credential
envelope and sends success; the client then transmits `PingRequest` while
bootstrap is pending.

## Transport

CONFIRMED (HIGH): initial TCP, custom Narya/Presents serialization. No initial
TLS record, HTTP request, or WebSocket upgrade was observed. Later UDP is
configured but was not exercised. TCP reads may fragment or combine frames;
receive-only mode labels its output as read chunks. Secure-handshake mode
accumulates bounded frames across reads.

## Message framing

CONFIRMED (HIGH, N1, C1, R1):

| Byte offset | Encoding | Meaning |
| --- | --- | --- |
| 0–3 | Big-endian 32-bit integer | Total frame length, including header |
| 4 onward | Narya serialized object | Message instance, class mapping, fields |

The observed prefix was `00 00 01 b5`: total 437 bytes, body 433 bytes.
The public `FramingOutputStream` and installed `com.threerings.io.s` agree
on that convention.

CONFIRMED (HIGH, N1, C1): serialization is not Java's standard
`java.io.ObjectOutputStream` format. It has stream-local signed 16-bit class
codes: negative values introduce class names, positive values reuse mappings,
and zero represents null in the relevant object context. A new mapping writes
the class name using Java `writeUTF` encoding (16-bit byte length plus modified
UTF-8). Do not confuse a class mapping or upstream sequence ID with a globally
assigned packet opcode.

CONFIRMED (HIGH, C1): `ProjectXApp.main` configures ordinal enum serialization.
UNKNOWN: all field layouts/custom streamers necessary for bootstrap and game
messages. Do not assume arbitrary reflection order or compatibility with an
unmodified current upstream library merely from matching class names.

## Message: SecureRequest (first observed client frame)

Direction: Client -> Server.

Observed total length: 437 bytes in the networking probe and full-client runs.
This is one build/locale/credential-mode observation, not a fixed length for
every request. Ciphertext changes between runs.

Confidence: HIGH (public source, installed bytecode, captured full-client
traffic, and successful deserialization with the installed reader).

Header and first type mapping:

| Offset | Observed value | Interpretation |
| --- | --- | --- |
| 0 | `00 00 01 b5` | 437-byte frame |
| 4 | `ff ff` | New class mapping, code 1 |
| 6 | `00 29` | 41-byte class name |
| 8 | `com.threerings.presents.net.SecureRequest` | Serialized type |
| 49 | `00 01` | Upstream message sequence ID 1 |
| 51 | `ff fe` | New class mapping, code 2 |
| 53 | `00 30` | 48-byte credential class name |
| 55 | `com.threerings.presents.net.PublicKeyCredentials` | Credential type |

Body values verified with the installed deserializer:

| Field | Confirmed observation |
| --- | --- |
| `messageId` | 1; sequence field, not packet type |
| `_creds._encodedSecret` | Byte array of length 256; contents remain local |
| `_creds._salt` | Byte array of length 4; contents remain local |
| `_creds._secureVersion` | 1 |
| `_version` | `20261006114613` |
| `_zone` | Client timezone string, **not a game zone identifier** |
| `_bootGroups` | Empty array in this request |

There was no account username/password, session ID, service ID, or scene ID
in this decoded request. That statement applies to this request, not future
authentication messages. No compression wrapper was used in this frame.

## Authentication messages and encryption

CONFIRMED (HIGH, N1, C1): `PublicKeyCredentials` generates a 16-byte client
secret and four-byte salt; the combined bytes are encrypted with the configured
RSA public key. The observed encrypted array is 256 bytes. The secure version
is 1. The server needs the matching private key to recover and validate it.

CONFIRMED by source and matching methods (HIGH): `SecureResponse` places a
hex representation of a server secret XORed with the client secret in response
data. `AESAuthRequest` uses the recovered shared secret for the subsequent
authentication payload; the AES helper uses `AES/CBC/PKCS5Padding`.
The initial response layout has now been checked with the installed serializer
and exercised against the full client (R2).

The official private key is neither available nor needed for our project.
The handshake experiment generates a fresh local keypair and replaces only
`key.public` in the ignored deployment overlay. RSA uses `RSA/ECB/PKCS1Padding`;
the decrypted 20 bytes contain the 16-byte client secret followed by the
four-byte salt, which the server validates. No production traffic is decrypted.

### SecureResponse implemented subset

CONFIRMED (HIGH, R2): this first downstream frame is 139 bytes for a 16-byte
server secret. Its independent class mapping starts with `SecureResponse`, then
`AuthResponseData`. Fields are the request's 16-bit message ID, response data's
32-bit object ID (zero here), a non-null string marker, and a modified-UTF string
containing 32 lowercase hexadecimal characters: server secret XOR client secret.

The request parser supports only secure version 1, 2048-bit RSA ciphertext,
four-byte salt, non-null version/timezone, and an empty bootstrap-group array.
It validates field boundaries and rejects unsupported layouts. Mapping codes
are connection-local; this is not a general-purpose Narya deserializer.

CONFIRMED (HIGH, R2): the full client responded with a 348-byte frame whose top
level type is `AESAuthRequest`. Secure-handshake-only mode identifies that type
and closes. Local-authentication mode now continues as described below.

### AESAuthRequest implemented subset

CONFIRMED (HIGH, R3): the outer object contains a 16-bit message ID, null outer
credentials, version and timezone strings, bootstrap-group array, then a 32-bit
ciphertext length and ciphertext. The full client requested four bootstrap
groups. Strings use modified UTF; the final string array uses its null bitmask.

The encrypted payload is an independent Narya stream with fresh class mappings.
AES uses the negotiated 16-byte server secret, `AES/CBC/PKCS5Padding`, and the
legacy protocol IV consisting of bytes 1 through 8 repeated twice. This IV is
verified in public source and installed bytecode. No IV travels in this message.

The supported credential type is `ProjectXCredentials`: inherited username
(`Name`) and password transform, then ident, language, sessionId, ticket,
segaPass, steamClient, siteId, invite, and region in the verified wire order.
The synthetic password transform matches MD5 hexadecimal output from the
installed constructor. This is compatibility research, not a proposed password
storage design. Identity and credential values are not logged.

The implementation accepts only the fixed synthetic username/password used by
the test wrapper, with no official session, Steam ticket, or partner flags.
Other passwords receive the verified `m.invalid_password` response code.
Unsupported credential shapes are rejected. Per-frame and ciphertext bounds
are implementation limits rather than official protocol limits.

### Authentication response and next observed message

CONFIRMED (HIGH, R3): the server replies with `AuthResponse`, the correlated
message ID, and `ProjectXAuthResponseData`. Its fields are object ID, code,
ident, sessionId, and authName (`Name`). Incoming and outgoing mapping tables
are independent and survive between frames. After the initial secure response,
this response introduces outgoing codes 3, 4, and 5; these are stream mappings,
not universal packet IDs.

The experiment generates a transient local session ID and returns `success`
only for the synthetic account. It stores no account or session record.
The client accepted a 232-byte response and sent a 49-byte `PingRequest` about
five seconds later. Public `BlockingCommunicator.logonSucceeded` and matching
installed bytecode show that its network writer starts only after `success`.
This confirms authentication response acceptance, not completion of full login.

Bootstrap remains UNKNOWN for the current game-specific service/object schema.
The server closes after identifying the first authenticated ping. No bootstrap,
character list, or zone data is sent. Decrypted credential buffers and negotiated
secrets are cleared when the experiment finishes.

UNKNOWN: whether/how individual post-authentication message classes use
encryption or compression. No evidence here supports describing the entire
TCP stream as encrypted or compressed.

## Server redirect messages

CONFIRMED by installed code (HIGH, C1): an authentication-error string beginning
`m.peer_redirect` is interpreted as redirect information; the handler consumes
host/TCP ports/datagram ports separated by `|~`. Its enclosing response and a
complete working server redirect remain unobserved. No numeric packet ID is assigned.

## Game server messages

CONFIRMED by public research (HIGH, N1, H1): Presents has bootstrap,
subscription, object-event, invocation, ping/pong, and logoff message types.
The headless client's exchange service uses the bootstrap-provided object ID.
UNKNOWN: sufficient current character-list/bootstrap/scene schemas. No packets
for these systems have been implemented.

## Local evidence and limits

Raw hex logs and extracted frame data stay under `local/research/` and
`local/captures/`. Each reproducible client run writes `listener.log`,
`console.log`, `app/projectx.log`, and `commands.json` under a fresh
`local/research/client-run-*` directory. These are never commit material.
Only short structural bytes and field summaries are documented here.

Receive-only, secure-response, and synthetic authentication-response milestones
are complete. Bootstrap, persistent accounts/sessions, character selection, and
zone entry remain unimplemented.

## Initial bootstrap/session subset (2026-10-07; superseded by R6)

The active server now sends `BootstrapNotification` immediately after a successful
`AuthResponse`. Downstream mappings 1–5 remain from authentication; the notification
and `ProjectXBootstrapData` introduce mappings 6 and 7. Notification message ID is
-1 because it is unsolicited. Fields, in the verified current-build order:

| Field | Wire encoding / current value |
| --- | --- |
| connectionId, clientOid | Two positive 32-bit integers, transient per connection |
| services | Presence byte 1, 32-bit count 0; typed list, no class mapping |
| dconfigOid | 32-bit integer 0, distributed configuration unavailable |
| hostname | Presence byte 1 and modified UTF string |
| ports | Presence byte 1, 32-bit count 1, TCP port integer |
| datagramPorts | Presence byte 1, 32-bit count 0 |
| serverOid through clientTimingsOid | Ten 32-bit integers, currently all 0 |

The optional IDs are server, exchange, PvP, PvP game info, mission, open hall,
admin, status, place browser, and client timings. The older headless client's
additional `nodePulseOid` is absent from the installed build and must not be
serialized. A localhost notification is 196 bytes including its length header.

`PingRequest` serializes only its type and 16-bit message ID: its pack/unpack
timestamps are transient. The old synthetic test's extra timestamp was removed.
`PongResponse` has the correlated message ID, 64-bit server pack timestamp, and
32-bit processing delay. The first pong introduces its type mapping; later pongs
reuse it. Per-direction class tables remain independent for the session lifetime.

`SubscribeRequest` and `UnsubscribeRequest` carry message ID and 32-bit object ID.
`LogoffRequest` carries only the message ID. Unknown requests and unexpected
trailing fields are rejected. The current player subscription receives a
`FailureResponse` containing the correlated ID, object ID, and failure string;
no player object or game service is implemented yet.

CONFIRMED: the installed full `ProjectXApp` accepted the independently encoded
notification, sent pings, and requested the advertised client object. Its log
then reported the explicit subscription failure. This verifies the envelope
and next request; it does not establish successful player initialization.

## Current object/session subset (R6, 2026-10-07)

Bootstrap now advertises eight login service handles, configuration OID 2 and
server OID 1. Each handle encodes its class then invocation OID 3 and a unique
service code. Client OIDs start at 1001. Other optional OIDs remain zero.
See [active architecture and roadmap](../server-architecture.md) for the scope.

ObjectResponse carries the request ID and a distributed object's class and full
current-build fields. PlayerObject begins with inherited OID, Name username,
DSet receivers, location/status/away message and pawn ID before player fields.
It supplies a non-null empty AvailableKnight array and initialized collections.
Inherited metadata and standalone serializer probes verified all field order.

ForwardEventRequest contains message ID and a typed DEvent; each event starts
with its target OID. CompoundEvent contains a StreamableArrayList (32-bit count,
typed events). Receiver reset/add/update/remove operations name `receivers` and
carry a typed value; Registration has nullable-string receiverCode and short ID.
Only the caller's own receiver set is writable. CompoundUpstreamMessage contains
message ID, typed-list presence/count and nested upstream objects, sharing the
connection's class map. Recursion and collection/node counts are bounded.

InvocationRequestEvent has target OID, integer service code, byte method ID,
Object-array presence/count and typed arguments. ListenerMarshaller subclasses
carry a short requestId. A failure reply is an unsolicited EventNotification
with InvocationResponseEvent: caller OID, correlated listener requestId, byte
method 0, Object-array presence/count 1 and a typed String reason.
Supported unimplemented invocations return this callback, keeping the session
usable. Unknown schemas are rejected; there is no generic Java deserialization.

Character creation envelopes include Name/null, strings, Integer, ordinal
KnightHeight/EyeType, a generic `java.util.List`, null and ConfirmMarshaller.
Decoding this envelope is distinct from implementing creation and persistence.
