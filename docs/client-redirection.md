# Connecting a Separate KnightLauncher

The server runs on the host network. Start it with `make server` or
`java -jar build/tortodrone-server.jar`, wait for `Listening on ...`, then start
the KnightLauncher fork from its own installation. Server startup needs no game
files, launcher, Python, Maven, bubblewrap, or nsenter.

## Address, port, and key

For this computer, the launcher needs `127.0.0.1`, port `47624`, and the entire
public key value from `build/data/keys/public.txt`. The public key format is a
hexadecimal RSA modulus followed by `#` and a hexadecimal exponent. Paste the
value, not a filename, PEM header, or private key.

| KnightLauncher property | Game deployment property | Server source |
| --- | --- | --- |
| `game.endpoint` | `server_host` | `client-host` in build/settings/server.properties |
| `game.port` | `server_ports`, `datagram_ports` | `server-port` in build/settings/server.properties |
| `game.publicKey` | `key.public` | build/data/keys/public.txt |

For another computer, use this server's reachable address in `client-host` and
in the launcher. `127.0.0.1` always refers to the computer running the game;
`0.0.0.0` is the server's bind address and is not a destination for players.
Remote connections also require a reachable TCP port through your network's
firewall/router. Changing `client-host` changes the exported connection values;
it does not configure a router or a firewall.

`build/data/client-connection.properties` exports the game deployment keys above and
`anonymous_allowed=false`. A launcher may merge these values into the client's
complete `deployment.properties`. **This export is not a complete deployment
resource**: preserve the client's other build/content/region settings.
A separate complete deployment resource before `config.jar` on the game
classpath is the historically verified overlay approach. Simple
`-Dserver_host`/`-Dserver_ports` JVM flags do not override the client's resource
loader.

The inspected fork at `../KnightLauncherCustom` saves advanced connection
settings and applies them to `code/config.jar` through its settings manager
when the Official selection applies game settings. Its `game.getdownURL` and
`game.getdownFullURL` are content/updater settings; they are not the game socket
address. This server does not host Getdown resources. Client launching and
resource modification belong to the separate launcher project.

## Test login and the expected limit

Use the normal username/password form:

```text
Username: tortodrone
Password: tortodrone
```

Guest login uses a different credential shape/account and is unsupported;
Steam tickets and official account sessions are unsupported too. Only this
fixed synthetic account is currently accepted. Other passwords receive a login
rejection. The exported `anonymous_allowed=false` can disable the guest option
when your launcher applies it to deployment configuration.

Successful progress appears in `build/data/logs/latest.log` as `SecureRequest`,
`SecureResponse`, `AESAuthRequest`, an authentication result,
`BootstrapNotification sent`, `PongResponse sent`, and a `SubscribeRequest`
marked `client bootstrap accepted`, followed by `PlayerObject snapshot sent`,
receiver registrations, and `ServerObject snapshot sent`. The client now accepts
the character roster and reports itself logged on. The session timeout is
60 seconds after authentication, allowing normal heartbeats.

Name validation and new non-Steam character creation are implemented. Successful
creation logs `Character saved` and writes `build/data/accounts/1.properties` before
sending the roster update and result. The result explicitly says the knight was
saved and world entry is unavailable, clearing the creator's busy state. Restart
the game and log in to see saved knights.
Other supported calls with listeners receive `Server operation not implemented yet`; unknown object
subscriptions receive `No such object`. A request outside the verified bounded
schema closes the connection and records a fixed diagnostic, without its values.

## Troubleshooting

- No server connection log: check the server is listening, the actual client
  destination, the port, and network reachability.
- Secure exchange fails: verify that the launcher applied the public key from
  this server's `build/data/keys/public.txt` and restart the game after changing it.
- Authentication rejected: use the test account through the username/password
  form, with guest/Steam authentication disabled.
- Character creation fails: check name rules (3–16 letters/hyphens), duplicate
  names, the three-slot limit, and `Character save failed` in the server log.
- Creation reports "Knight saved": reconnect to see the saved roster. World
  entry remains the next server milestone. Earlier server builds sent a success
  callback that left the stock client stuck at "Creating knight"; use the rebuilt JAR.
- A saved knight appears but selection fails: selection and world entry are
  not implemented yet; changing launcher settings cannot enable them.
- Session closes with a protocol diagnostic: that request is outside the current
  protocol subset; use the server log to identify the remaining protocol boundary.

The previous `make server` used an isolated loopback network namespace. A game
started normally could not reach that listener even with `127.0.0.1:47624` and
matching keys. The installed client's log showed that exact destination, and
its saved public key matched the preserved server keypair. The new standalone
server removes this namespace barrier. The full installed `ProjectXApp` was
then verified against the new server on the host network with temporary client
settings and the preserved keypair: secure exchange, test authentication,
bootstrap, ping/pong, player and server snapshots, and receiver registration
succeeded. A temporary probe also confirmed the logged-on character creation
state, name validation, and character creation with the real creator's choices.
These checks used temporary client settings and account data; the game
installation was unchanged. The separate fork's Play button was not automated;
the user confirmed the rendered creator.
