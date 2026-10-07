# Spiral Knights Protocol Research

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
UNKNOWN: a working TortodroneServer implementation of these responses.

## Transport

CONFIRMED (HIGH): initial TCP, custom Narya/Presents serialization. No initial
TLS record, HTTP request, or WebSocket upgrade was observed. Later UDP is
configured but was not exercised. TCP reads may fragment or combine frames;
the server deliberately labels its output as read chunks.

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
Details of every response field and its serialized layout still need a local
round-trip test before implementing the response.

The official private key is neither available nor needed for our project.
The next experiment should generate a local keypair, replace only `key.public`
in the ignored deployment overlay, retain the private key under ignored local
research, and implement only the compatible secure response. Keep synthetic
credentials and network isolation. Do not attempt to decrypt production traffic.

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

The receive-only milestone is complete. Secure response, account authentication,
character selection, and zone entry remain unimplemented.
