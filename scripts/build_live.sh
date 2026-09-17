#!/usr/bin/env bash
set -euo pipefail

graphharness_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$graphharness_root"
npm --prefix ui ci --no-audit
npm --prefix parsers ci --no-audit
npm --prefix ui run build
if command -v gradle >/dev/null 2>&1; then
    gradle test installDist --console=plain
elif command -v nix >/dev/null 2>&1; then
    nix develop --command gradle test installDist --console=plain
elif test -x /nix/var/nix/profiles/default/bin/nix; then
    /nix/var/nix/profiles/default/bin/nix develop --command gradle test installDist --console=plain
else
    printf '%s\n' 'Install JDK 21 and Gradle 8, or Nix, then rerun this script.' >&2
    exit 1
fi
