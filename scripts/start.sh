#!/usr/bin/env bash
# start.sh - Start all containers (local or cloud).
#
# Usage:
#   ./scripts/start.sh              # start whichever profile matches config
#   ./scripts/start.sh --local      # start local dev stack
#   ./scripts/start.sh --cloud      # start cloud/prod stack (on the server via SSH)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${CONSUMER_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"
cd "$PROJECT_DIR"

if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-$(basename "$PROJECT_DIR" | tr '[:upper:]' '[:lower:]' | sed 's/[^a-z0-9_-]/-/g')}"
MODE="${1:-}"

start_local() {
  echo "Starting local stack (project: ${COMPOSE_PROJECT_NAME})..."
  "$SCRIPT_DIR/dev-up.sh"
}

start_cloud() {
  local host="${DROPLET_HOST:-}"
  local user="${DEPLOY_USER:-deploy}"
  local key="${DEPLOY_KEY_PATH:-$HOME/.ssh/mc_deploy_key}"
  local dir
  dir="$(basename "$PROJECT_DIR")"

  if [[ -z "$host" ]]; then
    echo "DROPLET_HOST not set in .env — can't reach the server."
    exit 1
  fi

  echo "Starting cloud stack on ${host}..."
  ssh -o ConnectTimeout=10 -i "$key" "${user}@${host}" \
    "cd ~/${dir} && docker compose --profile cloud up -d" 2>&1
  echo "Cloud stack started."
}

case "$MODE" in
  --local)  start_local ;;
  --cloud)  start_cloud ;;
  *)
    if [[ -n "${DROPLET_HOST:-}" && "${CLOUD_PROVIDER:-local}" != "local" ]]; then
      start_cloud
    else
      start_local
    fi
    ;;
esac
