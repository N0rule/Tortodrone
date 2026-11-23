# Map sources

Development inputs for `compileMapsFromSource`. **Nothing here is shipped and nothing here is
committed.** The server and every release read the compiled deployment maps under
`content/maps/`; the raw client scene exports stay on the author's machine.

```text
map-sources/
  README.md          # this guide
  map.example.yml    # the only tracked template
  <name>/            # local only: ignored by Git
    scene.dat        # client Clyde scene export
    map.yml          # your copy of map.example.yml, filled in
```

## Why only a template is tracked

A scene export belongs to the game's rights holders, not to Tortodrone. Committing one would also
commit a large opaque binary. The compiler is still fully reproducible: every compiled map records
the source scene's identity in `content/maps/<type>/<name>/native/metadata.properties`
(`scene-id`, `zone-id`, `zone-name`, `version`, `sha256.scene.dat`), so a fresh export can be
checked against the shipped map before replacing anything.

## Converting a new map

1. Export the scene from a read-only client install. The stock exporter produces one
   `scene.dat` per scene.
2. Create `map-sources/<name>/`, put `scene.dat` there, and copy `map.example.yml` to
   `map-sources/<name>/map.yml`. `<name>` must match `[a-z][a-z0-9_]{0,63}`.
3. Fill in the identity fields. `zone-id` and `zone-name` are the two values a cached scene does
   not establish; use the values the client uses for that zone (`zone-name` is a bundle key such
   as `%town:m.haven`).
4. Compile only that source while iterating:

   ```bash
   ./gradlew compileMapsFromSource -Ponly=<name> -PgameDir=/path/to/read-only-game
   ```

   Add `-PreplaceMaps` only for disposable output; an existing deployment map is refused without
   it. Omit `-PgameDir` to use the Steam locator.
5. Validate and commit **only** the generated deployment map:

   ```bash
   ./gradlew validateContent
   git add content/maps/<type>/<name>
   ```

   `native/compile-report.txt` in the generated folder lists the scene id, type, zone and routing
   note. Routing beyond an existing portal is a design decision and is never invented.

## Re-importing a shipped map

The source export is not in Git. To rebuild one:

- read `zone-id` and `zone-name` from `content/maps/<type>/<name>/native/metadata.properties`;
- read `id`, `name`, `type` and the default spawn from that folder's `map.yml`;
- compare your export's SHA-256 with `sha256.scene.dat` before compiling;
- add `spawn-x`/`spawn-y` only when a town scene has several `Dynamic/Portal/Destination` markers
  and the arrival must be chosen explicitly.

## Rights

Do not commit `scene.dat`, extracted artwork, audio, captures or client binaries, and do not add
exports for maps you are not entitled to redistribute. See
[licensing and provenance](../docs/provenance.md).
