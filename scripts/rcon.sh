#!/usr/bin/env bash
# rcon.sh - Run RCON commands without typing the ssh + docker exec dance.
#
# Auto-detects the target: if an mc container is running locally, talks to
# it; otherwise SSHes to production (DROPLET_HOST from .env). No response
# usually means the server is autopaused (JVM frozen while empty), not down.
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

TARGET="auto"
case "${1:-}" in
  --remote) TARGET="remote"; shift ;;
  --local) TARGET="local"; shift ;;
esac

if [[ "$TARGET" == "auto" ]]; then
  if docker ps --format '{{.Names}}' 2> /dev/null | grep -qx mc; then
    TARGET="local"
  else
    TARGET="remote"
  fi
fi

if [[ "$TARGET" == "local" ]]; then
  if [[ $# -eq 0 ]]; then
    exec docker exec -it mc rcon-cli
  fi
  exec docker exec -i mc rcon-cli "$@"
fi

: "${DROPLET_HOST:?Set DROPLET_HOST in .env (or run with --local)}"
DEPLOY_USER="${DEPLOY_USER:-deploy}"
SSH_KEY="${RCON_SSH_KEY:-$HOME/.ssh/mc_deploy_key}"

if [[ $# -eq 0 ]]; then
  exec ssh -t -i "$SSH_KEY" "${DEPLOY_USER}@${DROPLET_HOST}" 'docker exec -it mc rcon-cli'
fi
# shellcheck disable=SC2029
exec ssh -i "$SSH_KEY" "${DEPLOY_USER}@${DROPLET_HOST}" "docker exec -i mc rcon-cli \"$*\""
