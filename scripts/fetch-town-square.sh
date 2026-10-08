#!/bin/sh
set -eu
output=$1
expected=045528ddd9bb3c3a940586b28a3c9c3c5a3b3d2db47345f86d47ea0cc3db874f
verify() { printf '%s  %s\n' "$expected" "$1" | sha256sum -c - >/dev/null; }
if [ -f "$output" ]; then verify "$output"; touch "$output"; exit 0; fi
mkdir -p "$(dirname "$output")"
temporary="$output.download"
trap 'rm -f "$temporary"' EXIT HUP INT TERM
curl --fail --location --silent --show-error --connect-timeout 15 --max-time 120 --retry 2 \
 https://raw.githubusercontent.com/reference-source/SpiralKnightsSceneArchive/ae49faf06788dd83b35a959306c9fe4c25688004/Master/Static/1 -o "$temporary"
verify "$temporary"
mv "$temporary" "$output"
