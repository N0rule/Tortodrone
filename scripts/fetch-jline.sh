#!/bin/sh
set -eu
output=$1
expected=091c6aaf65de86d4106528a626fe1d9daebcd2313102d6f7e484700846d0e63d
verify() { printf '%s  %s\n' "$expected" "$1" | sha256sum -c - >/dev/null; }
if [ -f "$output" ]; then verify "$output"; touch "$output"; exit 0; fi
mkdir -p "$(dirname "$output")"
temporary="$output.download"
trap 'rm -f "$temporary"' EXIT HUP INT TERM
curl --fail --location --silent --show-error --connect-timeout 15 --max-time 120 --retry 2 \
  https://repo.maven.apache.org/maven2/org/jline/jline/4.4.7/jline-4.4.7-jdk11.jar -o "$temporary"
verify "$temporary"
mv "$temporary" "$output"
