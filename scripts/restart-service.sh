#!/usr/bin/env bash
# restart-service.sh - Restart a service on the production server via SSH.
#
# Usage:
#   ./scripts/restart-service.sh nav-proxy        # restart one service
#   ./scripts/restart-service.sh nav-proxy pack-web  # restart multiple
#   ./scripts/restart-service.sh --list            # show available services
#   ./scripts/restart-service.sh --all             # restart all sidecars (not mc)
#
# Does NOT restart mc - use deploy.sh for that (it handles countdowns, kicks,
# config sync, and whitelist restoration). Restarting mc directly skips all of
# that and risks data loss.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib.sh"
load_env

: "${DEPLOY_USER:=deploy}"
: "${DROPLET_HOST:?Set DROPLET_HOST in .env}"

SSH_KEY="$HOME/.ssh/${BRAND_SLUG:+${BRAND_SLUG}_}mc_deploy_key"
SSH_CMD="ssh -i $SSH_KEY ${DEPLOY_USER}@${DROPLET_HOST}"
SERVER_DIR="$(basename "$(git rev-parse --show-toplevel 2>/dev/null || echo server)")"

SERVICES=(
  nav-proxy
  pack-web
  cloudflared
  uptime-kuma
  mc-backup
  idle-tasks
  mod-checker
  discord-sync
)

usage() {
  echo "Usage: $0 <service> [service...]"
  echo "       $0 --list"
  echo "       $0 --all"
  echo ""
  echo "Available services:"
  for svc in "${SERVICES[@]}"; do
    echo "  $svc"
  done
  echo ""
  echo "mc is excluded - use deploy.sh for safe restarts."
}

if [[ $# -eq 0 ]]; then
  usage
  exit 1
fi

case "$1" in
  --list | -l)
    for svc in "${SERVICES[@]}"; do echo "$svc"; done
    exit 0
    ;;
  --all | -a)
    targets=("${SERVICES[@]}")
    ;;
  --help | -h)
    usage
    exit 0
    ;;
  *)
    targets=("$@")
    ;;
esac

for svc in "${targets[@]}"; do
  if [[ "$svc" == "mc" ]]; then
    warn "Skipping mc - use deploy.sh for safe restarts."
    continue
  fi

  echo "Recreating $svc..."
  $SSH_CMD "cd ~/${SERVER_DIR} && docker compose --profile cloud up -d --force-recreate --no-deps $svc" \
    && echo "  ✓ $svc recreated" \
    || warn "$svc recreate failed"
done
