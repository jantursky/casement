#!/usr/bin/env bash
# Starts the ext-apps example servers the demo's "Servers" source connects to (ports 39101-39103).
# Needs bun and git. Usage: scripts/run-sample-servers.sh [ext-apps-tag]   (default v2.1.0). Stop with Ctrl+C.
set -euo pipefail

tag="${1:-v2.1.0}"
work="${CASEMENT_SERVERS_DIR:-$(cd "$(dirname "$0")/.." && pwd)/build/ext-apps}"
pids=()
trap 'for p in "${pids[@]:-}"; do kill "$p" 2>/dev/null || true; done' EXIT

if [ ! -d "$work/src" ]; then
  mkdir -p "$work"
  git clone --quiet --depth 1 --branch "$tag" https://github.com/modelcontextprotocol/ext-apps.git "$work/src"
fi

# example-directory  port
for entry in "budget-allocator-server 39101" "cohort-heatmap-server 39102" "system-monitor-server 39103"; do
  read -r example port <<<"$entry"
  if [ ! -d "$work/$example" ]; then
    cp -R "$work/src/examples/$example" "$work/$example"
    (cd "$work/$example" && bun install --ignore-scripts --silent && INPUT=mcp-app.html bunx vite build >/dev/null)
  fi
  (cd "$work/$example" && PORT="$port" exec bun main.ts) &
  pids+=("$!")
  echo "$example on http://localhost:$port/mcp"
done
wait
