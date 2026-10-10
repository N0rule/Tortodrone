# Connecting the separate game installation

Tortodrone neither installs nor patches the client/launcher. Start the server and
wait for `Done! Listening`, then use your separately maintained KnightLauncher.

## Connection settings

| Launcher setting | Default local deployment |
| --- | --- |
| `game.endpoint` | `127.0.0.1` |
| `game.port` | `47624` |
| `game.publicKey` | Entire value from `build/data/keys/public.txt` |

For another computer, use the server's reachable LAN/public address. `0.0.0.0`
is a listening interface, not a destination. The configured port is TCP only;
remote connections need firewall/router reachability. Same-LAN connections need
no port forwarding.

A deployed JAR uses its own adjacent `data/keys/public.txt`. The key value is a
hexadecimal RSA modulus, `#`, then a hexadecimal exponent: paste the value, not a
filename/PEM header/private key. Preserve `data/keys/` across deployments. Restart
the game after changing connection settings. All accounts share endpoint/key.

Use the normal username/password form with a locally created account; there is
no default login on fresh installations. Steam/guest login is unsupported. Create
or select a knight to enter Haven directly. Equipment and balances persist;
position resets to spawn. Reconnect to change characters. See
[current gameplay limits](PROJECT.md) and [administration](administration.md).

## Troubleshooting

Logs are in the server terminal and `data/logs/latest.log`. Stop the server before
setting `log-mode=debug` in its adjacent `data/server.properties`, then restart.
Debug adds protocol diagnostics while excluding secrets/raw payloads.

| Symptom | Check |
| --- | --- |
| No connection log | Listener, destination/port, firewall and TCP reachability |
| Secure exchange fails | This deployment's public key; restart client after settings changes |
| Login rejected | Registered normal account; password check/reset in local manager |
| Invalid setting at startup | Compare `src/main/resources/server.properties`; remove obsolete settings |
| Invalid keys/saved state | Inspect error and restore backup; do not generate replacement identities |
| Character creation fails | Name rules, duplicates, three-slot limit and save warnings |
| Knight already online | Disconnect its controlling session before selecting it |
| Scene remains loading | Server/client logs, current JAR and bundled town resource |
| Animation/input stutters | Recheck fresh entry vs reconnect; see entry investigation and isolated graphical workflow |

On Windows, `Test-NetConnection SERVER_ADDRESS -Port 47624` checks TCP reachability.
Use your actual address/port. If ping works but TCP fails, investigate listener
and firewall before login/keys. On a host using UFW, allow the configured TCP port
from the intended subnet; do not assume the example network is your network.

[Graphical testing](development.md#isolated-graphical-checks) uses disposable state.
The installed client and launcher remain separate and unmodified.
