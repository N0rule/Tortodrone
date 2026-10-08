#!/bin/sh
# Reads installed stock definitions. Never starts a game or changes installation/preferences.
set -eu
if [ "$#" -lt 1 ] || [ "$#" -gt 2 ] || [ ! -f "$1/code/projectx-pcode.jar" ]; then
    echo 'Usage: sh scripts/prepare-items.sh "/path/to/Spiral Knights" [fresh-output-directory]' >&2
    exit 1
fi
project=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
game=$(CDPATH= cd -- "$1" && pwd)
output=${2:-"$project/build/items"}
make -C "$project" build
scratch=$(mktemp -d /tmp/tortodrone-items-XXXXXX)
trap 'rm -rf "$scratch"' EXIT HUP INT TERM
classpath="$project/build/tortodrone.jar:$game/code/projectx-pcode.jar:$game/code/*"
javac --release 17 -cp "$classpath" -d "$scratch" "$project/scripts/compat/ItemCatalogExport.java"
java --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED \
    -Djava.awt.headless=true -Dno_unpack_resources=true -Duser.home="$scratch" \
    -Djava.util.prefs.userRoot="$scratch/prefs" -cp "$scratch:$classpath" ItemCatalogExport "$game" "$output"
