#!/usr/bin/env bash
# chunky.sh - Check Chunky pre-generation status across all dimensions.
#
# Shows progress for any running task, completion markers, and the
# idle-tasks .skip-pause state. Works locally or via SSH to production.
#
# Usage:
#   ./scripts/chunky.sh                # auto-detect local/production
#   ./scripts/chunky.sh --remote       # force production
#   ./scripts/chunky.sh --local        # force local
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

run_check() {
  local rcon_cmd="docker exec mc rcon-cli"
  local data_dir="data"

  echo "Chunky pre-generation status"
  echo "=============================="
  echo ""

  echo "Active tasks:"
  local progress
  progress=$($rcon_cmd "chunky progress" 2> /dev/null || echo "")
  if [[ -z "$progress" ]]; then
    echo "  (RCON silent - server is autopaused)"
  elif echo "$progress" | grep -qi "No tasks running"; then
    echo "  None (if .skip-pause was just created, the JVM may still be waking)"
  else
    echo "$progress" | sed 's/^/  /'
  fi

  echo ""
  echo "Completion markers:"
  for marker in \
    ".chunky-complete:Overworld" \
    ".chunky-nether-complete:Nether" \
    ".chunky-end-complete:End" \
    ".chunky-paradise-lost-complete:Paradise Lost"; do
    file="${marker%%:*}"
    label="${marker##*:}"
    if [[ -f "${data_dir}/${file}" ]]; then
      echo "  ${label}: done"
    else
      echo "  ${label}: pending"
    fi
  done

  echo ""
  echo "Autopause bypass:"
  if [[ -f "${data_dir}/.skip-pause" ]]; then
    echo "  .skip-pause present (autopause disabled for pre-gen)"
  else
    echo "  .skip-pause absent (autopause active)"
  fi

  echo ""
  echo "Idle-tasks:"
  docker logs idle-tasks --tail 3 2>&1 | sed 's/^/  /'
}

if [[ "$TARGET" == "local" ]]; then
  run_check
else
  : "${DROPLET_HOST:?Set DROPLET_HOST in .env (or run with --local)}"
  DEPLOY_USER="${DEPLOY_USER:-deploy}"
  SSH_KEY="$HOME/.ssh/${BRAND_SLUG:+${BRAND_SLUG}_}mc_deploy_key"

  ssh -i "$SSH_KEY" "${DEPLOY_USER}@${DROPLET_HOST}" "cd ~/server && $(declare -f run_check) && run_check"
fi
