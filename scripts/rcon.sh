#!/usr/bin/env bash
# rcon.sh - Run RCON commands without typing the ssh + docker exec dance.
#
# Targets production by default; auto-detects local only when SERVICE_LOCAL=1
# (set by ./dev). No response usually means the server is autopaused (JVM
# frozen while empty), not down.
#
# Usage:
#   ./scripts/rcon.sh "list"                 # one command
#   ./scripts/rcon.sh "spark health"
#   ./scripts/rcon.sh                        # interactive console
#   ./scripts/rcon.sh --remote "list"        # force production
#   ./scripts/rcon.sh --local "list"         # force local container
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib.sh"
load_env

TARGET="remote"
if [[ "${SERVICE_LOCAL:-}" == "1" ]]; then
  TARGET="local"
fi
case "${1:-}" in
  --remote) TARGET="remote"; shift ;;
  --local) TARGET="local"; shift ;;
esac

if [[ "$TARGET" == "local" ]]; then
  if [[ $# -eq 0 ]]; then
    exec docker exec -it mc rcon-cli
  fi
  exec docker exec -i mc rcon-cli "$@"
fi

: "${DROPLET_HOST:?Set DROPLET_HOST in .env (or run with --local)}"
DEPLOY_USER="${DEPLOY_USER:-deploy}"
SSH_KEY="${RCON_SSH_KEY:-$HOME/.ssh/${BRAND_SLUG:+${BRAND_SLUG}_}mc_deploy_key}"

if [[ $# -eq 0 ]]; then
  exec ssh -t -i "$SSH_KEY" "${DEPLOY_USER}@${DROPLET_HOST}" 'docker exec -it mc rcon-cli'
fi
# shellcheck disable=SC2029
exec ssh -i "$SSH_KEY" "${DEPLOY_USER}@${DROPLET_HOST}" "docker exec -i mc rcon-cli \"$*\""
