#!/bin/sh
# Import one archived town with collision derived from the installed, unchanged client.
set -eu
if [ "$#" -ne 5 ] && [ "$#" -ne 7 ]; then
    echo 'Usage: scripts/prepare-scene.sh game-directory archived-scene output-directory zone-id zone-name [spawn-x spawn-y]' >&2
    exit 1
fi
project=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
game=$(CDPATH= cd -- "$1" && pwd)
shift
scratch=$(mktemp -d /tmp/tortodrone-scene-import-XXXXXX)
trap 'rm -rf "$scratch"' EXIT HUP INT TERM
classpath="$game/code/projectx-pcode.jar:$game/code/*"
javac --release 17 -cp "$classpath" -d "$scratch" "$project/scripts/compat/ScenePackExport.java"
java --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED \
    -Djava.awt.headless=true -Dno_unpack_resources=true -Duser.home="$scratch/home" \
    -Djava.util.prefs.userRoot="$scratch/prefs" -cp "$scratch:$classpath" ScenePackExport "$game" "$@"
