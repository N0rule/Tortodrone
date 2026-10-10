# Development workflow (Java 25)

Start with [AGENTS.md](../AGENTS.md), [README](../README.md), [milestone](PROJECT.md); inspect Git and task-specific files. Never touch installed client/launcher, real keys/data/preferences, or owner's live processes. Older detailed test procedures are available in Git history when needed.

## Build and tests

```sh
./gradlew buildServer buildAdmin # server and local admin JARs, no tests
./gradlew build                  # both JARs and full regression (Java 25)
./gradlew testUnit
./gradlew testIntegration
./gradlew prepareDefaults        # copy default maps/items without overwriting custom files
./gradlew prepareMaps
./gradlew clean                  # preserves build/data/, build/defaults/ and build/deps/
```

Standalone regression JVMs share one Gradle build-service slot, including when the configuration cache is reused. This avoids CPU contention from RSA/password hashing triggering local socket deadlines. Compilation and packaging retain normal worker scheduling; each fixture still runs its own concurrent scenarios. No `--max-workers=1` flag is needed for `build`, `testUnit` or `testIntegration`.

The pinned Gradle Wrapper uses Java 25. Dependency/plugin checksums are in `gradle/verification-metadata.xml`; downloads are cached in Gradle user home. Use `.\gradlew.bat` on Windows. For headless Linux: `env -u DISPLAY ./gradlew build` (not a substitute for graphical tests). Avoid `JAVA_TOOL_OPTIONS`: startup output can break packaged fixture assertions. Tests must use disposable deployments; `./gradlew runServer` or `./gradlew runAdmin` starts real processes/data. Persistent compat tools are **Java**; throwaway work goes under `/tmp`.

Before commit: add focused regression, run `./gradlew build`, run relevant compat/graphical checks, inspect `git diff` and `git diff --check`; update only current docs, commit major milestones, bump `VERSION` for behavioral releases only. Record commands/results, actual limitations and stopped processes.

## Load testing

```sh
./gradlew benchmark
./gradlew benchmark --args='--seconds 60 --clients 50,100,200,300'
./gradlew benchmark --args='--session-only --clients 50,100,200,300'
./gradlew benchmark --args='--instances 4 --clients 300'
```

Fixture copies JARs/defaults into disposable `/tmp` deployments, raises `max-clients` locally, enters town or session-only, sends movement/pings and cleans up its own clients/server. Default 20 s after ramp; `--instances` 1–16. Runs server at 512 MiB heap and 256 KiB thread stacks, measures ping percentiles, updates/client/s, CPU, RSS, thread count, bytes, readiness/failures. Run on idle machine; local synthetic benchmarks **do not prove** production WAN performance, smooth rendering, dynamic combat or long-run stability. 300-player snapshot is in [milestone](PROJECT.md); older detailed measurements are in Git history.

## Stock compatibility and graphics

```sh
./gradlew build
java scripts/compat/CheckClientWorld.java "/path/to/Spiral Knights"
java scripts/StockTools.java previews "/path/to/Spiral Knights"
```

These use the **unchanged installed client classes/resources** as an oracle; stock network fixture ≠ graphical acceptance. When relevant, run the task-specific Java compatibility tools under `scripts/compat/` and document exact output.

Graphical acceptance is authorized only in an **isolated** environment: disposable server, accounts, game preferences/cache/home/native/temp extraction and endpoint/public key. Read installed game read-only; never patch launcher/client or overwrite real preferences. On niri, `_JAVA_AWT_WM_NONREPARENTING=1`; verify test window/process/focus before synthetic input, then check both local/peer views, movement, inventory/equipment and actual scene transitions. Set stock `resource_dir` to installed read-only `rsrc/` and `java.io.tmpdir` to disposable writable path. Known caveats: niri vsync and virtual gamepad detection; see Git history for earlier environment notes. **Stop only spawned tests**; record inconclusive behavior honestly.

## Source/resource rules

`VERSION` is bundled in both JARs. `gradle.properties` enables Gradle’s configuration cache. `.build/` is scratch output; `build/defaults/`, legacy `build/deps/` cache and `build/data/` live state survive clean. Pin/digest and license provenance: [dependencies](dependencies.md); validated maps: [maps](maps.md); client redirection: [client-redirection](client-redirection.md). Do not commit game assets or copy unlicensed upstream server code.
