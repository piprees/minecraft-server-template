#!/usr/bin/env bash
# map-render.sh - The web map (map.DOMAIN): the last export's summary, or an export now.
#
# Context: the custom-dimensions mod draws the map. `customdim map export`
# writes every dimension's generated layers and the explored layer from saved
# chunks into data/map-web, which nav-proxy serves; idle-tasks sends
# `customdim map export all` once pre-generation is done, every MAP_INTERVAL.
# This script SSHes to production. `render` touches data/.map-export-started
# and data/.skip-pause-map (autopause stays off while the export runs in the
# mod, off the server thread) and sends the command over RCON; the mod stamps
# data/map-web/.export-finished when it ends, and idle-tasks then clears
# .skip-pause-map, as it does for its own exports.
#
# Usage (via ops):
#   ./ops map status                                   # last export, whether one runs, the map's size
#   ./ops map render [dimension|all] [generated|explored|force]
#                                                      # export now (default: all, both layers)
#
# Gotchas: an RCON command wakes a paused server, which is the point here.
# A dimension's generated layers are redrawn only when it changed (`force`
# redraws them) and the explored layer only from region files changed since
# the last export, so `render all` is cheap after the first one.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib.sh"
load_env

: "${DROPLET_HOST:?Set DROPLET_HOST in .env}"
: "${DEPLOY_USER:=deploy}"
SSH_KEY="$HOME/.ssh/${BRAND_SLUG:+${BRAND_SLUG}_}mc_deploy_key"
SSH_CMD="ssh -i $SSH_KEY ${DEPLOY_USER}@${DROPLET_HOST}"
MC="${CONTAINER_PREFIX:-}mc"

ACTION="${1:-status}"
shift || true

case "$ACTION" in
  status)
    $SSH_CMD "cd ~/server && if [ -f data/map-web/.export-finished ]; then \
      echo \"last export: \$(cat data/map-web/.export-finished) (ended \$(date -u -r data/map-web/.export-finished '+%Y-%m-%d %H:%M UTC'))\"; \
      else echo 'no export yet'; fi; \
      if [ -f data/.skip-pause-map ]; then echo 'an export is running'; fi; \
      du -sh data/map-web 2> /dev/null || true"
    ;;
  render)
    TARGET="${1:-all}"
    WHAT="${2:-}"
    [[ "$TARGET" =~ ^[a-z0-9_]+$ ]] || die "dimension must be a slug or 'all'"
    case "$WHAT" in "" | generated | explored | force) ;; *) die "what must be generated, explored or force" ;; esac
    $SSH_CMD "docker exec $MC sh -c 'touch /data/.map-export-started /data/.skip-pause-map /data/.skip-pause' \
      && timeout 30 docker exec $MC rcon-cli 'customdim map export $TARGET${WHAT:+ $WHAT}'"
    echo "Export started. Follow it: ./ops map status"
    ;;
  *)
    echo "Usage: map-render.sh status | render [dimension|all] [generated|explored|force]"
    exit 1
    ;;
esac
