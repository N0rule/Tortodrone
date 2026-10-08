#!/bin/sh
# Prepare verified default scene packs before startup. Existing deployment files are preserved.
set -eu
project=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
output=${1:-"$project/build/maps"}
archive=ae49faf06788dd83b35a959306c9fe4c25688004
fetch() {
    cached=$1
    scene_id=$2
    expected=$3
    if [ ! -f "$cached" ]; then
        mkdir -p "$(dirname -- "$cached")"
        temporary=$(mktemp "$cached.download.XXXXXX")
        trap 'rm -f "$temporary"' EXIT HUP INT TERM
        curl --fail --silent --show-error --location --max-time 60 \
            "https://raw.githubusercontent.com/reference-source/SpiralKnightsSceneArchive/$archive/Master/Static/$scene_id" -o "$temporary"
        printf '%s  %s\n' "$expected" "$temporary" | sha256sum -c - >/dev/null
        mv "$temporary" "$cached"
        trap - EXIT HUP INT TERM
    fi
    printf '%s  %s\n' "$expected" "$cached" | sha256sum -c - >/dev/null
}
fetch "$project/build/deps/town-square.dat" 1 045528ddd9bb3c3a940586b28a3c9c3c5a3b3d2db47345f86d47ea0cc3db874f
fetch "$project/build/deps/bazaar.dat" 2 e0410e91851f3367bdd7283683ee4d45f6abf0e61d63f0e01bde5d10f5012033
mkdir -p "$output"
if [ ! -f "$output/catalog.properties" ]; then cp "$project/maps/default/catalog.properties" "$output/catalog.properties"; fi
for name in haven bazaar; do
    mkdir -p "$output/$name"
    for file in map.properties walkability.gz; do
        if [ ! -f "$output/$name/$file" ]; then cp "$project/maps/default/$name/$file" "$output/$name/$file"; fi
    done
    case "$name" in haven) layout=town-square.dat ;; bazaar) layout=bazaar.dat ;; esac
    if [ ! -f "$output/$name/scene.dat" ]; then cp "$project/build/deps/$layout" "$output/$name/scene.dat"; fi
done
