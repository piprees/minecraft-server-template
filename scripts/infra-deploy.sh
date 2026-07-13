#!/usr/bin/env bash
# infra-deploy.sh - The "infra" CI deploy tier. Runs ON the server from the
# stack bundle. Sidecars only - mc is never touched.
#
# What it does:
#   1. Re-runs the seed container so overlay/config changes land in volumes.
#   2. compose up with --no-recreate: mc is not touched (no player disruption).
#   3. Force-recreates sidecars so config changes actually load.
#      Keep this list in sync with deploy.sh's sidecar recreate list.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib.sh"

STACK_DIR="$PROJECT_DIR"
if [[ "$SCRIPT_DIR" == *"/.stack/"* ]]; then
  SERVER_DIR="${SCRIPT_DIR%%/.stack/*}"
else
  SERVER_DIR="${SERVER_DIR:-$PROJECT_DIR}"
fi
cd "$SERVER_DIR"

COMPOSE_FILE="$STACK_DIR/docker-compose.yml"

if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

COMPOSE_BASE="docker compose --project-directory $SERVER_DIR -f $COMPOSE_FILE"

# Re-run seed so overlay changes land in config/mods volumes
$COMPOSE_BASE --profile cloud up --force-recreate --no-deps seed

# Start everything but don't recreate mc (--no-recreate protects running mc)
$COMPOSE_BASE --profile cloud up -d --remove-orphans --no-recreate

# Force-recreate sidecars so updated configs/scripts load
$COMPOSE_BASE --profile cloud up -d --force-recreate --no-deps \
  bluemap nav-proxy pack-web cloudflared mod-checker kuma-init discord-sync idle-tasks 2> /dev/null || true

echo "infra deploy complete"
