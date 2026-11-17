#!/usr/bin/env bash
# Rebuilds fixtures/ext-apps from the ext-apps examples and records what their servers answer.
# Needs bun, node and git. Usage: scripts/build-fixtures.sh [ext-apps-tag]   (default v2.1.0)
# get-time.html is the one app kept in the repository; the others are rebuilt on demand (and in CI).
set -euo pipefail

tag="${1:-v2.1.0}"
root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
servers=()
trap 'for p in "${servers[@]:-}"; do kill "$p" 2>/dev/null || true; done; rm -rf "$work"' EXIT

git clone --quiet --depth 1 --branch "$tag" https://github.com/modelcontextprotocol/ext-apps.git "$work/src"

# name  example-directory  port
examples=(
  "get-time basic-server-vanillajs 39100"
  "budget-allocator budget-allocator-server 39101"
  "cohort-heatmap cohort-heatmap-server 39102"
  "system-monitor system-monitor-server 39103"
  "integration integration-server 39104"
)

for entry in "${examples[@]}"; do
  read -r name example port <<<"$entry"
  cp -R "$work/src/examples/$example" "$work/$example"
  # Dependencies install without running their scripts.
  (cd "$work/$example" && bun install --ignore-scripts --silent && INPUT=mcp-app.html bunx vite build >/dev/null)
  cp "$work/$example/dist/mcp-app.html" "$root/fixtures/ext-apps/$name.html"
  (cd "$work/$example" && PORT="$port" bun main.ts >"$work/$name.log" 2>&1 & echo $! >"$work/$name.pid")
  servers+=("$(cat "$work/$name.pid")")
done

sleep 5
for entry in "${examples[@]}"; do
  read -r name example port <<<"$entry"
  node "$root/scripts/record-fixture.mjs" "$port" "$root/fixtures/ext-apps/$name.json"
done
echo "Fixtures rebuilt from ext-apps $tag"
