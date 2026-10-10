# Development workflow (Java 25)

Start with [AGENTS.md](../AGENTS.md), [README](../README.md), [milestone](PROJECT.md); inspect Git and task-specific files. Never touch installed client/launcher, real keys/data/preferences, or owner's live processes. Older detailed test procedures are available in Git history when needed.

## Build and tests

```sh
make build                 # server and local admin JARs
make build test            # full regression (Java 25)
make test-unit
make test-integration
make defaults              # copy default maps/items without overwriting custom files
make maps
make clean                 # preserves build/data/ and build/deps/
```

No Maven/Gradle. Pinned/checksum-verified SQLite/JLine downloads are cached in `build/deps/`. For headless Linux: `env -u DISPLAY make build test` (not a substitute for graphical tests). Avoid `JAVA_TOOL_OPTIONS`: startup output can break packaged fixture assertions. Tests must use disposable deployments; `make server` or `make admin` starts real processes/data. Persistent compat tools are **Java**; throwaway work goes under `/tmp`.

Before commit: add focused regression, run `make build test`, run relevant compat/graphical checks, inspect `git diff` and `git diff --check`; update only current docs, commit major milestones, bump `VERSION` for behavioral releases only. Record commands/results, actual limitations and stopped processes.

## Load testing

```sh
make benchmark
make benchmark BENCHMARK_ARGS='--seconds 60 --clients 50,100,200,300'
make benchmark BENCHMARK_ARGS='--session-only --clients 50,100,200,300'
make benchmark BENCHMARK_ARGS='--instances 4 --clients 300'
```

Fixture copies JARs/defaults into disposable `/tmp` deployments, raises `max-clients` locally, enters town or session-only, sends movement/pings and cleans up its own clients/server. Default 20 s after ramp; `--instances` 1–16. Runs server at 512 MiB heap and 256 KiB thread stacks, measures ping percentiles, updates/client/s, CPU, RSS, thread count, bytes, readiness/failures. Run on idle machine; local synthetic benchmarks **do not prove** production WAN performance, smooth rendering, dynamic combat or long-run stability. 300-player snapshot is in [milestone](PROJECT.md); older detailed measurements are in Git history.

## Stock compatibility and graphics

```sh
make build test
java scripts/compat/CheckClientWorld.java "/path/to/Spiral Knights"
java scripts/StockTools.java previews "/path/to/Spiral Knights"
```

These use the **unchanged installed client classes/resources** as an oracle; stock network fixture ≠ graphical acceptance. When relevant, run the task-specific Java compatibility tools under `scripts/compat/` and document exact output.

Graphical acceptance is authorized only in an **isolated** environment: disposable server, accounts, game preferences/cache/home/native/temp extraction and endpoint/public key. Read installed game read-only; never patch launcher/client or overwrite real preferences. On niri, `_JAVA_AWT_WM_NONREPARENTING=1`; verify test window/process/focus before synthetic input, then check both local/peer views, movement, inventory/equipment and actual scene transitions. Set stock `resource_dir` to installed read-only `rsrc/` and `java.io.tmpdir` to disposable writable path. Known caveats: niri vsync and virtual gamepad detection; see Git history for earlier environment notes. **Stop only spawned tests**; record inconclusive behavior honestly.

## Source/resource rules

`VERSION` is bundled in both JARs. `build/.build/` is scratch packaging; `build/deps/` cache and `build/data/` live state survive clean. Pin/digest and license provenance: [dependencies](dependencies.md); validated maps: [maps](maps.md); client redirection: [client-redirection](client-redirection.md). Do not commit game assets or copy unlicensed upstream server code.
