#!/usr/bin/env bash
# service.sh - Start, stop, restart, or check status of individual services.
#
# Usage (via ops for production):
#   ./ops start nav-proxy          # start a stopped service
#   ./ops stop uptime-kuma         # stop a running service
#   ./ops restart nav-proxy        # force-recreate a service
#   ./ops status                   # show all container statuses
#   ./ops status nav-proxy         # show one service's status
#
# Usage (via dev for local):
#   ./dev start nav-proxy
#   ./dev stop uptime-kuma
#
# MC lifecycle is intentionally excluded: deploy.sh (or Discord /mc restart)
# owns the countdown, save, allowlist and config choreography. This script
# only performs raw Compose operations for named sidecars.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib.sh"
load_env

ACTION="${1:-}"
shift || true
show_banner "${ACTION:-service}" "${*:-all services}"

SERVICES=(
  nav-proxy
  pack-web
  cloudflared
  uptime-kuma
  kuma-init
  mc-backup
  mc-backup-local
  idle-tasks
  mod-checker
  discord-sync
  seed
)

usage() {
  echo "Usage: service.sh <start|stop|restart|status> [service...]"
  echo ""
  echo "Actions:"
  echo "  start    Start a stopped service"
  echo "  stop     Stop a running service"
  echo "  restart  Force-recreate a service"
  echo "  status   Show container status (all if no service named)"
  echo ""
  echo "Mutable services: ${SERVICES[*]}"
  echo "Read-only status also accepts: mc"
}

if [[ -z "$ACTION" || "$ACTION" == "help" || "$ACTION" == "--help" ]]; then
  usage
  exit 0
fi

# Determine if running locally or via SSH to production
LOCAL=0
if [[ "${SERVICE_LOCAL:-}" == "1" ]]; then
  LOCAL=1
fi

if [[ $LOCAL -eq 1 ]]; then
  # Local: resolve compose paths from consumer/stack dirs
  CONSUMER="${CONSUMER_DIR:-.}"
  STACK_DIR_RESOLVED="${STACK_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"
  COMPOSE_FILE="$STACK_DIR_RESOLVED/docker-compose.yml"
  LOCAL_OVERRIDE="$STACK_DIR_RESOLVED/docker-compose.local.yml"
  BRAND_SLUG="${BRAND_SLUG:-myserver}"
  COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-$BRAND_SLUG}"
  export COMPOSE_PROJECT_NAME

  compose_cmd() {
    docker compose \
      -f "$COMPOSE_FILE" \
      -f "$LOCAL_OVERRIDE" \
      --project-directory "$CONSUMER" \
      -p "$COMPOSE_PROJECT_NAME" \
      --profile local "$@"
  }
else
  # Production: SSH to the server
  : "${DROPLET_HOST:?Set DROPLET_HOST in .env}"
  : "${DEPLOY_USER:=deploy}"
  SSH_KEY="$HOME/.ssh/${BRAND_SLUG:+${BRAND_SLUG}_}mc_deploy_key"
  SSH_CMD="ssh -i $SSH_KEY ${DEPLOY_USER}@${DROPLET_HOST}"

  compose_cmd() {
    $SSH_CMD "cd ~/server && docker compose --profile cloud $*"
  }
fi

# Validate targets
targets=("$@")
if [[ ${#targets[@]} -eq 0 && "$ACTION" != "status" ]]; then
  echo "No service specified."
  usage
  exit 1
fi

is_sidecar() {
  local candidate="$1"
  local service
  for service in "${SERVICES[@]}"; do
    [[ "$service" == "$candidate" ]] && return 0
  done
  return 1
}

validate_targets() {
  local target
  for target in "${targets[@]}"; do
    if [[ "$target" == "mc" ]]; then
      if [[ "$ACTION" == "status" ]]; then
        continue
      fi
      die "Refusing raw MC lifecycle operation. Use deploy.sh or Discord /mc restart."
    fi
    is_sidecar "$target" || die "Unknown sidecar: $target"
  done
}

validate_targets

case "$ACTION" in
  start)
    for svc in "${targets[@]}"; do
      echo "Starting $svc..."
      compose_cmd up -d --no-deps "$svc" \
        && echo "  $svc started" \
        || warn "$svc start failed"
    done
    ;;
  stop)
    for svc in "${targets[@]}"; do
      echo "Stopping $svc..."
      compose_cmd stop "$svc" \
        && echo "  $svc stopped" \
        || warn "$svc stop failed"
    done
    ;;
  restart)
    for svc in "${targets[@]}"; do
      echo "Recreating $svc..."
      compose_cmd up -d --force-recreate --no-deps "$svc" \
        && echo "  $svc recreated" \
        || warn "$svc recreate failed"
    done
    ;;
  status)
    if [[ ${#targets[@]} -eq 0 ]]; then
      if [[ $LOCAL -eq 1 ]]; then
        compose_cmd ps -a
      else
        $SSH_CMD "docker ps -a --format 'table {{.Names}}\t{{.Status}}\t{{.Image}}'"
      fi
    else
      for svc in "${targets[@]}"; do
        if [[ $LOCAL -eq 1 ]]; then
          compose_cmd ps "$svc"
        else
          $SSH_CMD "docker ps -a --filter name=$svc --format 'table {{.Names}}\t{{.Status}}\t{{.Image}}'"
        fi
      done
    fi
    ;;
  *)
    echo "Unknown action: $ACTION"
    usage
    exit 1
    ;;
esac
