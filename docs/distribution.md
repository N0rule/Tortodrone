# Run Tortodrone

Install Java 25 and extract this entire release ZIP into a writable directory.
Keep `tortodrone.jar`, `tortodrone-admin.jar` and `maps/` together. The complete
regular Haven Town Square and Bazaar packs are included outside the JARs.
No Git, Make, game installation or map download is needed to run the server.

Open a terminal in the extracted directory:

```sh
java -jar tortodrone-admin.jar
```

Create a normal username/password account in the local manager. Then start:

```sh
java -jar tortodrone.jar
```

Wait for `Done! Listening`. Stop with `stop` or Ctrl+C. Both applications store
configuration, accounts, characters and server keys in `data/` beside their JARs.
The default game port is 47624; edit `data/server.properties` while stopped.
Configure your separately maintained KnightLauncher, then sign in through the
normal username/password form:

| Setting | Local server |
| --- | --- |
| `game.endpoint` | `127.0.0.1` (or the server's reachable address) |
| `game.port` | `47624` (or your configured port) |
| `game.publicKey` | Entire value from `data/keys/public.txt` |

Back up `data/` and edited `maps/` while the server is stopped. On compatible
updates, replace both JARs together and preserve that state. Unsupported database
schemas are refused without modification; Tortodrone does not upgrade databases.
Install map changes explicitly while stopped, keeping each scene, descriptor and
collision mask together. Missing or damaged packs fail startup; no download is
attempted. See `DEPENDENCIES.md` and `MAP-SOURCES.properties` for provenance.
