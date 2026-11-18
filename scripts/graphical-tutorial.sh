#!/bin/sh
# Isolated stock-client acceptance for the opening tutorial.
#
# Creates a disposable account, server, home/prefs and temporary directory, then
# launches the unmodified installed client fullscreen and plays the private Crash
# Site tutorial to Haven: NPC conversations, all three enemy families, helper
# combat, the Ironwood Giant, doors, portal, heat settlement and a reconnect.
# Nothing touches the owner's deployment, saves or preferences.
#
# Usage: scripts/graphical-tutorial.sh [game-dir] [output-dir] [scenario-flags...]
#   game-dir       Spiral Knights install (default: $SPIRAL_KNIGHTS_DIR or the
#                  Linux Steam library path); must contain full.dat and
#                  code/projectx-pcode.jar
#   output-dir     new directory for screenshots and logs (default: a timestamped
#                  directory under $TMPDIR); it must not exist yet
#   scenario-flags forwarded to CheckCombatRendering; default --opening-tutorial-only
#                  (other useful values: --tutorial-only, --ready-room-only)
#
# Set TORTODRONE_SKIP_BUILD=1 to reuse the current build/tortodrone.jar.
set -eu

usage() {
    sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//'
}

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root"

game=${SPIRAL_KNIGHTS_DIR:-}
out=
scenario=
for arg in "$@"; do
    case $arg in
        -h|--help)
            usage
            exit 0
            ;;
        --*)
            scenario="$scenario $arg"
            ;;
        *)
            if [ -z "$game" ]; then
                game=$arg
            elif [ -z "$out" ]; then
                out=$arg
            else
                echo "graphical-tutorial: unexpected argument: $arg" >&2
                exit 2
            fi
            ;;
    esac
done

if [ -z "$game" ]; then
    game=${HOME:-/root}/.local/share/Steam/steamapps/common/Spiral Knights
fi
if [ -z "${DISPLAY:-}" ] && [ -z "${WAYLAND_DISPLAY:-}" ]; then
    echo "graphical-tutorial: a display is required; the fixture forces fullscreen" >&2
    exit 2
fi
for required in full.dat code/projectx-pcode.jar; do
    if [ ! -f "$game/$required" ]; then
        echo "graphical-tutorial: $game/$required is missing; pass the Spiral Knights install directory" >&2
        exit 2
    fi
done
if [ -z "$scenario" ]; then
    scenario=" --opening-tutorial-only"
fi
if [ -z "$out" ]; then
    out=${TMPDIR:-/tmp}/tortodrone-graphical-$(date +%Y%m%d-%H%M%S)
fi
if [ -e "$out" ]; then
    echo "graphical-tutorial: output directory already exists: $out" >&2
    exit 2
fi

if [ "${TORTODRONE_SKIP_BUILD:-0}" != 1 ]; then
    echo "Building build/tortodrone.jar"
    ./gradlew buildServer
fi
if [ ! -f build/tortodrone.jar ]; then
    echo "graphical-tutorial: build/tortodrone.jar is missing; run ./gradlew buildServer" >&2
    exit 2
fi

echo "Game:   $game"
echo "Output: $out"
echo "Flags:$scenario"
if java -cp build/tortodrone.jar scripts/compat/CheckCombatRendering.java "$game" "$out" $scenario; then
    echo
    echo "Screenshots and logs: $out"
    echo "Captures: $out/captures"
    echo "Client:   $out/client.log"
    echo "Server:   $out/server.log"
else
    status=$?
    echo "graphical-tutorial: scenario failed (exit $status); inspect $out" >&2
    exit "$status"
fi
