#!/usr/bin/env bash
# live-stats.sh - Docker container stats from the production server.
#
# Default mode streams like `docker stats` (refreshes until Ctrl+C) - that's
# for humans. Agents and scripts: use --once, which returns a single snapshot
# of system load, memory, disk, per-container stats, and who's online.
#
# Usage:
#   ./scripts/live-stats.sh --once       # snapshot + summary (non-blocking)
#   ./scripts/live-stats.sh              # live stream, all containers
#   ./scripts/live-stats.sh mc           # live stream, single container
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"
load_env
SERVER_DIR="$(basename "$(git rev-parse --show-toplevel 2>/dev/null || echo server)")"

: "${DROPLET_HOST:?Set DROPLET_HOST in .env or environment}"
DEPLOY_USER="${DEPLOY_USER:-deploy}"

CONTAINER="${1:-}"
ONCE=0
[[ "$CONTAINER" == "--once" ]] && ONCE=1 && CONTAINER=""

if [[ $ONCE -eq 1 ]]; then
  # One-shot snapshot with server summary
  echo "=== Server: ${DROPLET_HOST} ==="
  ssh "${DEPLOY_USER}@${DROPLET_HOST}" '
    echo ""
    echo "--- System ---"
    uptime
    free -h | head -2
    df -h / | tail -1 | awk "{printf \"Disk: %s used / %s (%s)\n\", \$3, \$2, \$5}"
    cd ~/'"${SERVER_DIR}"' && du -sh data/world data/bluemap 2>/dev/null
    echo ""
    echo "--- Containers ---"
    sudo docker stats --no-stream --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.NetIO}}\t{{.PIDs}}"
    echo ""
    echo "--- Minecraft ---"
    LIST=$(timeout 8 sudo docker exec mc rcon-cli list 2>/dev/null || true)
    if [ -n "$LIST" ]; then
      echo "$LIST"
      timeout 10 sudo docker exec mc rcon-cli "spark health" 2>/dev/null | grep -iE "tps|memory" | head -3
    else
      echo "(RCON silent - autopaused or booting; normal when empty)"
    fi
  '
elif [[ -n "$CONTAINER" ]]; then
  exec ssh "${DEPLOY_USER}@${DROPLET_HOST}" "sudo docker stats $CONTAINER"
else
  exec ssh "${DEPLOY_USER}@${DROPLET_HOST}" "sudo docker stats"
fi
