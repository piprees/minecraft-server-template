#!/usr/bin/env bash
# map-render.sh - Drive the unmined-render sidecar: status and forced passes.
#
# Maps are rendered by the `unmined-render` service (uNmINeD CLI) on an
# interval (UNMINED_INTERVAL). Renders are incremental — only changed
# regions re-render — so a forced pass is cheap. There is no RCON
# interface; this script SSHes to production and works with the container
# directly.
#
# Usage (via ops):
#   ./ops map status              # container state + recent render activity
#   ./ops map render              # force a render pass now (restarts the loop)
#
# A restart triggers an immediate pass: the loop renders on startup, then
# sleeps for UNMINED_INTERVAL between passes. Per-dimension selection isn't
# needed — unchanged dimensions are skipped via mtime markers.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib.sh"
load_env

: "${DROPLET_HOST:?Set DROPLET_HOST in .env}"
: "${DEPLOY_USER:=deploy}"
SSH_KEY="$HOME/.ssh/${BRAND_SLUG:+${BRAND_SLUG}_}mc_deploy_key"
SSH_CMD="ssh -i $SSH_KEY ${DEPLOY_USER}@${DROPLET_HOST}"

ACTION="${1:-render}"
shift || true

case "$ACTION" in
  status)
    $SSH_CMD "docker inspect unmined-render --format 'State={{.State.Status}}  Started={{.State.StartedAt}}'; echo; docker logs unmined-render --tail 20 2>&1"
    ;;
  render)
    echo "Restarting unmined-render (a restart triggers an immediate pass)..."
    $SSH_CMD "docker restart unmined-render" > /dev/null
    echo "Pass started. Watch it: ./ops map status"
    ;;
  *)
    echo "Usage: map-render.sh <render|status>"
    exit 1
    ;;
esac
