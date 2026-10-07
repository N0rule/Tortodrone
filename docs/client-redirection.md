# Connecting a separate KnightLauncher

Tortodrone runs on the host network and provides a TCP listener. Start it with
`make server` or `java -jar build/tortodrone.jar`, wait for `Done! Listening on ...`,
then start your separately maintained KnightLauncher normally.

The server does not launch, patch, or install the game. This guide describes
connection settings only. Client and launcher implementation changes belong
outside this project.

## Address, port, and public key

For this computer, configure `127.0.0.1`, port `47624`, and the entire public key
value from `build/data/keys/public.txt`. The key is a hexadecimal RSA modulus,
followed by `#` and a hexadecimal exponent. Paste the value into the launcher;
do not use a filename, PEM header, or private key.

All `data/` paths refer to the directory beside the running JAR, regardless of
your shell's working directory. A deployed `tortodrone.jar` uses its own adjacent
`data/` directory.

| KnightLauncher setting | Game deployment property | Server setting/file |
| --- | --- | --- |
| `game.endpoint` | `server_host` | `client-host` in data/server.properties |
| `game.port` | `server_ports`, `datagram_ports` | `server-port` in data/server.properties |
| `game.publicKey` | `key.public` | data/keys/public.txt |

`server-ip` tells the OS which local interface to listen on. `client-host` tells
clients which reachable address to use. Listening on `0.0.0.0` and advertising
`127.0.0.1` is appropriate for this computer. For another computer, advertise the
server's LAN/public address and use that in the launcher. Behind a router, the
public address may differ from the machine's local address. Never use `0.0.0.0`
as the client's destination.

Remote connections require a reachable TCP port through the network's
firewall/router. `client-host` only controls the advertised endpoint; it does not
configure the network. The current server does not implement UDP.

`data/client-connection.properties` exports deployment connection values and
`anonymous_allowed=false`. It is not a complete deployment resource. Use the
separate launcher's supported connection settings to apply address, port and key;
keep its other content/build settings. This server does not host Getdown resources.
Keep `data/keys/` across restarts: rotating the keypair requires updating clients.

## Normal login and current limits

Use the normal username/password form:

```text
Username: tortodrone
Password: tortodrone
```

Only this fixed test account is currently accepted. Normal persistent accounts
are future server work. Steam, guest and official-account login will not be added.

The creator validates names and saves new knights. After saving, it reports
"Knight saved. Reconnect to view it; world entry is not available yet." Reconnect
to see the saved roster. Selecting a knight fails explicitly because world entry
is not implemented. Character editing and deletion are unavailable.

## Logs and troubleshooting

Normal mode records startup, connection/login events, character saves, warnings,
errors and shutdown in the console and `data/logs/latest.log`. For protocol
investigation, stop the server, set `log-mode=debug`, and restart. Debug mode adds
secure exchange, authentication result, bootstrap, ping/pong, subscriptions,
snapshots and receiver registration. It still excludes secrets and raw payloads.

- No connection message: check the server is listening, the launcher's destination,
  TCP port, and network reachability.
- Secure exchange fails: verify the separate launcher applied this server's public
  key and restart the game after changing its connection settings.
- Login rejected: use the test account through the normal login form.
- Startup rejects `data-directory`: remove that obsolete setting. Data always
  lives beside the JAR; preserve the keys and accounts there.
- Startup rejects keys or saved characters: restore the corresponding files from
  backup. The server refuses to silently replace an incomplete identity or corrupt save.
- Character creation fails: check name rules, duplicate names, the three-slot limit,
  and save warnings in the server log.
- Knight saved, or selection fails: reconnect to see the roster; world entry remains
  unimplemented and cannot be enabled through connection settings.
- A protocol diagnostic closes the session: investigate the remaining server
  protocol boundary using debug logs and synthetic fixtures.

[Historical research](research/README.md) includes old client experiments and an
investigation of an identity bug in earlier server builds. Current authentication
responses keep the client's persisted identity unchanged. Those records are
historical evidence, not instructions to patch clients as part of this project.
