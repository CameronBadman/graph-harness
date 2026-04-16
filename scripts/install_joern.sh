#!/usr/bin/env bash
set -euo pipefail

INSTALL_ROOT="${1:-$HOME/.local/share/graphharness/joern}"
mkdir -p "$INSTALL_ROOT"

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

installer="$tmpdir/joern-install.sh"
curl -fsSL https://raw.githubusercontent.com/joernio/joern/master/joern-install.sh -o "$installer"
bash "$installer" --install-dir="$INSTALL_ROOT" --without-plugins --reinstall

echo "Joern installed under: $INSTALL_ROOT"
echo "Set GRAPHHARNESS_JOERN_HOME=$INSTALL_ROOT or add $INSTALL_ROOT/joern-cli to PATH"
