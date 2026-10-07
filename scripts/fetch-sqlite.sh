#!/bin/sh
set -eu
output=$1
expected=bcb1f51e36f940867e83342f9efbf5968ac44a6bef4d397bb4af7b17b45cd2fb
verify() { printf '%s  %s\n' "$expected" "$1" | sha256sum -c - >/dev/null; }
if [ -f "$output" ]; then verify "$output"; touch "$output"; exit 0; fi
mkdir -p "$(dirname "$output")"
temporary="$output.download"
trap 'rm -f "$temporary"' EXIT HUP INT TERM
curl --fail --location --silent --show-error --connect-timeout 15 --max-time 120 --retry 2 \
  https://repo.maven.apache.org/maven2/org/xerial/sqlite-jdbc/3.53.4.0/sqlite-jdbc-3.53.4.0.jar -o "$temporary"
verify "$temporary"
mv "$temporary" "$output"
