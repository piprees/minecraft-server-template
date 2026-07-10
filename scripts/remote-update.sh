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
QUICK=0
VERSION=""

for arg in "$@"; do
  case "$arg" in
    --images-only) IMAGES_ONLY=1 ;;
    --quick) QUICK=1 ;;
    --help | -h)
      echo "Usage: ./ops update [version] [--images-only] [--quick]"
      echo ""
      echo "  version       Pin to a specific release (e.g. v1.0.18)"
      echo "  --images-only Skip bundle pull, just update images + restart"
      echo "  --quick       Restart without the 60s countdown"
      exit 0
      ;;
    *)
      VERSION="$arg"
      ;;
  esac
done

if [[ $IMAGES_ONLY -eq 0 ]]; then
  # Sync stack-pull.sh from the local bundle before running it on the server
  # (the puller lives in the bundle at scripts/stack-pull.sh)
  LOCAL_PULL="$CONSUMER_DIR/.stack/current/stack/scripts/stack-pull.sh"
  if [[ -f "$LOCAL_PULL" ]]; then
    log "Syncing stack-pull.sh to server..."
    scp -i "$SSH_KEY" "$LOCAL_PULL" "${DEPLOY_USER}@${DROPLET_HOST}:~/server/stack-pull.sh"
    $SSH_CMD "chmod +x ~/server/stack-pull.sh"
  fi

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

log "Rebuilding modpack..."
$SSH_CMD "cd ~/server && .stack/current/stack/scripts/pack-build.sh" \
  || warn "Modpack build failed (non-fatal)"

DEPLOY_FLAGS="--non-interactive"
[[ $QUICK -eq 1 ]] && DEPLOY_FLAGS="$DEPLOY_FLAGS --quick"

log "Restarting stack..."
$SSH_CMD "cd ~/server && .stack/current/stack/scripts/deploy.sh $DEPLOY_FLAGS"

log "Refreshing kuma-init..."
$SSH_CMD "cd ~/server && docker compose --project-directory . -f .stack/current/stack/docker-compose.yml --profile cloud up -d --force-recreate --no-deps kuma-init" \
  || warn "kuma-init refresh failed (non-fatal)"

log "Update complete."
