# Map sources

One folder per map:

```text
<name>/scene.dat
<name>/map.yml
```

`scene.dat` is the client's Clyde scene export. `map.yml` carries only the facts the scene does
not establish:

```yaml
id: 1
name: Haven|Town Square
type: town
zone-id: 1
zone-name: "%town:m.haven"
defaultSpawn: default_spawn
instances: 1
```

`type` is `town`, `training` or `level`. `zone-id`/`zone-name` cannot be read reliably from a
cached body; supply them once. See `docs/MAP-PIPELINE.md`.

Build:

```bash
./gradlew compileMapsFromSource -PgameDir=/path/to/read-only-game -PreplaceMaps
```

The compiler derives collision, portals, entrances, supported actors and map identity. It refuses
a scene whose arrival is blocked or whose configuration is unavailable.
