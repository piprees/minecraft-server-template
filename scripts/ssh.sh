#!/usr/bin/env bash
# ssh.sh - Drop into a shell on the production server.
#
# No arguments needed — uses DROPLET_HOST, DEPLOY_USER, and BRAND_SLUG
# from .env to build the SSH command. Pass extra arguments to run a
# one-shot command instead of an interactive shell.
#
# Usage:
#   ./scripts/ssh.sh                         # interactive shell
#   ./scripts/ssh.sh 'docker logs mc --tail 50'  # one-shot command
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib.sh"
load_env

: "${DROPLET_HOST:?Set DROPLET_HOST in .env}"
DEPLOY_USER="${DEPLOY_USER:-deploy}"
SSH_KEY="$HOME/.ssh/${BRAND_SLUG:+${BRAND_SLUG}_}mc_deploy_key"
REMOTE_DIR="server"

if [[ $# -eq 0 ]]; then
  exec ssh -t -i "$SSH_KEY" "${DEPLOY_USER}@${DROPLET_HOST}" "cd ~/${REMOTE_DIR} && exec \$SHELL -l"
fi

# shellcheck disable=SC2029
exec ssh -i "$SSH_KEY" "${DEPLOY_USER}@${DROPLET_HOST}" "cd ~/${REMOTE_DIR} && $*"
