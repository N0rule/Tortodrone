#!/bin/sh
# Isolated stock-client graphical acceptance, for agents and the owner.
#
# Every run creates a disposable account, server, home/prefs and temporary
# directory and launches the unmodified installed client fullscreen. Nothing
# touches the owner's deployment, saves or preferences.
#
# Usage: scripts/graphical-test.sh <scenario> [scene-id] [game-dir] [output-dir] [--more-flags]
#
# Scenarios:
#   tutorial        fresh account: private Crash Site tutorial to Haven, reconnect
#   crash           Ready Room -> Haven -> original Crash Site 398 entry
#   ready-room      client-local Ready Room and return to Haven
#   mail            Ready Room mail window
#   forge           Ready Room forge window
#   haven           Haven station dialogs
#   training        Advanced Training Hall movement/combat scenario
#   camp            Rescue Camp story
#   preview         seeded knight, GM preview combat to Haven
#   explore <id>    load a scene by native scene id and drive free stock movement
#   manual          disposable server + interactive client for the owner (no
#                   automation); uses their Steam launch parameters; close the
#                   game window to end the session
#   list            print the scenarios
#
# game-dir defaults to $SPIRAL_KNIGHTS_DIR or the Linux Steam library path.
# output-dir must not exist; default is a timestamped directory under $TMPDIR.
# Extra --flags are appended to CheckCombatRendering for other checked-in modes.
# Set TORTODRONE_SKIP_BUILD=1 to reuse the current build/tortodrone.jar.
set -eu

usage() {
    sed -n '2,28p' "$0" | sed 's/^# \{0,1\}//'
}

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root"

scenario=
scene=
game=${SPIRAL_KNIGHTS_DIR:-}
out=
raw=

for arg in "$@"; do
    case $arg in
        -h|--help)
            usage
            exit 0
            ;;
        list)
            sed -n '/^# Scenarios:/,/^# game-dir/p' "$0" | sed 's/^# \{0,1\}//' | sed '$d'
            exit 0
            ;;
        tutorial|crash|ready-room|mail|forge|haven|training|camp|preview|explore|manual)
            scenario=$arg
            ;;
        --*)
            raw="$raw $arg"
            ;;
        *)
            if [ "$scenario" = explore ] && [ -z "$scene" ] && printf '%s' "$arg" | grep -qE '^[0-9]+$'; then
                scene=$arg
            elif [ -z "$game" ]; then
                game=$arg
            elif [ -z "$out" ]; then
                out=$arg
            else
                echo "graphical-test: unexpected argument: $arg" >&2
                exit 2
            fi
            ;;
    esac
done

if [ -z "$scenario" ]; then
    scenario=tutorial
fi
case $scenario in
    tutorial) flags="--opening-tutorial-only" ;;
    crash) flags="--tutorial-entry-only" ;;
    ready-room) flags="--ready-room-only" ;;
    mail) flags="--ready-room-mail-only" ;;
    forge) flags="--ready-room-forge-only" ;;
    haven) flags="--town-dialogs-only" ;;
    training) flags="--training-only" ;;
    camp) flags="--camp-story-only" ;;
    preview) flags="--tutorial-only" ;;
    explore)
        if [ -z "$scene" ]; then
            echo "graphical-test: explore needs a numeric scene id, e.g. scripts/graphical-test.sh explore 398" >&2
            exit 2
        fi
        flags="--explore-only --scene=$scene"
        ;;
    manual) flags="--manual" ;;
esac
flags="$flags$raw"

if [ -z "$game" ]; then
    game=${HOME:-/root}/.local/share/Steam/steamapps/common/Spiral Knights
fi
if [ -z "${DISPLAY:-}" ] && [ -z "${WAYLAND_DISPLAY:-}" ]; then
    echo "graphical-test: a display is required; the fixture runs the stock client fullscreen" >&2
    exit 2
fi
for required in full.dat code/projectx-pcode.jar; do
    if [ ! -f "$game/$required" ]; then
        echo "graphical-test: $game/$required is missing; pass the Spiral Knights install directory" >&2
        exit 2
    fi
done
if [ -z "$out" ]; then
    out=${TMPDIR:-/tmp}/tortodrone-graphical-$scenario-$(date +%Y%m%d-%H%M%S)
fi
if [ -e "$out" ]; then
    echo "graphical-test: output directory already exists: $out" >&2
    exit 2
fi

if [ "${TORTODRONE_SKIP_BUILD:-0}" != 1 ]; then
    echo "Building build/tortodrone.jar"
    ./gradlew buildServer
fi
if [ ! -f build/tortodrone.jar ]; then
    echo "graphical-test: build/tortodrone.jar is missing; run ./gradlew buildServer" >&2
    exit 2
fi

echo "Scenario: $scenario"
echo "Game:     $game"
echo "Output:   $out"
echo "Flags:   $flags"
if java -cp build/tortodrone.jar scripts/compat/CheckCombatRendering.java "$game" "$out" $flags; then
    echo
    echo "Screenshots and logs: $out"
    echo "Captures: $out/captures"
    echo "Client:   $out/client.log"
    echo "Server:   $out/server.log"
else
    status=$?
    echo "graphical-test: scenario failed (exit $status); inspect $out" >&2
    exit "$status"
fi
