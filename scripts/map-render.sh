#!/usr/bin/env bash
# map-render.sh - Force-render all BlueMap maps with progress tracking.
#
# Bumps render threads via core.conf for speed, triggers force-update on
# all maps, polls progress until complete, then resets threads to 1.
# Safe to Ctrl+C — rendering continues server-side.
#
# Usage (via ops):
#   ./ops map render              # force-update all maps, poll progress
#   ./ops map render world        # force-update one map only
#   ./ops map status              # one-shot progress check
#   ./ops map threads 2           # set render threads manually
#
# BlueMap's render-thread-count is a config file setting (core.conf), not
# an RCON command. This script edits it via SSH, then reloads BlueMap.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib.sh"
load_env

: "${DROPLET_HOST:?Set DROPLET_HOST in .env}"
: "${DEPLOY_USER:=deploy}"
SSH_KEY="$HOME/.ssh/${BRAND_SLUG:+${BRAND_SLUG}_}mc_deploy_key"
SSH_CMD="ssh -i $SSH_KEY ${DEPLOY_USER}@${DROPLET_HOST}"

RENDER_THREADS="${RENDER_THREADS:-3}"
BM_CORE="server/data/config/bluemap/core.conf"

rcon_remote() {
  $SSH_CMD "docker exec mc rcon-cli '$*'" 2>/dev/null
}

wait_for_bluemap() {
  local tries=0
  while [[ $tries -lt 30 ]]; do
    local status
    status=$(rcon_remote "bluemap" || echo "")
    if echo "$status" | grep -q "maps are updated\|is currently being updated\|have pending"; then
      return 0
    fi
    tries=$((tries + 1))
    sleep 2
  done
  echo "Warning: BlueMap did not finish loading within 60s"
}

set_threads() {
  local count="$1"
  $SSH_CMD "sed -i 's/^render-thread-count: .*/render-thread-count: $count/' ~/$BM_CORE" 2>/dev/null
  rcon_remote "bluemap reload" >/dev/null 2>&1
  echo "  Waiting for BlueMap to reload..."
  wait_for_bluemap
  echo "Render threads set to $count"
}

ACTION="${1:-render}"
shift || true

case "$ACTION" in
  status)
    rcon_remote "bluemap"
    exit 0
    ;;
  threads)
    COUNT="${1:?Usage: map threads <count>}"
    set_threads "$COUNT"
    exit 0
    ;;
  render)
    ;;
  *)
    echo "Usage: map-render.sh <render|status|threads> [args]"
    exit 1
    ;;
esac

# --- Determine which maps to render ------------------------------------------
TARGET="${1:-}"
if [[ -n "$TARGET" ]]; then
  MAPS=("$TARGET")
else
  MAPS=(world world_the_nether world_the_end paradise_lost)
fi

# --- Bump render threads -----------------------------------------------------
echo "Setting render threads to ${RENDER_THREADS}..."
set_threads "$RENDER_THREADS"

# --- Trigger force-update ----------------------------------------------------
for m in "${MAPS[@]}"; do
  echo "Force-updating $m..."
  rcon_remote "bluemap force-update $m"
done
echo ""

# --- Poll progress until complete --------------------------------------------
cleanup() {
  echo ""
  echo "Resetting render threads to 1..."
  set_threads 1 || true
  echo "Rendering continues server-side. Check progress: ./ops map status"
}
trap cleanup EXIT INT TERM

echo "Monitoring render progress (Ctrl+C to detach — render continues)..."
echo ""

# Track consecutive "idle" polls to avoid false positives — BlueMap can
# briefly report "updated" between task batches.
IDLE_COUNT=0

while true; do
  STATUS=$($SSH_CMD "docker exec mc rcon-cli 'bluemap'" 2>/dev/null || echo "")

  # Strip ANSI codes
  CLEAN=$(echo "$STATUS" | sed 's/\x1b\[[0-9;]*m//g')

  # Extract progress fields (POSIX grep)
  CURRENT_MAP=$(echo "$CLEAN" | grep -o 'map [^ ]* is currently' | sed 's/map //;s/ is currently//' || true)
  PROGRESS=$(echo "$CLEAN" | grep -o 'progress: [0-9.]*%' | sed 's/progress: //' || true)
  REMAINING=$(echo "$CLEAN" | grep -o 'remaining time: .*' | sed 's/remaining time: //' || true)
  PENDING=$(echo "$CLEAN" | grep -o '[0-9]* maps have pending' | grep -o '[0-9]*' || true)

  if [[ -n "$CURRENT_MAP" ]]; then
    IDLE_COUNT=0
    PENDING_STR=""
    [[ -n "$PENDING" ]] && PENDING_STR=" (+${PENDING} queued)"
    printf "\r\033[K  Rendering %s: %s — %s remaining%s" "$CURRENT_MAP" "${PROGRESS:-?}" "${REMAINING:-calculating}" "$PENDING_STR"
  elif echo "$CLEAN" | grep -q "maps are updated"; then
    IDLE_COUNT=$((IDLE_COUNT + 1))
    if [[ $IDLE_COUNT -ge 3 ]]; then
      echo ""
      echo "All maps rendered."
      break
    fi
    printf "\r\033[K  Render idle (confirming completion %d/3)..." "$IDLE_COUNT"
  else
    printf "\r\033[K  Waiting for render to start..."
  fi

  sleep 10
done
