#!/usr/bin/env bash
# stop.sh - Stop all containers (local or cloud).
#
# Usage:
#   ./scripts/stop.sh              # stop whichever profile is running
#   ./scripts/stop.sh --local      # stop local dev stack
#   ./scripts/stop.sh --cloud      # stop cloud/prod stack (on the server via SSH)
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

stop_local() {
  echo "Stopping local stack (project: ${COMPOSE_PROJECT_NAME})..."
  docker compose -p "$COMPOSE_PROJECT_NAME" --profile local down 2>/dev/null \
    || docker compose -p "$COMPOSE_PROJECT_NAME" down 2>/dev/null || true
  echo "Local stack stopped."
}

stop_cloud() {
  local host="${DROPLET_HOST:-}"
  local user="${DEPLOY_USER:-deploy}"
  local key="${DEPLOY_KEY_PATH:-$HOME/.ssh/mc_deploy_key}"
  local dir
  dir="$(basename "$PROJECT_DIR")"

  if [[ -z "$host" ]]; then
    echo "DROPLET_HOST not set in .env — can't reach the server."
    exit 1
  fi

  echo "Stopping cloud stack on ${host}..."
  ssh -o ConnectTimeout=10 -i "$key" "${user}@${host}" \
    "cd ~/${dir} && docker compose --profile cloud down" 2>&1
  echo "Cloud stack stopped."
}

case "$MODE" in
  --local)  stop_local ;;
  --cloud)  stop_cloud ;;
  *)
    # Auto-detect: if local containers are running, stop those; otherwise try cloud
    if docker ps --format '{{.Names}}' 2>/dev/null | grep -q "^${CONTAINER_PREFIX:-}mc$"; then
      stop_local
    elif [[ -n "${DROPLET_HOST:-}" ]]; then
      stop_cloud
    else
      echo "No running stack detected. Use --local or --cloud to be explicit."
    fi
    ;;
esac
