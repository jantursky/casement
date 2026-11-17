#!/usr/bin/env bash
# Rebuilds fixtures/ext-apps from the ext-apps examples. Needs bun and git.
# Usage: scripts/build-fixtures.sh [ext-apps-tag]   (default v2.1.0)
set -euo pipefail

tag="${1:-v2.1.0}"
root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

git clone --quiet --depth 1 --branch "$tag" https://github.com/modelcontextprotocol/ext-apps.git "$work/src"

build() {
  local example="$1" output="$2"
  cp -R "$work/src/examples/$example" "$work/$example"
  # Dependencies install without running their scripts.
  (cd "$work/$example" && bun install --ignore-scripts --silent && INPUT=mcp-app.html bunx vite build >/dev/null)
  cp "$work/$example/dist/mcp-app.html" "$root/fixtures/ext-apps/$output"
}

build basic-server-vanillajs get-time.html
echo "Fixtures rebuilt from ext-apps $tag"
