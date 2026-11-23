#!/usr/bin/env bash
# Isolated stock-client graphical acceptance, for AI agents and the owner.
#
# Every run creates a disposable account, server, home/prefs and temporary
# directory and launches the unmodified installed client in a window. Only the
# interactive manual scenario starts fullscreen. Nothing touches the owner's
# deployment, saves or preferences.
#
# Usage: scripts/graphical-test.sh <scenario> [scene-id] [game-dir] [output-dir] [--flags]
#
# Scenarios:
#   tutorial        fresh account: private Crash Site tutorial to Haven, reconnect
#   crash           Ready Room -> Haven -> original Crash Site 398 entry
#   ready-room      client-local Ready Room and return to Haven
#   mail-campaign   stock Ready Room mail list with a seeded campaign and direct letter
#   mail            Ready Room mail window
#   forge           Ready Room forge window
#   haven           Haven station dialogs
#   training        Advanced Training Hall movement/combat scenario
#   camp            Rescue Camp story
#   preview         seeded knight, GM preview combat to Haven
#   mission         seeded knight, native Shadow Beast start into lobby 351 with
#                   MissionPartyObject gate/floor and the o.shadow_beast mission bar
#   playability     fast single-client smoke: Haven -> /training native actors,
#                   movement, conversation, vial use, sword/gun/bomb -> /haven
#   rapid           fastest single-client combat burst: login-scene jump, seeded
#                   GM knight, every weapon family, shield/bash/dodge, sprites,
#                   armor/shield equips and a real shield bump
#   explore <id>    load a scene by native scene id, drive free stock movement
#   manual          disposable server + fullscreen interactive client, auto-logged
#                   in with the owner's Steam launch wrapper (sk-nopad); only for
#                   the owner, close the game window to end the session
#   list            print the scenarios
#
# game-dir defaults to $SPIRAL_KNIGHTS_DIR or the Linux Steam library path.
# Extra --flags are appended to CheckCombatRendering for other checked-in modes.
# Set TORTODRONE_SKIP_BUILD=1 to reuse the current deployment JAR.
# The deployment defaults to dist/; set TORTODRONE_DEPLOYMENT to override it.
#
# Output layout (one directory per run, never reused):
#   run.log              this script plus the full fixture output
#   session/captures/    screenshots
#   session/client.log   stock client log
#   session/server.log   disposable server log
# Success prints a final line: RESULT PASS scenario=... session=...
set -euo pipefail

usage() {
    sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'
}

scenarios() {
    sed -n '/^# Scenarios:/,/^# game-dir/p' "$0" | sed 's/^# \{0,1\}//' | sed '$d'
}

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root"

scenario=
scene=
game=${SPIRAL_KNIGHTS_DIR:-}
out=
declare -a extra=()

for arg in "$@"; do
    case $arg in
        -h|--help)
            usage
            exit 0
            ;;
        list)
            scenarios
            exit 0
            ;;
        tutorial|crash|ready-room|mail-campaign|mail|forge|haven|training|camp|preview|mission|playability|rapid|explore|manual)
            scenario=$arg
            ;;
        --*)
            extra+=("$arg")
            ;;
        *)
            if [ "$scenario" = explore ] && [ -z "$scene" ] && [[ $arg =~ ^[0-9]+$ ]]; then
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
declare -a scenario_flags=()
case $scenario in
    tutorial) scenario_flags=(--opening-tutorial-only) ;;
    crash) scenario_flags=(--tutorial-entry-only) ;;
    ready-room) scenario_flags=(--ready-room-only) ;;
    mail-campaign) scenario_flags=(--mail-campaign-only) ;;
    mail) scenario_flags=(--ready-room-mail-only) ;;
    forge) scenario_flags=(--ready-room-forge-only) ;;
    haven) scenario_flags=(--town-dialogs-only) ;;
    training) scenario_flags=(--training-only) ;;
    camp) scenario_flags=(--camp-story-only) ;;
    preview) scenario_flags=(--tutorial-only) ;;
    mission) scenario_flags=(--mission-only) ;;
    playability) scenario_flags=(--playability-only) ;;
    rapid) scenario_flags=(--rapid-only) ;;
    explore)
        if [ -z "$scene" ]; then
            echo "graphical-test: explore needs a numeric scene id, e.g. scripts/graphical-test.sh explore 398" >&2
            exit 2
        fi
        scenario_flags=(--explore-only "--scene=$scene")
        ;;
    manual) scenario_flags=(--manual) ;;
esac

if [ -z "$game" ]; then
    game="${HOME:-/root}/.local/share/Steam/steamapps/common/Spiral Knights"
fi
if [ -z "${DISPLAY:-}" ] && [ -z "${WAYLAND_DISPLAY:-}" ]; then
    echo "graphical-test: a display is required; the fixture runs the stock client windowed" >&2
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
mkdir -p "$out"
runlog="$out/run.log"
session="$out/session"

log() {
    printf '[%s] graphical-test: %s\n' "$(date -Is)" "$*" | tee -a "$runlog"
}

log "START scenario=$scenario${scene:+ scene=$scene} game=$game"
log "FLAGS ${scenario_flags[*]:-}"
log "EXTRA ${extra[*]:-}"
log "OUTPUT $out"

deployment="${TORTODRONE_DEPLOYMENT:-dist}"
if [ "${TORTODRONE_SKIP_BUILD:-0}" != 1 ]; then
    log "BUILD $deployment/tortodrone.jar"
    ./gradlew buildServer >>"$runlog" 2>&1 || {
        status=$?
        log "RESULT FAIL scenario=$scenario stage=build exit=$status"
        exit "$status"
    }
fi
if [ ! -f "$deployment/tortodrone.jar" ]; then
    log "RESULT FAIL scenario=$scenario stage=build reason=missing-jar"
    echo "graphical-test: $deployment/tortodrone.jar is missing; run ./gradlew buildServer" >&2
    exit 2
fi

command=(java -cp "$deployment/tortodrone.jar" scripts/compat/CheckCombatRendering.java
    "$game" "$session" "${scenario_flags[@]}" "${extra[@]}")
log "COMMAND $(printf '%q ' "${command[@]}")"

set +e
"${command[@]}" 2>&1 | tee -a "$runlog"
status=${PIPESTATUS[0]}
set -e

if [ "$status" -eq 0 ]; then
    log "RESULT PASS scenario=$scenario${scene:+ scene=$scene} exit=0"
    log "ARTIFACTS run=$runlog captures=$session/captures client=$session/client.log server=$session/server.log"
    printf '\nScreenshots and logs: %s\n' "$out"
else
    log "RESULT FAIL scenario=$scenario${scene:+ scene=$scene} exit=$status"
    log "ARTIFACTS run=$runlog captures=$session/captures client=$session/client.log server=$session/server.log"
    echo "graphical-test: scenario failed (exit $status); inspect $runlog" >&2
    exit "$status"
fi
