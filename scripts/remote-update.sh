#!/usr/bin/env bash
# remote-update.sh - Pull the latest stack bundle + images on the server and
# restart the stack.
#
# This is the operational counterpart of `./dev update` (local). Connects via
# SSH, runs stack-pull.sh to fetch the latest (or a pinned) release, pulls
# updated Docker images, then runs deploy.sh to restart everything cleanly
# (with countdown, kicks, config sync, whitelist restore).
#
# Usage:
#   ./ops update                  # pull latest, full restart
#   ./ops update v1.0.18          # pin to a specific version
#   ./ops update --images-only    # pull images + restart, skip bundle
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib.sh"
load_env

: "${DEPLOY_USER:=deploy}"
: "${DROPLET_HOST:?Set DROPLET_HOST in .env}"

SSH_KEY="$HOME/.ssh/${BRAND_SLUG:+${BRAND_SLUG}_}mc_deploy_key"
SSH_CMD="ssh -i $SSH_KEY ${DEPLOY_USER}@${DROPLET_HOST}"

IMAGES_ONLY=0
VERSION=""

for arg in "$@"; do
  case "$arg" in
    --images-only) IMAGES_ONLY=1 ;;
    --help | -h)
      echo "Usage: ./ops update [version] [--images-only]"
      echo ""
      echo "  version       Pin to a specific release (e.g. v1.0.18)"
      echo "  --images-only Skip bundle pull, just update images + restart"
      exit 0
      ;;
    *)
      VERSION="$arg"
      ;;
  esac
done

if [[ $IMAGES_ONLY -eq 0 ]]; then
  if [[ -n "$VERSION" ]]; then
    log "Pulling stack bundle $VERSION on server..."
    $SSH_CMD "cd ~/server && STACK_VERSION='$VERSION' ./stack-pull.sh"
  else
    log "Pulling latest stack bundle on server..."
    $SSH_CMD "cd ~/server && ./stack-pull.sh"
  fi
fi

log "Pulling updated Docker images on server..."
$SSH_CMD "cd ~/server && docker compose --project-directory . -f .stack/current/stack/docker-compose.yml --profile cloud pull"

log "Restarting stack..."
$SSH_CMD "cd ~/server && .stack/current/stack/scripts/deploy.sh --non-interactive"

log "Update complete."
