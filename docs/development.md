# Development workflow

Read [AGENTS.md](../AGENTS.md), [README](../README.md) and the
[current milestone](world-entry-plan.md) first. Then inspect Git and relevant code.
[Architecture](server-architecture.md) records protocol/storage invariants;
[resource assessment](server-research.md) points to primary sources. Historical
research and ignored `local/` files are not build dependencies or current procedures.

## Build and automated checks

The project targets Java 17 and uses GNU Make, `javac` and `jar`. The first build
needs network access, `curl` and `sha256sum`; later builds use pinned cached inputs.
No Maven/Gradle, installed game or database service is required.

```sh
make build             # server and local admin JARs
make build-server      # server only
make build-admin       # manager only
make build test        # rebuild and run all checks
make test-unit         # Java unit/socket fixtures
make test-integration  # disposable packaged deployments
make clean             # keep runtime data and dependency cache
```

On Linux without an accessible display, use `env -u DISPLAY make build test`.
The table fixtures work headlessly; this does not replace graphical acceptance.
Avoid `JAVA_TOOL_OPTIONS` for this suite: its JVM startup message interferes with
packaged tests that check exact command output.

`make server` and `make admin` launch real tools using `build/data/`; use temporary
copies of the JARs for tests instead. Tests have Java `main` methods and names
ending in `Test`; shared synthetic wire helpers live in `src/test/java/org/tortodrone/support/`.
The current suite has 22 unit and three integration checks. It covers persistence,
migrations/rollback, ownership/revocation, protocol bounds, character/session
lifecycle, town input/deltas, local administration and packaged shutdown/restart.

Add meaningful regression checks for behavior changes. Review `git diff` and
`git diff --check`; update current docs and commit major completed milestones.
Documentation-only edits do not need a version increment. Leave test processes
stopped and report checks/limitations. Follow the owner's instruction on merging.

## Installed-client compatibility

From the repository root, with both JARs built:

```sh
java scripts/compat/CheckClientWorld.java "/path/to/Spiral Knights"
scripts/check-client-previews.sh "/path/to/Spiral Knights"
```

These load unchanged installed game classes/resources, not a graphical game.
Use a JDK that can read the installed JARs (the tested installation requires Java
25). The world harness copies both server JARs into temporary storage, creates
disposable accounts/preferences and stops its server on exit. It checks the stock
reader/predictor, creation/reconnect, delayed acknowledgements, activity timing,
model retention, equipment, currency and two-account actor synchronization.
The preview fixture compares starter model construction with the stock builder.
Persistent compatibility tools are Java; do not introduce Python scripts.

## Isolated graphical checks

The owner authorizes launching the installed, unmodified stock `ProjectXApp` for
acceptance checks. Read JARs, assets and native libraries from
`~/.local/share/Steam/steamapps/common/Spiral Knights`; never write there or to real
client preferences. No graphical test launcher is committed: assemble an isolated
environment for the installed version, using its deployment/JVM requirements.

1. Create a temporary directory with a server JAR copy, fresh data/keys, disposable
   normal accounts, client home/preferences/cache/native extraction directories.
2. Start that server on an isolated localhost port. Create a temporary deployment
   configuration retaining required stock settings but using its endpoint and
   public key. Read the installation through a read-only mount where available.
3. Launch stock `ProjectXApp` with a disposable username/password/character and
   isolated home/preferences. On niri set `_JAVA_AWT_WM_NONREPARENTING=1`.
4. Identify the target client window/process and verify focus before sending
   X11/XTest keys or mouse events. Use niri window screenshots to inspect rendering.
5. Check fresh creation and existing-character reconnect: close entry HUD, walk,
   stop/start, reverse direction, face the mouse, open/close inventory, equip/unequip.
   Check both views when accepting graphical multiplayer changes.
6. Stop only the clients/servers you started and verify cleanup. Preserve real
   accounts, characters, keys, installed files and preferences.

Network fixtures cannot prove that animations render. On the tested niri desktop,
vertical sync stalled native swaps; disabling it in **disposable preferences**
removed that stall. Virtual pointer devices can be detected as gamepads; exclude
joystick input devices in the temporary environment if necessary while preserving
GPU access. These are test-environment accommodations, not server/client patches.
Record inconclusive checks and environment limitations explicitly. See the
[0.7.1 investigation](town-entry-investigation.md) for accepted checks.

## Resources and releases

Sources/resources are discovered recursively. `VERSION` becomes bundled
`version.txt`; both JARs use it. `build/.build/` is disposable packaging output,
`build/deps/` caches verified inputs, and `build/data/` is real runtime state.
`make clean` preserves the latter two.

Dependency pins/digests live in the Makefile and `scripts/fetch-*.sh`; update
their [notices](dependencies.md) when changing them. Scene geometry and the
derived `town-walkability.gz` must match; `scripts/compat/TownCollisionExport.java`
documents collision derivation. Never commit installed game assets or assume an
unlicensed reference project can be copied into the server.
