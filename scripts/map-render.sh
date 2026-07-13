#!/usr/bin/env bash
# map-render.sh - Drive the standalone BlueMap sidecar: status, force
# re-renders, and render-thread tuning.
#
# BlueMap runs as a CLI sidecar container (`bluemap` service), not a server
# mod — there is no RCON interface. This script SSHes to production and
# works with the container directly.
#
# Usage (via ops):
#   ./ops map status              # container state + recent render activity
#   ./ops map render              # force re-render ALL maps (watcher stopped during)
#   ./ops map render world        # force re-render one map id
#   ./ops map threads 2           # set render-thread-count, restart sidecar
#
# `render` stops the watching sidecar, runs a one-off container with
# --force-render attached to your terminal (progress streams live; Ctrl+C
# aborts the render), then restarts the sidecar either way. Normal updates
# never need this — the sidecar's file watcher picks up world changes on
# its own. Force re-renders are for texture/config changes or tile damage.
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
# Same compose invocation deploy.sh uses on the server.
COMPOSE_REMOTE="cd ~/server && docker compose --project-directory . -f .stack/current/stack/docker-compose.yml --profile cloud"

set_threads() {
  local count="$1"
  $SSH_CMD "sed -i 's/^render-thread-count: .*/render-thread-count: $count/' ~/$BM_CORE && docker restart bluemap" > /dev/null
  echo "Render threads set to $count (sidecar restarted)"
}

ACTION="${1:-render}"
shift || true

case "$ACTION" in
  status)
    $SSH_CMD "docker inspect bluemap --format 'State={{.State.Status}}  Health={{if .State.Health}}{{.State.Health.Status}}{{end}}  Started={{.State.StartedAt}}'; echo; docker logs bluemap --tail 15 2>&1"
    exit 0
    ;;
  threads)
    COUNT="${1:?Usage: map threads <count>}"
    set_threads "$COUNT"
    exit 0
    ;;
  render) ;;
  *)
    echo "Usage: map-render.sh <render|status|threads> [args]"
    exit 1
    ;;
esac

TARGET="${1:-}"
MAP_FLAG=""
[[ -n "$TARGET" ]] && MAP_FLAG="-m $TARGET"

# The one-off replaces the service CMD entirely, so re-specify the mods
# mount flag and MC version the service normally passes.
MC_VER="${MC_VERSION:-1.21.1}"

cleanup() {
  echo ""
  echo "Restarting the bluemap sidecar (watch mode)..."
  $SSH_CMD "$COMPOSE_REMOTE start bluemap" > /dev/null 2>&1 || true
  echo "Done. Sidecar status: ./ops map status"
}
trap cleanup EXIT INT TERM

echo "Bumping render threads to ${RENDER_THREADS} for the force render..."
$SSH_CMD "sed -i 's/^render-thread-count: .*/render-thread-count: $RENDER_THREADS/' ~/$BM_CORE" > /dev/null

echo "Stopping the watching sidecar (two renderers must not share render state)..."
$SSH_CMD "$COMPOSE_REMOTE stop bluemap" > /dev/null 2>&1

echo "Force-rendering ${TARGET:-all maps} — progress streams below (Ctrl+C aborts, sidecar restarts either way)..."
# shellcheck disable=SC2086
$SSH_CMD -t "$COMPOSE_REMOTE run --rm --no-deps bluemap -r -f $MAP_FLAG -n /app/mods -v $MC_VER"

echo ""
echo "Resetting render threads to 1..."
$SSH_CMD "sed -i 's/^render-thread-count: .*/render-thread-count: 1/' ~/$BM_CORE" > /dev/null
