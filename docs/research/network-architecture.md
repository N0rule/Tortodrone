# Spiral Knights Network Architecture

Historical client observations. The isolated experiments described here are no
longer the server's runtime workflow. See [client configuration](../client-redirection.md)
for the standalone host-network server.

Scope: installed build `20261006114613`, inspected 2026-10-07.
See [research-sources.md](research-sources.md) for evidence identifiers.

## High-level flow

CONFIRMED (HIGH, S1, C1, N1, R1–R3):

```text
Getdown updates/resources (HTTP; bypassed in local experiment)
    -> ProjectXApp reads deployment.properties from classpath
    -> resolve configured server_host
    -> initial TCP connection to server_ports
    -> SecureRequest containing PublicKeyCredentials
    -> SecureResponse with local shared secret
    -> AESAuthRequest with synthetic credentials
    -> AuthResponse success
    -> first authenticated PingRequest
    -> wait for bootstrap                   <-- current experiment stops here
```

CONFIRMED (HIGH, R2, R3): the secure response and synthetic authentication
response have been exercised against TortodroneServer. CONFIRMED by code
(HIGH, C1, N1): subsequent bootstrap supplies distributed-object and service
data, and UDP setup is deferred until then. The client has redirection handlers.

LIKELY (MEDIUM): the configured entry server can route clients to another
node according to authentication/region/session state. UNKNOWN: the live
deployment's gateway topology, load balancer, region allocation policy, and
whether a specific scene corresponds to a separate process. Do not infer
separate HTTP login, gateway, world, and zone services from their names alone.

## Endpoint configuration

CONFIRMED (HIGH, S1, C1, R1): `code/config.jar!/deployment.properties` contains:

| Key | Installed value | Meaning established |
| --- | --- | --- |
| `server_host` | `game.spiralknights.com` | Initial game host |
| `server_ports` | `47624` | TCP port list |
| `datagram_ports` | `47624` | Later UDP port list |
| `key.public` | RSA public key, value omitted | Secure authentication key |
| `supported_regions` | `us-east, eu-west, sa-east, ap-southeast` | Configured region choices |
| `client_root_url` | `http://gamemedia2.spiralknights.com` | Content/update base |

The obfuscated deployment accessor is `com.threerings.projectx.util.a`;
`aaX()`, `aaY()`, and `wU()` return host, TCP ports, and datagram ports.
Login controller `com.threerings.projectx.client.bN.wB()` calls all three.
Its loader ultimately uses `ClassLoader.getResourceAsStream` and
`Properties.load`; it does not merge arbitrary JVM properties.

CONFIRMED (HIGH, R1): `-Dserver_host=127.0.0.1 -Dserver_ports=12345` did not
change these accessors. A deployment resource placed first on the classpath
did, and redirected the full app successfully.

## Authentication

CONFIRMED (HIGH, C1, N1, R1): the first request has public-key credentials,
not the supplied account password. It carries an RSA-encrypted random secret,
salt, secure-protocol version, client build string, timezone, bootstrap groups,
and upstream sequence ID.

CONFIRMED by public implementation and matching bytecode (HIGH, N1, C1):
the next secure response establishes a shared secret; subsequent account
authentication uses `AESAuthRequest`. The helper supports RSA and
`AES/CBC/PKCS5Padding`. This is custom application-layer authentication over
TCP, not an initial TLS handshake. It does not establish encryption of all
later traffic.

CONFIRMED (HIGH, C1): game-specific credentials include session and Steam
ticket fields. CONFIRMED (HIGH, R3): our minimal `ProjectXAuthResponseData`
with success and transient local session/name data is accepted for the synthetic
account. The client's first authenticated ping verifies this boundary. UNKNOWN:
all required bootstrap objects and a complete login/session implementation.

## Initial backend connection and transport

CONFIRMED (HIGH, C1, N1, R1): Narya/Presents `BlockingCommunicator` uses
`SocketChannel` and custom length-framed object serialization. Runtime
observation saw a connection to `127.0.0.1:47624` and one 437-byte request.
The installed reader also enables TCP socket keepalive.

CONFIRMED by code (HIGH, N1, C1): `DatagramChannel` supports later unreliable
messages. Public code initializes it after bootstrap, attempts configured
datagram ports, and uses ping and sequencing helpers. UDP was not reached in
our experiment; no UDP listener is needed for this milestone.

UNKNOWN: every transport used by optional social/partner/content features.
There is no evidence of WebSocket framing on the observed login connection.

## Game server selection and redirection

CONFIRMED (HIGH, C1): `bN` recognizes an authentication error beginning
`m.peer_redirect`. It splits the string on the literal delimiter `|~`, reads
hostname, TCP port list, and datagram port list, then initiates the redirect.
This is a confirmed code path, not an observed live redirect packet. It
should not yet be replaced with an invented numeric redirect opcode.

CONFIRMED (HIGH, H1): the headless implementation sets the server directly
to historical IP `18.233.212.89`, port `47624`, and version
`20220124075521`. These are source constants, not current service discovery.
It does not implement the full game's redirect controller. Its `Region`
enum has only `us-east` and `eu-west` and is not wired into that host selection.
The four current deployment regions therefore must not be inferred from it.

## Exchange and distributed objects

CONFIRMED (HIGH, H1): `exchange` means the game's energy market service, not
a cryptographic exchange or initial gateway. `Service.EXCHANGE` obtains its
object ID from `ProjectXBootstrapData.exchangeOid`; `ExchangeService`
subscribes to that object and translates attribute changes into price/offer
events. That object ID is supplied by bootstrap, not a fixed packet ID.

CONFIRMED (HIGH, N1): Presents provides authentication, bootstrap,
subscriptions, object events, invocation marshallers, ping/pong, and logoff
messages. UNKNOWN: current game-specific bootstrap schema sufficient to
display characters. No world or zone service has been scaffolded.

## Ports and auxiliary URLs

CONFIRMED (HIGH, S1): TCP `47624` is the initial game port; UDP `47624` is
configured for later traffic. HTTP/HTTPS URLs in deployment configuration
serve updates, content, registration/password recovery, billing, support,
and forums. URL presence alone is not proof of a login/session HTTP API.
We made no gameplay connections to production and did not resolve its current IP.

## Message framing, keepalive, and disconnect

CONFIRMED (HIGH, N1, C1, R1): frames start with a four-byte big-endian total
length, including those four bytes. TCP read boundaries are not message
boundaries. Class codes are stream-local mappings with class names supplied
when first introduced. See [protocol.md](protocol.md).

CONFIRMED by source (HIGH, N1, H1): Narya sends `PingRequest` for keepalive
and clock synchronization and has `LogoffRequest` for orderly logout.
The headless client exposes explicit connect/disconnect and clears its client
on disconnect; no robust automatic reconnect/backoff policy was established.
The installed `Client` bytecode checks a 30-second idle-write interval; the
headless legacy library uses 60 seconds. Treat these as version-dependent.
UNKNOWN: complete production timing behavior and reconnect/session resumption.

## Open questions

1. Supply our own local RSA key through the overlay and verify the smallest
   compatible `SecureResponse`, without using a real account.
2. Observe the resulting `AESAuthRequest`, then document its framing and
   encrypted fields before implementing authentication.
3. Establish bootstrap and redirect requirements before character selection.
4. Defer UDP, scene replication, movement, and persistence until those layers work.
