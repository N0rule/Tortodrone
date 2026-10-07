# Connecting a separate KnightLauncher

Start Tortodrone with `make server` or `java -jar build/tortodrone.jar`, wait for
`Done! Listening on ...`, then start your separately maintained KnightLauncher.
The server does not launch, patch, or install the game.

## Address, port, and public key

Clients need three connection values: the server's reachable address, its TCP
port, and its public key. The server listener is configured through `server-ip`
and `server-port` in `data/server.properties` beside the JAR.

For this computer with the default settings:

| KnightLauncher setting | Value |
| --- | --- |
| `game.endpoint` | `127.0.0.1` |
| `game.port` | `47624` |
| `game.publicKey` | Entire value from `build/data/keys/public.txt` |

The default `server-ip=0.0.0.0` listens on all IPv4 interfaces. Use `127.0.0.1`
for a client on this computer, or this server's LAN/public address for a client
on another computer. For a specific interface, set `server-ip` to that interface's
address. Use `server-port` for the client port.

The public key is a hexadecimal RSA modulus followed by `#` and a hexadecimal
exponent. Paste the value into the launcher; do not use a filename, PEM header,
or private key. Keep `data/keys/` across restarts and deployments, since changing
the keypair requires updating the public key in clients.

All runtime data lives beside the JAR. With the built JAR, the public key is
`build/data/keys/public.txt`. A deployed `tortodrone.jar` uses its own adjacent
`data/keys/public.txt`. No additional client connection file is generated.

Remote connections require a reachable TCP port through the network's
firewall/router. The current server does not implement UDP.

## Normal login and current limits

Use the normal username/password form:

```text
Username: tortodrone
Password: tortodrone
```

This is the initial development account. Additional persistent normal accounts
can be created with the server's offline account commands. Steam and guest login
remain unsupported.

The creator validates names and saves new knights. After saving, it reports
"Knight saved. Reconnect to view it; world entry is not available yet." Reconnect
to see the saved roster. Selecting a knight fails explicitly because world entry
is not implemented. The server also supports editing saved names/appearance
through the existing save request and deletion through the picker's Delete action.
Edits preserve character IDs; deletion frees a slot. Both persist across restarts.

## Logs and troubleshooting

Normal mode records startup, connection/login events, character saves, warnings,
errors and shutdown in the console and `data/logs/latest.log`. For protocol
investigation, stop the server, set `log-mode=debug`, and restart. Debug mode adds
secure exchange, authentication results, bootstrap, ping/pong, subscriptions,
snapshots and receiver registration. It still excludes secrets and raw payloads.

- No connection message: check the server is listening, the client's address and
  TCP port, and network reachability.
- Secure exchange fails: verify the launcher uses this server's public key and
  restart the game after changing its connection settings.
- Login rejected: use a registered server account through the normal login form.
- Startup rejects an obsolete setting: compare the editable configuration with
  `src/main/resources/server.properties` and remove unused properties.
- Startup rejects keys or saved characters: restore the corresponding files from
  backup. The server refuses to silently replace an incomplete identity or corrupt save.
- Character creation fails: check name rules, duplicate names, the three-slot limit,
  and save warnings in the server log.
- Knight saved, or selection fails: reconnect to see the roster; world entry remains
  unimplemented and cannot be enabled through connection settings.
- A protocol diagnostic closes the session: investigate the remaining server
  protocol boundary using debug logs and synthetic fixtures.

[Historical research](research/README.md) records earlier experiments. Current
work changes server behavior and does not patch clients or launchers.

## Additional normal accounts

The server owner can create separate accounts with
`java -jar build/tortodrone.jar --create-account username` while the server is
stopped. Use the prompted password in the existing normal login form; the
endpoint and public key stay the same for every account. See the
[account commands](../README.md#normal-accounts).
