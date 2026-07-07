#!/usr/bin/env bash
# map-render.sh - Force-render all BlueMap maps with progress tracking.
#
# Bumps render threads for speed, triggers force-update on all maps,
# polls progress until complete, then resets threads. Safe to Ctrl+C —
# rendering continues server-side, threads reset on next idle cycle.
#
# Usage (via ops):
#   ./ops map render              # force-update all maps, poll progress
#   ./ops map render world        # force-update one map only
#   ./ops map status              # one-shot progress check
#   ./ops map threads 2           # set render threads manually
#
# The render threads are temporarily raised to RENDER_THREADS (default 3)
# for the duration of the render, then reset to 1. The game server stays
# responsive — BlueMap throttles itself between tile batches.
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

rcon_remote() {
  $SSH_CMD "docker exec mc rcon-cli '$*'" 2>/dev/null
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
    rcon_remote "bluemap render-threads $COUNT"
    echo "Render threads set to $COUNT"
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
rcon_remote "bluemap render-threads $RENDER_THREADS"

# --- Trigger force-update ----------------------------------------------------
for m in "${MAPS[@]}"; do
  echo "Force-updating $m..."
  rcon_remote "bluemap force-update $m"
done
echo ""

# --- Poll progress until complete --------------------------------------------
# Trap Ctrl+C to reset threads before exit
cleanup() {
  echo ""
  echo "Resetting render threads to 1..."
  rcon_remote "bluemap render-threads 1" || true
  echo "Rendering continues server-side. Check progress: ./ops map status"
}
trap cleanup EXIT INT TERM

echo "Monitoring render progress (Ctrl+C to detach — render continues)..."
echo ""

while true; do
  STATUS=$($SSH_CMD "docker exec mc rcon-cli 'bluemap'" 2>/dev/null || echo "")

  # Strip ANSI codes for parsing
  CLEAN=$(echo "$STATUS" | sed 's/\x1b\[[0-9;]*m//g')

  # Extract current map and progress
  CURRENT_MAP=$(echo "$CLEAN" | grep -oP 'map \K\S+(?= is currently)' || true)
  PROGRESS=$(echo "$CLEAN" | grep -oP 'progress: \K[0-9.]+%' || true)
  REMAINING=$(echo "$CLEAN" | grep -oP 'remaining time: \K.*' || true)
  PENDING=$(echo "$CLEAN" | grep -oP '\K[0-9]+(?= maps have pending)' || true)

  if [[ -n "$CURRENT_MAP" ]]; then
    PENDING_STR=""
    [[ -n "$PENDING" ]] && PENDING_STR=" (+${PENDING} queued)"
    printf "\r\033[K  Rendering %s: %s — %s remaining%s" "$CURRENT_MAP" "${PROGRESS:-?}" "${REMAINING:-calculating}" "$PENDING_STR"
  elif echo "$CLEAN" | grep -q "maps are updated"; then
    echo ""
    echo "All maps rendered."
    break
  else
    printf "\r\033[K  Waiting for render to start..."
  fi

  sleep 10
done
