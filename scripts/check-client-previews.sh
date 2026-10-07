#!/bin/sh
# Optional server compatibility fixture; reads installed resources without launching/modifying the game.
set -eu
if [ "$#" -ne 1 ] || [ ! -f "$1/code/projectx-pcode.jar" ]; then
    echo 'Usage: scripts/check-client-previews.sh "/path/to/Spiral Knights"' >&2
    exit 1
fi
project=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
game=$(CDPATH= cd -- "$1" && pwd)
make -C "$project" build-server
scratch=$(mktemp -d /tmp/tortodrone-preview-check-XXXXXX)
trap 'rm -rf "$scratch"' EXIT HUP INT TERM
classpath="$project/build/tortodrone.jar:$game/code/projectx-pcode.jar:$game/code/*"
javac --release 17 -cp "$classpath" -d "$scratch" "$project/scripts/compat/PreviewCompatibility.java"
java --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED \
    -Djava.awt.headless=true -Dno_unpack_resources=true -Duser.home="$scratch" \
    -Djava.util.prefs.userRoot="$scratch/prefs" -cp "$scratch:$classpath" \
    PreviewCompatibility "$game" "$project/src/main/resources/starter-models.txt"
